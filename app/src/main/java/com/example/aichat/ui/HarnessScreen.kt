package com.example.aichat.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.aichat.data.ApiProfile
import com.example.aichat.data.ChatMessage
import com.example.aichat.data.HttpClient
import com.example.aichat.linux.PhoneBridgeHttpServer
import com.example.aichat.viewmodel.ChatViewModel
import com.example.aichat.vm.DshState
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 两种模式：
 *  - 内置 Agent：直接复用 App 现有 Agent 循环（工具、手机控制、Python、Linux、记忆）
 *  - QEMU Guest：连接 guest 里 127.0.0.1:18000 的 OpenAI 兼容 Harness
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessScreen(
    chatViewModel: ChatViewModel,
    profile: ApiProfile,
    conversationId: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var mode by remember { mutableStateOf(if (com.example.aichat.vm.DshState.ready) "guest" else "builtin") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DS Harness") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    FilterChip(
                        selected = mode == "builtin",
                        onClick = { mode = "builtin" },
                        label = { Text("内置") }
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = mode == "guest",
                        onClick = { mode = "guest" },
                        label = { Text("QEMU") }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            )
        }
    ) { padding ->
        if (mode == "builtin") {
            BuiltinHarness(chatViewModel, profile, conversationId, Modifier.padding(padding))
        } else {
            GuestHarness(Modifier.padding(padding))
        }
    }
}

