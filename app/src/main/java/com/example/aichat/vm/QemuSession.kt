package com.example.aichat.vm

import com.example.aichat.linux.LinuxRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.StringBuilder

/**
 * QEMU 交互式串口会话。-nographic 的输入输出直接接在 PRoot / QEMU 进程上。
 */
class QemuSession(
    private val linux: LinuxRuntimeManager,
    private val qemuArgs: List<String>
) {
    companion object {
        private const val MAX_OUTPUT = 400_000
        private const val TRIM_TO = 200_000
    }

    private var process: Process? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        if (_running.value) return
        val payload = listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8"
        ) + qemuArgs
        val p = linux.startProcess(payload)
        process = p
        _running.value = true
        _output.value = ""

        readerJob = scope.launch {
            val sb = StringBuilder()
            var lastEmit = 0L
            try {
                p.inputStream.use { input ->
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                        if (sb.length > MAX_OUTPUT) {
                            sb.delete(0, sb.length - TRIM_TO)
                        }
                        // 节流：QEMU 串口输出量大，避免每 4KB 就触发一次 Compose 重绘
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 150L) {
                            lastEmit = now
                            _output.value = sb.toString()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { _output.value = sb.toString() } catch (_: Exception) {}
                _running.value = false
            }
        }
    }

    fun write(text: String) {
        val p = process ?: return
        try {
            p.outputStream.write((text + "\r").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        process?.destroyForcibly()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}