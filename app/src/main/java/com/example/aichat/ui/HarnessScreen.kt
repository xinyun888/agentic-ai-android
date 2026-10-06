package com.example.aichat.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aichat.data.ApiProfile
import com.example.aichat.data.ChatMessage
import com.example.aichat.data.HttpClient
import com.example.aichat.linux.PhoneBridgeHttpServer
import com.example.aichat.viewmodel.ChatViewModel
import com.example.aichat.vm.DshState
import com.example.aichat.vm.QemuManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
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
    active: Boolean = true,
    onBack: () -> Unit
) {
    BackHandler(enabled = active) { onBack() }
    var mode by remember { mutableStateOf(if (DshState.ready || DshState.externalBrowser) "guest" else "builtin") }
    var externalModeUi by remember { mutableStateOf(DshState.externalBrowser) }
    LaunchedEffect(Unit) {
        while (true) {
            externalModeUi = DshState.externalBrowser
            delay(1000)
        }
    }
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
                    Spacer(Modifier.width(2.dp))
                    TextButton(onClick = {
                        mode = "guest"
                        chatViewModel.qemuManager.setExternalBrowser(!externalModeUi)
                    }) { Text(if (externalModeUi) "内置渲染" else "浏览器", style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.width(2.dp))
                    TextButton(onClick = {
                        mode = "guest"
                        chatViewModel.qemuManager.restartGuestHarness()
                    }) { Text("重配", style = MaterialTheme.typography.labelSmall) }
                }
            )
        }
    ) { padding ->
        if (mode == "builtin") {
            BuiltinHarness(chatViewModel, profile, conversationId, Modifier.padding(padding))
        } else {
            GuestHarness(chatViewModel.qemuManager, active, Modifier.padding(padding))
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
private fun GuestHarness(qemuManager: QemuManager, active: Boolean, modifier: Modifier = Modifier) {
    // Guest 里的 DeepSeek Harness（dsh web）就绪后：外部浏览器模式交给 Chrome，内置模式才挂 WebView
    var dshUrl by remember { mutableStateOf(DshState.webUrl) }
    var dshGeneration by remember { mutableStateOf(DshState.generation) }
    var external by remember { mutableStateOf(DshState.externalBrowser) }
    var browserUrl by remember { mutableStateOf(DshState.browserUrl) }
    var browserNonce by remember { mutableStateOf(DshState.browserLaunchNonce) }
    LaunchedEffect(Unit) {
        while (true) {
            dshUrl = DshState.webUrl
            dshGeneration = DshState.generation
            external = DshState.externalBrowser
            browserUrl = DshState.browserUrl
            browserNonce = DshState.browserLaunchNonce
            delay(2000)
        }
    }
    if (external) {
        ExternalBrowserPanel(qemuManager, active, browserUrl, browserNonce, modifier)
        return
    }
    if (!dshUrl.isNullOrBlank()) {
        DshWebView(dshUrl!!, dshGeneration, active, modifier)
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

/** 外部浏览器模式下由 App 拉起系统浏览器，不消耗 WebView 的 cookie 会话。 */
private fun launchDshInBrowser(context: Context): String? {
    val raw = DshState.browserUrl ?: return "DSH 还没有启动完成"
    val target = if (DshState.browserTokenConsumed) raw.substringBefore("?token=") else raw
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        // 只有真正拉起浏览器后才算 token 已交给浏览器；拉起失败时保留 raw token，下次还能重试。
        if (!DshState.browserTokenConsumed) DshState.browserTokenConsumed = true
        null
    } catch (e: Exception) {
        "无法打开系统浏览器: ${e.message}"
    }
}

@Composable
private fun ExternalBrowserPanel(
    qemuManager: QemuManager,
    active: Boolean,
    browserUrl: String?,
    launchNonce: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var lastAutoNonce by remember { mutableStateOf(Int.MIN_VALUE) }
    LaunchedEffect(browserUrl, launchNonce, active) {
        if (active && !browserUrl.isNullOrBlank() && launchNonce != lastAutoNonce) {
            lastAutoNonce = launchNonce
            error = launchDshInBrowser(context)
        }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("DSH 由系统浏览器渲染", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            if (browserUrl.isNullOrBlank())
                "等待 guest DSH 启动\n启动完成后会自动打开系统浏览器。QEMU 由前台服务保持运行，切到浏览器不会中断。"
            else
                "地址：\n" + browserUrl.substringBefore("?token="),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { error = launchDshInBrowser(context) },
            enabled = !browserUrl.isNullOrBlank()
        ) { Text("重新打开浏览器") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            error = null
            qemuManager.restartGuestHarness()
        }) { Text("重配 DSH 并重新打开") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            qemuManager.setExternalBrowser(false)
        }) { Text("切回内置 WebView") }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "注意：系统浏览器自身也要支持 Promise.withResolvers（Chrome 119+）；如果浏览器版本太老，" +
                "内嵌 WebView 的兼容层反而更稳，可点「切回内置 WebView」。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 内嵌 Guest 里 DeepSeek Harness 的 Web UI。
 * 除了加载页面，还带：
 *  - 顶部小条：网址 / 重载 / 用系统浏览器打开 / 诊断（控制台错误 + 空白页自检）
 *  - WebView 兼容设置：桌面 UA（DSH 是桌面优先的 UI）、宽视口、双指缩放、Cookie 接收
 *  - 白屏自检：onPageFinished 后 3 秒查一次 document.body.innerText 长度，过短就提示"页面渲染为空"
 */
/**
 * DSH 前端要求 Chrome/WebView 119+（Promise.withResolvers、AbortSignal.any）。
 * 手机自带 WebView 往往更老，缺这几个 API 会让 DSH 卡在 "Loading plugins" 或整页白屏。
 * 这段脚本必须在 DSH 的 inline boot 脚本之前执行，只补齐缺失的 API，不覆盖新 WebView 的原生实现。
 */
private val DSH_COMPAT_JS = """
    (function(){
      try {
        if (typeof Promise.withResolvers !== 'function') {
          Object.defineProperty(Promise, 'withResolvers', {
            configurable: true,
            writable: true,
            value: function () {
              var resolve, reject;
              var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
              return { promise: promise, resolve: resolve, reject: reject };
            }
          });
        }
        if (typeof AbortSignal.any !== 'function') {
          AbortSignal.any = function (signals) {
            var ctrl = new AbortController();
            var abort = function (reason) { if (!ctrl.signal.aborted) ctrl.abort(reason); };
            for (var i = 0; signals && i < signals.length; i++) {
              var s = signals[i];
              if (!s) continue;
              if (s.aborted) { abort(s.reason); break; }
              s.addEventListener('abort', function () { abort(this.reason); }, { once: true });
            }
            return ctrl.signal;
          };
        }
        if (typeof AbortSignal.timeout !== 'function') {
          AbortSignal.timeout = function (ms) {
            var ctrl = new AbortController();
            setTimeout(function () {
              try { ctrl.abort(new DOMException('TimeoutError', 'TimeoutError')); }
              catch (_) { ctrl.abort(); }
            }, ms);
            return ctrl.signal;
          };
        }
        if (typeof Object.groupBy !== 'function') {
          Object.groupBy = function (items, keyFn) {
            var out = Object.create(null), i = 0;
            for (var item of items) { var k = keyFn(item, i++); (out[k] || (out[k] = [])).push(item); }
            return out;
          };
        }
        if (typeof Map.groupBy !== 'function') {
          Map.groupBy = function (items, keyFn) {
            var out = new Map(), i = 0;
            for (var item of items) {
              var k = keyFn(item, i++);
              var arr = out.get(k);
              if (arr) arr.push(item); else out.set(k, [item]);
            }
            return out;
          };
        }
        console.log('[aichat] compat pwr=' + typeof Promise.withResolvers + ' any=' + typeof AbortSignal.any);
      } catch (e) {
        try { console.error('[aichat] compat polyfill failed', e); } catch (_) {}
      }
    })();
""".trimIndent()

/** 诊断里不要把超长 combo/插件 URL 整个吞掉，否则真正的错误消息会被 takeLast 截掉。 */
private fun shortDiagUrl(url: String?): String {
    if (url.isNullOrBlank()) return "null"
    return if (url.length <= 180) url else url.take(180) + ""
}

/** 在 UI 线程执行渲染探针，供白屏看门狗使用。 */
private suspend fun evaluateDshProbe(view: WebView, js: String): String? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r) }
    }

