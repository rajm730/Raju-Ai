package com.raju.aiassistant.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.di.AppContainer
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.model.AssistantState
import com.raju.aiassistant.domain.model.ChatRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val assistantState: AssistantState = AssistantState.Idle,
    /** Non-null while the user is previewing a state's visuals (no real work is happening). */
    val previewState: AssistantState? = null,
    val online: Boolean = true,
    val listening: Boolean = false,
    val partialTranscript: String = "",
    val lastReply: String? = null,
    val defaultProvider: ProviderId = ProviderId.GEMINI,
    val providerKeySaved: Boolean = false,
    /** True only if the last real connection test for the default provider succeeded. */
    val providerConnected: Boolean = false,
    val lowPowerMode: Boolean = false,
    val reducedMotion: Boolean = false,
    val paused: Boolean = false,
    val safetyMode: Boolean = false,
)

private data class EngineView(val state: AssistantState, val listening: Boolean, val partial: String, val lastReply: String?)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val engine = c.engine
    private val preview = MutableStateFlow<AssistantState?>(null)

    val speechLevel: StateFlow<Float> = engine.speechLevel

    private val engineView = combine(engine.state, engine.listening, engine.partialTranscript, engine.transcript) { s, l, p, t ->
        EngineView(s, l, p, t.lastOrNull { it.role == ChatRole.ASSISTANT }?.text)
    }

    val uiState: StateFlow<HomeUiState> =
        combine(engineView, c.secureKeyStore.configured, c.currentSettings, c.online, preview) { ev, configured, settings, online, previewState ->
            val real = if (!online && ev.state == AssistantState.Idle) AssistantState.Offline else ev.state
            HomeUiState(
                assistantState = previewState ?: real,
                previewState = previewState,
                online = online,
                listening = ev.listening,
                partialTranscript = ev.partial,
                lastReply = ev.lastReply,
                defaultProvider = settings.defaultProvider,
                providerKeySaved = settings.defaultProvider.secret in configured,
                providerConnected = settings.lastTests[settings.defaultProvider]?.success == true,
                lowPowerMode = settings.lowPowerMode,
                reducedMotion = settings.reducedMotion,
                paused = settings.assistantPaused,
                safetyMode = settings.safetyMode,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun hasMicPermission() = c.voiceInput.hasPermission()
    fun startVoice() {
        preview.value = null
        engine.startVoice()
    }

    fun stop() {
        preview.value = null
        c.stopEverything()
    }

    /** Shows a state's visuals for testing the UI. Pass null to return to the real state. */
    fun setPreview(state: AssistantState?) {
        preview.value = state
    }
}
