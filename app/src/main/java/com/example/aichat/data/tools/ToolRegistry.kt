package com.example.aichat.data.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.example.aichat.data.HttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

// ==================== 核心类型 ====================

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

// ==================== 工具接口 ====================

interface Tool {
    val definition: ToolDef
    suspend fun execute(args: Map<String, String>, context: Context): ToolResult
    /** 按会话执行。默认委托给 execute()；记忆类工具为隔离而覆写。 */
    suspend fun executeForConv(args: Map<String, String>, context: Context, convId: String): ToolResult =
        execute(args, context)
}

// ==================== 工作区 ====================

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

// ==================== 工具实现 ====================

class ReadFileTool : Tool {
    override val definition = ToolDef(
        name = "read_file",
        description = "读取工作区中的文件内容，支持分段读取。大文件请用 offset/limit 参数分段读",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "文件路径，如 '报告.txt'"),
                "offset" to mapOf("type" to "string", "description" to "起始字符位置，默认0（分段读取时用）"),
                "limit" to mapOf("type" to "string", "description" to "读取字符数，默认10000")
            ),
            "required" to listOf("path")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val path = args["path"] ?: return ToolResult("", false, "缺少 path 参数")
        val file = Workspace(context).resolve(path)
        if (!file.exists()) return ToolResult("", false, "文件不存在: $path")
        val offset = (args["offset"]?.trim()?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (args["limit"]?.trim()?.toIntOrNull() ?: 10000).coerceIn(1, 50000)

        return try {
            val content = when {
                file.extension.lowercase() == "docx" -> readDocx(file)
                file.length() > 500 * 1024 -> "文件过大 (${file.length() / 1024}KB)，请用 offset/limit 分段读取:\n" +
                        file.readText().substring(offset, minOf(offset + limit, file.length().toInt()))
                else -> file.readText().substring(offset, minOf(offset + limit, file.length().toInt()))
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
                    // 去除 XML 标签，保留文本
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
            val client = HttpClient.shortTimeout
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""
            // 先尝试提取有意义的内容
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

    /** 从 HTML 提取正文，跳过导航/侧边栏/广告 */
    private fun extractArticleText(html: String): String {
        // 优先使用 <article> 或 <main> 标签
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
        description = "发送 HTTP 请求到指定 URL 并返回结果",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "url" to mapOf("type" to "string", "description" to "请求 URL"),
                "method" to mapOf("type" to "string", "description" to "HTTP 方法 (GET/POST/PUT/DELETE)，默认 GET"),
                "body" to mapOf("type" to "string", "description" to "请求体（POST/PUT 时使用，JSON 字符串）")
            ),
            "required" to listOf("url")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: Context): ToolResult {
        val url = args["url"] ?: return ToolResult("", false, "缺少 url 参数")
        return try {
            val method = (args["method"] ?: "GET").uppercase()
            val body = args["body"]
            val builder = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
            when (method) {
                "POST" -> builder.post((body ?: "").toRequestBody("application/json".toMediaType()))
                "PUT" -> builder.put((body ?: "").toRequestBody("application/json".toMediaType()))
                "DELETE" -> builder.delete()
            }
            val request = builder.build()
            val client = HttpClient.shortTimeout
            val response = client.newCall(request).execute()
            val respBody = response.body?.string()?.take(5000) ?: ""
            ToolResult("", true, "HTTP ${response.code}\n$respBody")
        } catch (e: Exception) {
            ToolResult("", false, "请求失败: ${e.message}")
        }
    }
}

// ==================== 生成 HTML ====================

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

// ==================== 注册表 ====================

// ==================== Python 工具 ====================

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

    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult =
        executeForConv(args, context, "")

    override suspend fun executeForConv(args: Map<String, String>, context: android.content.Context, convId: String): ToolResult {
        val py = pyManager() ?: return ToolResult("", false, "Python 环境未初始化")
        val code = args["code"] ?: return ToolResult("", false, "缺少 code 参数")
        // 默认会话按对话隔离；模型显式指定 session 时尊重其选择
        val session = args["session"]?.takeIf { it.isNotBlank() } ?: "conv_$convId"

        // 需要时预安装包
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
            // 进度通过 onProgress 回调流式输出
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

// ==================== 命理工具（固定实现，模型只传参） ====================

/** 八字排盘：Kotlin 校验参数后调用打包内的固定脚本，模型禁止自写排盘代码 */
class BaziPaipanTool(private val pyManager: () -> com.example.aichat.python.PythonSessionManager?) : Tool {
    override val definition = ToolDef(
        name = "bazi_paipan",
        description = "八字排盘（确定性实现）。传生辰和性别，返回四柱/十神/起运/大运/流年。排盘只能用本工具，禁止自写排盘代码",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "year" to mapOf("type" to "string", "description" to "出生年（公历1900-2100）"),
                "month" to mapOf("type" to "string", "description" to "出生月（1-12）"),
                "day" to mapOf("type" to "string", "description" to "出生日（1-31）"),
                "hour" to mapOf("type" to "string", "description" to "出生时（0-23，未知可不填，将按子时假设并警告）"),
                "minute" to mapOf("type" to "string", "description" to "出生分（0-59，可选）"),
                "gender" to mapOf("type" to "string", "description" to "性别，必填：0=女 1=男"),
                "is_lunar" to mapOf("type" to "string", "description" to "输入是否为农历：0=公历(默认) 1=农历"),
                "longitude" to mapOf("type" to "string", "description" to "出生地东经度数（如成都104.06）。默认120=北京时间口径不修正；传入则按真太阳时修正")
            ),
            "required" to listOf("year", "month", "day", "gender")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult =
        executeForConv(args, context, "")

    override suspend fun executeForConv(args: Map<String, String>, context: android.content.Context, convId: String): ToolResult {
        val py = pyManager() ?: return ToolResult("", false, "Python 环境未初始化")
        val y = args["year"]?.trim()?.toIntOrNull()
            ?: return ToolResult("", false, "year 必须为数字")
        val m = args["month"]?.trim()?.toIntOrNull()
            ?: return ToolResult("", false, "month 必须为数字")
        val d = args["day"]?.trim()?.toIntOrNull()
            ?: return ToolResult("", false, "day 必须为数字")
        val g = args["gender"]?.trim()?.toIntOrNull()
            ?: return ToolResult("", false, "gender 必填：0=女 1=男")
        if (y !in 1900..2100) return ToolResult("", false, "仅支持 1900-2100 年，超出范围拒绝排盘")
        if (m !in 1..12 || d !in 1..31) return ToolResult("", false, "月/日超出合法范围")
        if (g !in 0..1) return ToolResult("", false, "gender 必须为 0(女) 或 1(男)")
        val hour = args["hour"]?.trim()?.takeIf { it.isNotEmpty() }
        hour?.toIntOrNull()?.let { if (it !in 0..23) return ToolResult("", false, "小时超出 0-23 范围") }
        val minute = (args["minute"]?.trim()?.toIntOrNull() ?: 0).let { if (it in 0..59) it else 0 }
        val isLunar = if ((args["is_lunar"]?.trim() ?: "0") == "1") 1 else 0
        val lng = args["longitude"]?.trim()?.toDoubleOrNull() ?: 120.0

        val call = buildString {
            append("from bazi_paipan import paipan\n")
            append("paipan(year=$y, month=$m, day=$d, ")
            append(if (hour == null) "hour=None" else "hour=${hour.toInt()}")
            append(", minute=$minute, gender=$g, is_lunar=$isLunar, longitude=$lng)")
        }
        val session = args["session"]?.takeIf { it.isNotBlank() } ?: "conv_$convId"
        val result = py.execute(call, session) { }
        val sb = StringBuilder()
        if (result.output.isNotBlank()) sb.appendLine(result.output)
        if (result.error.isNotBlank()) sb.appendLine("--- 错误 ---\n${result.error}")
        return ToolResult("", result.error.isEmpty(), sb.toString().trim())
    }
}

/** 日期换算：农历/干支/星期/节气/生肖，固定实现 */
class DateConvertTool(private val pyManager: () -> com.example.aichat.python.PythonSessionManager?) : Tool {
    override val definition = ToolDef(
        name = "date_convert",
        description = "日期换算（确定性实现）。任何'某日是什么干支/农历/节气/星期/生肖'的换算必须用本工具，禁止自写代码或凭记忆推算",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "date" to mapOf("type" to "string", "description" to "公历日期，如 2026-8-10 或 2026年8月10日"),
                "hour" to mapOf("type" to "string", "description" to "小时 0-23，默认12（影响时柱）")
            ),
            "required" to listOf("date")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult =
        executeForConv(args, context, "")

    override suspend fun executeForConv(args: Map<String, String>, context: android.content.Context, convId: String): ToolResult {
        val py = pyManager() ?: return ToolResult("", false, "Python 环境未初始化")
        val date = args["date"]?.trim() ?: return ToolResult("", false, "缺少 date 参数")
        if (!date.matches(Regex("[0-9年/月日\\-]{6,20}"))) return ToolResult("", false, "日期格式非法，请用 2026-8-10 或 2026年8月10日")
        val hour = (args["hour"]?.trim()?.toIntOrNull() ?: 12).let { if (it in 0..23) it else 12 }
        val call = "from bazi_paipan import date_info\ndate_info(\"${date.replace("\"", "")}\", $hour)"
        val session = args["session"]?.takeIf { it.isNotBlank() } ?: "conv_$convId"
        val result = py.execute(call, session) { }
        val sb = StringBuilder()
        if (result.output.isNotBlank()) sb.appendLine(result.output)
        if (result.error.isNotBlank()) sb.appendLine("--- 错误 ---\n${result.error}")
        return ToolResult("", result.error.isEmpty(), sb.toString().trim())
    }
}

