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

    /** true = 不内嵌 WebView，把带 token 的 DSH 地址交给系统浏览器渲染。 */
    @Volatile
    var externalBrowser: Boolean = false

    /** 外部浏览器模式下，QemuManager 捕获到的最新 AICHAT_DSH_URL（带一次性 token）。 */
    @Volatile
    var browserUrl: String? = null

    /** 外部浏览器是否已经用掉当前 token（用掉后再打开应走不带 token 的干净地址）。 */
    @Volatile
    var browserTokenConsumed: Boolean = false

    /** 每次需要 App 主动拉起系统浏览器时自增；外部模式面板监听它避免重复打开。 */
    @Volatile
    var browserLaunchNonce: Int = 0

    /** DSH 已打印 URL，但仍在加载/预热插件；此时打开浏览器会一直重新连接中，应等它变 false。 */
    @Volatile
    var browserWarmup: Boolean = false

    /** supervisor 抓到的最近一次 DSH 崩溃摘要（退出码 + 日志尾 + dmesg），供 VM 页一键复制。 */
    @Volatile
    var crashReport: String? = null

    /** crashReport 的抓取时间；DSH 已恢复时可据此提示这是历史崩溃。 */
    @Volatile
    var crashReportAt: Long = 0L

    /** true 表示崩溃摘要抓取后 DSH 已经重新就绪，报告是历史的，不是当前仍在崩。 */
    @Volatile
    var crashReportStale: Boolean = false

    /** guest 串口里 AICHAT_SEED_KEYLEN 的值；<0 表示还没读到，0 表示没配置 API key。 */
    @Volatile
    var seedKeyLen: Int = -1

    /** 每次 VM/DSH 会话重启自增；用于让保留的 WebView 真正重新加载一次。 */
    @Volatile
    var generation: Int = 0

    fun reset() {
        webUrl = null
        ready = false
        browserUrl = null
        browserTokenConsumed = false
        browserLaunchNonce++
        browserWarmup = false
        crashReport = null
        crashReportAt = 0L
        crashReportStale = false
        seedKeyLen = -1
        generation++
    }
}
