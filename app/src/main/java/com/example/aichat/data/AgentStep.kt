package com.example.aichat.data

/**
 * Agent 执行步骤（工具调用/结果等），随最终消息落盘供溯源展示。
 * auto=true 表示系统自动注入的步骤（非模型调用），溯源条与审计器据此区分来源。
 */
data class AgentStep(
    val type: String,        // "tool_call" | "tool_result" | "thinking" | "final"
    val toolName: String = "",
    val toolArgs: String = "",
    val content: String = "",
    val auto: Boolean = false
)