// ===== 记忆工具（模型控制的记忆卡）=====

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
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult =
        executeForConv(args, context, "")

    override suspend fun executeForConv(args: Map<String, String>, context: android.content.Context, convId: String): ToolResult {
        val key = args["key"]?.trim() ?: return ToolResult("", false, "需要 key")
        val content = args["content"]?.trim() ?: return ToolResult("", false, "需要 content")
        // 约定（约定-xxx）单独存储，便于心跳代码管理其生命周期
        if (key.startsWith("约定-")) {
            val apptFile = java.io.File(memDirFor(convId, context), "appointments.md")
            try {
                val existing = if (apptFile.exists()) apptFile.readText() else ""
                val pattern = Regex("## ${Regex.escape(key)}\n[^#]*").find(existing)
                val body = if (pattern != null) {
                    existing.replace(pattern.value, "## $key\n$content")
                } else {
                    "$existing\n## $key\n$content".trim()
                }
                apptFile.writeText(body.trim() + "\n")
                return ToolResult("", true, "已记录约定: $key")
            } catch (e: Exception) {
                return ToolResult("", false, "约定保存失败: ${e.message}")
            }
        }
        val memDir = memDirFor(convId, context)
        val memFile = java.io.File(memDir, "memory.md")
        try {
            val existing = if (memFile.exists()) memFile.readText() else ""
            // 去重：替换同 key 的已有条目
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

/** 记忆目录，按会话隔离 */
private fun memDirFor(convId: String, context: android.content.Context): java.io.File {
    val dir = java.io.File(context.filesDir, "memory").also { it.mkdirs() }
    if (convId.isBlank()) return dir
    val sub = convId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return java.io.File(dir, sub).also { it.mkdirs() }
}

class MemoryLoadTool : Tool {
    override val definition = ToolDef(
        name = "memory_load",
        description = "读取你的长期记忆，回顾之前记住的用户偏好、项目决策和重要上下文",
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    )
    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult =
        executeForConv(args, context, "")

    override suspend fun executeForConv(args: Map<String, String>, context: android.content.Context, convId: String): ToolResult {
        val memFile = java.io.File(memDirFor(convId, context), "memory.md")
        if (!memFile.exists()) return ToolResult("", true, "(记忆为空)")
        val content = memFile.readText().take(10000)
        return ToolResult("", true, content)
    }
}

// ===== 起卦工具（纯 Kotlin 实现，省 token，模型直接调用） =====

class GuaYaoTool : Tool {
    override val definition = ToolDef(
        name = "gua_yao",
        description = "起卦占卜工具。六爻摇卦、梅花易数、小六壬，返回完整卦象和吉凶。需要占卜时直接调用，不要自己写起卦代码。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "method" to mapOf("type" to "string", "description" to "起卦方法：liuyao(六爻摇卦) / meihua(梅花易数) / xiaoliuren(小六壬)"),
                "question" to mapOf("type" to "string", "description" to "所问之事，如'问感情''问事业'（可选）"),
                "year" to mapOf("type" to "string", "description" to "农历年地支（梅花需要，如'酉'），不传用当前年"),
                "month" to mapOf("type" to "string", "description" to "农历月（梅花、小六壬需要，如'六月'）"),
                "day" to mapOf("type" to "string", "description" to "农历日（梅花、小六壬需要，如'廿九'）"),
                "hour" to mapOf("type" to "string", "description" to "农历时辰（梅花、小六壬需要，如'戌时'）")
            ),
            "required" to listOf("method")
        )
    )

    override suspend fun execute(args: Map<String, String>, context: android.content.Context): ToolResult {
        return try {
            val method = args["method"]?.trim()?.lowercase() ?: ""
            val question = args["question"]?.trim().orEmpty()
            val q = if (question.isNotBlank()) "问：$question\n" else ""
            when (method) {
                "liuyao", "六爻" -> ToolResult("", true, q + liuyao())
                "meihua", "梅花", "梅花易数" -> ToolResult("", true, q + meihua(args))
                "xiaoliuren", "小六壬" -> ToolResult("", true, q + xiaoliuren(args))
                else -> ToolResult("", false, "未知起卦方法: $method（可选 liuyao/六爻、meihua/梅花易数、xiaoliuren/小六壬）")
            }
        } catch (e: Exception) {
            ToolResult("", false, "起卦失败: ${e.message}")
        }
    }

    private fun liuyao(): String {
        // 六爻：掷三枚铜钱六次。概率按三枚铜钱：老阴(6)1/8、少阳(7)3/8、少阴(8)3/8、老阳(9)1/8
        val yao = IntArray(6) {
            when (kotlin.random.Random.nextInt(8)) {
                0 -> 6
                1, 2, 3 -> 7
                4, 5, 6 -> 8
                else -> 9
            }
        }
        val names = listOf("初爻", "二爻", "三爻", "四爻", "五爻", "上爻")
        val yaoName = mapOf(6 to "老阴", 7 to "少阳", 8 to "少阴", 9 to "老阳")
        val ben = StringBuilder()
        val bian = StringBuilder()
        val dong = mutableListOf<Int>()
        for (i in 0 until 6) {
            val y = yao[i]
            val yang = (y == 7 || y == 9)
            ben.append(if (yang) "阳" else "阴")
            if (y == 6 || y == 9) {
                dong.add(i)
                bian.append(if (yang) "阴" else "阳")
            } else {
                bian.append(if (yang) "阳" else "阴")
            }
        }
        val benStr = ben.toString()
        val bianStr = bian.toString()
        val upIdx = triToIndex(benStr.substring(3))
        val downIdx = triToIndex(benStr.substring(0, 3))
        val benGua = LIU_SHI_SI[upIdx * 8 + downIdx]
        val bianGua = guaName(bianStr)

        // 八宫、世应
        val gongIdx = GONG_IDX[upIdx * 8 + downIdx]
        val xu = GONG_XU[upIdx * 8 + downIdx]
        val shi = SHI[xu - 1]
        val ying = ((shi - 1 + 3) % 6) + 1
        val meWx = GONG_WX[gongIdx]

        val sb = StringBuilder()
        sb.append("六爻摇卦结果：\n")
        sb.append("本卦：$benGua（${BA_GUA[gongIdx]}宫）  变卦：$bianGua\n")
        sb.append("世爻：${names[shi - 1]}  应爻：${names[ying - 1]}\n")
        sb.append("动爻：${if (dong.isEmpty()) "无（静卦）" else dong.joinToString("、") { names[it] }}\n\n")
        sb.append("爻位 | 纳甲 | 六亲\n")
        for (i in 0 until 6) {
            val najia = najiaForYao(upIdx, downIdx, i)
            val liuqin = liuQin(najia, meWx)
            sb.append("${names[i]} ${yaoName[yao[i]]} | $najia | $liuqin\n")
        }
        sb.append("\n（请结合卦象、六亲、世应、动爻解读所问之事）")
        return sb.toString()
    }

    // 某爻的纳甲：内卦三爻用下卦纳甲内三爻，外卦三爻用上卦纳甲外三爻
    private fun najiaForYao(upIdx: Int, downIdx: Int, i: Int): String {
        return if (i < 3) NA_JIA[downIdx][i] else NA_JIA[upIdx][i]
    }

    // 六亲：以本卦宫五行为"我"
    private fun liuQin(najia: String, meWx: String): String {
        val zhi = najia.last().toString()
        val wx = ZHI_WX[zhi] ?: return "?"
        return when {
            wx == meWx -> "兄弟"
            sheng(wx, meWx) -> "父母"   // 生我
            sheng(meWx, wx) -> "子孙"   // 我生
            ke(wx, meWx) -> "官鬼"      // 克我
            ke(meWx, wx) -> "妻财"      // 我克
            else -> "?"
        }
    }

    private fun sheng(a: String, b: String): Boolean {
        return (a == "木" && b == "火") || (a == "火" && b == "土") || (a == "土" && b == "金") ||
               (a == "金" && b == "水") || (a == "水" && b == "木")
    }
    private fun ke(a: String, b: String): Boolean {
        return (a == "木" && b == "土") || (a == "土" && b == "水") || (a == "水" && b == "火") ||
               (a == "火" && b == "金") || (a == "金" && b == "木")
    }

    private fun meihua(args: Map<String, String>): String {
        val m = lunarNum(args["month"]) ?: return "梅花起卦需要农历月（如'六月'）"
        val d = lunarNum(args["day"]) ?: return "梅花起卦需要农历日（如'廿九'）"
        val h = shichen(args["hour"])
        // 年用地支序数（子1丑2...亥12），未提供则按公历年算地支序：(year-4)%12+1
        val nowYear = java.time.LocalDate.now().year
        val year = args["year"]?.trim()?.let { zhiXu(it) }
            ?: ((nowYear - 4) % 12 + 1)
        val sum1 = year + m + d
        val sum2 = year + m + d + h
        val upIdx = ((sum1 % 8).let { if (it == 0) 8 else it }) - 1
        val downIdx = ((sum2 % 8).let { if (it == 0) 8 else it }) - 1
        val dongPos = (sum2 % 6).let { if (it == 0) 6 else it }  // 动爻位置 1-6

        val benGua = LIU_SHI_SI[upIdx * 8 + downIdx]
        // 本卦六爻（下到上）：下卦三爻 + 上卦三爻
        val benYao = triYang(downIdx) + triYang(upIdx)  // 6 个"阳/阴"
        // 互卦：本卦二三四爻为下卦，三四五爻为上卦
        val huXia = triToIndex(benYao.substring(1, 4))
        val huShang = triToIndex(benYao.substring(2, 5))
        val huGua = LIU_SHI_SI[huShang * 8 + huXia]
        // 变卦：动爻变
        val bianArr = benYao.toCharArray()
        bianArr[dongPos - 1] = if (bianArr[dongPos - 1] == '阳') '阴' else '阳'
        val bianStr = String(bianArr)
        val bianGua = guaName(bianStr)
        // 体用：动爻所在的卦为用，另一个为体
        val ti = if (dongPos <= 3) "下卦${BA_GUA[downIdx]}" else "上卦${BA_GUA[upIdx]}"
        val yong = if (dongPos <= 3) "上卦${BA_GUA[upIdx]}" else "下卦${BA_GUA[downIdx]}"

        return buildString {
            append("梅花易数（时间起卦）：\n")
            append("上卦：${BA_GUA[upIdx]}  下卦：${BA_GUA[downIdx]}  动爻：第${dongPos}爻\n")
            append("本卦：$benGua\n")
            append("互卦：$huGua\n")
            append("变卦：$bianGua\n")
            append("体卦：$ti  用卦：$yong\n")
            append("（请结合体用生克、卦辞与动爻爻辞解读所问之事）")
        }
    }

    // 八卦索引 -> 三爻阴阳字符串（下到上）
    private fun triYang(idx: Int): String {
        return when (idx) {
            0 -> "阳阳阳"  // 乾
            1 -> "阳阳阴"  // 兑
            2 -> "阳阴阳"  // 离
            3 -> "阳阴阴"  // 震
            4 -> "阴阳阳"  // 巽
            5 -> "阴阳阴"  // 坎
            6 -> "阴阴阳"  // 艮
            else -> "阴阴阴"  // 坤
        }
    }

    // 地支序数：子1丑2...亥12
    private fun zhiXu(s: String): Int? {
        val map = mapOf("子" to 1, "丑" to 2, "寅" to 3, "卯" to 4, "辰" to 5, "巳" to 6,
            "午" to 7, "未" to 8, "申" to 9, "酉" to 10, "戌" to 11, "亥" to 12)
        val t = s.trim().removeSuffix("年")
        if (t.isBlank()) return null
        t.toIntOrNull()?.let { return it }
        // 干支如"丙午"取尾字"午"
        return map[t] ?: map[t.last().toString()]
    }

    private fun xiaoliuren(args: Map<String, String>): String {
        val m = lunarNum(args["month"]) ?: return "小六壬需要农历月（如'六月'）"
        val d = lunarNum(args["day"]) ?: return "小六壬需要农历日（如'廿九'）"
        val h = shichen(args["hour"])
        val pos = arrayOf("大安", "留连", "速喜", "赤口", "小吉", "空亡")
        val meaning = mapOf(
            "大安" to "吉，平稳顺利", "留连" to "拖延阻碍，事难速成",
            "速喜" to "喜事临门，快而有成", "赤口" to "口舌是非，宜谨言",
            "小吉" to "小事可成，和气生财", "空亡" to "谋事难成，宜守不宜进"
        )
        var i = (m - 1) % 6
        i = (i + d - 1) % 6
        i = (i + h - 1) % 6
        val p = pos[i]
        return "小六壬：$p（${meaning[p]}）\n（农历${m}月${d}日 ${args["hour"] ?: "?"}时）"
    }

    private fun lunarNum(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val digits = mapOf(
            "正" to 1, "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10, "冬" to 11, "腊" to 12
        )
        var t = s.trim()
        t = t.removeSuffix("月").removeSuffix("日").removeSuffix("号")
        if (t.isBlank()) return null
        t.toIntOrNull()?.let { return it }
        // 初X = X（如"初五"）
        if (t.startsWith("初")) {
            val tail = t.removePrefix("初")
            return digits[tail] ?: tail.toIntOrNull()
        }
        digits[t]?.let { return it }
        // 十一~十九
        if (t.startsWith("十")) {
            val tail = t.removePrefix("十")
            if (tail.isEmpty()) return 10
            digits[tail]?.let { return 10 + it }
        }
        // 廿X = 20+X
        if (t.startsWith("廿")) {
            val tail = t.removePrefix("廿")
            if (tail.isEmpty()) return 20
            digits[tail]?.let { return 20 + it }
        }
        // 二十X = 20+X（如"二十八"）
        if (t.startsWith("二十")) {
            val tail = t.removePrefix("二十")
            if (tail.isEmpty()) return 20
            digits[tail]?.let { return 20 + it }
        }
        if (t == "三十") return 30
        return null
    }

    private fun shichen(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val map = mapOf(
            "子" to 0, "丑" to 1, "寅" to 2, "卯" to 3, "辰" to 4, "巳" to 5,
            "午" to 6, "未" to 7, "申" to 8, "酉" to 9, "戌" to 10, "亥" to 11
        )
        val t = s.trim().removeSuffix("时")
        map[t]?.let { return it }
        return t.toIntOrNull() ?: 0
    }

    private fun guaName(yangStr: String): String {
        // yangStr 是 6 个"阳/阴"，从下往上；上卦=后三爻，下卦=前三爻
        val up = yangStr.substring(3)
        val down = yangStr.substring(0, 3)
        val upIdx = triToIndex(up)
        val downIdx = triToIndex(down)
        return guaNameByIndex(upIdx, downIdx)
    }

    private fun triToIndex(tri: String): Int {
        // 三爻（下到上，初爻=bit0）转八卦索引 0-7
        var v = 0
        for (i in 0 until 3) if (tri[i] == '阳') v = v or (1 shl i)
        // 乾111=7, 兑011=3, 离101=5, 震001=1, 巽110=6, 坎010=2, 艮100=4, 坤000=0
        return when (v) {
            7 -> 0  // 乾
            3 -> 1  // 兑
            5 -> 2  // 离
            1 -> 3  // 震
            6 -> 4  // 巽
            2 -> 5  // 坎
            4 -> 6  // 艮
            else -> 7  // 坤
        }
    }

    private fun guaNameByIndex(upIdx: Int, downIdx: Int): String {
        return LIU_SHI_SI[upIdx * 8 + downIdx]
    }

    companion object {
        private val BA_GUA = listOf("乾", "兑", "离", "震", "巽", "坎", "艮", "坤")
        private val LIU_SHI_SI = arrayOf(
            // 上乾
            "乾为天", "天泽履", "天火同人", "天雷无妄", "天风姤", "天水讼", "天山遁", "天地否",
            // 上兑
            "泽天夬", "兑为泽", "泽火革", "泽雷随", "泽风大过", "泽水困", "泽山咸", "泽地萃",
            // 上离
            "火天大有", "火泽睽", "离为火", "火雷噬嗑", "火风鼎", "火水未济", "火山旅", "火地晋",
            // 上震
            "雷天大壮", "雷泽归妹", "雷火丰", "震为雷", "雷风恒", "雷水解", "雷山小过", "雷地豫",
            // 上巽
            "风天小畜", "风泽中孚", "风火家人", "风雷益", "巽为风", "风水涣", "风山渐", "风地观",
            // 上坎
            "水天需", "水泽节", "水火既济", "水雷屯", "水风井", "坎为水", "水山蹇", "水地比",
            // 上艮
            "山天大畜", "山泽损", "山火贲", "山雷颐", "山风蛊", "山水蒙", "艮为山", "山地剥",
            // 上坤
            "地天泰", "地泽临", "地火明夷", "地雷复", "地风升", "地水师", "地山谦", "坤为地"
        )
        // 八卦纳甲（初爻到上爻，天干+地支），京房纳甲
        private val NA_JIA = arrayOf(
            arrayOf("甲子", "甲寅", "甲辰", "壬午", "壬申", "壬戌"),  // 乾
            arrayOf("丁巳", "丁卯", "丁丑", "丁亥", "丁酉", "丁未"),  // 兑
            arrayOf("己卯", "己丑", "己亥", "己酉", "己未", "己巳"),  // 离
            arrayOf("庚子", "庚寅", "庚辰", "庚午", "庚申", "庚戌"),  // 震
            arrayOf("辛丑", "辛亥", "辛酉", "辛未", "辛巳", "辛卯"),  // 巽
            arrayOf("戊寅", "戊辰", "戊午", "戊申", "戊戌", "戊子"),  // 坎
            arrayOf("丙辰", "丙午", "丙申", "丙戌", "丙子", "丙寅"),  // 艮
            arrayOf("乙未", "乙巳", "乙卯", "癸丑", "癸亥", "癸酉")   // 坤
        )
        // 地支五行
        private val ZHI_WX = mapOf(
            "子" to "水", "丑" to "土", "寅" to "木", "卯" to "木", "辰" to "土", "巳" to "火",
            "午" to "火", "未" to "土", "申" to "金", "酉" to "金", "戌" to "土", "亥" to "水"
        )
        // 宫五行（按八卦索引）
        private val GONG_WX = arrayOf("金", "金", "火", "木", "木", "水", "土", "土")
        // 64卦所属八宫（按 LIU_SHI_SI 顺序）
        private val GONG_IDX = intArrayOf(0,6,2,4,0,2,0,0,7,1,5,3,3,1,1,1,0,6,2,4,2,2,2,0,7,1,5,3,3,3,1,3,4,6,4,4,4,2,6,0,7,5,5,5,3,5,1,7,6,6,6,4,4,2,6,0,7,7,5,7,3,5,1,7)
        private val GONG_XU = intArrayOf(1,6,8,5,2,7,3,4,6,1,5,8,7,2,4,3,8,5,1,6,3,4,2,7,5,8,6,1,4,3,7,2,2,7,3,4,1,6,8,5,7,2,4,3,6,1,5,8,3,4,2,7,8,5,1,6,4,3,7,2,5,8,6,1)
        // 世爻位置（按宫序 1-8）
        private val SHI = intArrayOf(6, 1, 2, 3, 4, 5, 4, 3)
    }
}

