package com.example.aichat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.aichat.R
import com.example.aichat.data.AgentStep
import com.example.aichat.data.ApiProfile
import com.example.aichat.data.ChatMessage
import com.example.aichat.data.Persona
import com.example.aichat.data.Personas
import com.example.aichat.data.TaskPlan
import com.example.aichat.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipInputStream

// 文本类扩展名：直接按 UTF-8 文本读取
val TEXT_EXTS = setOf("txt", "md", "csv", "json", "xml", "html", "htm", "css", "js", "ts",
    "py", "java", "kt", "cpp", "c", "h", "rs", "go", "rb", "php", "swift",
    "yaml", "yml", "toml", "ini", "cfg", "log", "sql", "sh", "bat", "ps1")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    profile: ApiProfile,
    conversationId: String,
    onBack: () -> Unit
) {
    // 系统返回键回对话列表，而不是直接退出应用
    androidx.activity.compose.BackHandler { onBack() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val workspaceRoot = remember { java.io.File(context.filesDir, "workspace").also { it.mkdirs() } }
    val workspaceDir = remember(workspaceRoot, conversationId) {
        if (conversationId.isBlank()) workspaceRoot
        else java.io.File(workspaceRoot, conversationId.replace(Regex("[^a-zA-Z0-9_-]"), "_")).also { it.mkdirs() }
    }
    var showWorkspaceFiles by remember { mutableStateOf(false) }
    var showPersonaEditor by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<Persona?>(null) }
    var showActiveModeDialog by remember { mutableStateOf(false) }
    var pmFrequency by remember { mutableStateOf(15) }
    var pmImmersive by remember { mutableStateOf(false) }
    var pmHideThink by remember { mutableStateOf(true) }
    val frequencyOptions = remember { listOf(5, 15, 30, 60, 120, 240) }
    val frequencyLabels = remember { listOf("5分钟", "15分钟", "30分钟", "1小时", "2小时", "4小时") }
    // 长按消息"删除此条及之后"：保存保留条数（截断点）
    var truncateKeep by remember { mutableStateOf<Int?>(null) }
    // 工作区文件删除确认
    var deleteFile by remember { mutableStateOf<java.io.File?>(null) }

    val startNow: () -> Unit = {
        val i = Intent(context, com.example.aichat.service.ActiveModeService::class.java).apply {
            action = com.example.aichat.service.ActiveModeService.ACTION_START
            putExtra(com.example.aichat.service.ActiveModeService.EXTRA_PERSONA_ID, viewModel.activePersonaId)
            putExtra(com.example.aichat.service.ActiveModeService.EXTRA_CONV_ID, conversationId)
            putExtra(com.example.aichat.service.ActiveModeService.EXTRA_INTERVAL_MIN, pmFrequency)
            putExtra(com.example.aichat.service.ActiveModeService.EXTRA_IMMERSIVE, pmImmersive)
            putExtra(com.example.aichat.service.ActiveModeService.EXTRA_SHOW_THINKING, !pmHideThink)
            putExtra(com.example.aichat.service.ActiveModeService.EXTRA_START_HOUR, 0)
            putExtra(com.example.aichat.service.ActiveModeService.EXTRA_END_HOUR, 24)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
        else context.startService(i)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startNow() }
    var workspaceFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    // 文件查看器浮层状态
    var viewerFile by remember { mutableStateOf<java.io.File?>(null) }
    var viewerContent by remember { mutableStateOf("") }

    // 图片选择器
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 图片落盘到应用私有目录，消息里只存文件路径（避免 base64 撑爆存储）
            val mime = context.contentResolver.getType(it) ?: "image/jpeg"
            val ext = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            val imgDir = java.io.File(context.filesDir, "images").also { d -> d.mkdirs() }
            val imgFile = java.io.File(imgDir, "img_${System.currentTimeMillis()}.$ext")
            val saved = try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    imgFile.outputStream().use { out -> input.copyTo(out) }
                }
                imgFile.absolutePath
            } catch (_: Exception) { null }
            viewModel.pendingImageUri = saved
        }
    }

    // 文件选择器 —— 保存到工作区，交给 Agent 用工具处理
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val name = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else "未知文件"
            } ?: "未知文件"
            val ext = name.substringAfterLast('.').lowercase()

            // 保存到当前会话的工作区子目录，和 Agent 看到的一致
            val wsDir = workspaceDir
            val destFile = java.io.File(wsDir, name)
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) { return@let }
            val relName = if (wsDir == workspaceRoot) name else "${wsDir.name}/$name"
            viewModel.pendingFileText = Pair(relName, ext)
            workspaceFiles = workspaceDir.listFiles()?.sortedBy { it.name }?.toList() ?: emptyList()

            // 若是文档格式，同时提取文本预览
            if (ext in TEXT_EXTS || ext in setOf("docx", "pptx", "xlsx")) {
                val text = try {
                    val bytes = destFile.readBytes()
                    when (ext) {
                        "docx" -> readDocxText(bytes)
                        "pptx" -> readPptxText(bytes)
                        "xlsx" -> readXlsxText(bytes)
                        in TEXT_EXTS -> bytes.toString(Charsets.UTF_8)
                        else -> ""
                    }
                } catch (_: Exception) { "" }
                viewModel.fileTextContent = text
            }
        }
    }

    // 分享启动器
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ -> }
    val shareFile: (String) -> Unit = { fileName ->
        val uri = viewModel.shareUri(fileName)
        val mime = when (fileName.substringAfterLast('.').lowercase()) {
            "html", "htm" -> "text/html"
            "txt", "md", "csv" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        shareLauncher.launch(Intent.createChooser(intent, "分享 $fileName"))
    }

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.scrollToItem(viewModel.messages.size - 1)
        }
    }

    LaunchedEffect(conversationId) {
        workspaceFiles = workspaceDir.listFiles()?.sortedBy { it.name }?.toList() ?: emptyList()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = viewModel.conversationTitle.ifEmpty { "对话" },
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    // 角色选择器
                    var showPersonaMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { showPersonaMenu = true }) {
                            Text(
                                "${viewModel.getActivePersona(context).emoji} ${viewModel.getActivePersona(context).name}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        DropdownMenu(
                            expanded = showPersonaMenu,
                            onDismissRequest = { showPersonaMenu = false }
                        ) {
                            Personas.getAll(context).forEach { p ->
                                DropdownMenuItem(
                                    text = { Text("${p.emoji} ${p.name}") },
                                    onClick = {
                                        viewModel.setActivePersona(p.id)
                                        showPersonaMenu = false
                                    },
                                    leadingIcon = {
                                        if (viewModel.activePersonaId == p.id)
                                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                )
                            }
                            // 自定义角色管理
                            Divider()
                            DropdownMenuItem(
                                text = { Text("+ 创建角色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) },
                                onClick = { editingPersona = null; showPersonaEditor = true; showPersonaMenu = false },
                                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                            )
                            // 自定义角色的删除按钮
                            val customPersonas = Personas.loadCustom(context)
                            customPersonas.forEach { cp ->
                                DropdownMenuItem(
                                    text = { Text("✏️ 编辑 ${cp.name}", style = MaterialTheme.typography.labelSmall) },
                                    onClick = { editingPersona = cp; showPersonaEditor = true; showPersonaMenu = false },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("🗑 删除 ${cp.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        Personas.deleteCustom(context, cp.id)
                                        if (viewModel.activePersonaId == cp.id) viewModel.setActivePersona("default")
                                        showPersonaMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    }
                    // 工作区文件
                    IconButton(
                        onClick = {
                            workspaceFiles = workspaceDir.listFiles()?.sortedBy { it.name }?.toList() ?: emptyList()
                            showWorkspaceFiles = !showWorkspaceFiles
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = "工作区",
                            tint = if (showWorkspaceFiles)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 主动模式开关
                    val isActiveRunning = com.example.aichat.service.ActiveModeService.isRunning(viewModel.activePersonaId)
                    IconButton(
                        onClick = { showActiveModeDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("❤️", fontSize = if (isActiveRunning) 18.sp else 14.sp,
                            modifier = Modifier.alpha(if (isActiveRunning) 1f else 0.4f))
                    }
                    // 手机控制设置
                    val accessibilityEnabled = com.example.aichat.service.ScreenControlService.instance != null
                    IconButton(
                        onClick = {
                            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = "手机控制",
                            tint = if (accessibilityEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 学习模式开关
                    IconButton(
                        onClick = { viewModel.toggleLearning() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Psychology,
                            contentDescription = "学习模式",
                            tint = if (viewModel.learningMode)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // 图片预览
                viewModel.pendingImageUri?.let { path ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(java.io.File(path)).build(),
                                contentDescription = "图片预览",
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "图片已附加",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.pendingImageUri = null },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // 文件预览
                viewModel.pendingFileText?.let { (name, _) ->
                    val fileSize = java.io.File(context.filesDir, "workspace/$name").let {
                        if (it.exists()) "${it.length() / 1024}KB" else ""
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "📎 $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (fileSize.isNotEmpty()) "已保存到 workspace · $fileSize"
                                    else "已保存到 workspace",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { viewModel.pendingFileText = null },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Surface(
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("输入消息...") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    // 带下拉菜单的 + 按钮
                    var showTools by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showTools = true }) {
                            Icon(
                                Icons.Filled.AddCircle,
                                contentDescription = "工具",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        DropdownMenu(
                            expanded = showTools,
                            onDismissRequest = { showTools = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("📷 图片") },
                                onClick = { showTools = false; imagePicker.launch("image/*") },
                                leadingIcon = { Icon(Icons.Outlined.Image, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("📎 文件") },
                                onClick = { showTools = false; filePicker.launch(arrayOf("*/*")) },
                                leadingIcon = { Icon(Icons.Filled.UploadFile, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🌐 联网搜索")
                                        Spacer(Modifier.weight(1f))
                                        if (viewModel.searchEnabled) Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = { viewModel.toggleSearch() },
                                leadingIcon = { Icon(Icons.Filled.TravelExplore, null) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🛡 查证模式")
                                        Spacer(Modifier.weight(1f))
                                        if (viewModel.factCheckEnabled) Text("✓", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                onClick = { viewModel.toggleFactCheck() },
                                leadingIcon = { Icon(Icons.Filled.VerifiedUser, null) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🧠 显示思考过程")
                                        Spacer(Modifier.weight(1f))
                                        if (viewModel.showThinking) Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = { viewModel.toggleShowThinking() },
                                leadingIcon = { Icon(Icons.Outlined.Psychology, null) }
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (viewModel.isLoading) {
                                viewModel.cancelLoading()
                            } else {
                                val text = inputText.trim()
                                inputText = ""
                                viewModel.sendMessage(text, profile)
                            }
                        },
                        enabled = if (viewModel.isLoading) true else (inputText.isNotBlank() || viewModel.pendingImageUri != null || viewModel.pendingFileText != null)
                    ) {
                        Icon(
                            if (viewModel.isLoading) Icons.Filled.Close else Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (viewModel.isLoading) "停止" else "发送",
                            tint = if (viewModel.isLoading) MaterialTheme.colorScheme.error
                                   else if ((inputText.isNotBlank() || viewModel.pendingImageUri != null || viewModel.pendingFileText != null))
                                       MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            Image(
                painter = coil.compose.rememberAsyncImagePainter(R.drawable.bg_chat),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.7f
            )
            Column {
            // 恢复任务横幅
            if (viewModel.resumePending) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "检测到未完成的任务",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearSavedState() }) {
                            Text("放弃", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(onClick = { viewModel.resumeFromBreakpoint(profile) }) {
                            Text("恢复", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // 错误横幅
            viewModel.errorMessage?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("关闭")
                        }
                    }
                }
            }

            // 计划卡片（审核模式或执行中）
            val plan = viewModel.currentPlan
            val showPlan = plan != null && (viewModel.planPhase == ChatViewModel.PlanPhase.REVIEWING || viewModel.planPhase == ChatViewModel.PlanPhase.EXECUTING)
            if (showPlan) {
                PlanCard(
                    plan = plan!!,
                    completedIds = viewModel.completedTaskIds,
                    isExecuting = viewModel.planPhase == ChatViewModel.PlanPhase.EXECUTING,
                    onConfirm = { optimized ->
                        viewModel.confirmPlan(optimized)
                        viewModel.executeCurrentPlan(profile)
                    },
                    onReject = { viewModel.rejectPlan() },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // 预览面板 —— 折叠条 + 可展开列表 + 三态预览
            val items = viewModel.previewItems
            if (items.isNotEmpty()) {
                val active = viewModel.activePreviewFile()
                val mode = viewModel.previewMode
                var showFileList by remember { mutableStateOf(false) }

                when (mode) {
                    ChatViewModel.PreviewMode.COLLAPSED -> {
                        Column {
                            // 折叠条
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("\uD83D\uDCC1 ${items.size} 个预览", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                    // 文件列表开关
                                    IconButton(
                                        onClick = { showFileList = !showFileList },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.List, "文件列表", Modifier.size(18.dp),
                                            tint = if (showFileList) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    // 打开预览
                                    IconButton(
                                        onClick = { viewModel.togglePreview() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.Fullscreen, "打开预览", Modifier.size(18.dp))
                                    }
                                }
                            }
                            // 可展开的文件列表
                            if (showFileList) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ) {
                                    LazyColumn {
                                        items(items.size) { i ->
                                            val item = items[i]
                                            val isActive = i == viewModel.activePreviewIndex
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                        else MaterialTheme.colorScheme.surface,
                                                onClick = { viewModel.selectPreview(i) }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        if (isActive) Icons.Filled.CheckCircle else Icons.Filled.Description,
                                                        null, Modifier.size(16.dp),
                                                        tint = if (isActive) MaterialTheme.colorScheme.primary
                                                               else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(item.title, style = MaterialTheme.typography.bodySmall)
                                                        Text(item.file, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    // 在预览中打开此项
                                                    IconButton(onClick = { viewModel.selectPreview(i); viewModel.togglePreview() }, modifier = Modifier.size(28.dp)) {
                                                        Icon(Icons.Filled.PlayArrow, "预览", Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ChatViewModel.PreviewMode.HALF -> {
                        val weight = 0.55f
                        Surface(
                            modifier = Modifier.fillMaxWidth().weight(weight),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 4.dp
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 可滚动的标签栏
                                    LazyRow(modifier = Modifier.weight(1f)) {
                                        items(items.size) { i ->
                                            val item = items[i]
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (i == viewModel.activePreviewIndex) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                                modifier = Modifier.padding(horizontal = 2.dp),
                                                onClick = { viewModel.selectPreview(i) }
                                            ) {
                                                Text(
                                                    item.title.take(16),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    // 分享
                                    val activeFile = viewModel.activePreviewFile()
                                    if (activeFile != null) {
                                        IconButton(onClick = { shareFile(activeFile) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Filled.Share, null, Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    // 全屏开关
                                    IconButton(onClick = { viewModel.togglePreview() }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            if (mode == ChatViewModel.PreviewMode.FULL) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                            null, Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { viewModel.collapsePreview() }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Filled.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (active != null) {
                                    HtmlPreview(
                                        filePath = java.io.File(LocalContext.current.filesDir, "workspace/$active").absolutePath,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                    ChatViewModel.PreviewMode.FULL -> { /* 由全屏浮层处理 */ }
                }
            }

            // 工作区文件列表
            if (showWorkspaceFiles && workspaceFiles.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    shadowElevation = 2.dp
                ) {
                    LazyColumn(modifier = Modifier.padding(4.dp)) {
                        items(workspaceFiles.size) { i ->
                            val f = workspaceFiles[i]
                            val ext = f.extension.lowercase()
                            val sizeStr = if (f.isFile) " · ${f.length() / 1024}KB" else " · 文件夹"
                            val isHtml = ext == "html" || ext == "htm"
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(6.dp),
                                onClick = {
                                    if (f.isFile) {
                                        when (ext) {
                                            "html", "htm" -> viewModel.addPreviewItem(
                                                f.relativeTo(workspaceRoot).path.replace('\\', '/')
                                            )
                                            else -> {
                                                // 在文本查看器中打开
                                                try {
                                                    viewerFile = f
                                                    viewerContent = readTextHead(f, 50000)
                                                } catch (_: Exception) {
                                                    viewerContent = "(无法读取此文件: ${f.name})"
                                                    viewerFile = f
                                                }
                                            }
                                        }
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(if (f.isFile) "📄" else "📁", modifier = Modifier.width(20.dp))
                                    Text(f.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Text(sizeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (f.isFile) {
                                        Spacer(Modifier.width(2.dp))
                                        Icon(
                                            if (isHtml) Icons.Filled.PlayArrow else Icons.Filled.Visibility,
                                            null, Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            deleteFile = f
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(viewModel.messages, key = { it.id ?: "${it.timestamp}-${it.role}-${it.content.hashCode()}" }) { message ->
                    // 在助手/实时消息之前显示思考过程
                    if (viewModel.showThinking && message.thinking.isNotBlank() && (message.role == "assistant" || message.role == "assistant_live")) {
                        ThinkingChainCard(content = message.thinking)
                    }
                    // 工具步骤卡：本回复实际执行的工具（系统注入标"系统"，模型调用标"调用"）
                    message.toolSteps?.forEach { step ->
                        when (step.type) {
                            "tool_call" -> AgentToolCard(
                                Icons.Filled.Build,
                                (if (step.auto) "系统 · " else "调用 · ") + step.toolName,
                                step.toolArgs.take(120),
                                MaterialTheme.colorScheme.tertiaryContainer
                            )
                            "tool_result" -> AgentToolCard(
                                Icons.Filled.CheckCircle,
                                step.toolName + " · 结果",
                                step.content.take(300) + if (step.content.length > 300) "…" else "",
                                MaterialTheme.colorScheme.secondaryContainer
                            )
                        }
                    }
                    // 最后一条助手消息：客户端打字机（最终回答已改非流式，观感由这里模拟）
                    val isLastAssistant = message.role == "assistant" &&
                        viewModel.messages.lastOrNull { it.role == "assistant" } === message
                    MessageBubble(
                        message = message,
                        typewriter = isLastAssistant,
                        onLongClick = {
                            // 引用相等定位（同内容消息不能靠 indexOf）
                            val idx = viewModel.messages.indexOfFirst { it === message }
                            if (idx >= 0) truncateKeep = idx
                        }
                    )
                }

                if (viewModel.isLoading) {
                    item {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "思考中...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // === 长按消息：删除此条及之后（重开被污染对话）===
            truncateKeep?.let { keep ->
                AlertDialog(
                    onDismissRequest = { truncateKeep = null },
                    title = { Text("删除此条及之后？") },
                    text = { Text("将删除这条消息及其后的所有消息（保留前 $keep 条）。用于重开被污染的历史对话。此操作不可撤销。") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.truncateFrom(conversationId, keep)
                            truncateKeep = null
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { truncateKeep = null }) { Text("取消") } }
                )
            }

            // === 工作区文件删除确认 ===
            deleteFile?.let { file ->
                AlertDialog(
                    onDismissRequest = { deleteFile = null },
                    title = { Text("删除文件？") },
                    text = { Text("确定删除「${file.name}」？此操作不可恢复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                            workspaceFiles = workspaceDir.listFiles()?.sortedBy { it.name }?.toList() ?: emptyList()
                            viewModel.forgetPreviews()
                            deleteFile = null
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("取消") } }
                )
            }

            // === 全屏预览浮层（覆盖整个 Box）===
            val fullItems = viewModel.previewItems
            val fullActive = viewModel.activePreviewFile()
            if (fullItems.isNotEmpty() && viewModel.previewMode == ChatViewModel.PreviewMode.FULL && fullActive != null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(viewModel.activePreviewTitle() ?: fullActive, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            IconButton(onClick = { shareFile(fullActive) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Share, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.togglePreview() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.FullscreenExit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.collapsePreview() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HtmlPreview(
                            filePath = java.io.File(LocalContext.current.filesDir, "workspace/$fullActive").absolutePath,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // === 文本文件查看器浮层 ===
            val vf = viewerFile
            if (vf != null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📄 ${vf.name}", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f).padding(start = 8.dp))
                            Text("${viewerContent.length}字符", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { viewerFile = null }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = viewerContent,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // 角色编辑器对话框 —— 同时支持创建和编辑
    if (showPersonaEditor) {
        val editing = editingPersona
        var pName by remember { mutableStateOf(editing?.name ?: "") }
        var pEmoji by remember { mutableStateOf(editing?.emoji ?: "🤖") }
        var pIdentity by remember { mutableStateOf(editing?.identity ?: "") }
        var pPersonality by remember { mutableStateOf(editing?.personality ?: "") }
        var pSpeaking by remember { mutableStateOf(editing?.speaking ?: "") }
        var pTaboos by remember { mutableStateOf(editing?.taboos ?: "") }
        var pDetailed by remember { mutableStateOf(editing?.detailed ?: "") }
        AlertDialog(
            onDismissRequest = { showPersonaEditor = false; editingPersona = null },
            title = { Text(if (editing != null) "编辑角色" else "创建自定义角色") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 500.dp)
                ) {
                    OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text("角色名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pEmoji, onValueChange = { pEmoji = it.take(2) }, label = { Text("图标") }, singleLine = true, modifier = Modifier.width(80.dp))
                    OutlinedTextField(value = pIdentity, onValueChange = { pIdentity = it }, label = { Text("一句话身份 *心跳核心") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pPersonality, onValueChange = { pPersonality = it }, label = { Text("性格 *心跳核心") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                    OutlinedTextField(value = pSpeaking, onValueChange = { pSpeaking = it }, label = { Text("说话方式 *心跳核心") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                    OutlinedTextField(value = pTaboos, onValueChange = { pTaboos = it }, label = { Text("禁忌 (选填)") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                    OutlinedTextField(value = pDetailed, onValueChange = { pDetailed = it }, label = { Text("详细设定 (选填，仅沉浸模式加载)") }, modifier = Modifier.fillMaxWidth().height(120.dp), maxLines = 8)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = editing?.id ?: "custom_${System.currentTimeMillis()}"
                    val fullPrompt = buildString {
                        if (pIdentity.isNotBlank()) append("身份：$pIdentity\n")
                        if (pPersonality.isNotBlank()) append("性格：$pPersonality\n")
                        if (pSpeaking.isNotBlank()) append("说话方式：$pSpeaking\n")
                        if (pTaboos.isNotBlank()) append("禁忌：$pTaboos\n")
                        if (pDetailed.isNotBlank()) append("详细设定：$pDetailed\n")
                        if (isEmpty()) append("你是一个自定义 AI 助手。")
                    }
                    Personas.saveCustom(context, Persona(
                        id, pName.ifBlank { "自定义" }, pEmoji.ifBlank { "🤖" },
                        fullPrompt + Personas.BASE,
                        pIdentity, pPersonality, pSpeaking, pTaboos, pDetailed
                    ))
                    viewModel.setActivePersona(id)
                    showPersonaEditor = false
                    editingPersona = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showPersonaEditor = false }) { Text("取消") } }
        )
    }

    // 主动模式配置对话框
    if (showActiveModeDialog) {
        val ctx = context
        val isRunning = com.example.aichat.service.ActiveModeService.isRunning(viewModel.activePersonaId)

        AlertDialog(
            onDismissRequest = { showActiveModeDialog = false },
            title = { Text("❤️ 主动模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("角色：${viewModel.getActivePersona(ctx).emoji} ${viewModel.getActivePersona(ctx).name}")
                    Text("每轮心跳消息会写回当前对话。多个角色可同时开启。", style = MaterialTheme.typography.labelSmall)
                    Text("频率", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        frequencyOptions.forEachIndexed { i, freq ->
                            FilterChip(
                                selected = pmFrequency == freq,
                                onClick = { pmFrequency = freq },
                                label = { Text(frequencyLabels[i], style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pmImmersive, onCheckedChange = { pmImmersive = it })
                        Text("沉浸模式 ${if (pmImmersive) "(完整上下文)" else "(轻盈≈500t)"}", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pmHideThink, onCheckedChange = { pmHideThink = it })
                        Text("隐藏思考过程", style = MaterialTheme.typography.labelSmall)
                    }
                    if (isRunning) {
                        Text("✅ 正在运行", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isRunning) {
                        val stopIntent = Intent(ctx, com.example.aichat.service.ActiveModeService::class.java).apply {
                            action = com.example.aichat.service.ActiveModeService.ACTION_STOP
                            putExtra(com.example.aichat.service.ActiveModeService.EXTRA_PERSONA_ID, viewModel.activePersonaId)
                        }
                        ctx.startService(stopIntent)
                        showActiveModeDialog = false
                        return@TextButton
                    }
                    // 检查通知权限 —— 若被拒绝，授权后启动器会自动启动
                    if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        showActiveModeDialog = false
                        return@TextButton
                    }
                    startNow()
                    showActiveModeDialog = false
                }) { Text(if (isRunning) "停止" else "开始主动陪伴") }
            },
            dismissButton = { TextButton(onClick = { showActiveModeDialog = false }) { Text("取消") } }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: ChatMessage, typewriter: Boolean = false, onLongClick: (() -> Unit)? = null) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    // 客户端打字机：最终回答是非流式整包返回的，这里按 3 字/帧分批显示模拟流式观感
    var shownChars by remember(message.timestamp, message.id) {
        mutableStateOf(if (typewriter) 0 else message.content.length)
    }
    LaunchedEffect(message.timestamp, message.id) {
        if (typewriter && shownChars < message.content.length) {
            var i = shownChars
            while (i < message.content.length) {
                i = minOf(i + 3, message.content.length)
                shownChars = i
                delay(16)
            }
        }
    }
    val displayContent = if (typewriter) message.content.take(shownChars) else message.content

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongClick),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = bubbleColor
        ) {
            Column {
                // 图片展示
                message.imageUri?.let { uri ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "发送的图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                // 文本
                if (displayContent.isNotBlank()) {
                    Text(
                        text = displayContent,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // 排盘确认卡（只读）
                message.paipanData?.let { PaipanCard(it) }
                // 溯源条：本回复实际调用过的工具（系统起卦/日期注入也计入）
                message.toolBadges?.takeIf { it.isNotEmpty() }?.let { badges ->
                    Text(
                        text = "本回复调用工具：" + badges.joinToString(" / "),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                // 复制按钮
                val context = LocalContext.current
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", message.content))
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, "复制", Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

/** 排盘确认卡（只读）：展示四柱/日主/农历/时辰供用户确认 */
@Composable
fun PaipanCard(jsonStr: String) {
    val data = remember(jsonStr) {
        try {
            com.google.gson.Gson().fromJson(jsonStr, Map::class.java)
        } catch (_: Exception) { null }
    } ?: return
    val bazi = data["bazi"] as? String ?: return
    val dayMaster = data["dayMaster"] as? String ?: ""
    val lunar = data["lunar"] as? String ?: ""
    val hour = data["hour"] as? String ?: ""

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("🔮 排盘确认", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(4.dp))
            Text("八字：$bazi", style = MaterialTheme.typography.bodyMedium)
            if (dayMaster.isNotBlank()) Text("日主：$dayMaster", style = MaterialTheme.typography.bodySmall)
            if (lunar.isNotBlank()) Text("农历：$lunar", style = MaterialTheme.typography.bodySmall)
            if (hour.isNotBlank()) Text("时辰：$hour", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("请确认排盘无误；若生辰信息有误，请告诉我更正", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AgentToolCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }
            }
        }
    }
}

@Composable
fun ThinkingChainCard(content: String) {
    var expanded by remember { mutableStateOf(false) }
    val preview = content.take(80)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "思考过程",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (expanded) "收起 ▲" else "展开 ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            } else if (preview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$preview...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun PlanCard(
    plan: TaskPlan,
    completedIds: Set<Int> = emptySet(),
    isExecuting: Boolean = false,
    onConfirm: (Boolean) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var useOptimized by remember { mutableStateOf(true) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        border = ButtonDefaults.outlinedButtonBorder
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDCCB 任务计划", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        "复杂度 ${plan.complexity}/5",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (plan.reasoning.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(plan.reasoning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            plan.tasks.forEachIndexed { i, task ->
                Row(verticalAlignment = Alignment.Top) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (completedIds.contains(task.id)) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else if (useOptimized) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            if (completedIds.contains(task.id)) "\u2713" else "${i + 1}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.description, style = MaterialTheme.typography.bodySmall)
                        if (task.tool.isNotBlank()) {
                            Text(
                                "\uD83D\uDD27 ${task.tool}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (task.question.isNotBlank()) {
                            Text(
                                "\u2753 ${task.question}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (i < plan.tasks.size - 1) Spacer(Modifier.height(8.dp))
            }

            if (plan.optimizations.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("\uD83D\uDCA1 优化建议", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        plan.optimizations.forEach { opt ->
                            Text(
                                "${opt.original} \u2192 ${opt.better}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (opt.reason.isNotEmpty()) {
                                Text(
                                    opt.reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = useOptimized,
                                onCheckedChange = { useOptimized = it },
                                modifier = Modifier.size(20.dp)
                            )
                            Text("采纳优化方案", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isExecuting) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("执行中... ${completedIds.size}/${plan.tasks.size}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(onClick = { onConfirm(useOptimized) }, modifier = Modifier.weight(1f)) {
                        Text("开始执行")
                    }
                }
            }
        }
    }
}

@Composable
fun HtmlPreview(filePath: String, modifier: Modifier = Modifier) {
    val file = remember(filePath) { java.io.File(filePath) }
    if (!file.exists()) {
        Box(modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
            Text("预览加载中...", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val html = remember(filePath) { file.readText() }
    key(filePath) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = false
                    // 安全：禁止 file:// 访问，防模型生成的 HTML 里 JS 读本地文件（含记忆文件）
                    settings.allowFileAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            },
            modifier = modifier,
            update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) }
        )
    }
}
