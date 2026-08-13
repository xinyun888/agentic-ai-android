package com.example.aichat.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

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
    val visionApiKey: String = "",    // 独立视觉 API Key
    val reasoningLevel: String = "balanced"  // 推理档位: fast=快(省钱) / balanced=平衡 / deep=深
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

    companion object {
        // 进程内共享锁：ChatViewModel 与 ActiveModeService 并发读写同一份会话数据时串行化
        private val LOCK = Any()
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("moyu_storage", Context.MODE_PRIVATE)
    private val gson = Gson()

    // 会话分文件存储：每个对话一个 JSON 文件；index 只存元数据（含最后一条消息供列表预览）
    private val convDir: File = File(context.filesDir, "conversations").also { it.mkdirs() }
    private val indexFile: File = File(convDir, "index.json")

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

    // ===== 会话（分文件存储）=====

    private fun convFile(id: String): File = File(convDir, "$id.json")

    // 旧版把全部会话塞在 SP 的 "conversations" key 里，首次访问时迁移到分文件
    private fun migrateIfNeeded() {
        val legacyJson = prefs.getString("conversations", null) ?: return
        try {
            val legacy: List<Conversation> = gson.fromJson(legacyJson, object : TypeToken<List<Conversation>>() {}.type) ?: return
            for (conv in legacy) {
                convFile(conv.id).writeText(gson.toJson(conv), Charsets.UTF_8)
            }
            writeIndex(legacy)
            prefs.edit().remove("conversations").apply()
        } catch (_: Exception) {}
    }

    // index 只存元数据 + 最后一条消息，避免把全部消息塞进一个 JSON
    private fun writeIndex(convs: List<Conversation>) {
        val slim = convs.map { it.copy(messages = it.messages.takeLast(1)) }
        indexFile.writeText(gson.toJson(slim), Charsets.UTF_8)
    }

    private fun readIndex(): List<Conversation> {
        if (!indexFile.exists()) return emptyList()
        return try {
            gson.fromJson(indexFile.readText(Charsets.UTF_8), object : TypeToken<List<Conversation>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }

    fun getConversations(): List<Conversation> = synchronized(LOCK) {
        migrateIfNeeded()
        readIndex()
    }

    fun saveConversations(convs: List<Conversation>) = synchronized(LOCK) {
        migrateIfNeeded()
        writeIndex(convs)
    }

    fun getConversation(id: String): Conversation? = synchronized(LOCK) {
        migrateIfNeeded()
        val f = convFile(id)
        if (f.exists()) {
            try {
                gson.fromJson(f.readText(Charsets.UTF_8), Conversation::class.java)
            } catch (_: Exception) { null }
        } else {
            readIndex().find { it.id == id }
        }
    }

    fun saveConversation(conv: Conversation) = synchronized(LOCK) {
        migrateIfNeeded()
        val updated = conv.copy(updatedAt = System.currentTimeMillis())
        convFile(conv.id).writeText(gson.toJson(updated), Charsets.UTF_8)
        val list = readIndex().toMutableList()
        val idx = list.indexOfFirst { it.id == conv.id }
        if (idx >= 0) list[idx] = updated else list.add(0, updated)
        writeIndex(list)
    }

    fun renameConversation(id: String, newTitle: String) = synchronized(LOCK) {
        migrateIfNeeded()
        val conv = getConversation(id) ?: return
        saveConversation(conv.copy(title = newTitle))
    }

    fun deleteConversation(id: String) = synchronized(LOCK) {
        migrateIfNeeded()
        convFile(id).delete()
        writeIndex(readIndex().filter { it.id != id })
        // 清理孤儿数据：工作区文件、记忆、断点状态
        val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        convDir.parentFile?.let { root ->
            File(root, "workspace/$safeId").deleteRecursively()
            File(root, "memory/$safeId").deleteRecursively()
        }
    }

    fun getActiveConversationId(): String {
        return prefs.getString("active_conv_id", "") ?: ""
    }

    fun setActiveConversationId(id: String) {
        prefs.edit().putString("active_conv_id", id).apply()
    }

    // 通用偏好（角色选择、开关等 UI 状态持久化）
    fun getStringPref(key: String, default: String): String = prefs.getString(key, default) ?: default

    fun setStringPref(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getBoolPref(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    fun setBoolPref(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    // ===== 消息便捷方法 =====

    /** 以更新后的消息保存会话（原子读写，避免并发覆盖） */
    fun updateMessages(convId: String, messages: List<ChatMessage>) = synchronized(LOCK) {
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

    /** 追加一条消息（原子），供后台服务与主界面并发写入时避免覆盖 */
    fun appendMessage(convId: String, message: ChatMessage) = synchronized(LOCK) {
        val conv = getConversation(convId) ?: return
        saveConversation(conv.copy(messages = conv.messages + message))
    }

    /** 原子追加用户消息（含自动命名），避免与心跳 appendMessage 竞态导致消息覆盖 */
    fun appendUserMessage(convId: String, message: ChatMessage) = synchronized(LOCK) {
        val conv = getConversation(convId) ?: return
        val newMessages = conv.messages + message
        val title = if (conv.title == "新对话" && message.role == "user" && message.content.isNotBlank()) {
            message.content.take(30).trim().let {
                if (it.length > 25) it.take(25) + "…" else it
            }
        } else conv.title
        saveConversation(conv.copy(messages = newMessages, title = title))
    }
}
