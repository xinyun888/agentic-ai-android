package com.example.aichat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.aichat.linux.LinuxRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxScreen(
    manager: LinuxRuntimeManager,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(manager.statusText()) }
    val scrollState = rememberScrollState()

    fun refreshStatus() {
        status = manager.statusText()
    }

    LaunchedEffect(Unit) { refreshStatus() }
    LaunchedEffect(output) {
        if (output.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linux 环境") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
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
                    Text("运行时状态", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    manager.compatibilityWarning()?.let { warning ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (manager.prootFile() == null) {
                        Text(
                            "缺少 libproot_exec.so：请先在项目根目录运行 fetch-linux-runtime.ps1，然后重新构建 APK。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (!manager.rootfsInstalled()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "尚未安装 Alpine rootfs。安装后即可使用 apk / sh / phone 等命令。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                output = "开始安装 Alpine rootfs...\n"
                                scope.launch {
                                    val result = manager.installRootfs { msg ->
                                        scope.launch { output += "$msg\n" }
                                    }
                                    busy = false
                                    refreshStatus()
                                    if (result.isSuccess) {
                                        output += "安装完成。试试输入: uname -a\n"
                                    } else {
                                        output += "安装失败: ${result.exceptionOrNull()?.message}\n"
                                    }
                                }
                            }
                        ) {
                            Text(if (manager.rootfsInstalled()) "检查/修复 rootfs" else "安装 Alpine rootfs")
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val r = manager.exec("uname -a; cat /etc/os-release 2>/dev/null | head -n 2")
                                    output += r.output + "\n"
                                    busy = false
                                    refreshStatus()
                                }
                            }
                        ) {
                            Text("测试")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium
            ) {
                SelectionContainer {
                    Text(
                        text = output.ifEmpty { "输出会显示在这里。\n\n示例：\n  uname -a\n  apk add python3\n  phone dump\n  phone find 设置\n  phone tap 100 200\n" },
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
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入 Linux 命令，如 uname -a") },
                    singleLine = false,
                    maxLines = 3,
                    enabled = !busy
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !busy && command.isNotBlank(),
                    onClick = {
                        val cmd = command.trim()
                        if (cmd.isEmpty()) return@Button
                        command = ""
                        output += "\n$ $cmd\n"
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                manager.exec(cmd)
                            }
                            output += r.output
                            if (r.timedOut) output += "\n[命令超时]"
                            else if (r.exitCode != 0) output += "\n[退出码 ${r.exitCode}]"
                            output += "\n"
                            busy = false
                            refreshStatus()
                        }
                    }
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("运行")
                    }
                }
            }
        }
    }
}