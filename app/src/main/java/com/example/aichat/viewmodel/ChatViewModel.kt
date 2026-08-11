package com.example.aichat.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.*
import com.example.aichat.data.tools.*
import com.example.aichat.python.PythonSessionManager
import com.example.aichat.service.ActiveModeService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class AgentStep(
    val type: String,        // "tool_call" | "tool_result" | "thinking" | "final"
    val toolName: String = "",
    val toolArgs: String = "",
    val content: String = ""
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val storage = StorageManager(application)
    val workspace = Workspace(application)
    val pyManager = PythonSessionManager(application)

    init {
        ToolRegistry.init { pyManager }
    }

    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    // Parallel conversations: one Job per conversation ID
    private val currentJobs = mutableMapOf<String, Job>()
    private val loadingConvs = mutableSetOf<String>()

    fun cancelLoading() {
        currentJobs.remove(currentConvId)?.cancel()
        loadingConvs.remove(currentConvId)
        isLoading = currentConvId in loadingConvs
    }

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var conversationTitle by mutableStateOf("")
        private set

    var searchEnabled by mutableStateOf(false)

    var factCheckEnabled by mutableStateOf(false)

    var agentSteps by mutableStateOf<List<AgentStep>>(emptyList())
        private set

    var pendingImageUri by mutableStateOf<String?>(null)

    var pendingFileText by mutableStateOf<Pair<String, String>?>(null)  // (fileName, ext)
    var fileTextContent by mutableStateOf("")  // pre-extracted text for text-based files

    var activePersonaId by mutableStateOf("default")

    // Plan mode state
    var currentPlan by mutableStateOf<TaskPlan?>(null)
        private set
    var planPhase by mutableStateOf<PlanPhase>(PlanPhase.IDLE)
        private set
    val completedTaskIds = mutableSetOf<Int>()

    // HTML preview state
    var previewItems by mutableStateOf<List<PreviewItem>>(emptyList())
        private set
    var activePreviewIndex by mutableStateOf(-1)
        private set
    var previewMode by mutableStateOf(PreviewMode.COLLAPSED)
        private set

    data class PreviewItem(val file: String, val title: String)
    enum class PreviewMode { COLLAPSED, HALF, FULL }

    fun togglePreview() {
        previewMode = when (previewMode) {
            PreviewMode.COLLAPSED -> { activePreviewIndex = previewItems.lastIndex; PreviewMode.HALF }
            PreviewMode.HALF -> PreviewMode.FULL
            PreviewMode.FULL -> PreviewMode.COLLAPSED
        }
    }
    fun selectPreview(index: Int) {
        activePreviewIndex = index.coerceIn(0, previewItems.lastIndex)
        if (previewMode == PreviewMode.COLLAPSED) previewMode = PreviewMode.HALF
    }
    fun collapsePreview() { previewMode = PreviewMode.COLLAPSED; activePreviewIndex = -1 }

    /** Open a workspace file in the preview panel (HTML) or prepare for text viewer */
    fun addPreviewItem(fileName: String) {
        previewItems = previewItems.filter { it.file != fileName } + PreviewItem(file = fileName, title = fileName)
        activePreviewIndex = previewItems.lastIndex
        previewMode = PreviewMode.HALF
    }

    /** Create a share intent URI for a workspace file */
    fun shareUri(fileName: String): android.net.Uri {
        val ctx = getApplication<android.app.Application>()
        val file = java.io.File(ctx.filesDir, "workspace/$fileName")
        return androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }
    fun activePreviewFile(): String? = previewItems.getOrNull(activePreviewIndex)?.file
    fun activePreviewTitle(): String? = previewItems.getOrNull(activePreviewIndex)?.title

    // Learning mode
    var learningMode by mutableStateOf(false)

    // Dangerous operation confirmation
    var pendingConfirmation by mutableStateOf<DangerousOp?>(null)
        private set

    // Resume state
    var hasSavedState by mutableStateOf(false)
        private set
    var resumePending by mutableStateOf(false)
        private set

    data class DangerousOp(
        val toolName: String,
        val message: String,
        val onConfirm: () -> Unit
    )

    enum class PlanPhase { IDLE, GENERATING, REVIEWING, EXECUTING, COMPLETED }

    fun getActivePersona(ctx: android.content.Context? = null): Persona =
        if (ctx != null) Personas.getByIdWithCustom(activePersonaId, ctx)
        else Personas.getById(activePersonaId)

    fun confirmDangerousOp() {
        pendingConfirmation?.onConfirm?.invoke()
        pendingConfirmation = null
    }

    fun rejectDangerousOp() {
        pendingConfirmation = null
    }

    fun confirmPlan(useOptimized: Boolean = false) {
        val plan = currentPlan ?: return
        if (useOptimized && plan.optimizations.isNotEmpty()) {
            currentPlan = plan.copy(
                tasks = plan.tasks.map { task ->
                    val opt = plan.optimizations.find { it.original.contains(task.description) }
                    if (opt != null) task.copy(description = opt.better)
                    else task
                }
            )
        }
        planPhase = PlanPhase.EXECUTING
    }

    /** Trigger agent to execute the confirmed plan. Called from UI after confirm. */
    fun executeCurrentPlan(profile: ApiProfile) {
        val plan = currentPlan ?: return
        if (planPhase != PlanPhase.EXECUTING) return
        val planContext = buildString {
            appendLine("请按以下计划逐步执行：")
            plan.tasks.forEachIndexed { i, t ->
                appendLine("${i + 1}. ${t.description}${if (t.tool.isNotBlank()) " [建议工具: ${t.tool}]" else ""}")
            }
            if (plan.optimizations.isNotEmpty()) {
                appendLine("\n优化建议：")
                plan.optimizations.forEach { o ->
                    appendLine("- ${o.original} → ${o.better}（${o.reason}）")
                }
            }
            appendLine("\n请逐步执行，每步完成后汇报进度。完成后给出总结。")
        }
        sendMessage(planContext, profile)
    }

    fun rejectPlan() {
        currentPlan = null
        planPhase = PlanPhase.IDLE
    }

    fun forgetPreviews() {
        previewItems = emptyList()
        activePreviewIndex = -1
        previewMode = PreviewMode.COLLAPSED
    }

    private var currentConvId: String = ""
    private var apiService: ApiService? = null
    private var lastBaseUrl: String = ""
    private val gson = Gson()

    fun currentConversationId(): String = currentConvId

    /** Persist messages to a specific conversation. Sync UI only if that conversation is being viewed. */
    private fun commitMessages(convId: String, msgs: List<ChatMessage>) {
        storage.updateMessages(convId, msgs)
        if (convId == currentConvId) messages = msgs
    }

    /** Append a user message to a specific conversation, independent of current UI state. */
    private fun commitUserMessage(convId: String, msg: ChatMessage) {
        val conv = storage.getConversation(convId)
        val msgs = (conv?.messages ?: emptyList()) + msg
        storage.updateMessages(convId, msgs)
        if (convId == currentConvId) messages = msgs
    }

    fun loadConversation(convId: String) {
        if (convId == currentConvId && messages.isNotEmpty()) return
        currentConvId = convId
        val conv = storage.getConversation(convId)
        messages = conv?.messages ?: emptyList()
        conversationTitle = conv?.title ?: ""
        errorMessage = null
        agentSteps = emptyList()
        currentPlan = null
        planPhase = PlanPhase.IDLE
        completedTaskIds.clear()
        // Per-conversation loading state: this conversation may still be loading in background
        isLoading = convId in loadingConvs
        // Check for breakpoint
        restoreState()
    }

    fun clearError() {
        errorMessage = null
    }

    fun setPendingImage(uri: String?) { pendingImageUri = uri }

    fun sendMessage(content: String, profile: ApiProfile) {
        val hasImage = pendingImageUri != null
        val hasFile = pendingFileText != null
        if (content.isBlank() && !hasImage && !hasFile) return
        // Per-conversation isolation: only block if THIS conversation is loading
        val myConvId = currentConvId
        if (myConvId in loadingConvs) return

        // Build API content (with file text if applicable)
        var apiContent = content.ifBlank {
            if (hasImage) "请描述这张图片" else ""
        }
        val fileInfo = pendingFileText
        if (fileInfo != null) {
            val (fileName, _) = fileInfo
            val wsDir = java.io.File(getApplication<android.app.Application>().filesDir, "workspace")
            val file = java.io.File(wsDir, fileName)
            val sizeKB = if (file.exists()) file.length() / 1024 else 0
            val textPreview = fileTextContent

            val tip = if (textPreview.isNotBlank()) {
                "【上传文件：$fileName（${sizeKB}KB）】\n\n以下为文件文本内容预览：\n\n$textPreview\n\n---\n用户问题：${content.ifBlank { "请分析以上文件" }}"
            } else {
                "【上传文件：$fileName（${sizeKB}KB）】\n\n文件已保存到 workspace/$fileName。该文件为二进制格式，请使用 Python 工具或 read_file 读取其内容。\n\n用户问题：${content.ifBlank { "请处理以上文件" }}"
            }

            apiContent = tip
            pendingFileText = null
            fileTextContent = ""
        }

        val userMessage = ChatMessage(
            role = "user",
            content = apiContent,
            imageUri = pendingImageUri
        )
        val imageUri = pendingImageUri
        pendingImageUri = null
        // Commit user message to storage; sync UI only if this conversation is being viewed
        commitUserMessage(myConvId, userMessage)
        loadingConvs.add(myConvId)
        isLoading = myConvId in loadingConvs
        errorMessage = null
        // Keep previous agent steps, don't clear
        // agentSteps = emptyList() — removed to preserve history

        if (lastBaseUrl != profile.baseUrl) {
            apiService = null
            lastBaseUrl = profile.baseUrl
        }

        // Run on IO thread so backgrounding doesn't cancel network calls
        currentJobs[myConvId] = viewModelScope.launch(Dispatchers.IO) {
            // This conversation's own message list — independent of shared UI state
            var myMsgs = storage.getConversation(myConvId)?.messages ?: emptyList()
            try {
                // Clear plan state for new message (unless executing plan)
                if (planPhase != PlanPhase.EXECUTING) {
                    currentPlan = null
                    planPhase = PlanPhase.IDLE
                    forgetPreviews()
                }

                // Build conversation history as DTOs
                val conversationDtos = mutableListOf<ChatMessageDto>()

                // Static persona + rules (always first for DeepSeek prefix caching)
                val persona = getActivePersona(getApplication<android.app.Application>())
                val workspaceFiles = workspaceStatus(myConvId)
                conversationDtos.add(
                    ChatMessageDto(role = "system",
                        content = persona.prompt + "\n\n" + """
Agent 助手。你拥有工具，不要凭记忆回答可验证的事实。

## 工具使用铁律
① **时间和日期**：任何时候涉及"现在""今天""当前"，必须用 python_exec 执行 `import datetime; print(datetime.datetime.now())` 获取真实时间，禁止用训练数据中的时间。
⑪ **日期换算（硬性）**：任何"某月某日是什么干支/农历/节气/星期"的换算问题，必须用 python_exec 调用 lunar_python 或 datetime 计算，禁止凭记忆推算。示例：`from lunar_python import Solar; s=Solar.fromYmd(2026,8,10); l=s.getLunar(); print('农历:', l.toString()); print('日干支:', l.getEightChar().getDay())`。日期算错是最严重的错误，宁可调用工具也不要猜。
② **计算和数据**：涉及数字计算、数据分析、表格处理，必须用 python_exec，不要心算。
③ **实时信息**：天气、新闻、股价、汇率等，用 web_fetch 搜索，不要编造。
④ **文件操作**：读写文件用 read_file / write_file，生成页面用 build_html。
⑤ **写完自测**：任何代码写完后立即 python_exec 跑一遍验证。
⑥ **对外回答**：最终回答用户时，禁止说"我用了 xxx 工具""根据 xxx 的执行结果"等内部过程描述。直接给结论，像你本来就该知道一样。
⑦ **长期记忆**：每次发现用户偏好、项目决策、重要上下文时，立即用 `memory_save` 记下来。下次对话开始时会自动加载你的记忆，这样你不会忘。
⑧ **对话隔离**：工作区和记忆都按对话隔离。write_file / build_html 保存文件时，写到 workspace/${if (myConvId.isBlank()) "" else sanitize(myConvId) + "/"} 目录下（你的专属目录）。默认只查看工作区中本对话的文件。如果用户主动要求查看其他对话或全局文件，可以用 read_file 读取完整路径。
⑨ **工具调用纪律**：需要计算、执行代码、搜索、读写文件时，直接发出工具调用（function calling），系统会自动执行并把结果返回给你。禁止把工具调用、代码、JSON 结构写进你的回答文本——回答里只放最终结论。禁止假装执行（如"我用Python算了一下结果是X"）——没调用工具就是没算。
⑩ **约定识别**：当用户提到未来的约定或承诺（"一会儿""等下""晚上""明天""下周"+要做的事）时，立即用 memory_save 记录。key 格式"约定-xxx"，content 必须写明：约定内容 + 约定时间（参照"## 当前时间"推算）。这样即使过很久，你也能在约定的时间提起它。

$workspaceFiles

${PlanParser.planInstruction()}
""".trimIndent()
                    )
                )

                // Collect dynamic system messages (inserted AFTER history for cache friendliness)
                val dynamicSystemMsgs = mutableListOf<ChatMessageDto>()

                // Real-time clock so the model always knows the current time
                val now = java.time.LocalDateTime.now()
                val nowStr = "${now.year}年${now.monthValue}月${now.dayOfMonth}日 ${now.hour}:${String.format("%02d", now.minute)}"
                dynamicSystemMsgs.add(ChatMessageDto(role = "system",
                    content = "## 当前时间\n\n现在是 $nowStr。判断约定/待办/时效性话题时以此为准。"))

                // Add search/verify context (run on IO)
                val effectiveSearch = searchEnabled || factCheckEnabled
                if (effectiveSearch) {
                    val searchContext = performSearch(content)
                    if (searchContext.isNotBlank()) {
                        dynamicSystemMsgs.add(
                            ChatMessageDto(role = "system",
                                content = "以下是关于用户问题的网络搜索结果，请基于这些信息回答：\n\n$searchContext")
                        )
                    }
                }

                // Fact-check mode prompt
                if (factCheckEnabled) {
                    dynamicSystemMsgs.add(
                        ChatMessageDto(role = "system",
                            content = "你处于「事实查证模式」。请严格遵守：\n1. 对每条断言进行核实，优先使用官方来源\n2. 关键事实必须附上引用来源（格式：[来源：URL]）\n3. 无法从可靠来源验证的信息请标注「未核实」\n4. 仅传播有可靠来源支撑的信息")
                    )
                }

                // Vision pre-processing
                if (imageUri != null && profile.visionModel.isNotBlank()) {
                    val (visionDesc, _) = describeImage(imageUri, content, profile)
                    if (visionDesc != null) {
                        dynamicSystemMsgs.add(ChatMessageDto(
                            role = "system",
                            content = "用户发送了一张图片，视觉模型描述如下：\n\n$visionDesc\n\n请基于以上描述回答用户后续问题。注意不要透露这段描述的存在，直接自然地回答。"
                        ))
                        withContext(Dispatchers.Main) {
                            agentSteps = agentSteps + AgentStep(type = "tool_call", toolName = profile.visionModel, toolArgs = "识别图片")
                            agentSteps = agentSteps + AgentStep(type = "tool_result", toolName = profile.visionModel, content = visionDesc)
                        }
                    }
                }

                // Add message history — sliding window: last 8 turns + summary of older
                val historyMsgs = myMsgs.map { msg ->
                    if (msg.imageUri != null && msg.role == "user" && profile.visionModel.isBlank()) {
                        val contentsList = mutableListOf<Map<String, Any?>>()
                        if (msg.content.isNotBlank()) contentsList.add(mapOf("type" to "text", "text" to msg.content))
                        contentsList.add(mapOf("type" to "image_url", "image_url" to mapOf("url" to msg.imageUri)))
                        ChatMessageDto(role = msg.role, content = contentsList)
                    } else {
                        ChatMessageDto(role = msg.role, content = msg.content)
                    }
                }
                // Keep system messages + last 16 user/assistant pairs, smart-summarize the rest
                val keepRecent = 16
                val userAssistant = historyMsgs.filter { it.role != "system" }
                if (userAssistant.size > keepRecent * 2) {
                    val dropped = userAssistant.dropLast(keepRecent * 2)
                    val oldCount = dropped.size
                    // Build a real summary from what's being dropped
                    val userQs = dropped.filter { it.role == "user" }.mapNotNull { (it.content?.toString() ?: "").take(80) }
                    val asReplies = dropped.filter { it.role == "assistant" }.mapNotNull { (it.content?.toString() ?: "").take(80) }
                    val summary = buildString {
                        append("上下文摘要（前面 $oldCount 条消息）：")
                        if (userQs.isNotEmpty()) {
                            append("用户处理过：")
                            userQs.take(5).forEachIndexed { i, q -> if (q.isNotBlank()) append("  ${i + 1}. $q") }
                        }
                        if (asReplies.isNotEmpty()) {
                            append(" 已完成：")
                            asReplies.takeLast(5).forEachIndexed { i, r -> if (r.isNotBlank()) append("  ${i + 1}. $r") }
                        }
                    }.take(600)
                    conversationDtos.addAll(historyMsgs.filter { it.role == "system" })
                    conversationDtos.add(ChatMessageDto(role = "system", content = summary))
                    conversationDtos.addAll(userAssistant.takeLast(keepRecent * 2))
                } else {
                    conversationDtos.addAll(historyMsgs)
                }

                // Learning mode
                if (learningMode) {
                    dynamicSystemMsgs.add(ChatMessageDto(role = "system",
                        content = "你处于「学习模式」。每个操作完成后，用 [WHY] 起头，用 1-2 句话解释你为什么要这样做、为什么选择这个工具而不是其他方案。"))
                }

                // Auto-load model's workspace memory (isolated per conversation)
                val memFile = java.io.File(memoryDir(myConvId), "memory.md")
                if (memFile.exists() && memFile.readText().isNotBlank()) {
                    val mem = memFile.readText().take(4000)
                    dynamicSystemMsgs.add(ChatMessageDto(role = "system",
                        content = "## 你的长期记忆\n\n以下是之前对话中你主动记录的重要信息（用 memory_load 可随时重读）：\n\n$mem"))
                    // Proactive recall: scan user message for keywords matching memory entries
                    val memContent = memFile.readText()
                    val userLower = content.lowercase()
                    val sections = memContent.split(Regex("^## ", RegexOption.MULTILINE)).filter { it.isNotBlank() }
                    val matched = sections.mapNotNull { sec ->
                        val lines = sec.trim().split("\n", limit = 2)
                        val key = lines.firstOrNull()?.trim() ?: return@mapNotNull null
                        val body = lines.getOrNull(1)?.trim() ?: ""
                        // Check if user message mentions the key or key words from the body
                        val keyWords = (key + " " + body).split(Regex("[\\s,，、。.；;:：]"))
                            .filter { it.length > 2 }
                        if (keyWords.any { kw -> userLower.contains(kw.lowercase()) }) {
                            "## $key\n$body"
                        } else null
                    }
                    if (matched.isNotEmpty()) {
                        dynamicSystemMsgs.add(ChatMessageDto(role = "system",
                            content = "## 记忆召回（与当前问题相关）\n\n${matched.joinToString("\n\n")}"))
                    }
                }

                // User memory（按对话隔离）
                val memory = loadUserMemory(myConvId)
                if (memory.isNotBlank()) {
                    dynamicSystemMsgs.add(ChatMessageDto(role = "system",
                        content = "## 用户记忆\n\n以下是之前对话中记录的偏好和习惯，请在思考和决策时参考：\n\n$memory"))
                }

                // Append dynamic system messages after history (after static prefix for cache)
                conversationDtos.addAll(dynamicSystemMsgs)

                // Agent loop: no hard round limit, model decides when to stop
                var finishReason: String? = null
                var round = 0
                val consecutiveErrors = mutableMapOf<String, Int>()

                while (finishReason != "stop" && isActive) {
                    round++

                    // Context compression: aggressively trim when over 60K chars (~20K tokens)
                    val totalChars = conversationDtos.sumOf { (it.content?.toString()?.length ?: 0) }
                    if (totalChars > 60_000 && conversationDtos.size > 8) {
                        val oldTools = conversationDtos.filter { it.role == "tool" }
                        if (oldTools.size > 3) {
                            val remove = oldTools.dropLast(3)
                            // Smart summary: extract what these tool calls actually did
                            val toolNames = remove.mapNotNull { dto ->
                                conversationDtos.find { it.toolCallId == dto.toolCallId }?.let { _ ->
                                    // Find the assistant message that called this tool
                                    val callMsg = conversationDtos.find { m ->
                                        m.toolCalls?.any { tc -> tc.id == dto.toolCallId } == true
                                    }
                                    callMsg?.toolCalls?.find { tc -> tc.id == dto.toolCallId }?.function?.name
                                }
                            }.distinct().take(5)
                            val toolOutcomes = remove.joinToString("; ") { (it.content?.toString() ?: "").take(100) }
                                .replace(Regex("\\s+"), " ").take(300)
                            conversationDtos.removeAll { it in remove }
                            conversationDtos.add(2, ChatMessageDto(role = "system",
                                content = "已使用的工具（${remove.size}次）：${toolNames.joinToString("，")}。结果摘要：$toolOutcomes"))
                        }
                        // Trim oldest user/assistant pairs beyond 16
                        val ua = conversationDtos.filter { it.role == "user" || it.role == "assistant" }
                        if (ua.size > 32) {
                            val drop = ua.take(ua.size - 32)
                            // Save critical context to memory before dropping
                            val userMsgs = drop.filter { it.role == "user" }
                            val keyRequests = userMsgs.mapNotNull { (it.content?.toString() ?: "").take(200) }
                                .filter { it.length > 20 }
                            if (keyRequests.isNotEmpty()) {
                                saveUserMemory(myConvId, "用户曾处理：${keyRequests.take(3).joinToString("；")}")
                            }
                            conversationDtos.removeAll { it in drop }
                        }
                    }

                    // Error loop detection: if same tool+error 3+ times, nudge
                    val worstError = consecutiveErrors.entries.maxByOrNull { it.value }
                    if (worstError != null && worstError.value >= 3) {
                        conversationDtos.add(ChatMessageDto(role = "system",
                            content = "工具 '${worstError.key}' 已连续失败 ${worstError.value} 次。请尝试其他方法，不要重复调用此工具。"))
                        consecutiveErrors.clear()
                    }

                    val request = ChatRequest(
                        model = profile.model,
                        messages = conversationDtos,
                        // Agent loop: use "low" reasoning instead of "max" — fast enough for tool
                        // decisions but still gives the model a chance to think before acting.
                        // Final SSE streaming answer still uses "max" for deep reasoning.
                        // Companionship conversations hide thinking entirely
                        reasoningEffort = if (profile.thinkingEnabled && myConvId !in ActiveModeService.runningConversations) "low" else null,
                        thinking = if (profile.thinkingEnabled && myConvId !in ActiveModeService.runningConversations) mapOf("type" to "enabled") else null,
                        tools = gson.fromJson(
                            ToolRegistry.toolCallsToJson(),
                            object : TypeToken<List<Map<String, Any>>>() {}.type
                        ),
                        stream = false
                    )

                    val response = getApiService(profile).chatCompletion(
                        auth = "Bearer ${profile.apiKey}",
                        request = request
                    )
                    if (!response.isSuccessful) {
                        val errorBody = response.errorBody()?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            errorMessage = "API 错误 ${response.code()}: ${response.message()}\n${errorBody.take(200)}"
                        }
                        return@launch
                    }
                    val choice = response.body()?.choices?.firstOrNull()
                    finishReason = choice?.finishReason
                    val msg = choice?.message ?: continue

                    // ALWAYS process tool calls if present, regardless of finishReason
                    if (!msg.toolCalls.isNullOrEmpty()) {
                        // Preserve any text the model emitted alongside tool calls
                        if (!msg.content.isNullOrBlank()) {
                            myMsgs = myMsgs + ChatMessage(role = "assistant", content = msg.content)
                            commitMessages(myConvId, myMsgs)
                            conversationDtos.add(ChatMessageDto(role = "assistant", content = msg.content))
                        }
                        // Don't pass the model's "我来搜索..." chatter to API — it's noise
                        conversationDtos.add(ChatMessageDto(role = "assistant", content = null, toolCalls = msg.toolCalls))
                        for (tc in msg.toolCalls) {
                            val args: Map<String, String> = try {
                                gson.fromJson(tc.function.arguments, object : TypeToken<Map<String, String>>() {}.type)
                            } catch (e: Exception) { mapOf("_raw" to tc.function.arguments) }
                            withContext(Dispatchers.Main) {
                                agentSteps = agentSteps + AgentStep(type = "tool_call", toolName = tc.function.name, toolArgs = tc.function.arguments)
                            }
                            val result = ToolRegistry.execute(ToolCall(id = tc.id, name = tc.function.name, arguments = args), getApplication(), myConvId)
                            // Preview detection: build_html output
                            val previewJson = Regex("""\{"type":"preview","file":"([^"]+)","title":"([^"]*)"\}""").find(result.content)
                            if (previewJson != null && tc.function.name == "build_html") {
                                val f = previewJson.groupValues[1]
                                val t = previewJson.groupValues[2].ifBlank { f }
                                withContext(Dispatchers.Main) {
                                    // Deduplicate by file name
                                    previewItems = previewItems.filter { it.file != f } + PreviewItem(f, t)
                                    activePreviewIndex = previewItems.lastIndex
                                    previewMode = PreviewMode.HALF
                                }
                            }
                            // Error tracking
                            val errKey = if (!result.success) "${tc.function.name}:${result.content.take(60)}" else ""
                            if (errKey.isNotBlank()) {
                                consecutiveErrors[errKey] = (consecutiveErrors[errKey] ?: 0) + 1
                            } else {
                                consecutiveErrors.clear()
                            }
                            withContext(Dispatchers.Main) {
                                agentSteps = agentSteps + AgentStep(type = "tool_result", toolName = tc.function.name, content = result.content)
                            }
                            conversationDtos.add(ChatMessageDto(role = "tool", content = result.content, toolCallId = tc.id))
                        }
                        // Save breakpoint after each tool round
                        if (planPhase == PlanPhase.EXECUTING) {
                            saveState(conversationDtos, round)
                        }
                    } else {
                        // Plan detection: check if model output contains a plan JSON
                        val textContent = msg.content ?: ""
                        if (planPhase != PlanPhase.EXECUTING && textContent.contains("\"tasks\"")) {
                            val parsed = PlanParser.tryParse(textContent)
                            if (parsed != null) {
                                withContext(Dispatchers.Main) {
                                    currentPlan = parsed
                                    planPhase = PlanPhase.REVIEWING
                                }
                                // Show the plan text to user (strip the JSON block)
                                val displayText = textContent.replace(Regex("```json[\\s\\S]*?```"), "").trim()
                                val finalDisplay = if (displayText.isNotEmpty()) displayText
                                    else "📋 已生成任务计划（${parsed.tasks.size} 个步骤），请确认后开始执行。"
                                myMsgs = myMsgs + ChatMessage(role = "assistant", content = finalDisplay)
                                commitMessages(myConvId, myMsgs)
                                conversationDtos.add(ChatMessageDto(role = "assistant", content = finalDisplay))
                                finishReason = "stop"
                                continue
                            }
                        }

                        // Task completion tracking
                        if (planPhase == PlanPhase.EXECUTING && currentPlan != null) {
                            val doneRegex = Regex("""\[TASK_DONE:\s*(\d+)\]""").findAll(textContent)
                            doneRegex.forEach { match ->
                                val taskId = match.groupValues[1].toIntOrNull() ?: return@forEach
                                completedTaskIds.add(taskId)
                            }
                            if (completedTaskIds.size >= currentPlan!!.tasks.size) {
                                planPhase = PlanPhase.COMPLETED
                            }
                            // User memory: auto-detect preference statements
                            val prefPatterns = listOf(
                                Regex("""用户(偏好|习惯|喜欢|倾向于)(.+?)[。.]"""),
                                Regex("""建议使用(.+?)[。.]""")
                            )
                            for (pat in prefPatterns) {
                                val found = pat.find(textContent) ?: continue
                                saveUserMemory(myConvId, found.value.trim())
                            }
                        }

                        // === REAL SSE STREAMING for final answer ===
                        val streamRequest = mapOf(
                            "model" to profile.model,
                            "messages" to conversationDtos.map { dto ->
                                val m = mutableMapOf<String, Any?>("role" to dto.role)
                                if (dto.toolCalls != null) {
                                    m["tool_calls"] = dto.toolCalls
                                    if (dto.content != null) m["content"] = dto.content
                                } else if (dto.toolCallId != null) {
                                    m["tool_call_id"] = dto.toolCallId
                                    m["content"] = dto.content ?: ""
                                } else {
                                    m["content"] = dto.content ?: ""
                                }
                                m
                            },
                            // Include tools so the model keeps using tool-call mechanism in streaming,
                            // instead of emitting tool-call text as its answer
                            "tools" to gson.fromJson(ToolRegistry.toolCallsToJson(), object : TypeToken<List<Map<String, Any>>>() {}.type),
                            "tool_choice" to "auto",
                            "stream" to true
                        ).let { base ->
                            val full = base.toMutableMap()
                            if (profile.thinkingEnabled && myConvId !in ActiveModeService.runningConversations) {
                                full["reasoning_effort"] = "max"
                                full["thinking"] = mapOf("type" to "enabled")
                            }
                            full
                        }

                        val bodyJson = gson.toJson(streamRequest)
                        val client = OkHttpClient.Builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(180, TimeUnit.SECONDS)
                            .build()
                        val req = Request.Builder()
                            .url("${profile.baseUrl.trimEnd('/')}/chat/completions")
                            .addHeader("Authorization", "Bearer ${profile.apiKey}")
                            .addHeader("Content-Type", "application/json")
                            .post(bodyJson.toRequestBody("application/json".toMediaType()))
                            .build()

                        val streamResp = client.newCall(req).execute()
                        if (!streamResp.isSuccessful) {
                            val err = streamResp.body?.string()?.take(200) ?: ""
                            streamResp.close()
                            withContext(Dispatchers.Main) {
                                errorMessage = "流式请求失败 ${streamResp.code}: $err"
                            }
                            return@launch
                        }

                        val reader = BufferedReader(InputStreamReader(streamResp.body!!.byteStream()))
                        val textBuf = StringBuilder()
                        val thinkBuf = StringBuilder()
                        // Collect streaming tool_calls (index -> accumulated name/arguments)
                        val streamToolCalls = mutableMapOf<Int, MutableMap<String, String>>()

                        reader.useLines { lines ->
                            lines.forEach { line ->
                                if (!isActive) return@forEach
                                if (line.startsWith("data: ") && line.length > 6) {
                                    val data = line.substring(6).trim()
                                    if (data == "[DONE]") return@forEach
                                    try {
                                        val json = gson.fromJson(data, Map::class.java)
                                        val choices = json["choices"] as? List<Map<String, Any>>
                                        val delta = choices?.firstOrNull()?.get("delta") as? Map<String, Any>
                                        val rc = delta?.get("reasoning_content") as? String
                                        val tc = unescapeUnicode(delta?.get("content") as? String)
                                        if (!rc.isNullOrBlank()) thinkBuf.append(rc)
                                        if (!tc.isNullOrBlank()) textBuf.append(tc)
                                        // Streaming tool calls
                                        val tcs = delta?.get("tool_calls") as? List<Map<String, Any>>
                                        if (!tcs.isNullOrEmpty()) {
                                            for (t in tcs) {
                                                val idx = (t["index"] as? Double)?.toInt() ?: 0
                                                val fn = t["function"] as? Map<String, Any> ?: continue
                                                val entry = streamToolCalls.getOrPut(idx) { mutableMapOf("name" to "", "arguments" to "") }
                                                (fn["name"] as? String)?.let { entry["name"] = entry["name"] + it }
                                                (fn["arguments"] as? String)?.let { entry["arguments"] = entry["arguments"] + it }
                                            }
                                        }
                                        // Update UI live (only if viewing this conversation)
                                        myMsgs = myMsgs.filter { it.role != "assistant_live" } + ChatMessage(
                                            role = "assistant_live",
                                            content = textBuf.toString(),
                                            thinking = thinkBuf.toString()
                                        )
                                        if (myConvId == currentConvId) {
                                            withContext(Dispatchers.Main) { messages = myMsgs }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        streamResp.close()

                        // If the model streamed tool calls, execute them and continue the agent loop
                        if (streamToolCalls.isNotEmpty()) {
                            // Preserve text/thinking already emitted this round — the model may
                            // have written part of its answer before calling a tool (e.g. saving memory).
                            // Without this, the final answer gets swallowed.
                            val partialText = textBuf.toString()
                            val partialThink = thinkBuf.toString()
                            if (partialText.isNotBlank() || partialThink.isNotBlank()) {
                                myMsgs = myMsgs.filter { it.role != "assistant_live" } + ChatMessage(
                                    role = "assistant",
                                    content = partialText,
                                    thinking = partialThink
                                )
                                commitMessages(myConvId, myMsgs)
                                conversationDtos.add(ChatMessageDto(role = "assistant", content = partialText))
                            }
                            val calls = streamToolCalls.entries.sortedBy { it.key }.mapIndexed { i, (_, m) ->
                                val rawArgs = m["arguments"] ?: "{}"
                                val parsedArgs: Map<String, String> = try {
                                    gson.fromJson(rawArgs, object : TypeToken<Map<String, String>>() {}.type)
                                } catch (e: Exception) { mapOf("_raw" to rawArgs) }
                                com.example.aichat.data.tools.ToolCall(
                                    id = "stream_${System.currentTimeMillis()}_$i",
                                    name = m["name"] ?: "",
                                    arguments = parsedArgs
                                )
                            }
                            // Keep live message on screen while tools run
                            conversationDtos.add(ChatMessageDto(
                                role = "assistant", content = null,
                                toolCalls = calls.map { ToolCallDto(
                                    id = it.id, type = "function",
                                    function = ToolCallFunctionDto(name = it.name, arguments = gson.toJson(it.arguments))
                                )}
                            ))
                            for (tc in calls) {
                                withContext(Dispatchers.Main) {
                                    agentSteps = agentSteps + AgentStep(type = "tool_call", toolName = tc.name, toolArgs = gson.toJson(tc.arguments))
                                }
                                val result = ToolRegistry.execute(ToolCall(id = tc.id, name = tc.name, arguments = tc.arguments), getApplication(), myConvId)
                                val previewJson = Regex("""\{"type":"preview","file":"([^"]+)","title":"([^"]*)"\}""").find(result.content)
                                if (previewJson != null && tc.name == "build_html") {
                                    val f = previewJson.groupValues[1]
                                    val t = previewJson.groupValues[2].ifBlank { f }
                                    withContext(Dispatchers.Main) {
                                        previewItems = previewItems.filter { it.file != f } + PreviewItem(f, t)
                                        activePreviewIndex = previewItems.lastIndex
                                        previewMode = PreviewMode.HALF
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    agentSteps = agentSteps + AgentStep(type = "tool_result", toolName = tc.name, content = result.content)
                                }
                                conversationDtos.add(ChatMessageDto(role = "tool", content = result.content, toolCallId = tc.id))
                            }
                            if (planPhase == PlanPhase.EXECUTING) saveState(conversationDtos, round)
                            continue
                        }

                        // Finalize with NonCancellable so backgrounding doesn't lose the message
                        withContext(NonCancellable + Dispatchers.Main) {
                            val finalText = textBuf.toString()
                            val finalThink = thinkBuf.toString()
                            myMsgs = myMsgs.filter { it.role != "assistant_live" } + ChatMessage(
                                role = "assistant",
                                content = finalText,
                                thinking = finalThink
                            )
                            commitMessages(myConvId, myMsgs)
                        }
                        conversationDtos.add(ChatMessageDto(role = "assistant", content = textBuf.toString()))
                        finishReason = "stop"
                    }
                }

                if (finishReason != "stop" && isActive) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "任务执行中断。请重试。"
                    }
                }
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.Main) {
                    // Save partial content if streaming was interrupted (screen switch, network drop, etc.)
                    val liveMsg = myMsgs.find { it.role == "assistant_live" }
                    if (liveMsg != null && liveMsg.content.isNotBlank()) {
                        myMsgs = myMsgs.filter { it.role != "assistant_live" } + ChatMessage(
                            role = "assistant",
                            content = liveMsg.content + "\n\n⚠️ [连接中断，以上为已生成内容]",
                            thinking = liveMsg.thinking
                        )
                        commitMessages(myConvId, myMsgs)
                    }
                    errorMessage = "请求失败: ${e.localizedMessage ?: "未知错误"}"
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    currentJobs.remove(myConvId)
                    loadingConvs.remove(myConvId)
                    isLoading = myConvId in loadingConvs
                }
            }
        }
    }

    /** Decode \uXXXX unicode escapes and common HTML entities to actual characters */
    private fun unescapeUnicode(input: String?): String? {
        if (input == null || !input.contains("\\u")) return input
        return try {
            val sb = StringBuffer()
            val m = Regex("\\\\u([0-9a-fA-F]{4})").findAll(input)
            var lastEnd = 0
            for (match in m) {
                sb.append(input.substring(lastEnd, match.range.first))
                sb.append(match.groupValues[1].toInt(16).toChar())
                lastEnd = match.range.last + 1
            }
            sb.append(input.substring(lastEnd))
            sb.toString()
        } catch (_: Exception) { input }
    }

    private fun getApiService(profile: ApiProfile): ApiService {
        val current = apiService
        if (current != null) return current

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val baseUrl = profile.baseUrl.trimEnd('/') + "/"

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(ApiService::class.java)
        apiService = service
        return service
    }

    // --- Web Search (multi-source OkHttp, works from China) ---

    private fun workspaceStatus(convId: String): String {
        val wsRoot = java.io.File(getApplication<android.app.Application>().filesDir, "workspace").also { it.mkdirs() }
        // Isolate: only show this conversation's own subdirectory
        val wsDir = if (convId.isBlank()) wsRoot
                    else java.io.File(wsRoot, sanitize(convId)).also { it.mkdirs() }
        val files = wsDir.listFiles()?.toList() ?: return ""
        val count = files.size
        if (count == 0) return ""
        val names = files.sortedBy { it.name }.take(15).joinToString(", ") { it.name }
        val pathHint = if (convId.isBlank()) "workspace/" else "workspace/${sanitize(convId)}/"
        return "工作区（$pathHint）有 $count 个文件: $names" + if (count > 15) " ..." else ""
    }

    private suspend fun performSearch(query: String): String {
        return withContext(Dispatchers.IO) {
            // 1. DuckDuckGo Instant Answer API (free, structured JSON)
            try {
                val ddg = tryDdgApi(query)
                if (ddg.isNotBlank()) return@withContext ddg
            } catch (_: Exception) {}

            // 2. Bing HTML scraping (works from China)
            try {
                val results = trySearchEngine("https://www.bing.com/search?q=${URLEncoder.encode(query, "UTF-8")}&setlang=zh-cn", ::parseBingHtml)
                if (results.isNotEmpty()) return@withContext results
            } catch (_: Exception) {}

            // 3. DuckDuckGo Lite fallback
            try {
                val results = trySearchEngine("https://lite.duckduckgo.com/lite/?q=${URLEncoder.encode(query, "UTF-8")}", ::parseSearchHtml)
                if (results.isNotEmpty()) return@withContext results
            } catch (_: Exception) {}

            ""
        }
    }

    private fun tryDdgApi(query: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val url = "https://api.duckduckgo.com/?q=${URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1&skip_disambig=1"
        val req = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return ""
        resp.close()
        val json = gson.fromJson(body, Map::class.java) ?: return ""

        val sb = StringBuilder()
        // Abstract/answer
        (json["AbstractText"] as? String)?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("【摘要】$it")
            (json["AbstractSource"] as? String)?.let { src -> sb.appendLine("来源: $src") }
        }
        // Answer (e.g. calculator, time, weather)
        (json["Answer"] as? String)?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("【直接回答】$it")
        }
        // Related topics
        val topics = json["RelatedTopics"] as? List<Map<String, Any?>>
        if (!topics.isNullOrEmpty()) {
            sb.appendLine("\n【相关条目】")
            topics.take(6).forEach { t ->
                val text = (t["Text"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
                sb.appendLine("- $text")
            }
        }
        return sb.toString().trim()
    }

    private fun trySearchEngine(url: String, parser: (String) -> List<String>): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()
        val resp = client.newCall(req).execute()
        val html = resp.body?.string() ?: ""
        resp.close()
        val results = parser(html)
        return if (results.isNotEmpty()) results.joinToString("\n\n") else ""
    }

    private fun parseSearchHtml(html: String): List<String> {
        val results = mutableListOf<String>()
        // DDG Lite: each result is a <tr> with link and snippet
        val linkRegex = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>")
        val snippetRegex = Regex("<td class=\"result-snippet\">([^<]+)</td>")
        val matches = linkRegex.findAll(html).take(8)
        val snippets = snippetRegex.findAll(html).take(8).map { it.groupValues[1].trim() }.toList()

        matches.forEachIndexed { i, m ->
            val url = m.groupValues[1]
            val title = m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            if (url.startsWith("//")) return@forEachIndexed
            val snippet = snippets.getOrElse(i) { "" }
            val entry = "【${i + 1}】$title\n$snippet\n链接: $url"
            results.add(entry)
        }
        return results
    }

    private fun parseBingHtml(html: String): List<String> {
        val results = mutableListOf<String>()
        // Bing: results in <li class="b_algo">
        val blockRegex = Regex("<li class=\"b_algo\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
        val blocks = blockRegex.findAll(html).take(8)
        for ((i, block) in blocks.withIndex()) {
            val text = block.groupValues[1]
            val titleMatch = Regex("<a[^>]*>(.*?)</a>").find(text)
            val urlMatch = Regex("href=\"(https?://[^\"]+)\"").find(text)
            val snippetMatch = Regex("<p[^>]*>(.*?)</p>").find(text)
            val title = titleMatch?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: continue
            val url = urlMatch?.groupValues?.get(1) ?: ""
            val snippet = snippetMatch?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""
            results.add("【${i + 1}】$title\n$snippet\n链接: $url")
        }
        return results
    }

    // --- User Memory (isolated per conversation) ---

    private fun memoryDir(convId: String): java.io.File {
        val dir = java.io.File(getApplication<android.app.Application>().filesDir, "memory")
        return if (convId.isBlank()) dir else java.io.File(dir, sanitize(convId)).also { it.mkdirs() }
    }

    private fun sanitize(id: String): String = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private fun getMemoryFile(convId: String): java.io.File {
        return java.io.File(memoryDir(convId), ".agent_memory.txt")
    }

    private fun loadUserMemory(convId: String): String {
        return try {
            val f = getMemoryFile(convId)
            if (f.exists()) f.readText(Charsets.UTF_8).take(2000) else ""
        } catch (_: Exception) { "" }
    }

    private fun saveUserMemory(convId: String, entry: String) {
        try {
            val f = getMemoryFile(convId)
            f.parentFile?.mkdirs()
            val existing = if (f.exists()) f.readText(Charsets.UTF_8) else ""
            if (existing.contains(entry)) return // dedup
            f.writeText("$existing\n$entry".trim(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    // --- Dangerous Operation Detection ---

    fun checkDangerousOp(toolName: String, args: Map<String, String>): Boolean {
        val wsDir = java.io.File(getApplication<android.app.Application>().filesDir, "workspace")
        return when (toolName) {
            "write_file" -> {
                val path = args["path"] ?: return false
                val file = java.io.File(wsDir, path)
                file.exists()
            }
            "delete_file" -> {
                val path = args["path"] ?: return false
                java.io.File(wsDir, path).exists()
            }
            else -> false
        }
    }

    // --- Resume / Breakpoint ---

    private data class AgentState(
        val conversationDtos: List<Map<String, Any?>> = emptyList(),
        val round: Int = 0,
        val completedTaskIds: Set<Int> = emptySet(),
        val planJson: String = "",
        val planPhase: String = "IDLE",
        val messageCount: Int = 0
    )

    private fun getStateFile(): java.io.File {
        return java.io.File(getApplication<android.app.Application>().filesDir, "workspace/.agent_state.json")
    }

    private fun saveState(dtos: List<ChatMessageDto>, round: Int) {
        try {
            val state = AgentState(
                conversationDtos = dtos.map { dto ->
                    mapOf(
                        "role" to dto.role,
                        "content" to (dto.content?.toString() ?: ""),
                        "tool_calls" to (dto.toolCalls?.let { gson.toJson(it) } ?: ""),
                        "tool_call_id" to (dto.toolCallId ?: "")
                    )
                },
                round = round,
                completedTaskIds = completedTaskIds,
                planJson = currentPlan?.let { gson.toJson(it) } ?: "",
                planPhase = planPhase.name,
                messageCount = messages.size
            )
            getStateFile().writeText(gson.toJson(state), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun restoreState(): Boolean {
        return try {
            val f = getStateFile()
            if (!f.exists()) return false
            val state = gson.fromJson(f.readText(Charsets.UTF_8), AgentState::class.java)
            state.conversationDtos.isEmpty() // if empty, no valid state
            hasSavedState = true
            resumePending = !state.conversationDtos.isNullOrEmpty()
            resumePending
        } catch (_: Exception) {
            false
        }
    }

    fun resumeFromBreakpoint(profile: ApiProfile) {
        val f = getStateFile()
        if (!f.exists()) return
        try {
            val state = gson.fromJson(f.readText(Charsets.UTF_8), AgentState::class.java)
            if (state.conversationDtos.isNullOrEmpty()) return

            // Restore plan state (normalize null lists from Gson)
            if (state.planJson.isNotBlank()) {
                val raw = gson.fromJson(state.planJson, TaskPlan::class.java)
                currentPlan = raw?.copy(
                    tasks = raw.tasks ?: emptyList(),
                    optimizations = raw.optimizations ?: emptyList()
                )
            }
            planPhase = try { PlanPhase.valueOf(state.planPhase) } catch (_: Exception) { PlanPhase.IDLE }
            completedTaskIds.clear()
            completedTaskIds.addAll(state.completedTaskIds)

            // Build a resume message
            val resumeMsg = buildString {
                appendLine("🔁 从断点恢复执行")
                if (currentPlan != null) {
                    appendLine("已完成 ${completedTaskIds.size}/${currentPlan!!.tasks.size} 个任务")
                    appendLine("继续执行剩余任务...")
                }
            }

            val myConvId = currentConvId
            var myMsgs = storage.getConversation(myConvId)?.messages ?: emptyList()
            myMsgs = myMsgs + ChatMessage(role = "system", content = resumeMsg)
            commitMessages(myConvId, myMsgs)
            resumePending = false
            hasSavedState = false
            // Clean up
            f.delete()

            // Trigger agent to continue - inject saved DTOs
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    loadingConvs.add(myConvId)
                    isLoading = myConvId in loadingConvs
                    val dtos = state.conversationDtos.map { dto ->
                        ChatMessageDto(
                            role = dto["role"] as? String ?: "",
                            content = dto["content"] ?: "",
                            toolCallId = (dto["tool_call_id"] as? String)?.ifBlank { null }
                        )
                    }.toMutableList()

                    dtos.add(ChatMessageDto(role = "system",
                        content = "你从断点恢复执行。之前的对话历史已保留。请检查当前进度并继续未完成的工作。"))

                    val request = ChatRequest(
                        model = profile.model,
                        messages = dtos,
                        reasoningEffort = if (profile.thinkingEnabled && myConvId !in ActiveModeService.runningConversations) "max" else null,
                        thinking = if (profile.thinkingEnabled && myConvId !in ActiveModeService.runningConversations) mapOf("type" to "enabled") else null,
                        tools = gson.fromJson(ToolRegistry.toolCallsToJson(),
                            object : TypeToken<List<Map<String, Any>>>() {}.type),
                        stream = false
                    )

                    // Simple non-streaming resume call
                    val resp = getApiService(profile).chatCompletion("Bearer ${profile.apiKey}", request)
                    if (resp.isSuccessful) {
                        val reply = resp.body()?.choices?.firstOrNull()?.message?.content ?: ""
                        withContext(Dispatchers.Main) {
                            myMsgs = myMsgs + ChatMessage(role = "assistant", content = reply)
                            commitMessages(myConvId, myMsgs)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "恢复失败: ${e.localizedMessage}"
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        currentJobs.remove(myConvId)
                        loadingConvs.remove(myConvId)
                        isLoading = myConvId in loadingConvs
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun clearSavedState() {
        hasSavedState = false
        resumePending = false
        getStateFile().delete()
    }

    // --- Vision API (OkHttp direct, bypasses Retrofit URL prefix issue) ---

    private suspend fun describeImage(imageDataUri: String, userQuestion: String, profile: ApiProfile): Pair<String?, String> {
        return try {
            val model = profile.visionModel.ifBlank { return Pair(null, "") }
            val base = profile.visionBaseUrl.ifBlank { "https://open.bigmodel.cn/api/paas/v4/" }.trimEnd('/')
            val url = "$base/chat/completions"
            val key = profile.visionApiKey.ifBlank { profile.apiKey }

            val hasQuestion = userQuestion.isNotBlank() && userQuestion != "请描述这张图片"
            val prompt = buildString {
                if (hasQuestion) {
                    append("用户的问题是：「$userQuestion」。")
                }
                append("请仔细分析图片中的所有内容。")
                append("【重要】所有数学公式、方程式、表达式必须用标准 LaTeX 格式输出，用 ${'$'}...${'$'} 或 ${'$'}${'$'}...${'$'}${'$'} 包裹。")
                append("例如：${'$'}x^2+y^2=1${'$'}、${'$'}\\int_0^1 f(x)dx${'$'}、${'$'}\\frac{a}{b}${'$'}。")
                append("如果是题目，请逐道完整转录，包括选项。")
                append("不要遗漏任何细节。")
            }

            val body = gson.toJson(mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to listOf(
                        mapOf("type" to "image_url", "image_url" to mapOf("url" to imageDataUri)),
                        mapOf("type" to "text", "text" to prompt)
                    ))
                )
            ))

            withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val json = gson.fromJson(resp.body?.string() ?: "{}", Map::class.java)
                    val choices = json["choices"] as? List<Map<String, Any>>
                    val desc = (choices?.firstOrNull()?.get("message") as? Map<String, Any>)?.get("content") as? String
                    resp.close()
                    Pair(desc, base)
                } else {
                    val errBody = resp.body?.string()?.take(200) ?: ""
                    resp.close()
                    errorMessage = "视觉模型错误 ${resp.code}: $errBody"
                    Pair(null, "")
                }
            }
        } catch (e: Exception) {
            errorMessage = "视觉识别失败: ${e.localizedMessage}"
            Pair(null, "")
        }
    }
}
