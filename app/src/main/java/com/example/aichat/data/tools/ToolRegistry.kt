package com.example.aichat.data.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

// ==================== Core Types ====================

data class ToolDef(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String>
)

data class ToolResult(
    val toolCallId: String,
    val success: Boolean,
    val content: String
)

// ==================== Tool Interface ====================

interface Tool {
    val definition: ToolDef
    suspend fun execute(args: Map<String, String>, context: Context): ToolResult
}

// ==================== Workspace ====================

class Workspace(private val context: Context) {
    val root: File
        get() = File(context.filesDir, "workspace").also { it.mkdirs() }

    fun resolve(path: String): File {
        val clean = path.trimStart('/').replace("..", "")
        return File(root, clean)
    }

    fun list(): String {
        val sb = StringBuilder()
        listRecursive(root, "", sb)
        return sb.toString().ifEmpty { "工作区为空" }
    }

    private fun listRecursive(dir: File, prefix: String, sb: StringBuilder) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isDirectory) {
                sb.appendLine("📁 $prefix${f.name}/")
                listRecursive(f, "$prefix  ", sb)
            } else {
                val kb = f.length() / 1024
                sb.appendLine("📄 $prefix${f.name} (${kb}KB)")
            }
        }
    }
}

// ==================== Tool Implementations ====================

class ReadFileTool : Tool {
    override val definition = ToolDef(
        name = "read_file",
        description = "读取工作区中的文件内容。支持 .txt, .md, .docx, .json 格式",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "文件路径，如 '报告.txt'")
            ),
            "required" to listOf("path")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val path = args["path"] ?: return ToolResult("", false, "缺少 path 参数")
        val file = Workspace(context).resolve(path)
        if (!file.exists()) return ToolResult("", false, "文件不存在: $path")

        return try {
            val content = when {
                file.extension.lowercase() == "docx" -> readDocx(file)
                file.length() > 500 * 1024 -> "文件过大 (${file.length() / 1024}KB)，只读取前 10KB:\n" +
                        file.readText().take(10 * 1024)
                else -> file.readText()
            }
            ToolResult("", true, content)
        } catch (e: Exception) {
            ToolResult("", false, "读取失败: ${e.message}")
        }
    }

    private fun readDocx(file: File): String {
        val sb = StringBuilder()
        ZipInputStream(FileInputStream(file)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zis.readBytes().toString(Charsets.UTF_8)
                    // Strip XML tags, keep text
                    sb.append(xml.replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ").trim())
                    break
                }
                entry = zis.nextEntry
            }
        }
        return sb.toString().ifEmpty { "(空文档)" }
    }
}

class WriteFileTool : Tool {
    override val definition = ToolDef(
        name = "write_file",
        description = "将内容写入工作区文件。会覆盖已有文件",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "文件路径，如 '总结.md'"),
                "content" to mapOf("type" to "string", "description" to "要写入的文件内容")
            ),
            "required" to listOf("path", "content")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val path = args["path"] ?: return ToolResult("", false, "缺少 path 参数")
        val content = args["content"] ?: return ToolResult("", false, "缺少 content 参数")
        return try {
            val file = Workspace(context).resolve(path)
            file.parentFile?.mkdirs()
            val existed = file.exists()
            val oldSize = if (existed) file.length() else 0
            file.writeText(content)
            val warn = if (existed) " ⚠️ 已覆盖原文件 (${oldSize / 1024}KB) → 新文件 (${content.length}字符)" else ""
            ToolResult("", true, "已写入 ${file.name} (${content.length} 字符)$warn")
        } catch (e: Exception) {
            ToolResult("", false, "写入失败: ${e.message}")
        }
    }
}

