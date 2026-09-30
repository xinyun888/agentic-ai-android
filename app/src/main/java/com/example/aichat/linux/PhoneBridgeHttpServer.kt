package com.example.aichat.linux

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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

    @Volatile
    private var tokenValue: String = ""

    @Volatile
    var isRunning: Boolean = false
        private set

    val token: String get() = tokenValue

    fun start(context: Context): Boolean {
        if (isRunning) return true
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
        return "mkdir -p /usr/local/bin && (ip link set eth0 up 2>/dev/null; udhcpc -i eth0 -n -q 2>/dev/null) && " +
            "wget -qO /usr/local/bin/phone 'http://10.0.2.2:$PORT/phone.sh?token=$tokenValue' && " +
            "chmod +x /usr/local/bin/phone && phone available"
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
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bytes, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(bytes, 0, read, Charsets.UTF_8)
            } else ""

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
                method == "POST" && path == "/phone/exec" -> {
                    val result = PhoneBridgeManager.executeRemote(body)
                    writeResponse(socket, 200, result)
                }
                else -> writeResponse(socket, 404, "not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun guestScript(): String {
        val dollar = "$"
        return """#!/bin/sh
# QEMU guest phone bridge client, generated by Ai Chat.
TOKEN='$tokenValue'
BASE='http://10.0.2.2:$PORT'
if [ ${dollar}# -eq 0 ]; then
  echo '用法: phone available|dump|find 文本|tap x y|swipe x1 y1 x2 y2 [ms]|text 内容|back|home'
  exit 2
fi
BODY="$(printf '%s\n' "${dollar}@")"
OUT="$(wget -qO- --post-data="${dollar}BODY" "${dollar}BASE/phone/exec?token=${dollar}TOKEN" 2>/dev/null)" || {
  echo 'ERR: 无法连接宿主，请先执行: ip link set eth0 up; udhcpc -i eth0'
  exit 3
}
echo "${dollar}OUT"
"""
    }

    private fun writeResponse(socket: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().use { out ->
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }
}
