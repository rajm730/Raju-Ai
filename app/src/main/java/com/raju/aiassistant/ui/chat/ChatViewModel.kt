package com.raju.aiassistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.di.AppContainer
import com.raju.aiassistant.domain.ai.ModelAdvice
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
    val model: String? = null,
    /** Shown in the header when the chosen model is a poor fit (e.g. Arabic-only). */
    val modelWarning: String? = null,
    val historyEnabled: Boolean = true,
    /** The last request failed: offer a Retry button. */
    val canRetry: Boolean = false,
)

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val engine = c.engine

    val uiState: StateFlow<ChatUiState> =
        combine(engine.transcript, engine.state, c.currentSettings, engine.lastError) { messages, state, settings, error ->
            val model = settings.modelFor(settings.defaultProvider)
            ChatUiState(
                messages = messages,
                state = state,
                providerName = settings.defaultProvider.displayName,
                model = model,
                modelWarning = ModelAdvice.warning(model),
                historyEnabled = settings.saveHistory,
                canRetry = error != null && !state.isBusy && engine.canRetry,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun send(text: String) = engine.submit(text)
    fun stop() = c.stopEverything()
    fun retry() = engine.retry()
    fun hasMicPermission() = c.voiceInput.hasPermission()
    fun startVoice() = engine.startVoice()
}
