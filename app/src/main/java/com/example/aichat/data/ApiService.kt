package com.example.aichat.data

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*
import okhttp3.ResponseBody

// --- Request DTOs ---

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = false,
    val tools: List<Map<String, Any>>? = null,
    @SerializedName("reasoning_effort") val reasoningEffort: String? = null,
    val thinking: Map<String, String>? = null
)

data class ChatMessageDto(
    val role: String,           // "user" | "assistant" | "system" | "tool"
    val content: Any? = null,   // String for text, List<Map> for multimodal
    @SerializedName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null
)

data class ToolCallDto(
    val id: String = "",
    val type: String = "function",
    val function: ToolCallFunctionDto = ToolCallFunctionDto()
)

data class ToolCallFunctionDto(
    val name: String = "",
    val arguments: String = "" // JSON string
)

// --- Response DTOs ---

data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Long = 0,
    @SerializedName("completion_tokens") val completionTokens: Long = 0,
    @SerializedName("prompt_cache_hit_tokens") val cacheHitTokens: Long = 0,
    @SerializedName("prompt_cache_miss_tokens") val cacheMissTokens: Long = 0
)

data class Choice(
    val message: Message = Message(),
    @SerializedName("finish_reason") val finishReason: String? = null,
    val index: Int = 0
)

data class Message(
    val role: String = "",
    val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerializedName("reasoning_content") val reasoningContent: String? = null
)

// --- Retrofit Interface ---

interface ApiService {
    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): Response<ChatResponse>
}
