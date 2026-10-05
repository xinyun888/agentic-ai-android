package com.example.aichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichat.linux.PhoneBridgeManager
import com.example.aichat.ui.ChatScreen
import com.example.aichat.ui.HarnessScreen
import com.example.aichat.ui.LinuxScreen
import com.example.aichat.ui.MainScreen
import com.example.aichat.ui.VmScreen
import com.example.aichat.ui.ProfileScreen
import com.example.aichat.ui.theme.AiChatTheme
import com.example.aichat.viewmodel.ChatViewModel
import com.example.aichat.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 启动 guest Linux -> 无障碍 的文件桥
        PhoneBridgeManager.start(applicationContext)
        // debug 构建：跑一遍人物卡回归自检（解析/合并/溢出/引用校验），失败只记日志不影响使用
        if (BuildConfig.DEBUG) {
            try {
                val fails = com.example.aichat.data.CardStore.selfTest() +
                    com.example.aichat.data.ToolCallSanitizer.selfTest().map { "tool:" + it }
                if (fails.isEmpty()) android.util.Log.i("CardSelfTest", "PASS (cards + tool protocol)")
                else android.util.Log.e("CardSelfTest", "FAIL: " + fails.joinToString("; "))
            } catch (e: Exception) {
                android.util.Log.e("CardSelfTest", "crash: " + e.message)
            }
        }
        setContent {
            AiChatTheme {
                val mainViewModel: MainViewModel = viewModel()
                val chatViewModel: ChatViewModel = viewModel()
                var harnessEverOpened by remember { mutableStateOf(false) }
                LaunchedEffect(mainViewModel.showHarness) {
                    if (mainViewModel.showHarness) harnessEverOpened = true
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    // DS Harness 一旦打开就保持挂载：退出再回来不重建 WebView、不重新加载页面。
                    if (harnessEverOpened) {
                        val harnessConvId = remember { mainViewModel.ensureHarnessConversation() }
                        LaunchedEffect(harnessConvId) { chatViewModel.loadConversation(harnessConvId) }
                        HarnessScreen(
                            chatViewModel = chatViewModel,
                            profile = mainViewModel.getActiveProfile() ?: com.example.aichat.data.ApiProfile(),
                            conversationId = harnessConvId,
                            active = mainViewModel.showHarness,
                            onBack = {
                                mainViewModel.showHarness = false
                                mainViewModel.refreshConversations()
                            }
                        )
                    }
                    // Harness 不显示时只是被其它页面盖住，WebView 的会话和页面状态保留。
                    if (!mainViewModel.showHarness) {
                        if (mainViewModel.showProfileManager) {
                            ProfileScreen(
                                viewModel = mainViewModel,
                                onBack = { mainViewModel.showProfileManager = false }
                            )
                        } else if (mainViewModel.showLinux) {
                            LinuxScreen(
                                manager = chatViewModel.linuxManager,
                                onBack = { mainViewModel.showLinux = false }
                            )
                        } else if (mainViewModel.showVm) {
                            VmScreen(
                                manager = chatViewModel.qemuManager,
                                onBack = { mainViewModel.showVm = false }
                            )
                        } else if (mainViewModel.showChat && mainViewModel.activeConversationId.isNotEmpty()) {
                            val profile = mainViewModel.getActiveProfile()
                            LaunchedEffect(mainViewModel.activeConversationId) {
                                chatViewModel.loadConversation(mainViewModel.activeConversationId)
                            }
                            ChatScreen(
                                viewModel = chatViewModel,
                                profile = profile ?: com.example.aichat.data.ApiProfile(),
                                conversationId = mainViewModel.activeConversationId,
                                onBack = {
                                    mainViewModel.showChat = false
                                    mainViewModel.refreshConversations()
                                }
                            )
                        } else {
                            MainScreen(
                                viewModel = mainViewModel,
                                onEnterChat = { mainViewModel.showChat = true }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        PhoneBridgeManager.stop()
        super.onDestroy()
    }
}
