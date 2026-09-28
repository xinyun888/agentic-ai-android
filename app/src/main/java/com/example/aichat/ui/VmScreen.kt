package com.example.aichat.ui

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
import com.example.aichat.vm.QemuManager
import com.example.aichat.vm.QemuSession
import kotlinx.coroutines.Dispatchers
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
    var session by remember { mutableStateOf<QemuSession?>(null) }
    var running by remember { mutableStateOf(false) }
    var vmOutput by remember { mutableStateOf("") }

    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        launch { s.running.collect { running = it } }
        launch { s.output.collect { vmOutput = it } }
    }
    val scrollState = rememberScrollState()

    fun refreshStatus() {
        status = manager.statusText()
    }

    fun appendLog(text: String) {
        log = (log + text + "\n").takeLast(12_000)
    }

    DisposableEffect(Unit) {
        onDispose { manager.stopSession() }
    }

    LaunchedEffect(Unit) { refreshStatus() }
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
                        "第一步装 rootfs，然后离线装 QEMU，释放 ISO，创建磁盘，最后启动。QEMU 在 PRoot 里以 TCG 软件模拟运行，首次启动会比较慢。",
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
                ) { Text("3. 释放 ISO", maxLines = 1) }

                OutlinedButton(
                    enabled = !busy && manager.rootfsInstalled() && manager.qemuInstalled() && manager.imagesReady() && !manager.diskReady(),
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

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running && manager.abiSupported() && manager.qemuInstalled() && manager.imagesReady() && manager.diskReady(),
                    onClick = {
                        val s = manager.startSession()
                        if (s == null) {
                            appendLog("启动失败：环境不完整")
                        } else {
                            session = s
                            appendLog("QEMU 已启动，等待内核/initramfs 输出...")
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
                        session?.stop()
                        appendLog("已请求停止 QEMU")
                    }
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                val terminalText = buildString {
                    if (vmOutput.isNotBlank()) append(vmOutput)
                    if (vmOutput.isNotBlank() && log.isNotBlank()) append('\n')
                    if (log.isNotBlank()) append(log)
                }
                SelectionContainer {
                    Text(
                        text = terminalText.ifBlank { "串口输出和安装日志会显示在这里。" },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
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
}