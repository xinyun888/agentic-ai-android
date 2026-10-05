package com.example.aichat.vm

/**
 * Guest 里 DeepSeek Harness（dsh web）的运行状态。
 * QemuManager 从串口日志里捕获 AICHAT_DSH_URL= / AICHAT_DSH_OK，UI（DS Harness 页）据此直接内嵌 WebView。
 */
object DshState {
    @Volatile
    var webUrl: String? = null

    @Volatile
    var ready: Boolean = false

    /** 每次 VM/DSH 会话重启自增；用于让保留的 WebView 真正重新加载一次。 */
    @Volatile
    var generation: Int = 0

    fun reset() {
        webUrl = null
        ready = false
        generation++
    }
}