class DeleteFileTool : Tool {
    override val definition = ToolDef(
        name = "delete_file",
        description = "删除工作区中的文件。注意：此操作不可逆，使用前请确认。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "要删除的文件路径，如 'temp.txt'")
            ),
            "required" to listOf("path")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val path = args["path"] ?: return ToolResult("", false, "缺少 path 参数")
        return try {
            val file = Workspace(context).resolve(path)
            if (!file.exists()) return ToolResult("", false, "文件不存在: $path")
            val size = file.length() / 1024
            val deleted = file.delete()
            if (deleted) ToolResult("", true, "已删除 $path (${size}KB)\n⚠️ 此操作不可逆")
            else ToolResult("", false, "删除失败: $path")
        } catch (e: Exception) {
            ToolResult("", false, "删除失败: ${e.message}")
        }
    }
}

class ListFilesTool : Tool {
    override val definition = ToolDef(
        name = "list_files",
        description = "列出工作区中的所有文件",
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        return ToolResult("", true, Workspace(context).list())
    }
}

class WebFetchTool : Tool {
    override val definition = ToolDef(
        name = "web_fetch",
        description = "抓取指定 URL 的网页内容（提取纯文本）",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "url" to mapOf("type" to "string", "description" to "要抓取的完整 URL")
            ),
            "required" to listOf("url")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val url = args["url"] ?: return ToolResult("", false, "缺少 url 参数")
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""
            // Try to extract meaningful content first
            val text = extractArticleText(html)
                .takeIf { it.length > 100 }
                ?: html
                    .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<nav[^>]*>.*?</nav>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<footer[^>]*>.*?</footer>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("&[a-z]+;")) { match ->
                        when (match.value) {
                            "&amp;" -> "&"; "&lt;" -> "<"; "&gt;" -> ">"
                            "&quot;" -> "\""; "&nbsp;" -> " "
                            "&#x27;" -> "'"; else -> match.value
                        }
                    }
                    .replace(Regex("\\s+"), " ").trim()
            ToolResult("", true, text.take(20000) + if (text.length > 20000) "\n\n(... 内容已截断至20000字符)" else "")
        } catch (e: Exception) {
            ToolResult("", false, "抓取失败: ${e.message}")
        }
    }

    /** Extract main article text from HTML, skipping nav/sidebar/ads */
    private fun extractArticleText(html: String): String {
        // Prefer <article> or <main> tags
        val article = Regex("<article[^>]*>(.*?)</article>", RegexOption.DOT_MATCHES_ALL).find(html)
        val main = Regex("<main[^>]*>(.*?)</main>", RegexOption.DOT_MATCHES_ALL).find(html)
        val target = article?.groupValues?.get(1) ?: main?.groupValues?.get(1) ?: return ""
        return target
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }
}

class RegexTool : Tool {
    override val definition = ToolDef(
        name = "run_regex",
        description = "对文本执行正则匹配提取。返回所有匹配结果",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "text" to mapOf("type" to "string", "description" to "要搜索的文本"),
                "pattern" to mapOf("type" to "string", "description" to "正则表达式模式，如 '\\d{4}-\\d{2}-\\d{2}'")
            ),
            "required" to listOf("text", "pattern")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val text = args["text"] ?: return ToolResult("", false, "缺少 text 参数")
        val pattern = args["pattern"] ?: return ToolResult("", false, "缺少 pattern 参数")
        return try {
            val regex = Pattern.compile(pattern, Pattern.MULTILINE)
            val matcher = regex.matcher(text)
            val results = mutableListOf<String>()
            while (matcher.find()) {
                val groups = (0..matcher.groupCount()).mapNotNull {
                    try { matcher.group(it) } catch (_: Exception) { null }
                }
                results.add(groups.joinToString(" | "))
            }
            val output = if (results.isEmpty()) "未找到匹配"
            else results.take(50).joinToString("\n").let {
                if (results.size > 50) "$it\n... (共 ${results.size} 条，只显示前 50)" else it
            }
            ToolResult("", true, output)
        } catch (e: Exception) {
            ToolResult("", false, "正则错误: ${e.message}")
        }
    }
}

