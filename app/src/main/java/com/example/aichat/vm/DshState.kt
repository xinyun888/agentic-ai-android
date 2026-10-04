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

    fun reset() {
        webUrl = null
        ready = false
    }
}