// ===== 屏幕控制工具 =====

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

// ==================== 注册表 ====================

object ToolRegistry {
    private var tools: List<Tool> = emptyList()
    private val gson = Gson()

    fun init(pyManager: () -> com.example.aichat.python.PythonSessionManager?) {
        tools = listOf(
            ReadFileTool(), WriteFileTool(), DeleteFileTool(), ListFilesTool(),
            WebFetchTool(), RegexTool(), TimeTool(),
            ClipboardTool(), ShareTool(), HttpTool(),
            BuildHtmlTool(),
            PythonExecTool(pyManager),
            PythonSessionCloseTool(pyManager),
            BaziPaipanTool(pyManager), DateConvertTool(pyManager),
            MemorySaveTool(), MemoryLoadTool(),
            GuaYaoTool(),
            ScreenInfoTool(), ScreenTapTool(), ScreenSwipeTool(),
            ScreenTypeTool(), ScreenBackTool(), ScreenHomeTool()
        )
    }

    fun getDefinitions(): List<ToolDef> {
        if (tools.isEmpty()) init { null }
        return tools.map { it.definition }
    }

    // 输出闸门策略：KEEP 不截断 / COMPACT 宽松上限 / SPILL 4KB+落盘指针
    private enum class Gate { KEEP, COMPACT, SPILL }