class TimeTool : Tool {
    override val definition = ToolDef(
        name = "get_time",
        description = "获取当前日期时间",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "format" to mapOf("type" to "string", "description" to "时间格式，默认 yyyy-MM-dd HH:mm:ss")
            )
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val fmt = args["format"] ?: "yyyy-MM-dd HH:mm:ss"
        return try {
            val sdf = SimpleDateFormat(fmt, Locale.getDefault())
            ToolResult("", true, sdf.format(Date()))
        } catch (e: Exception) {
            ToolResult("", true, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        }
    }
}

class ClipboardTool : Tool {
    override val definition = ToolDef(
        name = "clipboard_write",
        description = "将文本写入剪贴板",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "text" to mapOf("type" to "string", "description" to "要复制的文本")
            ),
            "required" to listOf("text")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val text = args["text"] ?: return ToolResult("", false, "缺少 text 参数")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("墨语", text))
        return ToolResult("", true, "已复制到剪贴板")
    }
}

class ShareTool : Tool {
    override val definition = ToolDef(
        name = "share_text",
        description = "通过系统分享菜单发送文本（微信/钉钉/邮件等）",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "text" to mapOf("type" to "string", "description" to "要分享的文本"),
                "title" to mapOf("type" to "string", "description" to "分享标题，默认'C₉H₁₉ 分享'")
            ),
            "required" to listOf("text")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val text = args["text"] ?: return ToolResult("", false, "缺少 text 参数")
        val title = args["title"] ?: "C₉H₁₉ 分享"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, title))
        return ToolResult("", true, "已打开分享面板")
    }
}

class HttpTool : Tool {
    override val definition = ToolDef(
        name = "http_request",
        description = "发送 HTTP GET 请求到指定 URL 并返回结果",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "url" to mapOf("type" to "string", "description" to "请求 URL"),
                "method" to mapOf("type" to "string", "description" to "HTTP 方法 (GET/POST)，默认 GET")
            ),
            "required" to listOf("url")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val url = args["url"] ?: return ToolResult("", false, "缺少 url 参数")
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()?.take(5000) ?: ""
            ToolResult("", true, "HTTP ${response.code}\n$body")
        } catch (e: Exception) {
            ToolResult("", false, "请求失败: ${e.message}")
        }
    }
}

// ==================== Build HTML ====================

class BuildHtmlTool : Tool {
    override val definition = ToolDef(
        name = "build_html",
        description = "创建或更新一个可直接预览的 HTML 页面。适合生成小游戏、可视化图表、交互式工具等。文件将实时显示在预览面板中。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "filename" to mapOf("type" to "string", "description" to "文件名，如 game.html，保存在 workspace 下"),
                "html" to mapOf("type" to "string", "description" to "完整的 HTML 源码，包含 <!DOCTYPE html>、<style>、<script> 等"),
                "title" to mapOf("type" to "string", "description" to "预览面板显示的标题")
            ),
            "required" to listOf("filename", "html")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val filename = args["filename"] ?: return ToolResult("", false, "缺少 filename")
        val html = args["html"] ?: return ToolResult("", false, "缺少 html")
        val title = args["title"] ?: filename
        val workspaceDir = java.io.File(context.filesDir, "workspace").also { it.mkdirs() }
        val file = java.io.File(workspaceDir, filename)
        try {
            file.writeText(html, Charsets.UTF_8)
            return ToolResult("", true, "{\"type\":\"preview\",\"file\":\"$filename\",\"title\":\"$title\"}\n✓ 页面已保存并可在预览面板查看: workspace/$filename")
        } catch (e: Exception) {
            return ToolResult("", false, "保存失败: ${e.message}")
        }
    }
}

// ==================== Registry ====================

// ==================== Python Tools ====================

