package com.example.aichat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.data.Conversation
import com.example.aichat.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

// 全局单例：避免每条对话各建一个格式化器
private val DATE_FORMAT = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onEnterChat: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf<Conversation?>(null) }
    var showRenameDialog by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("命苦打工人") },
                actions = {
                    IconButton(onClick = { viewModel.showProfileManager = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "API 配置")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    viewModel.createNewConversation()
                    onEnterChat()
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新对话") }
            )
        }
    ) { padding ->
        if (viewModel.conversations.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "还没有对话",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "点击下方按钮开始",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(viewModel.conversations, key = { it.id }) { conv ->
                    ConversationItem(
                        conversation = conv,
                        onClick = {
                            viewModel.selectConversation(conv.id)
                            onEnterChat()
                        },
                        onDelete = { showDeleteDialog = conv },
                        onRename = { showRenameDialog = conv }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    showDeleteDialog?.let { conv ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「${conv.title}」？消息无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(conv.id)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消") }
            }
        )
    }

    // 重命名对话框
    showRenameDialog?.let { conv ->
        var newName by remember(conv.id) { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.renameConversation(conv.id, newName.trim())
                        showRenameDialog = null
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("取消") }
            }
        )
    }
}

@Composable
fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    val lastMessage = conversation.messages.lastOrNull()
    val preview = when {
        lastMessage != null -> lastMessage.content.take(50)
        else -> "新对话"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (conversation.messages.isEmpty()) Icons.Outlined.ChatBubbleOutline
                        else Icons.Outlined.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (conversation.messages.isNotEmpty()) {
                Text(
                    text = DATE_FORMAT.format(Date(conversation.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // 重命名按钮
            IconButton(onClick = onRename) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "重命名",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }

            // 删除按钮
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}
