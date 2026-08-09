package com.example.aichat.data

data class ChatMessage(
    val role: String,  // "user" | "assistant" | "system" | "assistant_live"
    val content: String,
    val thinking: String = "",  // thinking chain for assistant messages
    val imageUri: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
