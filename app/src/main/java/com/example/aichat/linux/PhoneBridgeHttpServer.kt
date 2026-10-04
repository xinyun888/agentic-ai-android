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
            when {
                requestToken != tokenValue || tokenValue.isBlank() -> {
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
        val seedKey = profile?.apiKey.orEmpty().replace("'", "'\\''")
        val seedBase = profile?.baseUrl.orEmpty().trimEnd('/')
        val seedModel = profile?.model.orEmpty()
        // 用 printf 写配置（不用 heredoc：万一终止符不匹配会把后面的启动代码一起吞掉）
        val seedBlock = if (seedKey.isNotBlank() && seedBase.isNotBlank() && seedModel.isNotBlank()) {
            "mkdir -p /root/.dsh/profiles/web; " +
                "printf -- '- id: llm-pi-ai\\n  config:\\n    providers:\\n      aichat:\\n" +
                "        apiKeyEnv: AICHAT_API_KEY\\n        api: openai-completions\\n" +
                "        baseURL: %s\\n        models:\\n          - id: %s\\n' " +
                "'$seedBase' '$seedModel' > /root/.dsh/profiles/web/cordis.patch.yml; " +
                "echo AICHAT_DSH_CONFIG_WRITTEN\n"
        } else "echo AICHAT_DSH_CONFIG_SKIP\n"
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
if [ -x /opt/dsh/bin/dsh ]; then
  ln -sf /opt/dsh/bin/dsh /usr/local/bin/dsh 2>/dev/null || true
  echo "dsh ${dollar}(/opt/dsh/bin/dsh --version 2>/dev/null)"
  export DSH_HOME=/root/.dsh
  cd /root
  # DSH 出于安全只允许监听 127.0.0.1；用 node 写个 TCP 转发，把 0.0.0.0:8000 转到 127.0.0.1:3080
  if [ ! -f /usr/local/bin/dsh-forward.js ]; then
    (nohup true) 2>/dev/null
    printf '%s\n' "const net = require('net');" "net.createServer((c) => {" "  const s = net.connect(3080, '127.0.0.1');" "  c.pipe(s); s.pipe(c);" "  c.on('error', () => s.destroy());" "  s.on('error', () => c.destroy());" "}).listen(8000, '0.0.0.0');" > /usr/local/bin/dsh-forward.js
  fi
  $seedBlock  # 脚本可能被重跑（App 超时重试/手动再执行）：已经起过就直接复用，避免第二个实例抢 3080
  # 失败并把 /tmp/dsh-web.log 覆盖掉（那样 token 就丢了，界面会误报 AICHAT_DSH_FAIL）
  if grep -q 'token=' /tmp/dsh-web.log 2>/dev/null; then
    echo AICHAT_DSH_REUSE
  else
    (nohup env AICHAT_API_KEY='$seedKey' /opt/dsh/bin/dsh --profile web --no-open --port 3080 --host 127.0.0.1 \
        --trusted-host 127.0.0.1:18000 >>/tmp/dsh-web.log 2>&1 &)
    sleep 2
  fi
  if [ -f /tmp/dsh-forward.pid ] && kill -0 ${dollar}(cat /tmp/dsh-forward.pid) 2>/dev/null; then
    echo AICHAT_FWD_REUSE
  else
    (nohup node /usr/local/bin/dsh-forward.js >>/tmp/dsh-forward.log 2>&1 & echo ${dollar}! > /tmp/dsh-forward.pid)
    sleep 1
  fi
  i=0
  while [ ${dollar}i -lt 90 ]; do
    sleep 2
    if grep -q 'token=' /tmp/dsh-web.log 2>/dev/null; then
      echo AICHAT_DSH_OK
      tail -2 /tmp/dsh-web.log 2>/dev/null
      break
    fi
    i=${dollar}((i+1))
    if [ ${dollar}((i % 15)) -eq 0 ]; then
      echo "AICHAT_DSH_WAIT=${dollar}((i*2))s"
      tail -1 /tmp/dsh-web.log 2>/dev/null
    fi
  done
  if [ ${dollar}i -ge 90 ]; then
    echo AICHAT_DSH_FAIL
    tail -30 /tmp/dsh-web.log 2>/dev/null || true
  else
    # 把 dsh 打印的监听 URL（guest IP:8000）改写成 App 侧可访问的 hostfwd 地址
    URL=${dollar}(grep -o 'http://[^ ]*token=[^ ]*' /tmp/dsh-web.log 2>/dev/null | head -1)
    if [ -n "${dollar}URL" ]; then
      echo "AICHAT_DSH_URL=${dollar}(echo ${dollar}URL | sed 's#^http://[^/]*#http://127.0.0.1:18000#')"
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
