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
import java.util.concurrent.TimeUnit

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
        /**
         * 串口写入分块大小。
         *
         * QEMU 的 PL011 只有 16 字节 RX FIFO：宿主一次灌太多、guest 来不及取就会静默丢字符。
         * guest-setup 命令动辄 500+ 字符，之前用 48 字节/25ms 发送（约 1920 B/s）时经常被写坏，
         * 于是 harness 永远装不上。现在改成小分块 + 等 guest 回显确认后再发下一块。
         */
        private const val WRITE_CHUNK = 12

        /** 等待 guest 回显上一块的超时；超时（如密码输入不回显）则退化为慢速发送。 */
        private const val ECHO_TIMEOUT_MS = 300L
    }

    private var process: Process? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    /** 不节流的原始串口输出，供写入时判断 guest 是否已经回显。 */
    @Volatile
    private var rawOutput: String = ""

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

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
        rawOutput = ""

        readerJob = scope.launch {
            val sb = StringBuilder()
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
                        // 必须每读到一块就发布一次：
                        // 之前用 150ms 节流，若最后一批输出落在节流窗口内、之后 guest 不再输出，
                        // 这批数据就永远停在 sb 里发不出去（read() 会一直阻塞），
                        // 表现为终端停在半行、提示符检测不到、harness 永远装不上。
                        // StateFlow 会自行合并，Compose 每帧最多重绘一次，不必手动节流。
                        rawOutput = sb.toString()
                        _output.value = sb.toString()
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    rawOutput = sb.toString()
                    _output.value = sb.toString()
                } catch (_: Exception) {}
                _running.value = false
                _exitCode.value = try { p.exitValue() } catch (_: Exception) { null }
            }
        }
    }

    /** 给终端追加一条 App 诊断信息，不经过 QEMU 串口。 */
    fun appendSynthetic(text: String) {
        try {
            _output.value = (_output.value + "\n" + text + "\n").takeLast(MAX_OUTPUT)
        } catch (_: Exception) {
        }
    }

    /**
     * 向 guest 串口写一行命令，末尾必须补 LF（0x0A）。
     *
     * 实测 guest 控制台是 icanon 规范模式：只有 LF 才会结束一行；只发 CR 时命令会一直
     * 留在行缓冲里不执行（Windows 版 QEMU 的 stdio 后端还会直接吞掉 CR）。之前用 "\r"
     * 发 root/guest-setup 命令就是因此一直不生效。
     *
     * 另外串口没有硬件流控：PL011 RX FIFO 仅 16 字节，一次性写入长命令时 guest 经常
     * 来不及取数据而丢字符。这里按小块发送，并等 guest 回显（说明已从 FIFO 取走）再发下一块。
     */
    fun write(text: String) {
        val p = process ?: return
        try {
            val line = text + "\n"
            var index = 0
            while (index < line.length) {
                val end = minOf(index + WRITE_CHUNK, line.length)
                val chunk = line.substring(index, end)
                p.outputStream.write(chunk.toByteArray(Charsets.UTF_8))
                p.outputStream.flush()
                index = end
                waitForEcho(chunk)
            }
        } catch (_: Exception) {
        }
    }

    /** 等 guest 回显 [chunk]，最多 [ECHO_TIMEOUT_MS]；回显通常来自 tty 的 ECHO。 */
    private fun waitForEcho(chunk: String) {
        val needle = chunk.trim()
        if (needle.isEmpty()) {
            try { Thread.sleep(20) } catch (_: InterruptedException) {}
            return
        }
        val deadline = System.currentTimeMillis() + ECHO_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // 行宽回显会在中间插入 CR/LF，比较前先去掉换行
            val tail = stripCrLf(rawOutput.takeLast(8000))
            if (tail.contains(needle)) return
            try { Thread.sleep(15) } catch (_: InterruptedException) { return }
        }
    }

    private fun stripCrLf(text: String): String {
        if (text.indexOf('\r') < 0 && text.indexOf('\n') < 0) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c != '\r' && c != '\n') sb.append(c)
        }
        return sb.toString()
    }

    fun stop() {
        process?.destroyForcibly()
    }

    fun shutdown() {
        val p = process
        try {
            p?.destroy()
            if (p != null && !p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            try { p?.destroyForcibly() } catch (_: Exception) {}
        }
        process = null
        scope.cancel()
    }
}