class PythonExecTool(private val pyManager: () -> com.example.aichat.python.PythonSessionManager?) : Tool {
    override val definition = ToolDef(
        name = "python_exec",
        description = "在本地 Python 3.13 环境中执行代码。支持数据分析和文件处理。预装了 pandas/numpy/matplotlib/openpyxl/requests 等库。session 为空时使用默认会话，指定 session 可保持变量会话。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "code" to mapOf("type" to "string", "description" to "要执行的 Python 源代码"),
                "session" to mapOf("type" to "string", "description" to "会话标识，同 session 共享变量。留空用 default"),
                "install" to mapOf("type" to "string", "description" to "运行前安装的包名，空格分隔。如 'pymupdf rdkit'")
            ),
            "required" to listOf("code")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val py = pyManager() ?: return ToolResult("", false, "Python 环境未初始化")
        val code = args["code"] ?: return ToolResult("", false, "缺少 code 参数")
        val session = args["session"] ?: "default"

        // Pre-install packages if needed
        val install = args["install"]
        if (!install.isNullOrBlank()) {
            for (pkg in install.split(Regex("\\s+"))) {
                if (pkg.isNotBlank()) {
                    val ok = py.install(pkg)
                    if (!ok) return ToolResult("", false, "pip install $pkg 失败")
                }
            }
        }

        val result = py.execute(code, session) { progress ->
            // Progress streaming available via onProgress callback
        }

        val sb = StringBuilder()
        if (result.output.isNotBlank()) sb.appendLine(result.output)
        if (result.error.isNotBlank()) sb.appendLine("--- 错误 ---\n${result.error}")
        if (result.files.isNotEmpty()) {
            sb.appendLine("--- 生成文件 ---")
            result.files.forEach { sb.appendLine("workspace/$it") }
        }

        return ToolResult("", result.error.isEmpty(), sb.toString().trim())
    }
}

class PipInstallTool(private val pyManager: () -> com.example.aichat.python.PythonSessionManager?) : Tool {
    override val definition = ToolDef(
        name = "pip_install",
        description = "安装 Python 包到本地环境。支持空格分隔多个包名。注意：部分 C 扩展包可能不兼容。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "packages" to mapOf("type" to "string", "description" to "要安装的包名，空格分隔，如 'pymupdf selenium'")
            ),
            "required" to listOf("packages")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val py = pyManager() ?: return ToolResult("", false, "Python 环境未初始化")
        val pkgs = args["packages"] ?: return ToolResult("", false, "缺少 packages 参数")
        val results = mutableListOf<String>()
        for (pkg in pkgs.split(Regex("\\s+"))) {
            if (pkg.isBlank()) continue
            val ok = py.install(pkg)
            results.add(if (ok) "✓ $pkg 安装成功" else "✗ $pkg 安装失败")
        }
        return ToolResult("", true, results.joinToString("\n"))
    }
}

class PythonSessionCloseTool(private val pyManager: () -> com.example.aichat.python.PythonSessionManager?) : Tool {
    override val definition = ToolDef(
        name = "session_close",
        description = "关闭一个 Python 执行会话，释放内存。结束数据分析任务后应调用此工具。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "session" to mapOf("type" to "string", "description" to "要关闭的会话标识，留空关闭默认会话")
            ),
            "required" to emptyList<String>()
        )
    )

    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val py = pyManager() ?: return ToolResult("", false, "Python 环境未初始化")
        val session = args["session"] ?: "default"
        py.closeSession(session)
        return ToolResult("", true, "会话 '$session' 已关闭")
    }
}

// ===== Memory Tools (model-controlled memory card) =====

class MemorySaveTool : Tool {
    override val definition = ToolDef(
        name = "memory_save",
        description = "将重要信息写入你的长期记忆。当你发现用户偏好、项目决策、重要上下文时主动使用，防止遗忘",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "key" to mapOf("type" to "string", "description" to "记忆条目的标题，如'用户偏好Python'"),
                "content" to mapOf("type" to "string", "description" to "要记住的具体内容")
            ),
            "required" to listOf("key", "content")
        )
    )
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val key = args["key"]?.trim() ?: return ToolResult("", false, "需要 key")
        val content = args["content"]?.trim() ?: return ToolResult("", false, "需要 content")
        val memDir = java.io.File(context.filesDir, "memory").also { it.mkdirs() }
        val memFile = java.io.File(memDir, "memory.md")
        try {
            val existing = if (memFile.exists()) memFile.readText() else ""
            // Deduplicate: replace existing entry with same key
            val pattern = Regex("## ${Regex.escape(key)}\n[^#]*").find(existing)
            val body = if (pattern != null) {
                existing.replace(pattern.value, "## $key\n$content")
            } else {
                "$existing\n## $key\n$content".trim()
            }
            memFile.writeText(body.trim() + "\n")
            return ToolResult("", true, "已记住: $key")
        } catch (e: Exception) {
            return ToolResult("", false, "记忆保存失败: ${e.message}")
        }
    }
}

