package com.example.aichat.python

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class PyResult(
    val output: String,
    val error: String = "",
    val files: List<String> = emptyList()
)

class PythonSessionManager(private val context: Context) {

    private val sessions = ConcurrentHashMap<String, PyObject>()
    private val initialized = AtomicBoolean(false)

    private fun ensureInitialized() {
        if (initialized.get()) return
        synchronized(this) {
            if (initialized.get()) return
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            initialized.set(true)
        }
    }

    /**
     * Execute Python code in a session.
     */
    suspend fun execute(
        code: String,
        sessionId: String = "default",
        retryCount: Int = 0,
        onProgress: ((String) -> Unit)? = null
    ): PyResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        val py = Python.getInstance()

        // 1. Syntax pre-check
        val syntaxErr = checkSyntax(py, code)
        if (syntaxErr != null) return@withContext PyResult("", syntaxErr)

        // 2. Get or create session (a dict-like namespace)
        val sessionDict = getOrCreateSession(py, sessionId)

        // 3. Auto-cwd to workspace
        val wsDir = File(context.filesDir, "workspace").also { it.mkdirs() }

        // 4. Capture pre-execution file list
        val beforeFiles = wsDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()

        // 5. Execute code
        val outcome = runScript(py, sessionDict, code, wsDir.absolutePath)

        // 6. Handle errors
        if (outcome.error.isNotEmpty()) {
            val module = extractMissingModule(outcome.error)
            if (module != null && retryCount == 0) {
                onProgress?.invoke("auto install: $module ...")
                if (pipInstall(py, module)) {
                    onProgress?.invoke("retrying...")
                    return@withContext execute(code, sessionId, retryCount = 1, onProgress)
                }
                return@withContext PyResult(outcome.output, "${outcome.error}\n\n无法安装模块 '$module'")
            }
        }

        // 7. Check for new files
        val afterFiles = wsDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        val newFiles = afterFiles - beforeFiles
        val relativeFiles = newFiles.map { it.removePrefix("${wsDir.absolutePath}/") }

        PyResult(
            output = outcome.output,
            error = outcome.error,
            files = relativeFiles
        )
    }

    suspend fun install(packageName: String): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()
        pipInstall(Python.getInstance(), packageName)
    }

    fun closeSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    // --- Private helpers ---

    data class ScriptResult(val output: String, val error: String)

    /**
     * Execute Python code in a given namespace dict.
     */
    private fun runScript(py: Python, ns: PyObject, code: String, wsPath: String): ScriptResult {
        val builtins = py.getModule("builtins")

        // Store code as a string variable in namespace to avoid Kotlin injection issues
        ns.callAttr("__setitem__", "__user_code__", code)
        ns.callAttr("__setitem__", "__ws_path__", wsPath)

        // Use exec to run the wrapper which executes the stored code
        val wrapper = """
import sys, io, os

os.chdir(__ws_path__)
_out = io.StringIO()
_err = io.StringIO()
_saved_out = sys.stdout
_saved_err = sys.stderr
sys.stdout = _out
sys.stderr = _err

try:
    exec(__user_code__)
    # Also set up matplotlib backend if imported
    try:
        import matplotlib
        matplotlib.use('Agg')
    except:
        pass
except Exception as __e__:
    import traceback
    traceback.print_exc(file=_err)
finally:
    sys.stdout = _saved_out
    sys.stderr = _saved_err

__result__ = (_out.getvalue(), _err.getvalue())
        """.trimIndent()

        builtins.callAttr("exec", wrapper, ns)
        val result = ns.callAttr("get", "__result__")

        val out: String
        val err: String
        if (result != null) {
            val list = result.asList()
            out = list[0].toString()
            err = list[1].toString()
        } else {
            out = ""
            err = "execution failed"
        }

        return ScriptResult(out, err)
    }

    private fun getOrCreateSession(py: Python, id: String): PyObject {
        return sessions.getOrPut(id) {
            val wsDir = File(context.filesDir, "workspace").also { it.mkdirs() }
            val wsAbs = wsDir.absolutePath

            // Create a dict as session namespace
            val ns = py.getModule("builtins").callAttr("dict")

            // Inject context bridge
            py.getModule("builtins").callAttr("exec", """
import os as _os

def _ctx_read(p):
    with open(p, 'r', encoding='utf-8') as f:
        return f.read()

def _ctx_write(p, c):
    with open(p, 'w', encoding='utf-8') as f:
        f.write(c)
    return True

def _ctx_list(d=None):
    if d is None:
        d = '.'
    return _os.listdir(d)

# Set as 'context' in the namespace we're about to store
context = type('_Ctx', (), {
    'read': staticmethod(_ctx_read),
    'write': staticmethod(_ctx_write),
    'list_files': staticmethod(_ctx_list)
})()
            """.trimIndent(), ns)
            ns
        }
    }

    private fun checkSyntax(py: Python, code: String): String? {
        return try {
            py.getModule("builtins")
                .callAttr("compile", code, "<script>", "exec")
            null
        } catch (e: com.chaquo.python.PyException) {
            "SyntaxError: ${e.message}"
        }
    }

    private fun extractMissingModule(error: String): String? {
        val patterns = listOf(
            Regex("No module named '([^']+)'"),
            Regex("No module named \"([^\"]+)\""),
            Regex("ModuleNotFoundError.*'([^']+)'"),
        )
        for (p in patterns) {
            val match = p.find(error) ?: continue
            val mod = match.groupValues[1].substringBefore('.')
            if (mod !in setOf("sys", "os", "io", "builtins", "math", "re", "json",
                "time", "datetime", "types", "abc", "typing", "collections", "functools", "itertools")
            ) {
                return mod
            }
        }
        return null
    }

    private fun pipInstall(py: Python, pkg: String): Boolean {
        return try {
            val pip = py.getModule("pip")
            pip?.callAttr("main", listOf("install", pkg))?.toInt() == 0
        } catch (e: Exception) {
            try {
                val mod = py.getModule("subprocess")
                val result = mod?.callAttr("run",
                    listOf("python", "-m", "pip", "install", pkg),
                    mapOf("capture_output" to true, "text" to true)
                )
                result?.get("returncode")?.toInt() == 0
            } catch (_: Exception) {
                false
            }
        }
    }
}
