package com.example.aichat.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.aichat.data.ApiProfile
import com.example.aichat.data.Conversation
import com.example.aichat.data.StorageManager

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val storage = StorageManager(application)

    var conversations by mutableStateOf(storage.getConversations())
        private set

    var showChat by mutableStateOf(false)

    var showProfileManager by mutableStateOf(false)

    var activeConversationId by mutableStateOf(storage.getActiveConversationId())
        private set

    fun createNewConversation(): String {
        val conv = Conversation()
        val list = conversations.toMutableList()
        list.add(0, conv)
        conversations = list
        storage.saveConversations(list)
        activeConversationId = conv.id
        storage.setActiveConversationId(conv.id)
        return conv.id
    }

    fun selectConversation(id: String) {
        activeConversationId = id
        storage.setActiveConversationId(id)
        showChat = true
    }

    fun deleteConversation(id: String) {
        conversations = conversations.filter { it.id != id }
        storage.deleteConversation(id)
        if (activeConversationId == id) {
            activeConversationId = ""
            storage.setActiveConversationId("")
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        storage.renameConversation(id, newTitle)
        refreshConversations()
    }

    fun refreshConversations() {
        conversations = storage.getConversations()
    }

    // ===== Profiles =====
    fun getProfiles(): List<ApiProfile> = storage.getProfiles()

    fun getActiveProfile(): ApiProfile? = storage.getActiveProfile()

    fun saveProfiles(profiles: List<ApiProfile>) {
        storage.saveProfiles(profiles)
    }

    fun setActiveProfileId(id: String) {
        storage.setActiveProfileId(id)
    }

    fun getActiveProfileId(): String = storage.getActiveProfileId()
}
