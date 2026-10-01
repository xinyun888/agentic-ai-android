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

    /** guest 自动配置脚本：等网络、加仓库、装 python3、装 phone 桥和内置 harness。 */
    private fun guestSetupScript(): String {
        val dollar = "$"
        return """#!/bin/sh
echo AICHAT_SETUP_BEGIN
mkdir -p /usr/local/bin
ip link set lo up 2>/dev/null || true
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
  ip link set eth0 up 2>/dev/null || true
  udhcpc -i eth0 -n -q 2>/dev/null || true
  wget -qO- "http://10.0.2.2:$PORT/phone/ping?token=$tokenValue" >/dev/null 2>&1 && break
  sleep 2
done
for repo in main community; do
  url="https://dl-cdn.alpinelinux.org/alpine/v3.24/${dollar}repo"
  grep -q "${dollar}url" /etc/apk/repositories 2>/dev/null || echo "${dollar}url" >> /etc/apk/repositories
done
apk update >/tmp/aichat-apk-update.log 2>&1 || true
wget -qO /usr/local/bin/phone 'http://10.0.2.2:$PORT/phone.sh?token=$tokenValue' && chmod +x /usr/local/bin/phone && phone available || echo phone-bridge-failed
wget -qO /usr/local/bin/ds-harness.py 'http://10.0.2.2:$PORT/harness.py?token=$tokenValue' && chmod +x /usr/local/bin/ds-harness.py || echo harness-download-failed
if ! command -v python3 >/dev/null 2>&1; then
  apk add --no-cache python3 >/tmp/ds-harness-install.log 2>&1 || true
fi
if command -v python3 >/dev/null 2>&1; then
  (nohup python3 /usr/local/bin/ds-harness.py >/tmp/ds-harness.log 2>&1 &)
  i=0
  while [ ${dollar}i -lt 30 ]; do
    sleep 1
    if wget -qO- http://127.0.0.1:8000/health >/dev/null 2>&1; then
      echo AICHAT_HARNESS_OK
      break
    fi
    i=${dollar}((i+1))
  done
  if [ ${dollar}i -ge 30 ]; then
    echo AICHAT_HARNESS_FAIL
    tail -20 /tmp/ds-harness.log 2>/dev/null || true
  fi
else
  echo AICHAT_HARNESS_NO_PYTHON
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
if yes | setup-disk -m sys -k virt /dev/vda >/tmp/aichat-disk.log 2>&1; then
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
