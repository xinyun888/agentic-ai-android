package com.example.aichat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.aichat.data.ApiProfile
import com.example.aichat.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val appCtx = LocalContext.current.applicationContext
    var profiles by remember { mutableStateOf(viewModel.getProfiles()) }
    // 每次进入页面都强制从存储刷新（不依赖 remember 初始化语义）
    LaunchedEffect(Unit) {
        profiles = viewModel.getProfiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API 配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val newProfile = ApiProfile(name = "新配置")
                        val updated = profiles + newProfile
                        profiles = updated
                        viewModel.saveProfiles(updated)
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加配置")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val usageText = remember { com.example.aichat.data.UsageMeter.stats() }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("📊 Token 用量（累计）", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(usageText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            itemsIndexed(profiles, key = { _, p -> p.id }) { index, profile ->
                ProfileCard(
                    profile = profile,
                    isActive = viewModel.getActiveProfileId() == profile.id,
                    onActivate = {
                        viewModel.setActiveProfileId(profile.id)
                    },
                    onUpdate = { updated ->
                        val newList = profiles.toMutableList()
                        newList[index] = updated
                        profiles = newList
                        viewModel.saveProfiles(newList)
                        // 立即读回验证：读不到刚保存的内容说明存储层有问题
                        val back = viewModel.getProfiles()
                        if (back.none { it.id == updated.id && it.apiKey == updated.apiKey }) {
                            android.widget.Toast.makeText(
                                appCtx, "⚠️ 保存验证失败，配置未落盘，请重试",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    onDelete = {
                        if (profiles.size > 1) {
                            val newList = profiles.toMutableList()
                            newList.removeAt(index)
                            profiles = newList
                            viewModel.saveProfiles(newList)
                            // 若删除的是当前启用项，则将第一项设为启用
                            if (viewModel.getActiveProfileId() == profile.id) {
                                viewModel.setActiveProfileId(newList.first().id)
                            }
                        }
                    },
                    canDelete = profiles.size > 1
                )
            }
        }
    }
}

@Composable
fun ProfileCard(
    profile: ApiProfile,
    isActive: Boolean,
    onActivate: () -> Unit,
    onUpdate: (ApiProfile) -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean
) {
    var editing by remember { mutableStateOf(false) }
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var baseUrl by remember(profile.id) { mutableStateOf(profile.baseUrl) }
    var apiKey by remember(profile.id) { mutableStateOf(profile.apiKey) }
    var model by remember(profile.id) { mutableStateOf(profile.model) }
    var thinkingEnabled by remember(profile.id) { mutableStateOf(profile.thinkingEnabled) }
    var reasoningLevel by remember(profile.id) { mutableStateOf(profile.reasoningLevel) }
    var visionModel by remember(profile.id) { mutableStateOf(profile.visionModel) }
    var visionBaseUrl by remember(profile.id) { mutableStateOf(profile.visionBaseUrl) }
    var visionApiKey by remember(profile.id) { mutableStateOf(profile.visionApiKey) }

    // 编辑态按返回键 = 自动保存（防止"填完没点保存就退出导致配置丢失"）
    if (editing) {
        androidx.activity.compose.BackHandler {
            onUpdate(profile.copy(
                name = name, baseUrl = baseUrl, apiKey = apiKey, model = model,
                thinkingEnabled = thinkingEnabled, reasoningLevel = reasoningLevel,
                visionModel = visionModel, visionBaseUrl = visionBaseUrl, visionApiKey = visionApiKey
            ))
            editing = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isActive) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        } else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isActive) Icons.Filled.CheckCircle
                    else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (editing) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.titleSmall
                    )
                } else {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (isActive) {
                        Text(
                            "使用中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (editing) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 推理开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Max 推理模式", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "启用后 AI 会先思考再回答（需模型支持）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = thinkingEnabled,
                        onCheckedChange = { thinkingEnabled = it }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 推理档位（最终回答的 reasoning_effort）
                Text("推理档位", style = MaterialTheme.typography.bodyMedium)
                Text("决定最终回答的思考深度，快=省 token，深=分析更细", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Triple("fast", "快", "省 token，日常问答"),
                        Triple("balanced", "平衡", "默认，兼顾质量与成本"),
                        Triple("deep", "深", "深度分析，消耗更多")
                    ).forEach { (value, label, desc) ->
                        FilterChip(
                            selected = reasoningLevel == value,
                            onClick = { reasoningLevel = value },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 视觉模型配置
                Text("视觉模型（图片前置处理）", style = MaterialTheme.typography.bodyMedium)
                Text("图片先发给视觉模型取描述，再注入主模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = visionModel,
                    onValueChange = { visionModel = it },
                    label = { Text("视觉模型名，如 glm-4v") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = visionBaseUrl,
                    onValueChange = { visionBaseUrl = it },
                    label = { Text("视觉 API 地址（留空默认智谱）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = visionApiKey,
                    onValueChange = { visionApiKey = it },
                    label = { Text("视觉 API Key（留空复用上方）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (!isActive) {
                    TextButton(onClick = onActivate) {
                        Text("启用")
                    }
                }

                if (editing) {
                    val ctx = LocalContext.current
                    TextButton(onClick = {
                        onUpdate(profile.copy(
                            name = name, baseUrl = baseUrl, apiKey = apiKey, model = model,
                            thinkingEnabled = thinkingEnabled, reasoningLevel = reasoningLevel,
                            visionModel = visionModel, visionBaseUrl = visionBaseUrl, visionApiKey = visionApiKey
                        ))
                        editing = false
                        android.widget.Toast.makeText(ctx, "配置已保存", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text("保存")
                    }
                    TextButton(onClick = { editing = false }) {
                        Text("取消")
                    }
                } else {
                    TextButton(onClick = { editing = true }) {
                        Text("编辑")
                    }
                    if (canDelete) {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}
