package com.example.aichat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aichat.vm.DshState
import com.example.aichat.vm.QemuManager
import com.example.aichat.vm.QemuSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmScreen(manager: QemuManager, onBack: () -> Unit) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(manager.statusText()) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val existingSession = remember { manager.currentSession() }
    var session by remember { mutableStateOf<QemuSession?>(existingSession) }
    var running by remember { mutableStateOf(existingSession?.running?.value ?: false) }
    var vmOutput by remember { mutableStateOf(existingSession?.output?.value ?: "") }
    var dshExternal by remember { mutableStateOf(DshState.externalBrowser) }
    var dshBrowserReady by remember { mutableStateOf(!DshState.browserUrl.isNullOrBlank()) }
    var dshWarmup by remember { mutableStateOf(DshState.browserWarmup) }
    var crashReport by remember { mutableStateOf(DshState.crashReport) }
    var crashReportStale by remember { mutableStateOf(DshState.crashReportStale) }
    var seedKeyLen by remember { mutableStateOf(DshState.seedKeyLen) }

    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        launch { s.running.collect { running = it } }
        launch { s.output.collect { vmOutput = it } }
    }
    val scrollState = rememberScrollState()
    val pageScroll = rememberScrollState()
    var showTerminalDialog by remember { mutableStateOf(false) }
    val terminalText = buildString {
        if (vmOutput.isNotBlank()) append(vmOutput)
        if (vmOutput.isNotBlank() && log.isNotBlank()) append('\n')
        if (log.isNotBlank()) append(log)
    }.ifBlank { "串口输出和安装日志会显示在这里。" }

    fun refreshStatus() {
        status = manager.statusText()
    }

    fun appendLog(text: String) {
        log = (log + text + "\n").takeLast(12_000)
    }

    // 退出页面不再停止 QEMU；由 QemuKeepAliveService 保持运行，重新进入时自动接管当前会话。

    // 外部浏览器模式下 DSH/QEMU 要在后台长期运行；申请忽略电池优化，减少被 OEM 后台清理导致
    // 浏览器退出后服务器就没了/一直重新连接。只会在 VM 页首次进入时请求一次。
    LaunchedEffect(Unit) {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(appContext.packageName)) {
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + appContext.packageName)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) {
        refreshStatus()
        while (isActive) {
            val cur = manager.currentSession()
            if (cur != null && session !== cur) {
                session = cur
                running = cur.running.value
                vmOutput = cur.output.value
            } else if (cur == null && session != null && !running) {
                session = null
                vmOutput = ""
            }
            dshExternal = DshState.externalBrowser
            dshBrowserReady = !DshState.browserUrl.isNullOrBlank()
            dshWarmup = DshState.browserWarmup
            crashReport = DshState.crashReport
            crashReportStale = DshState.crashReportStale
            seedKeyLen = DshState.seedKeyLen
            delay(1500)
        }
    }
    LaunchedEffect(vmOutput) {
        if (vmOutput.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linux VM") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { refreshStatus() }, enabled = !busy) {
                        Text("刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(pageScroll)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("运行状态", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "第一步装 rootfs，然后离线装 QEMU，释放内核/ISO，创建磁盘，最后启动。QEMU 在 PRoot 里以 TCG 软件模拟运行，首次启动会比较慢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Guest 环境: " + when {
                            vmOutput.contains("AICHAT_DSH_OK") -> " DeepSeek Harness 已就绪"
                            vmOutput.contains("AICHAT_DSH_FAIL") -> " DSH 启动失败，请看下方串口日志"
                            vmOutput.contains("AICHAT_DSH_BEGIN") -> " 正在解压并启动 DeepSeek Harness ..."
                            vmOutput.contains("AICHAT_TOOLCHAIN_OK") -> " node / pnpm / git 已就绪（准备 DSH）"
                            vmOutput.contains("AICHAT_TOOLCHAIN_FAIL") -> " 工具链安装失败，请看下方串口日志"
                            vmOutput.contains("AICHAT_TOOLCHAIN_BEGIN") -> " 正在安装 node / pnpm / git ..."
                            else -> " 等待 guest 自动安装（首次通常 1-3 分钟）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("处理中...", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !busy && manager.abiSupported() && !manager.rootfsInstalled(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = manager.installRootfs { msg -> scope.launch { appendLog(msg) } }
                            busy = false
                            refreshStatus()
                            if (r.isFailure) appendLog("失败: ${r.exceptionOrNull()?.message}")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("1. 安装 rootfs", maxLines = 1) }

                OutlinedButton(
                    enabled = !busy && manager.rootfsInstalled() && !manager.qemuInstalled() && manager.qemuApkAssetsReady(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = manager.installQemuFromAssets { msg -> scope.launch { appendLog(msg) } }
                            busy = false
                            refreshStatus()
                            if (r.isFailure) appendLog("失败: ${r.exceptionOrNull()?.message}")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("2. 离线装 QEMU", maxLines = 1) }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !busy && manager.rootfsInstalled() && !manager.imagesReady() && manager.netbootAssetsReady(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = manager.installIsoFromAssets { msg -> scope.launch { appendLog(msg) } }
                            busy = false
                            refreshStatus()
                            if (r.isFailure) appendLog("失败: ${r.exceptionOrNull()?.message}")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("3. 释放内核/ISO", maxLines = 1) }

                OutlinedButton(
                    enabled = !busy && manager.rootfsInstalled() && manager.qemuInstalled() && manager.imagesReady() &&
                            (!manager.diskReady() || (manager.preinstallImageReady() && !manager.preinstalledDiskLive())),
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = manager.createDisk { msg -> scope.launch { appendLog(msg) } }
                            busy = false
                            refreshStatus()
                            if (r.isFailure) appendLog("失败: ${r.exceptionOrNull()?.message}")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("4. 创建磁盘", maxLines = 1) }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val freed = manager.cleanupTemp()
                            busy = false
                            appendLog(
                                "已清理临时文件：释放 ${freed / 1024 / 1024} MB；" +
                                    "当前可用 ${manager.freeSpaceMb()} MB"
                            )
                            refreshStatus()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("清理临时文件（释放空间）", maxLines = 1) }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running && manager.abiSupported() && manager.qemuInstalled() && manager.imagesReady() &&
                        (manager.diskReady() || manager.preinstallImageReady()),
                    onClick = {
                        scope.launch {
                            // 内置预装系统镜像：第一次点"启动 VM"时自动展开（含 node/pnpm/git/DSH）
                            if (manager.preinstallImageReady() && !manager.preinstalledDiskLive()) {
                                appendLog("正在展开内置预装系统（node/pnpm/git/DeepSeek Harness，首次 30-60 秒）...")
                                val r = manager.installPreinstalledDisk { msg -> scope.launch { appendLog(msg) } }
                                if (r.isFailure) {
                                    appendLog("展开预装系统失败：" + (r.exceptionOrNull()?.message ?: "未知错误"))
                                    refreshStatus()
                                    return@launch
                                }
                            }
                            val s = manager.startSession()
                            if (s == null) {
                                appendLog("启动失败：环境不完整")
                            } else {
                                log = ""
                                session = s
                                appendLog("QEMU 已启动，等待内核/initramfs 输出...")
                                appendLog("QEMU 参数: " + manager.buildQemuArgs().joinToString(" "))
                            }
                            refreshStatus()
                        }
                    }
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("启动 VM")
                }
                OutlinedButton(
                    enabled = running,
                    onClick = {
                        manager.stopSession()
                        session = null
                        running = false
                        appendLog("已停止 QEMU")
                        refreshStatus()
                    }
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
                OutlinedButton(
                    enabled = !running && manager.abiSupported() && manager.qemuInstalled() && manager.imagesReady() && manager.diskReady(),
                    onClick = {
                        val s = manager.startSafeSession()
                        if (s == null) {
                            appendLog("安全模式启动失败：环境不完整")
                        } else {
                            log = ""
                            session = s
                            appendLog("QEMU 安全模式已启动（512MB / 1 CPU / tcg single）...")
                        }
                        refreshStatus()
                    }
                ) { Text("安全模式", maxLines = 1) }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!running) {
                            appendLog("请先启动 VM；DSH 启动完成后会自动打开系统浏览器。")
                        } else if (!dshExternal) {
                            appendLog("正在重配 DSH 并切换到系统浏览器完成后会自动打开浏览器。")
                            manager.setExternalBrowser(true)
                        } else if (dshWarmup) {
                            appendLog("DSH 已启动，正在预热插件；预热结束后会自动打开系统浏览器，请稍等。")
                        } else if (!manager.openDshInBrowser()) {
                            appendLog("DSH 还没就绪；启动完成后会自动打开，也可以点「复制 DSH 地址」手动打开。")
                        }
                    }
                ) { Text(if (dshExternal) "打开 DSH 浏览器" else "切换到浏览器渲染", maxLines = 1) }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val url = DshState.browserUrl
                        if (url.isNullOrBlank()) {
                            appendLog("DSH 地址还没生成；先启动 VM，等 DSH 就绪后再复制。")
                        } else {
                            val target = if (DshState.browserTokenConsumed) url.substringBefore("?token=") else url
                            val clip = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("dsh_url", target))
                            appendLog("已复制 DSH 地址：" + target)
                        }
                    }
                ) { Text("复制 DSH 地址", maxLines = 1) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = running,
                onClick = {
                    manager.restartGuestHarness()
                    appendLog("已请求重配 DSH：使用当前 API 配置重启并重新注入模型 Key。")
                }
            ) { Text("重配 DSH（应用 API 配置）", maxLines = 1) }
            TextButton(
                onClick = {
                    val report = DshState.crashReport
                    if (report.isNullOrBlank()) {
                        appendLog("暂无 DSH 崩溃摘要；如果刚崩溃，请等 3 秒后再点。")
                    } else {
                        val clip = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("dsh_crash", report))
                        appendLog("已复制 DSH 崩溃摘要到剪贴板。")
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    when {
                        crashReport.isNullOrBlank() -> "复制崩溃日志"
                        crashReportStale -> "复制历史崩溃日志（DSH 已恢复）"
                        else -> "复制崩溃日志（已抓取）"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (dshExternal) {
                Text(
                    if (dshWarmup)
                        "外部浏览器模式：DSH 已就绪，正在预热插件（慢机需要几分钟），预热结束后会自动打开浏览器。"
                    else if (dshBrowserReady)
                        "外部浏览器模式：DSH 已就绪，可点「打开 DSH 浏览器」或复制地址到任意浏览器。"
                    else
                        "外部浏览器模式：等待 DSH 启动，完成后会自动打开系统浏览器。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            // DSH 本身没有登录/填 key 弹窗；它会静默使用 App「API 配置」里当前 profile 的 key。
            // key 为空时 UI 仍允许发消息，但请求会一直挂起，所以这里必须明确提示。
            if (seedKeyLen == 0) {
                Text(
                    "模型 Key：未配置。DSH 可以输入消息，但模型请求会一直等待/失败；请到 App「API 配置」填好 key 后点「重配」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (seedKeyLen > 0) {
                Text(
                    "模型 Key：已从 App「API 配置」注入（${seedKeyLen} 字符），DSH 不会再次询问。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !busy && running && !manager.isDiskBootEnabled(),
                    onClick = {
                        val s = manager.currentSession()
                        if (s == null) {
                            appendLog("VM 没有运行")
                        } else {
                            busy = true
                            scope.launch {
                                val r = manager.installToDisk(s) { msg -> scope.launch { appendLog(msg) } }
                                busy = false
                                refreshStatus()
                                if (r.isFailure) appendLog("安装到磁盘失败: ${r.exceptionOrNull()?.message}")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("安装到磁盘", maxLines = 1) }
                OutlinedButton(
                    enabled = !busy && !running,
                    onClick = {
                        val next = !manager.isDiskBootEnabled()
                        manager.setDiskBootEnabled(next)
                        refreshStatus()
                        appendLog(if (next) "已切换为磁盘启动，下次启动生效" else "已切换为 Live 启动，下次启动生效")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (manager.isDiskBootEnabled()) "当前:磁盘" else "当前:Live", maxLines = 1) }
            }

            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                enabled = !busy && !running,
                onClick = {
                    val next = !manager.isFastBoot()
                    manager.setFastBoot(next)
                    refreshStatus()
                    appendLog(if (next) "已切换为快速模式(init=/bin/sh)，下次启动生效" else "已切换为完整 OpenRC 模式，下次启动生效")
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (manager.isFastBoot()) "快速模式: init=/bin/sh（推荐）" else "完整模式: OpenRC", maxLines = 1) }

            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    val cmd = manager.phoneBridgeSetupCommand()
                    if (cmd.isBlank()) {
                        appendLog("手机桥还没启动：请先启动 VM")
                    } else {
                        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("phone bridge", cmd))
                        appendLog("已复制 QEMU guest 手机桥安装命令，粘贴到 VM 串口执行：\n$cmd")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("复制 QEMU guest 手机桥命令", maxLines = 1) }

            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("串口终端", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        Text(vmOutput.length.toString() + " 字符", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { showTerminalDialog = true }) { Text("放大") }
                    }
                    SelectionContainer {
                        Text(
                            text = terminalText,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("在 VM 串口里输入，例如 root / setup-alpine") },
                    singleLine = true,
                    enabled = running
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = running && input.isNotBlank(),
                    onClick = {
                        val text = input
                        input = ""
                        session?.write(text)
                    }
                ) { Text("发送") }
            }
        }
    }

    if (showTerminalDialog) {
        AlertDialog(
            onDismissRequest = { showTerminalDialog = false },
            title = { Text("串口终端") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = terminalText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTerminalDialog = false }) { Text("关闭") }
            }
        )
    }
}