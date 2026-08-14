package com.example.aichat.data

data class ChatMessage(
    val role: String,  // "user" | "assistant" | "system" | "assistant_live"
    val content: String,
    val thinking: String = "",  // thinking chain for assistant messages
    val imageUri: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String? = null,  // 稳定标识，流式 live 消息固定为 "live"，供 LazyColumn 做 key
    val paipanData: String? = null,  // 排盘确认卡 JSON（bazi_paipan 结果，仅助手消息）
    val toolBadges: List<String>? = null,  // 溯源条（如 ["🏷 起卦", "📅 日期"]），旧数据缺字段为 null，仅助手消息
    val toolSteps: List<AgentStep>? = null  // 本回复的工具步骤快照（tool_call/tool_result），UI 渲染可见，仅助手消息
)
