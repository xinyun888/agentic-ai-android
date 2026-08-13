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
import androidx.compose.ui.unit.dp
import com.example.aichat.data.ApiProfile
import com.example.aichat.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var profiles by remember { mutableStateOf(viewModel.getProfiles()) }

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
    var visionModel by remember(profile.id) { mutableStateOf(profile.visionModel) }
    var visionBaseUrl by remember(profile.id) { mutableStateOf(profile.visionBaseUrl) }
    var visionApiKey by remember(profile.id) { mutableStateOf(profile.visionApiKey) }

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
                    TextButton(onClick = {
                        onUpdate(profile.copy(
                            name = name, baseUrl = baseUrl, apiKey = apiKey, model = model,
                            thinkingEnabled = thinkingEnabled,
                            visionModel = visionModel, visionBaseUrl = visionBaseUrl, visionApiKey = visionApiKey
                        ))
                        editing = false
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