class MemoryLoadTool : Tool {
    override val definition = ToolDef(
        name = "memory_load",
        description = "读取你的长期记忆，回顾之前记住的用户偏好、项目决策和重要上下文",
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    )
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val memFile = java.io.File(context.filesDir, "memory/memory.md")
        if (!memFile.exists()) return ToolResult("", true, "(记忆为空)")
        val content = memFile.readText().take(10000)
        return ToolResult("", true, content)
    }
}

// ===== Screen Control Tools =====

class ScreenInfoTool : Tool {
    override val definition = ToolDef(
        name = "screen_info",
        description = "读取当前屏幕的界面元素（文字、按钮、输入框等）。返回界面树，包含每个元素的内容和位置坐标",
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    )
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        if (!com.example.aichat.service.ScreenControlService.isAvailable())
            return ToolResult("", false, "手机控制服务未启动。请在 系统设置→无障碍→已安装应用→命苦打工人 中开启")
        val svc = com.example.aichat.service.ScreenControlService.instance!!
        val tree = svc.getAccessibilityTree()
        return ToolResult("", true, if (tree.isBlank()) "(界面为空或无法读取)" else tree)
    }
}

class ScreenTapTool : Tool {
    override val definition = ToolDef(
        name = "screen_tap",
        description = "点击屏幕上的元素。可以按坐标点击(x,y)或按文字内容点击(text)",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer", "description" to "点击的 X 坐标"),
                "y" to mapOf("type" to "integer", "description" to "点击的 Y 坐标"),
                "text" to mapOf("type" to "string", "description" to "要点击的文字内容（与坐标二选一）")
            )
        )
    )
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val svc = com.example.aichat.service.ScreenControlService.instance
            ?: return ToolResult("", false, "手机控制服务未启动。请在 系统设置→无障碍→已安装应用→命苦打工人 中开启")
        val text = args["text"]
        if (!text.isNullOrBlank()) {
            return if (svc.findAndClickByText(text)) ToolResult("", true, "已点击「$text」")
                   else ToolResult("", false, "未找到可点击的元素「$text」，尝试用坐标点击")
        }
        val x = args["x"]?.toFloatOrNull() ?: return ToolResult("", false, "需要提供坐标或文字")
        val y = args["y"]?.toFloatOrNull() ?: return ToolResult("", false, "需要提供坐标或文字")
        return if (svc.performClick(x, y)) ToolResult("", true, "已点击 ($x, $y)")
               else ToolResult("", false, "点击失败")
    }
}

class ScreenSwipeTool : Tool {
    override val definition = ToolDef(
        name = "screen_swipe",
        description = "在屏幕上滑动。用于翻页、下拉刷新等操作",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x1" to mapOf("type" to "integer", "description" to "起始 X"),
                "y1" to mapOf("type" to "integer", "description" to "起始 Y"),
                "x2" to mapOf("type" to "integer", "description" to "结束 X"),
                "y2" to mapOf("type" to "integer", "description" to "结束 Y"),
                "duration" to mapOf("type" to "integer", "description" to "滑动时长(ms)，默认300")
            )
        )
    )
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val svc = com.example.aichat.service.ScreenControlService.instance
            ?: return ToolResult("", false, "手机控制服务未启动。请在 系统设置→无障碍→已安装应用→命苦打工人 中开启")
        val x1 = args["x1"]?.toFloatOrNull() ?: return ToolResult("", false, "需要 x1")
        val y1 = args["y1"]?.toFloatOrNull() ?: return ToolResult("", false, "需要 y1")
        val x2 = args["x2"]?.toFloatOrNull() ?: return ToolResult("", false, "需要 x2")
        val y2 = args["y2"]?.toFloatOrNull() ?: return ToolResult("", false, "需要 y2")
        val dur = args["duration"]?.toLongOrNull() ?: 300L
        return if (svc.performSwipe(x1, y1, x2, y2, dur)) ToolResult("", true, "已滑动")
               else ToolResult("", false, "滑动失败")
    }
}