    private fun gateFor(name: String): Gate = when (name) {
        "get_time", "gua_yao", "regex", "memory_save", "memory_load",
        "clipboard_write", "share_text", "session_close", "date_convert",
        "delete_file", "write_file" -> Gate.KEEP
        "python_exec", "bazi_paipan", "list_files" -> Gate.COMPACT
        "web_fetch", "read_file", "http_request" -> Gate.SPILL
        else -> Gate.COMPACT
    }

    suspend fun execute(toolCall: ToolCall, context: Context, convId: String = ""): ToolResult {
        val tool = tools.find { it.definition.name == toolCall.name }
            ?: return ToolResult(toolCall.id, false, "未知工具: ${toolCall.name}")
        val raw = try {
            tool.executeForConv(toolCall.arguments, context, convId).copy(toolCallId = toolCall.id)
        } catch (e: Exception) {
            return ToolResult(toolCall.id, false, "执行错误: ${e.message}")
        }
        return gate(raw, toolCall.name, context, convId)
    }

    /** 错误优先闸门：失败结果错误置顶、全文保留；成功结果按策略截断并落盘 */
    private fun gate(result: ToolResult, name: String, context: Context, convId: String): ToolResult {
        if (!result.success) {
            // 错误永不截断（只防极端 runaway 时保尾部，traceback 关键在最后）
            val body = if (result.content.length > 6000) result.content.takeLast(6000) else result.content
            return result.copy(content = "❌ 执行失败（代码未能完成，以下输出不完整、不可用于结论）\n--- 错误信息 ---\n$body")
        }
        val policy = gateFor(name)
        if (policy == Gate.KEEP) return result
        val limit = if (policy == Gate.COMPACT) 12000 else 4000
        if (result.content.length <= limit) return result

        // 语义边界切割：优先段落边界，其次行边界，不切表格行
        var cut = limit
        val head = result.content
        val para = head.lastIndexOf("\n\n", limit)
        if (para > limit / 2) cut = para
        else {
            val line = head.lastIndexOf("\n", limit)
            if (line > limit / 2) cut = line
        }
        val headPart = head.take(cut)

        // 落盘 + 指针
        val spillDir = File(context.filesDir, "workspace").let { root ->
            if (convId.isBlank()) File(root, ".tool_outputs")
            else File(root, "${convId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}/.tool_outputs")
        }.also { it.mkdirs() }
        val fileName = "tool_${System.currentTimeMillis()}.txt"
        try { File(spillDir, fileName).writeText(result.content) } catch (_: Exception) {}
        val pointer = if (convId.isBlank()) ".tool_outputs/$fileName"
            else "${convId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}/.tool_outputs/$fileName"

        return result.copy(content = buildString {
            appendLine("[结果过长已存盘: workspace/$pointer (共${result.content.length / 1024}KB)]")
            appendLine("前 $cut 字符内容如下：")
            appendLine(headPart)
            appendLine("...")
            appendLine("如需完整内容，用 read_file 读取 workspace/$pointer（大文件可加 offset/limit 参数分段读）")
        })
    }

    // 命理师工具子集：只下发命理相关的工具，减少模型选择负担与 token
    private val FORTUNE_TOOLS = setOf(
        "python_exec", "session_close", "bazi_paipan", "date_convert", "gua_yao",
        "memory_save", "memory_load", "read_file", "write_file", "list_files",
        "web_fetch", "regex", "get_time"
    )

    fun toolCallsToJson(personaId: String = "", screenAvailable: Boolean = false): String = gson.toJson(
        tools.filter { tool ->
            val n = tool.definition.name
            when {
                personaId == "fortune" -> n in FORTUNE_TOOLS
                n == "gua_yao" -> false
                n.startsWith("screen_") -> screenAvailable
                else -> true
            }
        }.map { tool ->
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