@Composable
private fun BuiltinHarness(
    chatViewModel: ChatViewModel,
    profile: ApiProfile,
    conversationId: String,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val visible = chatViewModel.messages.filter { it.role == "user" || it.role == "assistant" }
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }
    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "内置 Agent  模型 ${profile.model}  工具：手机控制/Python/Linux/文件/搜索",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        chatViewModel.errorMessage?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    err.take(500),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visible) { msg ->
                val isUser = msg.role == "user"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(
                            msg.content.ifBlank { "..." },
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息，交给内置 Agent") },
                maxLines = 4
            )
            Spacer(Modifier.width(6.dp))
            if (chatViewModel.isLoading) {
                FilledIconButton(onClick = { chatViewModel.cancelLoading() }) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            input = ""
                            if (chatViewModel.currentConversationId() != conversationId) {
                                chatViewModel.loadConversation(conversationId)
                            }
                            chatViewModel.sendMessage(text, profile)
                        }
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
private fun GuestHarness(modifier: Modifier = Modifier) {
    // Guest 里的 DeepSeek Harness（dsh web）一就绪，就直接内嵌它的 Web UI
    var dshUrl by remember { mutableStateOf(DshState.webUrl) }
    LaunchedEffect(Unit) {
        while (true) {
            dshUrl = DshState.webUrl
            delay(2000)
        }
    }
    if (!dshUrl.isNullOrBlank()) {
        DshWebView(dshUrl!!, modifier)
        return
    }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("harness_client", Context.MODE_PRIVATE) }
    var baseUrl by remember { mutableStateOf(prefs.getString("baseUrl", "http://127.0.0.1:18000") ?: "") }
    var apiPath by remember { mutableStateOf(prefs.getString("apiPath", "/v1/chat/completions") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "deepseek-flash") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("检测中...") }
    var effectiveBase by remember { mutableStateOf(baseUrl) }
    var effectivePath by remember { mutableStateOf(apiPath) }
    var hostFallback by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<HarnessMsg>() }
    var streaming by remember { mutableStateOf(false) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun saveSettings() {
        prefs.edit().putString("baseUrl", baseUrl).putString("apiPath", apiPath)
            .putString("model", model).putString("token", token).apply()
    }

    fun checkHealth() {
        scope.launch(Dispatchers.IO) {
            var primaryOk = false
            try {
                val root = baseUrl.trim().trimEnd('/')
                primaryOk = listOf("$root/v1/models", "$root/health", root).any { url ->
                    try {
                        val req = Request.Builder().url(url)
                            .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                            .get().build()
                        HttpClient.instance.newCall(req).execute().use { true }
                    } catch (_: Exception) { false }
                }
            } catch (_: Exception) { primaryOk = false }
            if (primaryOk) {
                withContext(Dispatchers.Main) {
                    status = "已连接"
                    effectiveBase = baseUrl
                    effectivePath = apiPath
                    hostFallback = false
                }
                return@launch
            }
            val fb = "http://127.0.0.1:" + PhoneBridgeHttpServer.PORT
            val fbToken = PhoneBridgeHttpServer.token
            val fbOk = try {
                if (fbToken.isBlank()) false else {
                    val url = fb + "/v1/models?token=" + fbToken
                    HttpClient.instance.newCall(Request.Builder().url(url).get().build()).execute().use { true }
                }
            } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                if (fbOk) {
                    status = "已连接（宿主 Harness）"
                    effectiveBase = fb
                    effectivePath = "/v1/chat/completions"
                    hostFallback = true
                } else {
                    status = "未连接"
                    effectiveBase = baseUrl
                    effectivePath = apiPath
                    hostFallback = false
                }
            }
        }
    }

    LaunchedEffect(baseUrl, apiPath) {
        // guest 自动装 harness 需要时间；打开页面后持续探测，连上后继续轮询保活
        while (true) {
            checkHealth()
            delay(4000)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || streaming) return
        input = ""
        messages.add(HarnessMsg("user", text))
        streaming = true
        streamJob = scope.launch(Dispatchers.IO) {
            val assistantIndex = messages.size
            withContext(Dispatchers.Main) { messages.add(HarnessMsg("assistant", "")) }
            try {
                val payload = mutableMapOf<String, Any?>(
                    "model" to model,
                    "messages" to messages.dropLast(1).map { mapOf("role" to it.role, "content" to it.content) },
                    "stream" to true
                )
                val requestJson = Gson().toJson(payload)
                val root = effectiveBase.trim().trimEnd('/')
                val path = if (effectivePath.startsWith("/")) effectivePath else "/" + effectivePath
                val fallbackToken = if (hostFallback) {
                    val t = PhoneBridgeHttpServer.token
                    if (t.isBlank()) "" else "?token=" + t
                } else ""
                val url = root + path + fallbackToken
                val req = Request.Builder().url(url)
                    .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toRequestBody("application/json".toMediaType()))
                    .build()
                HttpClient.instance.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            messages[assistantIndex] = HarnessMsg("assistant", "HTTP ${resp.code}: ${err.take(300)}")
                        }
                        return@use
                    }
                    val body = resp.body ?: return@use
                    val contentType = resp.header("Content-Type") ?: ""
                    if (!contentType.contains("event-stream", ignoreCase = true)) {
                        val bodyText = body.string()
                        val reply = try {
                            JsonParser.parseString(bodyText).asJsonObject
                                .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                                ?.getAsJsonObject("message")?.get("content")?.asString
                        } catch (_: Exception) { null }
                        withContext(Dispatchers.Main) {
                            messages[assistantIndex] = HarnessMsg("assistant", reply ?: bodyText.take(1000))
                        }
                        return@use
                    }
                    val source = body.source()
                    val sb = StringBuilder()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val delta = try {
                            JsonParser.parseString(data).asJsonObject
                                .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                                ?.getAsJsonObject("delta")?.get("content")?.asString
                        } catch (_: Exception) { null }
                        if (!delta.isNullOrEmpty()) {
                            sb.append(delta)
                            withContext(Dispatchers.Main) {
                                messages[assistantIndex] = HarnessMsg("assistant", sb.toString())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messages[assistantIndex] = HarnessMsg("assistant", "连接失败: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) { streaming = false }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = if (status == "已连接") MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$status    $baseUrl", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showSettings = true }) { Text("设置") }
                TextButton(onClick = { messages.clear(); streaming = false; streamJob?.cancel() }) { Text("清空") }
            }
        }
        if (status != "已连接") {
            Text(
                "等待 Guest Harness 启动：VM 启动后通常需要 1-3 分钟自动安装；页面每 4 秒自动重试。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(messages) { _, msg ->
                val isUser = msg.role == "user"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(
                            msg.content.ifBlank { "..." },
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息，发给 QEMU guest 里的 harness") },
                maxLines = 4
            )
            Spacer(Modifier.width(6.dp))
            if (streaming) {
                FilledIconButton(onClick = { streamJob?.cancel(); streaming = false }) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(onClick = { send() }, enabled = input.isNotBlank()) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }

    if (showSettings) {
        var eBase by remember { mutableStateOf(baseUrl) }
        var ePath by remember { mutableStateOf(apiPath) }
        var eModel by remember { mutableStateOf(model) }
        var eToken by remember { mutableStateOf(token) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Guest Harness 设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(eBase, { eBase = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ePath, { ePath = it }, label = { Text("API Path") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(eModel, { eModel = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(eToken, { eToken = it }, label = { Text("Token（可空）") }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "guest 里让 harness 监听 0.0.0.0:8000；QEMU hostfwd 会把 127.0.0.1:18000 转发过去。",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    baseUrl = eBase.trim(); apiPath = ePath.trim(); model = eModel.trim(); token = eToken.trim()
                    saveSettings(); showSettings = false; checkHealth()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } }
        )
    }
}

/** 内嵌 Guest 里 DeepSeek Harness 的 Web UI（带 token 的启动 URL 会自动换取签名 cookie）。 */
@Composable
private fun DshWebView(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        update = { view ->
            if (view.url != url) view.loadUrl(url)
        }
    )
}

private data class HarnessMsg(val role: String, val content: String)
