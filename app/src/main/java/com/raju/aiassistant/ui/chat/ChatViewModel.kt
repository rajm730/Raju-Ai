package com.raju.aiassistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.data.prefs.SettingsRepository
import com.raju.aiassistant.data.repository.ConversationRepository
import com.raju.aiassistant.domain.model.ChatMessage
import com.raju.aiassistant.domain.model.ChatRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val historyEnabled: Boolean = true,
    val error: String? = null,
)

/**
 * Phase 1 chat: messages are stored (Room) and displayed, but no AI provider is
 * wired yet, so no assistant reply is generated. Phase 3 plugs the provider in here.
 */
class ChatViewModel(
    private val repository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val persisted = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val ephemeral = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val error = MutableStateFlow<String?>(null)
    private var conversationId: Long? = null

    val uiState: StateFlow<ChatUiState> =
        combine(persisted, ephemeral, settingsRepository.settings, error) { saved, temp, settings, err ->
            ChatUiState(messages = saved + temp, historyEnabled = settings.saveHistory, error = err)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            try {
                val id = repository.currentConversationId()
                conversationId = id
                repository.observeMessages(id).collect { persisted.value = it }
            } catch (e: Exception) {
                error.value = "Could not open chat history: ${e.message}"
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val saveHistory = settingsRepository.settings.first().saveHistory
            val id = conversationId
            if (saveHistory && id != null) {
                try {
                    repository.addMessage(id, ChatRole.USER, trimmed)
                    error.value = null
                } catch (e: Exception) {
                    error.value = "Message was not saved: ${e.message}"
                }
            } else {
                ephemeral.value = ephemeral.value + ChatMessage(
                    id = -(ephemeral.value.size + 1L),
                    conversationId = id ?: 0,
                    role = ChatRole.USER,
                    text = trimmed,
                    createdAt = System.currentTimeMillis(),
                )
            }
        }
    }
}