@Composable
private fun DshWebView(url: String, generation: Int, active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var diag by remember { mutableStateOf("") }
    var showDiag by remember { mutableStateOf(false) }
    var blankWarn by remember { mutableStateOf(false) }
    var showLoading by remember { mutableStateOf(true) }
    var slowLoad by remember { mutableStateOf(false) }
    var rendererGeneration by remember { mutableStateOf(0) }
    val webRef = remember { mutableStateOf<WebView?>(null) }
    // DSH 的 ?token= 是【一次性】启动令牌：首次加载会 303 重定向并下发会话 cookie。
    // 之后必须走"去掉 token 的干净地址"，否则同一个 token 再加载一次会被拒
    // （页面会显示 "dsh web authentication required"）。
    val cleanUrl = remember(url) { url.substringBefore("?token=") }
    val loadTarget = remember(url) { if (url.contains("?token=")) url else url.substringBefore("?token=") }
    val requestedUrl = remember { java.util.concurrent.atomic.AtomicReference(url) }
    val requestedGeneration = remember { java.util.concurrent.atomic.AtomicInteger(generation) }
    // 渲染探针：DSH 首启很慢，除了文字还看 #root 是否真的有可见子节点。
    val renderProbeJs = remember {
        "(function(){try{" +
            "var r=document.getElementById('root');" +
            "var t=document.body?document.body.innerText.trim():'';" +
            "var b=r?r.getBoundingClientRect():null;" +
            "if(t.indexOf('Loading plugins')>=0)return '2';" +
            "return ((t.length>40)||(r&&r.children.length>0&&b&&b.width>0&&b.height>0))?'1':'0'" +
        "}catch(e){return '-1'}})()"
    }
    val activeState = rememberUpdatedState(active)
    // 页面渲染看门狗：DSH 前端在同意执行后偶发整页空白/渲染进程异常时，自动重载恢复会话。
    // 首屏给 30 秒宽限；DSH 慢机加载插件时 body 会有 "Loading plugins"，探针返回 '2'，
    // 此时不能重载，否则会把正在启动的页面反复刷白。真正完全空白时连续 4 次（约 40 秒）
    // 才重载，最多 2 次。
    LaunchedEffect(url, generation, rendererGeneration) {
        delay(30_000)
        var blankStreak = 0
        var reloads = 0
        while (isActive) {
            delay(10_000)
            val view = webRef.value
            if (view == null || !activeState.value || view.visibility != android.view.View.VISIBLE) {
                blankStreak = 0
                continue
            }
            if (view.url.orEmpty().contains("token=")) {
                blankStreak = 0
                continue
            }
            val probe = try { evaluateDshProbe(view, renderProbeJs) } catch (_: Exception) { null }
            val probeValue = probe?.trim('"')
            val rendered = probeValue == "1"
            val loading = probeValue == "2"
            if (rendered || loading) {
                blankStreak = 0
            } else {
                blankStreak++
                if (blankStreak >= 4 && reloads < 2 && !cleanUrl.isNullOrBlank()) {
                    reloads++
                    blankStreak = 0
                    try {
                        view.evaluateJavascript(
                            "(function(){try{return JSON.stringify({href:location.href,ready:document.readyState," +
                                "boot:!!window.__DSH_BOOT__,loader:!!window.__ModuleLoader__,readyFlag:!!window.__DSH_BOOT_READY__," +
                                "rootChildren:(document.getElementById('root')&&document.getElementById('root').children)?document.getElementById('root').children.length:-1," +
                                "text:(document.body?document.body.innerText:'').slice(0,80)})}catch(e){return 'probe-error:'+e}})()"
                        ) { r -> diag = (diag + "\nboot=" + r).trim().takeLast(4000) }
                    } catch (_: Exception) {}
                    diag = (diag + "\n页面连续 40 秒无渲染，自动恢复重载（${reloads}/2）").trim().takeLast(4000)
                    // 第二次重载带随机 query，绕开可能的旧缓存；DSH 认证按 Host 绑定，query 不影响。
                    val target = if (reloads >= 2) cleanUrl + (if (cleanUrl.contains("?")) "&" else "?") + "aichat=" + System.currentTimeMillis() else cleanUrl
                    view.loadUrl(target)
                    delay(15_000)
                } else if (reloads >= 2) {
                    blankWarn = true
                }
            }
        }
    }
    // 原生加载遮罩：DSH 首帧之前的白屏/Loading plugins阶段至少给用户一个明确反馈，
    // 避免看起来像死机。探针返回 1（真正渲染）后立刻撤掉；即使超过 5 分钟也继续探测，
    // 一旦渲染就撤掉，不会永久挡住已经加载好的界面。
    LaunchedEffect(url, generation, rendererGeneration) {
        showLoading = true
        slowLoad = false
        val startedAt = System.currentTimeMillis()
        while (isActive) {
            delay(2_500)
            val view = webRef.value
            if (view == null || !activeState.value) continue
            val probe = try { evaluateDshProbe(view, renderProbeJs) } catch (_: Exception) { null }
            val value = probe?.trim('"')
            if (value == "1") {
                showLoading = false
                return@LaunchedEffect
            }
            if (System.currentTimeMillis() - startedAt > 5 * 60_000) slowLoad = true
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                url.take(48),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                // 用干净地址重载：靠首次换来的 cookie 认证；再次带 token 会因"一次性令牌已用"被拒
                showLoading = true
                slowLoad = false
                webRef.value?.loadUrl(cleanUrl)
            }) { Text("重载", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                // DSH 的 token 是一次性启动令牌（换完 cookie 就作废），且 cookie 名绑定 authority，
                // 所以同一链接给第二个客户端（系统浏览器）必然被拒这里只提供复制链接，方便排查
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("dsh_url", url))
            }) { Text("复制链接", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                showDiag = !showDiag
                if (!showDiag) {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("dsh_diag", diag))
                }
            }) { Text(if (showDiag) "收起" else "诊断", style = MaterialTheme.typography.labelSmall) }
        }
        if (showDiag) {
            Text(
                (if (diag.isBlank()) "（暂无控制台错误）" else diag) +
                    (if (blankWarn) "\n\n 检测到页面渲染为空（白屏）：已尝试自动重载一次，仍为空请把以上内容发我定位。\n" +
                        "常见原因： 某个 /api 或 WebSocket 请求被拒（会以 HTTP 4xx 或 console error 出现）\n" +
                        " 前端 JS 抛错（会出现在上面）\n" +
                        " DSH 首次创建默认工作区失败（Alpine 缺少 xdg-user-dir）新版已自动配置 /root 目录\n" +
                        " 改过 API key/模型后，点顶部「重配」会用当前配置重启 DSH\n" +
                        "注：DSH 的 token 是一次性启动令牌，同一链接不能给第二个客户端用（系统浏览器必被拒，属正常）" else "") +
                    "\n（点「收起」会把以上内容复制到剪贴板）",
                style = MaterialTheme.typography.labelSmall,
                color = if (blankWarn || diag.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            key(rendererGeneration) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    // DSH 的 Web UI 是桌面优先的：给桌面 UA + 宽视口，手机上用双指缩放看
                    settings.userAgentString =
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    if (com.example.aichat.BuildConfig.DEBUG) {
                        @Suppress("DEPRECATION")
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    visibility = if (active) android.view.View.VISIBLE else android.view.View.INVISIBLE
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, u, favicon)
                            // 1) 先补 WebView 缺失的现代 API（必须在 DSH inline boot 脚本之前执行）。
                            // 2) 再安装 window 错误钩子和 WebSocket 探针。
                            view.evaluateJavascript(
                                DSH_COMPAT_JS + "\n" +
                                "(function(){try{if(window.__aichatErrHooked)return;window.__aichatErrHooked=true;" +
                                    "window.addEventListener('error',function(e){try{console.error('[aichat][error] '+(e.message||e.error)+' @ '+(e.filename||'')+':'+(e.lineno||0))}catch(_){}});" +
                                    "window.addEventListener('unhandledrejection',function(e){try{console.error('[aichat][rejection] '+e.reason)}catch(_){}});" +
                                "}catch(_){}})();\n" +
                                "(function(){try{if(window.__aichatWsHooked)return;window.__aichatWsHooked=true;" +
                                    "var O=window.WebSocket;if(!O)return;" +
                                    "window.WebSocket=new Proxy(O,{construct:function(T,A){var ws=Reflect.construct(T,A);" +
                                        "try{var url=String(A[0]);console.log('[aichat][ws] open '+url);" +
                                            "ws.addEventListener('open',function(){console.log('[aichat][ws] opened '+url)});" +
                                            "ws.addEventListener('error',function(){console.log('[aichat][ws] error '+url)});" +
                                            "ws.addEventListener('close',function(e){console.log('[aichat][ws] closed '+url+' code='+e.code+' reason='+e.reason)})" +
                                        "}catch(_){}return ws;}});" +
                                "}catch(_){}})()", null)
                        }
                        override fun onPageFinished(view: WebView, u: String?) {
                            super.onPageFinished(view, u)
                            // token 回退路径被 WebView 自己消费成功后，最终 URL 会变成干净地址。
                            // 立即回写全局状态，并更新 requestedUrl，避免随后 url 变成干净地址时又 reload 一次。
                            if (!u.isNullOrBlank() && !u.contains("token=")) {
                                requestedUrl.set(url.substringBefore("?token="))
                            }
                            if (DshState.webUrl == url && !u.isNullOrBlank() && !u.contains("token=")) {
                                DshState.webUrl = cleanUrl
                            }
                            view.postDelayed({
                                view.evaluateJavascript(renderProbeJs) { r ->
                                    val pv = r?.trim('"')
                                    blankWarn = pv != "1" && pv != "2"
                                }
                            }, 15_000)
                        }
                        override fun onReceivedError(view: WebView, req: android.webkit.WebResourceRequest?, err: android.webkit.WebResourceError?) {
                            diag = ("ERR " + err?.errorCode + ": " + err?.description + " @ " + shortDiagUrl(req?.url?.toString())).takeLast(4000)
                        }
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            // Android 低内存时 WebView 渲染进程可能被系统回收，表现为整页突然空白。
                            // 返回 true 阻止 App 崩溃，重建 WebView 后重新加载当前 DSH 地址。
                            diag = ("渲染进程异常，正在重建页面 (crashed=" + (detail?.didCrash() == true) + ")").takeLast(4000)
                            webRef.value = null
                            rendererGeneration++
                            return true
                        }
                        override fun onReceivedHttpError(view: WebView, req: android.webkit.WebResourceRequest?, resp: android.webkit.WebResourceResponse?) {
                            diag = ("HTTP " + resp?.statusCode + " @ " + shortDiagUrl(req?.url?.toString())).takeLast(4000)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                            val line = "" + m?.messageLevel() + " " + m?.message() + " (" + shortDiagUrl(m?.sourceId()) + ":" + m?.lineNumber() + ")"
                            diag = (diag + "\n" + line).trim().takeLast(4000)
                            return true
                        }
                    }
                    // url 带 token 只可能来自 App 侧交换失败的回退：
                    // 让 WebView 自己完成一次 token -> 303 -> Set-Cookie；成功后 onPageFinished
                    // 会把 DshState.webUrl 更新为干净地址，避免同一个一次性 token 被加载第二次。
                    // 不能再用 Cookie 里是否有 dsh-auth- 来决定跳过 token：旧 cookie 可能已失效，
                    // 跳过会一直卡在 authentication required。
                    requestedUrl.set(url)
                    requestedGeneration.set(generation)
                    loadUrl(loadTarget)
                    webRef.value = this
                }
            },
            update = { view ->
                // 退出 Harness 时只让 WebView 不可见，保留 DOM/WebSocket/会话；回来直接复用。
                view.visibility = if (active) android.view.View.VISIBLE else android.view.View.INVISIBLE
                // 只在真正切换 token / VM 会话重启时加载；普通重组不会 reload。
                val generationChanged = requestedGeneration.get() != generation
                val urlChanged = requestedUrl.get() != url
                if (generationChanged || urlChanged) {
                    requestedGeneration.set(generation)
                    requestedUrl.set(url)
                    view.loadUrl(loadTarget)
                }
                }
            )
            }
            if (showLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(14.dp))
                        Text("DSH 正在加载插件", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (slowLoad) "加载时间较长，可继续等待；若一直空白请点右上角「诊断」"
                            else "首次可能需要 13 分钟，请不要退出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class HarnessMsg(val role: String, val content: String)
