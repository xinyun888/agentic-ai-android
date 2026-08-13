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

    // 会话上限：超过后淘汰最久未访问的，防止长期运行内存只增不减
    private val MAX_SESSIONS = 8
    private val sessions = ConcurrentHashMap<String, PyObject>()
    private val lastAccess = ConcurrentHashMap<String, Long>()
    private val initialized = AtomicBoolean(false)

    private fun ensureInitialized() {
        if (initialized.get()) return
        synchronized(this) {
            if (initialized.get()) return
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            // 把可写的 pip 目标目录加进 sys.path，运行时装的包才能 import
            try {
                val libsDir = File(context.filesDir, "pip_libs")
                libsDir.mkdirs()
                val py = Python.getInstance()
                py.getModule("sys").callAttr("path").callAttr("insert", 0, libsDir.absolutePath)
            } catch (_: Exception) {}
            initialized.set(true)
        }
    }

    /**
     * 在会话中执行 Python 代码。
     */
    suspend fun execute(
        code: String,
        sessionId: String = "default",
        retryCount: Int = 0,
        onProgress: ((String) -> Unit)? = null
    ): PyResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        val py = Python.getInstance()

        // 1. 语法预检查
        val syntaxErr = checkSyntax(py, code)
        if (syntaxErr != null) return@withContext PyResult("", syntaxErr)

        // 2. 获取或创建会话（类似 dict 的命名空间）
        val sessionDict = getOrCreateSession(py, sessionId)

        // 3. 自动切换到 workspace 目录
        val wsDir = File(context.filesDir, "workspace").also { it.mkdirs() }

        // 4. 记录执行开始时间（轻量，替代执行前全盘扫描）
        val startTime = System.currentTimeMillis()

        // 5. 执行代码
        val outcome = runScript(py, sessionDict, code, wsDir.absolutePath)

        // 6. 处理错误
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

        // 7. 检查执行期间新增/修改的文件（一次扫描，用时间戳识别）
        val relativeFiles = wsDir.walkTopDown()
            .filter { it.isFile && it.lastModified() >= startTime - 1000 }
            .map { it.absolutePath.removePrefix("${wsDir.absolutePath}/") }
            .toList()

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

    // --- 私有辅助函数 ---

    data class ScriptResult(val output: String, val error: String)

    /**
     * 在给定命名空间 dict 中执行 Python 代码。
     */
    private fun runScript(py: Python, ns: PyObject, code: String, wsPath: String): ScriptResult {
        val builtins = py.getModule("builtins")

        // 将代码以字符串变量存入命名空间，避免 Kotlin 注入问题
        ns.callAttr("__setitem__", "__user_code__", code)
        ns.callAttr("__setitem__", "__ws_path__", wsPath)

        // 使用 exec 运行包装器来执行已存储的代码
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
        lastAccess[id] = System.currentTimeMillis()
        return sessions.getOrPut(id) {
            evictIfNeeded(id)
            val wsDir = File(context.filesDir, "workspace").also { it.mkdirs() }
            val wsAbs = wsDir.absolutePath

            // 创建 dict 作为会话命名空间
            val ns = py.getModule("builtins").callAttr("dict")

            // 注入上下文桥
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

    // 淘汰最久未访问的会话（当前会话除外），保持内存有界
    private fun evictIfNeeded(currentId: String) {
        if (sessions.size < MAX_SESSIONS) return
        val oldest = lastAccess.entries
            .filter { it.key != currentId }
            .minByOrNull { it.value }
        if (oldest != null) {
            sessions.remove(oldest.key)
            lastAccess.remove(oldest.key)
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

    private val pipMirrors = listOf(
        "https://pypi.tuna.tsinghua.edu.cn/simple",
        "https://mirrors.aliyun.com/pypi/simple/",
        "https://mirrors.cloud.tencent.com/pypi/simple",
        "https://pypi.org/simple"
    )

    /** 成功返回空串，失败返回错误描述；装到可写的 app 私有目录 */
    private fun pipInstallVerbose(py: Python, pkg: String): String {
        val libsDir = File(context.filesDir, "pip_libs").absolutePath
        // 进程内 pip._internal.main + --target（规避 APK 只读 site-packages）+ 多镜像轮询
        val pip = try { py.getModule("pip._internal") } catch (_: Exception) { null }
        if (pip != null) {
            var lastErr = ""
            for (mirror in pipMirrors) {
                try {
                    val rc = pip.callAttr("main", listOf("install", "--target", libsDir,
                        "--no-cache-dir", "--disable-pip-version-check",
                        "-i", mirror, pkg))
                    if (rc?.toInt() == 0) return ""
                    lastErr = "pip 退出码 ${rc?.toInt() ?: "null"} (镜像: $mirror)"
                } catch (e: Exception) {
                    lastErr = "pip 异常: ${e.message} (镜像: $mirror)"
                }
            }
            return "所有镜像安装失败: $lastErr"
        }
        // 兜底：subprocess，带 stderr 诊断
        try {
            val builtins = py.getModule("builtins")
            val ns = builtins.callAttr("dict")
            ns.callAttr("__setitem__", "__pkg__", pkg)
            ns.callAttr("__setitem__", "__target__", libsDir)
            ns.callAttr("__setitem__", "__rc__", builtins.callAttr("int", -1))
            ns.callAttr("__setitem__", "__err__", builtins.callAttr("str", ""))
            val script = """
import sys, subprocess
r = subprocess.run([sys.executable, '-m', 'pip', 'install', '--target', __target__,
                    '--no-cache-dir', '--disable-pip-version-check',
                    '-i', '${pipMirrors[0]}', __pkg__],
                   capture_output=True, text=True, timeout=180)
__rc__ = r.returncode
__err__ = (r.stdout[-600:] + r.stderr[-600:]).strip()
""".trimIndent()
            builtins.callAttr("exec", script, ns)
            val rc = ns.callAttr("get", "__rc__").toInt()
            val err = ns.callAttr("get", "__err__").toString()
            return if (rc == 0) "" else "pip 退出码 $rc: $err"
        } catch (e2: Exception) {
            return "pip 执行失败: ${e2.message}"
        }
    }

    private fun pipInstall(py: Python, pkg: String): Boolean = pipInstallVerbose(py, pkg).isEmpty()
}
