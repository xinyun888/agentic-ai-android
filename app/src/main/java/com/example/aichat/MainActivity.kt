package com.example.aichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichat.ui.ChatScreen
import com.example.aichat.ui.MainScreen
import com.example.aichat.ui.ProfileScreen
import com.example.aichat.ui.theme.AiChatTheme
import com.example.aichat.viewmodel.ChatViewModel
import com.example.aichat.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiChatTheme {
                val mainViewModel: MainViewModel = viewModel()
                val chatViewModel: ChatViewModel = viewModel()

                if (mainViewModel.showProfileManager) {
                    ProfileScreen(
                        viewModel = mainViewModel,
                        onBack = { mainViewModel.showProfileManager = false }
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