class ScreenTypeTool : Tool {
    override val definition = ToolDef(
        name = "screen_type",
        description = "在当前焦点输入框中输入文本",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("text" to mapOf("type" to "string", "description" to "要输入的文本")),
            "required" to listOf("text")
        )
    )
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val svc = com.example.aichat.service.ScreenControlService.instance
            ?: return ToolResult("", false, "手机控制服务未启动。请在 系统设置→无障碍→已安装应用→命苦打工人 中开启")
        val text = args["text"] ?: return ToolResult("", false, "需要 text")
        return if (svc.performSetText(text)) ToolResult("", true, "已输入文本")
               else ToolResult("", false, "输入失败，请先点击输入框使其获得焦点")
    }
}

class ScreenBackTool : Tool {
    override val definition = ToolDef(
        name = "screen_back",
        description = "按返回键",
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    )
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val svc = com.example.aichat.service.ScreenControlService.instance
            ?: return ToolResult("", false, "手机控制服务未启动。请在 系统设置→无障碍→已安装应用→命苦打工人 中开启")
        return if (svc.performBack()) ToolResult("", true, "已按返回")
               else ToolResult("", false, "返回失败")
    }
}

class ScreenHomeTool : Tool {
    override val definition = ToolDef(
        name = "screen_home",
        description = "按 Home 键回到桌面",
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    )
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        val svc = com.example.aichat.service.ScreenControlService.instance
            ?: return ToolResult("", false, "手机控制服务未启动。请在 系统设置→无障碍→已安装应用→命苦打工人 中开启")
        return if (svc.performHome()) ToolResult("", true, "已回桌面")
               else ToolResult("", false, "Home 失败")
    }
}

// ==================== Registry ====================

object ToolRegistry {
    private var tools: List<Tool> = emptyList()
    private val gson = Gson()

    fun init(pyManager: () -> com.example.aichat.python.PythonSessionManager?) {
        tools = listOf(
            ReadFileTool(), WriteFileTool(), DeleteFileTool(), ListFilesTool(),
            WebFetchTool(), RegexTool(), TimeTool(),
            ClipboardTool(), ShareTool(), HttpTool(),
            BuildHtmlTool(),
            PythonExecTool(pyManager), PipInstallTool(pyManager),
            PythonSessionCloseTool(pyManager),
            MemorySaveTool(), MemoryLoadTool(),
            ScreenInfoTool(), ScreenTapTool(), ScreenSwipeTool(),
            ScreenTypeTool(), ScreenBackTool(), ScreenHomeTool()
        )
    }

    fun getDefinitions(): List<ToolDef> {
        if (tools.isEmpty()) init { null }
        return tools.map { it.definition }
    }

    suspend fun execute(toolCall: ToolCall, context: Context): ToolResult {
        val tool = tools.find { it.definition.name == toolCall.name }
            ?: return ToolResult(toolCall.id, false, "未知工具: ${toolCall.name}")
        return try {
            tool.execute(toolCall.arguments, context).copy(toolCallId = toolCall.id)
        } catch (e: Exception) {
            ToolResult(toolCall.id, false, "执行错误: ${e.message}")
        }
    }

    fun toolCallsToJson(): String = gson.toJson(
        tools.map { tool ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.definition.name,
                    "description" to tool.definition.description,
                    "parameters" to tool.definition.parameters
                )
            )
        }
    )
}
