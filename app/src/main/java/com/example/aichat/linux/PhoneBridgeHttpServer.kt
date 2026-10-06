package com.example.aichat.linux

import android.content.Context
import com.example.aichat.data.HttpClient
import com.example.aichat.data.StorageManager
import com.example.aichat.service.ScreenControlService
import com.google.gson.JsonParser
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * 给 QEMU guest 用的 HTTP 设备能力桥。
 *
 * guest 通过 QEMU user networking 访问 10.0.2.2:PORT，宿主只监听 127.0.0.1，
 * 所有请求都要带 token。最终仍然落到 PhoneBridgeManager -> 无障碍服务。
 */
object PhoneBridgeHttpServer {
    private const val TAG = "PhoneBridgeHttp"
    const val PORT = 48879
    private const val HOST = "127.0.0.1"
    private const val PREFS = "phone_bridge_http"
    private const val KEY_TOKEN = "token"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var appContext: Context? = null

    @Volatile
    private var tokenValue: String = ""

    @Volatile
    var isRunning: Boolean = false
        private set

    val token: String get() = tokenValue

    fun start(context: Context): Boolean {
        if (isRunning) return true
        appContext = context.applicationContext
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        tokenValue = prefs.getString(KEY_TOKEN, null)
            ?: UUID.randomUUID().toString().replace("-", "").also {
                prefs.edit().putString(KEY_TOKEN, it).apply()
            }
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(HOST, PORT))
            serverSocket = ss
            isRunning = true
            acceptJob = scope.launch {
                while (isActive) {
                    val socket = try { ss.accept() } catch (_: Exception) { break }
                    launch { handle(socket) }
                }
            }
            Log.i(TAG, "phone bridge listening on $HOST:$PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}", e)
            isRunning = false
            false
        }
    }

    fun stop() {
        isRunning = false
        appContext = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /** 手动粘贴用的一行命令（复制到 VM 串口终端里执行）。自动安装请用 [guestSetupCommands]。 */
    fun guestSetupCommand(): String {
        if (tokenValue.isBlank()) return ""
        val dollar = "$"
        val url = "http://10.0.2.2:$PORT/guest-setup.sh?token=$tokenValue"
        return "i=0; while [ ${dollar}i -lt 60 ]; do " +
            "ip link set lo up 2>/dev/null || true; ip link set eth0 up 2>/dev/null || true; " +
            "udhcpc -i eth0 -n -q 2>/dev/null || true; " +
            "wget -qO /tmp/aichat-setup.sh '${url}' && break; " +
            "i=${dollar}((i+1)); sleep 2; done; sh /tmp/aichat-setup.sh"
    }

    /**
     * 自动安装 Guest Harness 的命令序列。
     *
     * 关键点：每行都必须很短。之前把挂载 + 重试循环 + 下载 + 执行拼成一行 500+ 字符，
     * 在 QEMU 串口上很容易被丢字符写坏，脚本根本没被执行，于是 harness 一直连不上。
     * 现在拆成多行短命令，网络重试由 App 侧负责（见 QemuManager.startGuestSetup）。
     */
    fun guestSetupCommands(): List<String> {
        if (tokenValue.isBlank()) return emptyList()
        val url = "http://10.0.2.2:$PORT/guest-setup.sh?token=$tokenValue"
        return listOf(
            "mount -t proc proc /proc 2>/dev/null",
            "mount -t sysfs sysfs /sys 2>/dev/null",
            "mount -t devtmpfs devtmpfs /dev 2>/dev/null",
            "modprobe virtio_net 2>/dev/null",
            "ip link set eth0 up 2>/dev/null",
            "udhcpc -i eth0 -n -q 2>/dev/null",
            "wget -T 15 -O /tmp/aichat-setup.sh '${url}'",
            "sh /tmp/aichat-setup.sh"
        )
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 8000
            val input = socket.getInputStream()
            // 先按字节读 HTTP 头，避免 BufferedReader 预读吞掉 body 的第一个字节
            val headerBytes = java.io.ByteArrayOutputStream()
            while (headerBytes.size() < 32768) {
                val b = input.read()
                if (b < 0) return
                headerBytes.write(b)
                val arr = headerBytes.toByteArray()
                val n = arr.size
                if (n >= 4 && arr[n - 4] == 13.toByte() && arr[n - 3] == 10.toByte() &&
                    arr[n - 2] == 13.toByte() && arr[n - 1] == 10.toByte()
                ) break
            }
            val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
            val headerLines = headerText.split("\r\n").filter { it.isNotEmpty() }
            val requestLine = headerLines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = mutableMapOf<String, String>()
            headerLines.drop(1).forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bytes, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                bytes.copyOf(read)
            } else ByteArray(0)
            val body = String(bodyBytes, Charsets.UTF_8)

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val requestToken = query.split('&')
                .mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
                .firstOrNull { it[0] == "token" }?.get(1).orEmpty()

            // App 内置 Alpine Python 仓库：公开包，无需 token
            if (method == "GET" && path.startsWith("/guest-apks/")) {
                // APKINDEX.tar.gz 在打包时会被 AAPT 解压改名，实际资源存为 APKINDEX.bin
                val rel = path.removePrefix("/guest-apks/").trimStart('/').replace("..", "")
                val assetPath = if (rel.endsWith("APKINDEX.tar.gz")) "guest-apks/" + rel.removeSuffix(".tar.gz") + ".bin" else "guest-apks/" + rel
                val bytes = try {
                    appContext?.assets?.open(assetPath)?.use { it.readBytes() }
                } catch (_: Exception) { null }
                if (bytes == null) {
                    writeResponse(socket, 404, "not found")
                } else {
                    writeBytesResponse(
                        socket, 200, bytes,
                        if (rel.endsWith(".apk")) "application/octet-stream" else "application/gzip"
                    )
                }
                return
            }
            // 离线 DSH（DeepSeek Harness）依赖树：首次访问时从 assets 解出到 files 目录，再流式下发
            if (method == "GET" && path == "/dsh-bundle.tar.gz") {
                if (requestToken != tokenValue || tokenValue.isBlank()) {
                    writeResponse(socket, 403, "forbidden")
                } else {
                    val ctx = appContext
                    if (ctx == null) {
                        writeResponse(socket, 503, "app context not ready")
                    } else {
                        val f = File(ctx.filesDir, "dsh/dsh-bundle.tar.gz")
                        try {
                            if (!f.exists() || f.length() <= 0L) {
                                f.parentFile?.mkdirs()
                                val tmp = File(f.parentFile, f.name + ".part")
                                ctx.assets.open("dsh/dsh-bundle.bin").use { input ->
                                    tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                                }
                                if (f.exists()) f.delete()
                                tmp.renameTo(f)
                            }
                            writeFileResponse(socket, f, "application/gzip")
                        } catch (e: Exception) {
                            writeResponse(socket, 404, "dsh bundle missing: ${e.message}")
                        }
                    }
                }
                return
            }
            // DSH guest 通过 10.0.2.2 调手机桥模型代理，它拿不到 phone bridge token；
            // /v1/* 只暴露给 QEMU guest networking，且代理内部仍用 App 当前 profile 的 key，
            // 所以这几个模型转发路由免 token。其余设备能力接口仍必须带 token。
            val modelProxyPath = path == "/v1/chat/completions" || path == "/v1/models" || path == "/model/chat"
            when {
                (requestToken != tokenValue || tokenValue.isBlank()) && !modelProxyPath -> {
                    writeResponse(socket, 403, "forbidden")
                }
                method == "GET" && path == "/phone/ping" -> {
                    writeResponse(socket, 200, "OK")
                }
                method == "GET" && path == "/phone.sh" -> {
                    writeResponse(socket, 200, guestScript())
                }
                method == "GET" && path == "/harness.py" -> {
                    writeResponse(socket, 200, harnessScript())
                }
                method == "GET" && path == "/disk-install.sh" -> {
                    writeResponse(socket, 200, diskInstallScript())
                }
                method == "GET" && path == "/guest-setup.sh" -> {
                    writeResponse(socket, 200, guestSetupScript())
                }
                method == "GET" && path == "/phone/screenshot" -> {
                    val bytes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        ScreenControlService.instance?.captureScreenshotJpeg()
                    } else null
                    if (bytes == null || bytes.isEmpty()) {
                        writeResponse(socket, 503, "screenshot unavailable (need Android 11+ and accessibility service connected)")
                    } else {
                        writeBytesResponse(socket, 200, bytes, "image/jpeg")
                    }
                }
                method == "POST" && path == "/phone/exec" -> {
                    val result = PhoneBridgeManager.executeRemote(body)
                    writeResponse(socket, 200, result)
                }
                method == "POST" && path == "/model/chat" -> {
                    val result = proxyModelChat(body)
                    writeResponse(socket, result.first, result.second, "application/json; charset=utf-8")
                }
                method == "GET" && path == "/health" -> {
                    writeResponse(socket, 200, "OK")
                }
                method == "GET" && path == "/v1/models" -> {
                    writeResponse(
                        socket, 200,
                        "{\"object\":\"list\",\"data\":[{\"id\":\"host-harness\",\"object\":\"model\"}]}",
                        "application/json; charset=utf-8"
                    )
                }
                method == "POST" && path == "/v1/chat/completions" -> {
                    val result = proxyModelChat(body)
                    writeResponse(socket, result.first, result.second, "application/json; charset=utf-8")
                }
                method == "POST" && path == "/vm/upload" -> {
                    val rawName = query.split('&')
                        .mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
                        .firstOrNull { it[0] == "name" }?.get(1).orEmpty()
                    val safeName = rawName.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.take(64)
                    val ctx = appContext
                    if (safeName.isBlank() || ctx == null || bodyBytes.isEmpty()) {
                        writeResponse(socket, 400, "bad upload")
                    } else {
                        val dir = File(ctx.filesDir, "vm/upload").apply { mkdirs() }
                        File(dir, safeName).writeBytes(bodyBytes)
                        writeResponse(socket, 200, "OK")
                    }
                }
                else -> writeResponse(socket, 404, "not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** 用宿主保存的 API Key 代理模型请求，guest 不需要也不应该拿 Key。 */
    private fun proxyModelChat(body: String): Pair<Int, String> {
        val ctx = appContext ?: return 503 to "app context not ready"
        val profile = try {
            StorageManager(ctx).getActiveProfile() ?: StorageManager(ctx).getProfiles().firstOrNull()
        } catch (_: Exception) { null } ?: return 502 to "no active profile"
        if (profile.apiKey.isBlank()) return 502 to "active profile has empty API key"
        val requestJson = try {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.addProperty("model", profile.model)
            obj.addProperty("stream", false)
            obj.toString()
        } catch (_: Exception) {
            return 400 to "body must be a JSON object"
        }
        val url = profile.baseUrl.trim().trimEnd('/') + "/chat/completions"
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${profile.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()
            HttpClient.instance.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful) 200 to text else resp.code to text
            }
        } catch (e: Exception) {
            502 to "model proxy error: ${e.message}"
        }
    }

    /**
     * guest 自动配置脚本（尽量全离线）：
     * 1. 用手机桥内置的 aarch64 离线 apk 仓库装 node / npm / pnpm / git / bash
     * 2. 解压手机桥内置的 DeepSeek Harness（dsh）依赖树到 /opt/dsh
     * 3. 启动 dsh web（guest 8000 端口，App 通过 hostfwd 127.0.0.1:18000 访问），
     *    并把带 token 的启动 URL 用 AICHAT_DSH_URL= 输出给 App
     */
    private fun guestSetupScript(): String {
        val dollar = "$"
        // 用 App 当前 API 配置预置 DSH 的模型（DeepSeek 兼容 OpenAI 协议），做到免配置
        val profile = try {
            appContext?.let { StorageManager(it).getActiveProfile() }
                ?: appContext?.let { StorageManager(it).getProfiles().firstOrNull() }
        } catch (_: Exception) { null }
        // 清洗：换行/制表符会把生成的 shell 脚本断行（DSH 起不来、日志空白）；URL 去掉所有空白
        val seedKey = profile?.apiKey.orEmpty()
            .replace(Regex("[\r\n\t]"), "")
            .trim()
            .replace("'", "'\\''")
        val profileBase = profile?.baseUrl.orEmpty().filter { !it.isWhitespace() }.trimEnd('/')
        // guest 直连外网可能没有可用 DNS/路由（当前 QEMU dns=192.168.1.1 在部分网络下解析不了），
        // 所以有 key 时让 DSH 走手机桥的 OpenAI 代理（10.0.2.2:PORT/v1），由 App 用当前 profile 转发到模型。
        // 保留 profile 原始 baseURL：guest DNS 修好后直连官方模型；桥代理仍有 token/路由兼容问题。
        val seedBase = profileBase
        val seedModel = profile?.model.orEmpty()
            .replace(Regex("[\r\n\t]"), "")
            .trim()
        // 用 printf 写配置（不用 heredoc：万一终止符不匹配会把后面的启动代码一起吞掉）
        val seedBlock = if (seedKey.isNotBlank() && seedBase.isNotBlank() && seedModel.isNotBlank()) {
            "mkdir -p /root/.dsh/profiles/web; " +
                "printf -- '- id: llm-pi-ai\\n  config:\\n    providers:\\n      aichat:\\n" +
                "        apiKeyEnv: AICHAT_API_KEY\\n        api: openai-completions\\n" +
                "        baseURL: %s\\n        models:\\n          - id: %s\\n' " +
                "'$seedBase' '$seedModel' > /root/.dsh/profiles/web/cordis.patch.yml; " +
                "echo AICHAT_DSH_CONFIG_WRITTEN\n"
        } else "echo AICHAT_DSH_CONFIG_SKIP\n"
        // 用独立的 --patch overlay 写 DSH 配置，避免动 profile/home patch：
        // 1) Alpine 没有 xdg-user-dir，显式指定 documentsDirectory=/root，DSH 才能建默认工作区；
        // 2) QEMU 慢机连接 WebSocket mux 的 ready 帧可能超过默认 15 秒，放宽到 3 分钟，
        //    避免 "generation was not ready within 15000ms; cancelling generation" 反复重连导致白屏。
        val workspaceBlock = "mkdir -p /root/.dsh; " +
            // 先处理最容易导致创建不了工作区的两类 guest 侧问题：
            // 1) 根分区因 journal/异常被挂成 ro -> 重新挂载 rw；
            // 2) /root 或默认工作区目录不存在/权限不对 -> 以 root 预创建并 chmod 755。
            // DSH 在 guest 里以 root 运行，本身不受 Android 存储权限限制；真正的写失败基本都在这里。
            "mount -o remount,rw / 2>/dev/null || true; " +
            "mkdir -p /root/deepseek-harness/default-workspace 2>/dev/null || true; " +
            "chmod 755 /root /root/deepseek-harness /root/deepseek-harness/default-workspace 2>/dev/null || true; " +
            "if touch /root/deepseek-harness/default-workspace/.aichat-write-test 2>/dev/null; then " +
            "rm -f /root/deepseek-harness/default-workspace/.aichat-write-test; echo AICHAT_DSH_WRITE_OK; " +
            "else echo AICHAT_DSH_WRITE_FAIL; fi; " +
            "printf -- '- id: workspace-controller\\n  config:\\n    documentsDirectory: /root\\n" +
            "- id: connection\\n  config:\\n    recovery:\\n" +
            "      backoffBaseMs: 1000\\n      backoffFactor: 1.5\\n      backoffMaxMs: 30000\\n" +
            "      generationReadyWarnMs: 30000\\n      generationReadyTimeoutMs: 180000\\n' " +
            "> /root/.dsh/aichat.patch.yml; " +
            "echo AICHAT_DSH_WORKSPACE_PATCH_OK\n"
        return """#!/bin/sh
echo AICHAT_SETUP_BEGIN
mkdir -p /usr/local/bin /opt
ip link set lo up 2>/dev/null || true
# 等 guest 能通过 10.0.2.2 访问手机桥
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
  ip link set eth0 up 2>/dev/null || true
  udhcpc -i eth0 -n -q 2>/dev/null || true
  wget -qO- "http://10.0.2.2:$PORT/phone/ping?token=$tokenValue" >/dev/null 2>&1 && break
  sleep 2
done
echo "nameserver 10.0.2.3" > /etc/resolv.conf
# 手机桥内置离线仓库（node/npm/pnpm/git/bash 及其依赖）
echo "http://10.0.2.2:$PORT/guest-apks" > /etc/apk/repositories
apk update --allow-untrusted >/tmp/aichat-apk-update.log 2>&1 || true
wget -qO /usr/local/bin/phone 'http://10.0.2.2:$PORT/phone.sh?token=$tokenValue' && chmod +x /usr/local/bin/phone || true
echo AICHAT_TOOLCHAIN_BEGIN
apk add --no-cache --allow-untrusted nodejs npm pnpm git bash >/tmp/aichat-toolchain.log 2>&1 || true
if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && command -v git >/dev/null 2>&1; then
  echo "node ${dollar}(node --version 2>/dev/null)"
  echo "npm  ${dollar}(npm --version 2>/dev/null)"
  echo "git  ${dollar}(git --version 2>/dev/null)"
  command -v pnpm >/dev/null 2>&1 && echo "pnpm ${dollar}(pnpm --version 2>/dev/null)"
  echo AICHAT_TOOLCHAIN_OK
else
  echo AICHAT_TOOLCHAIN_FAIL
  tail -30 /tmp/aichat-toolchain.log 2>/dev/null || true
fi
# ---- DeepSeek Harness（离线依赖树）----
echo AICHAT_DSH_BEGIN
if [ ! -x /opt/dsh/bin/dsh ]; then
  rm -f /tmp/dsh-bundle.tar.gz
  wget -O /tmp/dsh-bundle.tar.gz 'http://10.0.2.2:$PORT/dsh-bundle.tar.gz?token=$tokenValue' || true
  mkdir -p /opt
  tar -xzf /tmp/dsh-bundle.tar.gz -C /opt >/tmp/aichat-dsh-untar.log 2>&1 || true
  rm -f /tmp/dsh-bundle.tar.gz
fi
# DSH 0.2.0-rc.2 的 ui-user-questions 在同意执行后的恢复流程里，如果 session binding 尚未就绪，
# reconcile 会同步抛错并冒泡，导致整个 DSH Web UI 白屏。给它加一层 try/catch 防御：出错只记日志，不拖垮壳。
UQ=/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-user-questions/lib/client.js
if [ -f "${dollar}UQ" ]; then
cat > /tmp/aichat-patch-uq.js <<'JS_UQ_EOF'
const fs = require('fs');
const p = '/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-user-questions/lib/client.js';
if (!fs.existsSync(p)) process.exit(0);
let s = fs.readFileSync(p, 'utf8');
if (s.indexOf('[aichat] uq reconcile guard') >= 0) { console.log('already patched'); process.exit(0); }
const open = 'const reconcile = () => {\n';
const close = '\n\t\t\t};\n\t\t\treconcile();';
if (s.indexOf(open) < 0 || s.indexOf(close) < 0) { console.log('pattern missing'); process.exit(0); }
s = s.replace(open, open + '\t\t\t\ttry {\n');
s = s.replace(close, '\n\t\t\t\t} catch (error) { console.error("[aichat] uq reconcile guard", error); }\n\t\t\t};\n\t\t\treconcile();');
fs.writeFileSync(p, s);
console.log('patched');
JS_UQ_EOF
node /tmp/aichat-patch-uq.js >/tmp/aichat-patch-uq.log 2>&1 || true
echo "AICHAT_DSH_UQ_PATCH=${dollar}(tail -1 /tmp/aichat-patch-uq.log 2>/dev/null)"
fi
# DSH 的 READY_MARKUP 在页面最前面调用 Promise.withResolvers()；老 WebView/浏览器没有这个 API 会直接抛错，
# __DSH_BOOT_READY__ 永远不 resolve，UI 停在 Loading plugins 或空白。这里在 DSH 自己的 HTML 模板里、
# READY_MARKUP 之前插入兼容层：内嵌 WebView 和系统浏览器（外部模式）都能直接受益。
HOSTWS=/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-host-webserver/lib/index.js
if [ -f "${dollar}HOSTWS" ]; then
cat > /tmp/aichat-patch-html.js <<'JS_HTML_EOF'
const fs = require('fs');
const p = '/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-host-webserver/lib/index.js';
if (!fs.existsSync(p)) process.exit(0);
let s = fs.readFileSync(p, 'utf8');
if (s.indexOf('__AICHAT_COMPAT__') >= 0) { console.log('already patched'); process.exit(0); }
const poly = '/*__AICHAT_COMPAT__*/' +
  "if(typeof Promise.withResolvers!=='function'){Object.defineProperty(Promise,'withResolvers',{configurable:true,writable:true,value:function(){var r,j,p=new Promise(function(a,b){r=a;j=b;});return {promise:p,resolve:r,reject:j};}});}" +
  "if(typeof AbortSignal.any!=='function'){AbortSignal.any=function(sigs){var c=new AbortController(),a=function(r){if(!c.signal.aborted)c.abort(r);};for(var i=0;sigs&&i<sigs.length;i++){var g=sigs[i];if(!g)continue;if(g.aborted){a(g.reason);break;}g.addEventListener('abort',function(){a(this.reason);},{once:true});}return c.signal;};}" +
  "if(typeof AbortSignal.timeout!=='function'){AbortSignal.timeout=function(ms){var c=new AbortController();setTimeout(function(){try{c.abort(new DOMException('TimeoutError','TimeoutError'));}catch(_){c.abort();}},ms);return c.signal;};}";
const html = '<script>' + poly + '</script><script>(globalThis.__DSH_BOOT_READY__ ??= Promise.withResolvers()).resolve()</script>';
const start = s.indexOf('const READY_MARKUP = ');
if (start < 0) { console.log('READY_MARKUP not found'); process.exit(0); }
const end = s.indexOf('\n', start);
if (end < 0) { console.log('READY_MARKUP line end not found'); process.exit(0); }
const line = 'const READY_MARKUP = ' + JSON.stringify(html) + ';';
s = s.slice(0, start) + line + s.slice(end + 1);
fs.writeFileSync(p, s);
console.log('patched');
JS_HTML_EOF
node /tmp/aichat-patch-html.js >/tmp/aichat-patch-html.log 2>&1 || true
echo "AICHAT_DSH_HTML_PATCH=${dollar}(tail -1 /tmp/aichat-patch-html.log 2>/dev/null)"
fi
# DSH 的 dsh-sandbox-local 在 Linux 上会先探测 bwrap，失败后探测 landlock-run。
# landlock-run 的 native probe 在部分真机/QEMU 组合下抛 std::system_error: No error information，
# 导致 DSH 启动即 abort（exit=134）。QEMU guest 本身就是隔离环境，这里直接让 confine 返回原始 argv，
# 跳过 bwrap/landlock 探测，避免该 native abort。
SB1=/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-sandbox-local/lib/index.js
SB2=/opt/dsh/lib/node_modules/@deepseek-ai/dsh-sandbox-local/lib/index.js
if [ -f "${dollar}SB1" ] || [ -f "${dollar}SB2" ]; then
cat > /tmp/aichat-patch-sandbox.js <<'JS_SB_EOF'
const fs = require('fs');
const paths = [
  '/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-sandbox-local/lib/index.js',
  '/opt/dsh/lib/node_modules/@deepseek-ai/dsh-sandbox-local/lib/index.js'
];
for (const p of paths) {
  if (!fs.existsSync(p)) continue;
  let s = fs.readFileSync(p, 'utf8');
  if (s.indexOf('[aichat] sandbox bypass') >= 0) { console.log('already patched ' + p); continue; }
  const fn = 'async confine(argv, policy, signal) {';
  const i = s.indexOf(fn);
  if (i < 0) { console.log('confine missing ' + p); continue; }
  const call = 'signal?.throwIfAborted();';
  const j = s.indexOf(call, i);
  if (j < 0) { console.log('throwIfAborted missing ' + p); continue; }
  const at = j + call.length;
  const inject = '\n\t\t/* [aichat] sandbox bypass: QEMU guest is the isolation boundary; landlock-run probe aborts on some devices */\n' +
    '\t\treturn Promise.resolve({ argv: [...argv], enforcement: "partial", denialSignatures: [], runnerFailureRules: [] });';
  s = s.slice(0, at) + inject + s.slice(at);
  fs.writeFileSync(p, s);
  console.log('patched ' + p);
}
JS_SB_EOF
node /tmp/aichat-patch-sandbox.js >/tmp/aichat-patch-sandbox.log 2>&1 || true
if grep -q 'patched' /tmp/aichat-patch-sandbox.log 2>/dev/null; then
  echo "AICHAT_DSH_SANDBOX_PATCH=patched"
else
  echo "AICHAT_DSH_SANDBOX_PATCH=failed"
  tail -5 /tmp/aichat-patch-sandbox.log 2>/dev/null || true
fi
# session-persistence-jsonl 在写会话时调用 native flock（system.node）。在部分真机/QEMU 组合里，
# 这个 native 调用会抛 std::system_error: No error information 并 abort（exit=134，发生在启动几分钟后）。
# guest 里同一时刻只有一个 DSH 进程，跨进程文件锁没有意义，这里直接去掉 native flock 调用。
SP1=/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js
SP2=/opt/dsh/lib/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js
if [ -f "${dollar}SP1" ] || [ -f "${dollar}SP2" ]; then
cat > /tmp/aichat-patch-flock.js <<'JS_FL_EOF'
const fs = require('fs');
const paths = [
  '/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js',
  '/opt/dsh/lib/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js'
];
for (const p of paths) {
  if (!fs.existsSync(p)) continue;
  let s = fs.readFileSync(p, 'utf8');
  if (s.indexOf('[aichat] skip native flock') >= 0) { console.log('already patched ' + p); continue; }
  const call = 'await tryLockExclusive(handle.fd);';
  const i = s.indexOf(call);
  if (i < 0) { console.log('tryLockExclusive missing ' + p); continue; }
  s = s.slice(0, i) + '/* [aichat] skip native flock: single DSH process per guest; native flock aborts on this QEMU combo */' + s.slice(i + call.length);
  fs.writeFileSync(p, s);
  console.log('patched ' + p);
}
JS_FL_EOF
node /tmp/aichat-patch-flock.js >/tmp/aichat-patch-flock.log 2>&1 || true
if grep -q 'patched' /tmp/aichat-patch-flock.log 2>/dev/null; then
  echo "AICHAT_DSH_FLOCK_PATCH=patched"
else
  echo "AICHAT_DSH_FLOCK_PATCH=failed"
  tail -5 /tmp/aichat-patch-flock.log 2>/dev/null || true
fi
fi
# dsh-subprocess-local 在 Linux 上也会用 koffi FFI 加载 libc（execve/fcntl）。
# koffi.node 在部分真机/QEMU 组合里一加载就抛 std::system_error -> abort（exit=134，浏览器里一用会话/工具就触发）。
# 让它的 Linux bootstrap 探测直接失败，走普通 child_process fallback，彻底不加载 koffi。
KO1=/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/runner-launch-B2zsQ1Dz.js
KO2=/opt/dsh/lib/node_modules/@deepseek-ai/dsh-subprocess-local/lib/runner-launch-B2zsQ1Dz.js
if [ -f "${dollar}KO1" ] || [ -f "${dollar}KO2" ]; then
cat > /tmp/aichat-patch-koffi.js <<'JS_KO_EOF'
const fs = require('fs');
const paths = [
  '/opt/dsh/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/runner-launch-B2zsQ1Dz.js',
  '/opt/dsh/lib/node_modules/@deepseek-ai/dsh-subprocess-local/lib/runner-launch-B2zsQ1Dz.js'
];
for (const p of paths) {
  if (!fs.existsSync(p)) continue;
  let s = fs.readFileSync(p, 'utf8');
  if (s.indexOf('[aichat] koffi disabled') >= 0) { console.log('already patched ' + p); continue; }
  const fn = 'function loadLinuxExecve() {';
  const i = s.indexOf(fn);
  if (i < 0) { console.log('loadLinuxExecve missing ' + p); continue; }
  const inject = '\n\tthrow new Error("[aichat] koffi disabled under QEMU to avoid native abort");';
  s = s.slice(0, i + fn.length) + inject + s.slice(i + fn.length);
  fs.writeFileSync(p, s);
  console.log('patched ' + p);
}
JS_KO_EOF
node /tmp/aichat-patch-koffi.js >/tmp/aichat-patch-koffi.log 2>&1 || true
if grep -q 'patched' /tmp/aichat-patch-koffi.log 2>/dev/null; then
  echo "AICHAT_DSH_KOFFI_PATCH=patched"
else
  echo "AICHAT_DSH_KOFFI_PATCH=failed"
  tail -5 /tmp/aichat-patch-koffi.log 2>/dev/null || true
fi
fi
fi
if [ -x /opt/dsh/bin/dsh ]; then
  ln -sf /opt/dsh/bin/dsh /usr/local/bin/dsh 2>/dev/null || true
  echo "dsh ${dollar}(/opt/dsh/bin/dsh --version 2>/dev/null)"
  export DSH_HOME=/root/.dsh
  # Node 22+ 的 V8 编译缓存：首次成功启动后生成，之后 DSH 启动会快很多（手机单核很需要）
  export NODE_COMPILE_CACHE=/root/.node-cache
  mkdir -p /root/.node-cache 2>/dev/null
  cd /root
  # DSH 出于安全只允许监听 127.0.0.1；用 node 做透明 TCP 转发：0.0.0.0:8000 -> 127.0.0.1:3080。
  # 这里绝不能改写 Host/Origin！DSH 的浏览器会话 cookie 名和签名 audience 都绑定请求 authority（Host）。
  # App/WebView 始终用 127.0.0.1:18000 访问，DSH 也必须始终看到 Host=127.0.0.1:18000，
  # 这样 token 交换时签发的 cookie 才会和后续所有 /api 请求一致。
  # 旧实现只改写每条 TCP 连接的第一个请求；浏览器 keep-alive 复用连接后，后续 /api 请求
  # 带原始 Host=127.0.0.1:18000 到达 DSH，cookie 名对不上 -> HTTP 401
  #（/api/session/modelCatalog、directoryPicker/createDirectory 等都会失败）。
  cat > /usr/local/bin/dsh-forward.js <<'JS_EOF'
const net = require('net');
net.createServer((c) => {
  const s = net.connect(3080, '127.0.0.1');
  c.on('data', (d) => { if (!s.destroyed) s.write(d); });
  s.on('data', (d) => { if (!c.destroyed) c.write(d); });
  c.on('end', () => s.end());
  s.on('end', () => c.end());
  c.on('error', () => s.destroy());
  s.on('error', () => c.destroy());
}).listen(8000, '0.0.0.0');
JS_EOF
  # DSH 进程守护：DSH/Node 偶发崩溃或被 OOM 杀掉后，自动重新拉起。
  # DSH 的浏览器 cookie 签名密钥持久化在 /root/.dsh（cookieMaxAgeDays=30），
  # 进程重启后旧 cookie 仍然有效，浏览器可自动重连，不需要重新发 token。
  cat > /usr/local/bin/aichat-dsh-supervisor.sh <<'DSH_SUP_EOF'
#!/bin/sh
# AICHAT_DSH_SUPERVISOR_V1
export DSH_HOME=/root/.dsh
export NODE_COMPILE_CACHE=/root/.node-cache
# DSH/Node 在 QEMU 慢机上偶发 V8 abort（exit=134）。显式限制 old space，避免默认堆上限过高导致
# 宿主 2GB guest 内触发 native abort；同时保留足够 heap 给 540 插件加载。
export NODE_OPTIONS="--max-old-space-size=1400 --max-semi-space-size=64"
cd /root
while true; do
  if [ -f /tmp/aichat-dsh-stop ]; then exit 0; fi
  if ! pgrep -f 'dsh --profile web' >/dev/null 2>&1; then
    echo "AICHAT_DSH_SUPERVISOR_START ${dollar}(date)" >>/tmp/dsh-web.log
    NODE_OPTIONS="${dollar}NODE_OPTIONS --report-on-fatalerror --report-directory=/root/.dsh" node --require /tmp/aichat-dsh-hook.js /opt/dsh/bin/dsh --profile web --patch /root/.dsh/aichat.patch.yml --no-open --port 3080 --host 127.0.0.1 --trusted-host 127.0.0.1:18000 >>/tmp/dsh-web.log 2>&1
    code=${dollar}?
    cp /tmp/dsh-web.log /tmp/aichat-dsh-last.log 2>/dev/null || true
    {
      echo "===== AICHAT_DSH_CRASH exit=${dollar}code ${dollar}(date) ====="
      echo "--- mem ---"
      free -m 2>/dev/null || true
      echo "--- dmesg tail (OOM? segfault?) ---"
      dmesg 2>/dev/null | tail -50 || true
      echo "--- dsh log tail ---"
      tail -100 /tmp/aichat-dsh-last.log 2>/dev/null || true
    } >> /tmp/aichat-dsh-crash.log 2>&1
    echo "AICHAT_DSH_EXIT=${dollar}code" >>/tmp/dsh-web.log
    # 同时往串口打崩溃摘要，VM 终端/App 日志能直接看到退出码、日志尾和 dmesg；
    # 这样不用再手动 cat crash.log，截图串口就能定位 exit=134 的原因。
    {
      echo "===== AICHAT_DSH_CRASH exit=${dollar}code ${dollar}(date) ====="
      echo "--- dsh log tail ---"
      tail -25 /tmp/aichat-dsh-last.log 2>/dev/null || true
      echo "--- runtime hook tail ---"
      tail -50 /tmp/aichat-dsh-runtime.log 2>/dev/null || true
      echo "--- dsh own logs ---"
      DSHLOG=${dollar}(ls -t /root/.dsh/logs/*.log 2>/dev/null | head -1)
      if [ -n "${dollar}DSHLOG" ]; then tail -60 "${dollar}DSHLOG" 2>/dev/null || true; fi
      echo "--- node report files ---"
      ls -lt /root/.dsh/report.*.json 2>/dev/null | head -3 || true
      echo "--- dmesg tail ---"
      dmesg 2>/dev/null | tail -12 || true
      echo "AICHAT_DSH_CRASH_END"
    } > /dev/ttyAMA0 2>&1 || true
  fi
  sleep 2
done
DSH_SUP_EOF
  chmod +x /usr/local/bin/aichat-dsh-supervisor.sh
  # DSH 运行期钩子：记录每个 native addon 的 dlopen、未捕获异常/警告和退出码。
  # 这样 exit=134 abort 后能知道最后加载/执行到哪个 native 模块。
  cat > /tmp/aichat-dsh-hook.js <<'JS_HOOK_EOF'
const fs = require('fs');
const log = (s) => {
  try { fs.appendFileSync('/tmp/aichat-dsh-runtime.log', new Date().toISOString() + ' ' + s + '\n'); } catch (_) {}
};
try {
  const origDlopen = process.dlopen;
  process.dlopen = function (module, filename, flags) {
    log('dlopen ' + filename);
    return origDlopen.apply(this, arguments);
  };
  process.on('uncaughtException', (e) => log('uncaughtException ' + (e && e.stack || e)));
  process.on('unhandledRejection', (e) => log('unhandledRejection ' + (e && e.stack || e)));
  process.on('warning', (w) => log('warning ' + (w && w.stack || w)));
  process.on('exit', (c) => log('exit ' + c));
  log('hook installed');
} catch (_) {}
JS_HOOK_EOF
  rm -f /tmp/aichat-dsh-runtime.log
  # 重启转发器（旧版本在跑的话换掉）
  if [ -f /tmp/dsh-forward.pid ]; then
    kill ${dollar}(cat /tmp/dsh-forward.pid) 2>/dev/null
    rm -f /tmp/dsh-forward.pid
  fi
  pkill -f dsh-forward.js 2>/dev/null
  (nohup node /usr/local/bin/dsh-forward.js >/tmp/dsh-forward.log 2>&1 & echo ${dollar}! > /tmp/dsh-forward.pid)
  sleep 2
  $workspaceBlock
  # OOM 保护：磁盘模式创建 256MB swap（Live/tmpfs 不创建，避免吃内存）。
  # 很多 DSH 崩溃其实是 guest 内存不足被内核 OOM kill；crash log 里会留下 dmesg 证据。
  if [ ! -f /root/.aichat-swap ] && (df -T / 2>/dev/null | grep -q ext4 || mount 2>/dev/null | grep ' / ' | grep -q ext4); then
    dd if=/dev/zero of=/root/.aichat-swap bs=1M count=256 2>/dev/null || true
    chmod 600 /root/.aichat-swap 2>/dev/null || true
    mkswap /root/.aichat-swap >/dev/null 2>&1 && swapon /root/.aichat-swap 2>/dev/null || true
    echo 10 > /proc/sys/vm/swappiness 2>/dev/null || true
  fi
  echo "AICHAT_SWAP=${dollar}(free -m 2>/dev/null | grep -i swap || echo none)"
  # guest 直连 api.deepseek.com 需要 DNS；QEMU 的 dns=192.168.1.1 在部分移动网络下不可达，
  # 这里强制写公共 DNS，避免 DSH 模型请求瞬间 transport failed。
  mkdir -p /etc
  printf -- 'nameserver 223.5.5.5\nnameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf
  echo "AICHAT_DNS=${dollar}(cat /etc/resolv.conf 2>/dev/null | tr '\n' ' ')"
  $seedBlock  # 脚本可能被重跑（App 超时重试/手动再执行）：supervisor 活着就复用，
  # 不重启也不覆盖日志（覆盖会把 token 弄丢，界面就误报 AICHAT_DSH_FAIL）
  # 先把 key 放进当前 setup shell，后面的自检 AICHAT_SEED_KEYLEN 才能反映真实注入长度；
  # 之前只给 supervisor 进程 env，echo 永远看到空值，App 就误报未配置。
  export AICHAT_API_KEY='$seedKey'
  rm -f /tmp/aichat-dsh-stop
  if pgrep -f aichat-dsh-supervisor.sh >/dev/null 2>&1; then
    echo AICHAT_DSH_REUSE
  else
    rm -f /tmp/dsh-web.log
    (nohup env AICHAT_API_KEY='$seedKey' /usr/local/bin/aichat-dsh-supervisor.sh >/tmp/aichat-dsh-supervisor.log 2>&1 &)
    sleep 3
  fi
  # 兜底：3 秒后 supervisor 还不在，就换一种写法再试（export 注入环境变量 + 直接 nohup 执行）
  if [ ${dollar}(pgrep -f aichat-dsh-supervisor.sh 2>/dev/null | wc -l) -eq 0 ]; then
    echo AICHAT_DSH_RETRY_SIMPLE
    export AICHAT_API_KEY='$seedKey'
    cd /root
    (nohup /usr/local/bin/aichat-dsh-supervisor.sh >>/tmp/aichat-dsh-supervisor.log 2>&1 &)
    sleep 4
  fi
  # 启动后立刻自检：进程数 / 日志大小 / 日志尾  出问题一眼能看出来
  echo "AICHAT_DSH_PS=${dollar}(pgrep -f 'dsh --profile web' 2>/dev/null | wc -l)"
  echo "AICHAT_DSH_SUP_PS=${dollar}(pgrep -f aichat-dsh-supervisor.sh 2>/dev/null | wc -l)"
  echo "AICHAT_DSH_LOG_BYTES=${dollar}(wc -c < /tmp/dsh-web.log 2>/dev/null || echo 0)"
  echo "AICHAT_SEED_KEYLEN=${dollar}{#AICHAT_API_KEY}"
  echo "AICHAT_SEED_BASE=$seedBase"
  echo "AICHAT_SEED_MODEL=$seedModel"
  echo AICHAT_DSH_LOG_TAIL
  tail -3 /tmp/dsh-web.log 2>/dev/null || true
  echo AICHAT_DSH_CFG_HEAD
  head -2 /root/.dsh/profiles/web/cordis.patch.yml 2>/dev/null || true
  echo AICHAT_DSH_STARTLOG
  # 注意：不能写 tail -3 $(ls ...)，匹配不到文件时 tail 会去读 stdin 卡死
  SLA=${dollar}(ls -t /root/.dsh/logs/*.log 2>/dev/null | head -1)
  [ -n "${dollar}SLA" ] && tail -3 "${dollar}SLA" 2>/dev/null
  true
  # 进程级诊断：命令行 / fd1 指向 / cwd / 监听端口 / HTTP 应答
  DPID=${dollar}(pgrep -f 'dsh --profile web' 2>/dev/null | head -1)
  if [ -n "${dollar}DPID" ]; then
    echo "DIAG_CMD=${dollar}(tr '\0' ' ' < /proc/${dollar}DPID/cmdline 2>/dev/null)"
    echo "DIAG_FD1=${dollar}(readlink /proc/${dollar}DPID/fd/1 2>/dev/null)"
    echo "DIAG_FD2=${dollar}(readlink /proc/${dollar}DPID/fd/2 2>/dev/null)"
    echo "DIAG_CWD=${dollar}(readlink /proc/${dollar}DPID/cwd 2>/dev/null)"
    # 兜底：如果它把输出写到了别的文件，就从那个文件里把 token 捡回来
    FD1=${dollar}(readlink /proc/${dollar}DPID/fd/1 2>/dev/null)
    if [ -n "${dollar}FD1" ] && [ -f "${dollar}FD1" ]; then
      T=${dollar}(grep -ho 'token=[A-Za-z0-9_-]*' "${dollar}FD1" 2>/dev/null | head -1)
      [ -n "${dollar}T" ] && echo "AICHAT_DSH_TOKEN_FROM_FD=${dollar}T"
    fi
  fi
  echo DIAG_LISTEN
  netstat -tln 2>/dev/null | grep -E ':3080|:8000' || echo "(no listen)"
  echo DIAG_HTTP_ROOT
  wget -qO- -T 3 http://127.0.0.1:3080/ </dev/null 2>&1 | head -3 || true
  echo DIAG_LOGDIR_LIST
  ls -la /root/.dsh/logs 2>/dev/null | head -6
  echo "AICHAT_FWD_PS=${dollar}(pgrep -f 'dsh-forward.js' 2>/dev/null | wc -l)"
  echo "AICHAT_DSH_HINT=首次启动要加载全部插件，慢机型可能要 5-15 分钟，请耐心等待（最多 30 分钟）；"
  echo "AICHAT_DSH_HINT2=成功后 NODE_COMPILE_CACHE 会缓存下来，以后再启动会明显变快"
  # 慢机型（单核 cortex-a53 + 单线程 TCG）加载 540 个包可能要 5-15 分钟，
  # 所以这里"只要进程还活着就一直等"，最多 30 分钟；进程死了才提前判失败
  i=0
  while [ ${dollar}i -lt 900 ]; do
    sleep 2
    if grep -q 'token=' /tmp/dsh-web.log 2>/dev/null || grep -q 'token=' /root/.dsh/logs/*.log 2>/dev/null \
       || { [ -n "${dollar}FD1" ] && [ -f "${dollar}FD1" ] && grep -q 'token=' "${dollar}FD1" 2>/dev/null; }; then
      echo AICHAT_DSH_OK
      tail -2 /tmp/dsh-web.log 2>/dev/null
      break
    fi
    i=${dollar}((i+1))
    if [ ${dollar}((i % 15)) -eq 0 ]; then
      WPS=${dollar}(pgrep -f 'dsh --profile web' 2>/dev/null | wc -l)
      WSZ=${dollar}(wc -c < /tmp/dsh-web.log 2>/dev/null || echo 0)
      # CPU 时间（时钟滴答）与进程状态：数字在涨 = 在干活（只是慢）；一直不动 = 可能真卡住
      WPID=${dollar}(pgrep -f 'dsh --profile web' 2>/dev/null | head -1)
      WCPU=${dollar}(awk '{print ${dollar}14+${dollar}15}' /proc/${dollar}WPID/stat 2>/dev/null)
      WST=${dollar}(awk '{print ${dollar}3}' /proc/${dollar}WPID/stat 2>/dev/null)
      WMEM=${dollar}(awk '/VmRSS/{print ${dollar}2}' /proc/${dollar}WPID/status 2>/dev/null)
      echo "AICHAT_DSH_WAIT=${dollar}((i*2))s PS=${dollar}WPS LOG=${dollar}WSZ CPU=${dollar}WCPU ST=${dollar}WST RSS=${dollar}WMEM"
      tail -1 /tmp/dsh-web.log 2>/dev/null
      if [ "${dollar}WPS" = "0" ]; then
        echo AICHAT_DSH_DEAD
        break
      fi
    fi
  done
  if [ ${dollar}i -ge 900 ]; then
    echo AICHAT_DSH_FAIL
    echo "DIAG_DSH_PS=${dollar}(pgrep -f 'dsh --profile web' 2>/dev/null | wc -l)"
    echo "DIAG_FWD_PS=${dollar}(pgrep -f 'dsh-forward.js' 2>/dev/null | wc -l)"
    echo "DIAG_DSH_LOG_BYTES=${dollar}(wc -c < /tmp/dsh-web.log 2>/dev/null || echo 0)"
    echo "DIAG_FWD_LOG_BYTES=${dollar}(wc -c < /tmp/dsh-forward.log 2>/dev/null || echo 0)"
    echo "DIAG_SEED_KEYLEN=${dollar}{#AICHAT_API_KEY}"
    echo "DIAG_SEED_BASE=$seedBase"
    echo "DIAG_SEED_MODEL=$seedModel"
    echo "DIAG_DSH_BIN=${dollar}(ls -l /opt/dsh/bin/dsh 2>&1 | head -1)"
    echo "DIAG_NODE=${dollar}(node -v 2>&1)"
    echo "DIAG_TMP_WRITE=${dollar}(touch /tmp/_w 2>&1 && echo ok && rm -f /tmp/_w)"
    echo "DIAG_PS_LIST"
    ps -o pid,args 2>/dev/null | grep -E 'dsh|node' | grep -v grep | head -8
    echo DIAG_DSH_LOG_TAIL
    tail -30 /tmp/dsh-web.log 2>/dev/null || true
    echo DIAG_FWD_LOG_TAIL
    tail -10 /tmp/dsh-forward.log 2>/dev/null || true
    echo DIAG_DSH_LOGDIR
    ls -lt /root/.dsh/logs 2>/dev/null | head -5
    echo DIAG_DSH_STARTUP_TAIL
    SLA2=${dollar}(ls -t /root/.dsh/logs/*.log 2>/dev/null | head -1)
    [ -n "${dollar}SLA2" ] && tail -20 "${dollar}SLA2" 2>/dev/null
    true
    echo DIAG_TOKEN_SEARCH
    grep -ho 'token=[A-Za-z0-9_-]\{20,\}' /tmp/dsh-web.log /root/.dsh/logs/*.log 2>/dev/null | tail -3
  else
    # 把 dsh 打印的监听 URL（guest IP:8000）改写成 App 侧可访问的 hostfwd 地址
    # 注意：这里绝不能用 | head -1  head 提前退出会给 grep 发 SIGPIPE，
    # 把长行截断（实测会出现 token 为空的 URL，WebView 就提示 authentication required）。
    # 用 tail -1（会读完输入，不会提前退出），并要求 token 至少 20 个字符。
    URL=${dollar}(grep -ho 'http://[^ ]*token=[A-Za-z0-9_-]\{20,\}' /tmp/dsh-web.log /root/.dsh/logs/*.log 2>/dev/null | tail -1)
    if [ -n "${dollar}URL" ]; then
      FIXED=${dollar}(echo "${dollar}URL" | sed 's#^http://[^/]*#http://127.0.0.1:18000#')
      echo "AICHAT_DSH_URL=${dollar}FIXED"
      echo "AICHAT_DSH_URL=${dollar}FIXED"
    fi
  fi
else
  echo AICHAT_DSH_FAIL
  tail -10 /tmp/aichat-dsh-untar.log 2>/dev/null || true
fi
echo AICHAT_SETUP_DONE
"""
    }

    private fun diskInstallScript(): String {
        val dollar = "$"
        return """#!/bin/sh
echo AICHAT_DISK_INSTALL_BEGIN
if [ ! -b /dev/vda ]; then
  echo AICHAT_DISK_NO_DISK
  exit 1
fi
# setup-disk 在 aarch64 可能把 u-boot 作为 world 依赖；先移除，避免没有该包时直接失败
sed -i '/^u-boot${dollar}/d' /etc/apk/world 2>/dev/null || true
# 确保 main/community 仓库可用，setup-disk 需要安装 kernel/bootloader 依赖
for repo in main community; do
  url="https://dl-cdn.alpinelinux.org/alpine/v3.24/${dollar}repo"
  grep -q "${dollar}url" /etc/apk/repositories 2>/dev/null || echo "${dollar}url" >> /etc/apk/repositories
done
apk update >/tmp/aichat-apk-update.log 2>&1 || true
# init=/bin/sh 快速模式下 mdev 守护没跑，分区后 /dev/vdaX 不会自动出现；先起 mdev
if command -v mdev >/dev/null 2>&1; then
  (mdev -d >/dev/null 2>&1 &)
  sleep 1
fi
# aarch64 上 setup-disk 默认要 u-boot（仓库里没有）；-B none 跳过 bootloader，\n# 反正 App 是用 -kernel/-initrd 直接引导，不需要 bootloader\nif yes | setup-disk -m sys -k virt -B none /dev/vda >/tmp/aichat-disk.log 2>&1; then
  # setup-disk 装的是 CDN 最新内核，和 App 用的 assets 内核（uname -r）不是同一个版本，
  # 磁盘系统里 /lib/modules/<当前内核> 为空，AF_PACKET/virtio_net 等都加载不了  磁盘模式没网络。
  # 把 Live 里匹配当前内核的模块树拷进目标根。
  mkdir -p /mnt2 && mount /dev/vda3 /mnt2 && cp -a /lib/modules/* /mnt2/lib/modules/ 2>/dev/null; sync; umount /mnt2 2>/dev/null
  echo AICHAT_DISK_MODULES_COPIED
  echo AICHAT_DISK_EXIT_0
else
  echo AICHAT_DISK_EXIT_FAIL
fi
tail -60 /tmp/aichat-disk.log
echo AICHAT_DISK_DONE
"""
    }

    private fun harnessScript(): String {
        return """#!/usr/bin/env python3
# Ai Chat guest harness: OpenAI-compatible; forwards model calls to host /model/chat.
import json
import socketserver
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = 'http://10.0.2.2:$PORT'
TOKEN = '$tokenValue'

class FastHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def server_bind(self):
        # HTTPServer.server_bind performs getfqdn reverse DNS, which can hang in guest network
        socketserver.TCPServer.server_bind(self)
        self.server_name = '0.0.0.0'
        self.server_port = 8000

class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, content_type='application/json'):
        data = body if isinstance(body, bytes) else json.dumps(body).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith('/health'):
            self._send(200, {'ok': True})
        elif self.path.startswith('/v1/models'):
            self._send(200, {'object': 'list', 'data': [{'id': 'ds-harness', 'object': 'model'}]})
        else:
            self._send(404, {'error': 'not found'})

    def do_POST(self):
        if not self.path.startswith('/v1/chat/completions'):
            self._send(404, {'error': 'not found'})
            return
        try:
            n = int(self.headers.get('Content-Length', '0'))
            body = self.rfile.read(n)
        except Exception:
            body = b'{}'
        try:
            req = urllib.request.Request(
                BASE + '/model/chat?token=' + TOKEN,
                data=body,
                headers={'Content-Type': 'application/json'},
                method='POST')
            with urllib.request.urlopen(req, timeout=300) as resp:
                data = resp.read()
                code = resp.status
        except urllib.error.HTTPError as e:
            data = e.read()
            code = e.code
        except Exception as e:
            data = json.dumps({'error': str(e)}).encode('utf-8')
            code = 502
        self._send(code, data)

    def log_message(self, *args):
        pass

if __name__ == '__main__':
    FastHTTPServer(('0.0.0.0', 8000), Handler).serve_forever()
"""
    }

    private fun guestScript(): String {
        val dollar = "$"
        return """#!/bin/sh
# QEMU guest phone bridge client, generated by Ai Chat.
TOKEN='$tokenValue'
BASE='http://10.0.2.2:$PORT'
if [ ${dollar}# -eq 0 ]; then
  echo '用法: phone available|dump|find 文本|tap x y|swipe x1 y1 x2 y2 [ms]|text 内容|back|home|screenshot [文件]'
  exit 2
fi
case "${dollar}1" in
  screenshot)
    OUT="${dollar}{2:-/tmp/phone-screen.jpg}"
    wget -qO "${dollar}OUT" "${dollar}BASE/phone/screenshot?token=${dollar}TOKEN" || {
      echo 'ERR: 截图失败，需要 Android 11+ 且无障碍服务已连接'
      exit 3
    }
    echo "saved ${dollar}OUT"
    exit 0
    ;;
esac
BODY="$(printf '%s\n' "${dollar}@")"
OUT="$(wget -qO- --post-data="${dollar}BODY" "${dollar}BASE/phone/exec?token=${dollar}TOKEN" 2>/dev/null)" || {
  echo 'ERR: 无法连接宿主，请先执行: ip link set eth0 up; udhcpc -i eth0'
  exit 3
}
echo "${dollar}OUT"
"""
    }

    private fun writeResponse(socket: Socket, code: Int, body: String, contentType: String = "text/plain; charset=utf-8") {
        writeBytesResponse(socket, code, body.toByteArray(Charsets.UTF_8), contentType)
    }

    /** 大文件流式响应（带 Content-Length），给离线包用，避免整包读进内存。 */
    private fun writeFileResponse(socket: Socket, file: File, contentType: String) {
        if (socket.isClosed) return
        val len = file.length()
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $len\r\n" +
            "Connection: close\r\n\r\n"
        file.inputStream().use { input ->
            socket.getOutputStream().use { out ->
                out.write(header.toByteArray(Charsets.UTF_8))
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        }
    }

    private fun writeBytesResponse(socket: Socket, code: Int, body: ByteArray, contentType: String) {
        if (socket.isClosed) return
        val reason = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            400 -> "Bad Request"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().use { out ->
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
        }
    }
}
