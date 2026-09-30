package com.example.aichat.linux

import android.content.Context
import com.example.aichat.service.ScreenControlService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Linux guest 与 Android 无障碍服务之间的文件桥。
 *
 * guest rootfs 里挂载 /phone 到宿主 files/phone_bridge。
 * `phone` 脚本往 request_<id>.txt 写命令，宿主执行无障碍操作后写 response_<id>.txt。
 *
 * 协议（每行一个参数）：
 *   dump
 *   find <text>
 *   tap <x> <y>
 *   swipe <x1> <y1> <x2> <y2> [durationMs]
 *   text <content...>
 *   back
 *   home
 *   available
 */
object PhoneBridgeManager {

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val dir = File(context.applicationContext.filesDir, LinuxRuntimeManager.BRIDGE_DIR_NAME).also { it.mkdirs() }
        cleanup(dir)
        job = scope.launch {
            while (running.get()) {
                try {
                    val requests = dir.listFiles { f -> f.isFile && f.name.startsWith("request_") }
                        ?.sortedBy { it.name }
                        ?: emptyList()
                    for (request in requests) {
                        handleRequest(dir, request)
                    }
                } catch (_: Exception) {
                    // 单次异常不能让 bridge 线程死掉
                }
                delay(120)
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
    }

    private fun cleanup(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("request_") || f.name.startsWith("response_")) {
                f.delete()
            }
        }
    }

    private fun handleRequest(dir: File, request: File) {
        val id = request.name.removePrefix("request_")
        val result = try {
            execute(request.readLines(Charsets.UTF_8))
        } catch (e: Exception) {
            BridgeResult(false, "bridge 异常: ${e.message}")
        }
        writeResponse(dir, id, result)
        request.delete()
    }

    /**
     * QEMU guest HTTP 桥入口：body 每行一个参数，返回 "OK\n结果" 或 "ERR\n原因"。
     * 与文件桥共用同一套 UIAutomator/无障碍执行逻辑。
     */
    fun executeRemote(commandText: String): String {
        val lines = commandText.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val result = try {
            execute(lines)
        } catch (e: Exception) {
            BridgeResult(false, "bridge 异常: ${e.message}")
        }
        return (if (result.ok) "OK\n" else "ERR\n") + result.data
    }

    private fun execute(lines: List<String>): BridgeResult {
        val cmd = lines.firstOrNull()?.trim()?.lowercase().orEmpty()
        if (cmd.isEmpty()) return BridgeResult(false, "空命令")
        val args = lines.drop(1)
        val service = ScreenControlService.instance
            ?: return BridgeResult(false, "无障碍服务未开启。请先在系统设置中启用本应用的无障碍服务。")

        return when (cmd) {
            "available" -> BridgeResult(true, "1")
            "dump" -> BridgeResult(true, service.getAccessibilityTree())
            "find" -> {
                val text = args.joinToString(" ").trim()
                if (text.isEmpty()) BridgeResult(false, "缺少查找文本")
                else {
                    val ok = service.findAndClickByText(text)
                    if (ok) BridgeResult(true, "clicked: $text")
                    else BridgeResult(false, "not found: $text")
                }
            }
            "tap" -> {
                val x = args.getOrNull(0)?.toFloatOrNull()
                val y = args.getOrNull(1)?.toFloatOrNull()
                if (x == null || y == null) BridgeResult(false, "用法: tap <x> <y>")
                else {
                    val ok = service.performClick(x, y)
                    BridgeResult(ok, if (ok) "tap $x $y" else "tap failed")
                }
            }
            "swipe" -> {
                val x1 = args.getOrNull(0)?.toFloatOrNull()
                val y1 = args.getOrNull(1)?.toFloatOrNull()
                val x2 = args.getOrNull(2)?.toFloatOrNull()
                val y2 = args.getOrNull(3)?.toFloatOrNull()
                val duration = args.getOrNull(4)?.toLongOrNull() ?: 300L
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    BridgeResult(false, "用法: swipe <x1> <y1> <x2> <y2> [durationMs]")
                } else {
                    val ok = service.performSwipe(x1, y1, x2, y2, duration)
                    BridgeResult(ok, if (ok) "swipe $x1 $y1 $x2 $y2" else "swipe failed")
                }
            }
            "text" -> {
                val text = args.joinToString(" ")
                val ok = service.performSetText(text)
                BridgeResult(ok, if (ok) "text ok" else "text failed")
            }
            "back" -> {
                val ok = service.performBack()
                BridgeResult(ok, if (ok) "back" else "back failed")
            }
            "home" -> {
                val ok = service.performHome()
                BridgeResult(ok, if (ok) "home" else "home failed")
            }
            else -> BridgeResult(false, "未知命令: $cmd")
        }
    }

    private fun writeResponse(dir: File, id: String, result: BridgeResult) {
        try {
            val text = (if (result.ok) "OK" else "ERR") + "\n" + result.data
            val tmp = File(dir, "response_$id.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            val target = File(dir, "response_$id")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private data class BridgeResult(val ok: Boolean, val data: String)
}