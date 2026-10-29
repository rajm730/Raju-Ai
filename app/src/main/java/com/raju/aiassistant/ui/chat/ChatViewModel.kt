package com.raju.aiassistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.di.AppContainer
import com.raju.aiassistant.domain.model.AssistantState
import com.raju.aiassistant.domain.model.ChatMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val state: AssistantState = AssistantState.Idle,
    val providerName: String = "",
    val historyEnabled: Boolean = true,
)

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val engine = c.engine

    val uiState: StateFlow<ChatUiState> =
        combine(engine.transcript, engine.state, c.currentSettings) { messages, state, settings ->
            ChatUiState(messages, state, settings.defaultProvider.displayName, settings.saveHistory)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun send(text: String) = engine.submit(text)
    fun stop() = engine.stop()
    fun hasMicPermission() = c.voiceInput.hasPermission()
    fun startVoice() = engine.startVoice()
}
