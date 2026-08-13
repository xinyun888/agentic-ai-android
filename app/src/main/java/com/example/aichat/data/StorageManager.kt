package com.example.aichat.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// --- 数据模型 ---

data class ApiProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "DeepSeek V4 Pro",
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-v4-pro",
    val thinkingEnabled: Boolean = true,
    val visionModel: String = "",     // 如 glm-4v，空则不启用视觉管道
    val visionBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4/",
    val visionApiKey: String = ""      // 独立视觉 API Key
)

data class Conversation(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val profileId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// --- 存储管理器 ---

class StorageManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("moyu_storage", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ===== API 配置 =====

    fun getProfiles(): List<ApiProfile> {
        val json = prefs.getString("profiles", null) ?: return listOf(ApiProfile(name = "Default"))
        return try {
            gson.fromJson(json, object : TypeToken<List<ApiProfile>>() {}.type)
        } catch (e: Exception) {
            listOf(ApiProfile(name = "Default"))
        }
    }

    fun saveProfiles(profiles: List<ApiProfile>) {
        prefs.edit().putString("profiles", gson.toJson(profiles)).apply()
    }

    fun getActiveProfileId(): String {
        return prefs.getString("active_profile_id", "") ?: ""
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString("active_profile_id", id).apply()
    }

    fun getActiveProfile(): ApiProfile? {
        val activeId = getActiveProfileId()
        return getProfiles().find { it.id == activeId }
            ?: getProfiles().firstOrNull()
    }

    // ===== 会话 =====

    fun getConversations(): List<Conversation> {
        val json = prefs.getString("conversations", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<Conversation>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveConversations(convs: List<Conversation>) {
        prefs.edit().putString("conversations", gson.toJson(convs)).apply()
    }

    fun getConversation(id: String): Conversation? {
        return getConversations().find { it.id == id }
    }

    fun renameConversation(id: String, newTitle: String) {
        val list = getConversations().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(title = newTitle, updatedAt = System.currentTimeMillis())
            saveConversations(list)
        }
    }

    fun saveConversation(conv: Conversation) {
        val list = getConversations().toMutableList()
        val idx = list.indexOfFirst { it.id == conv.id }
        val updated = conv.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) {
            list[idx] = updated
        } else {
            list.add(0, updated)
        }
        saveConversations(list)
    }

    fun deleteConversation(id: String) {
        val list = getConversations().filter { it.id != id }
        saveConversations(list)
    }

    fun getActiveConversationId(): String {
        return prefs.getString("active_conv_id", "") ?: ""
    }

    fun setActiveConversationId(id: String) {
        prefs.edit().putString("active_conv_id", id).apply()
    }

    // ===== 消息便捷方法 =====

    /** 以更新后的消息保存会话 */
    fun updateMessages(convId: String, messages: List<ChatMessage>) {
        val conv = getConversation(convId) ?: return
        // 自动命名：使用第一条用户消息（去除首尾空格）
        val title = if (conv.title == "新对话" && messages.isNotEmpty()) {
            val firstUser = messages.firstOrNull { it.role == "user" }
            firstUser?.content?.take(30)?.trim()?.let {
                if (it.length > 25) it.take(25) + "…" else it
            } ?: "新对话"
        } else conv.title

        saveConversation(conv.copy(messages = messages, title = title))
    }
}
