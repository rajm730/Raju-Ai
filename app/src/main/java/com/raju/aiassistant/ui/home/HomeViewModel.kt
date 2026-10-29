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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /** Hands-free (always listening) mode is on. */
    val handsFree: Boolean = false,
    val voiceEngine: String = "",
    val listenEngine: String = "",
    /** What speech recognition really understood (shown as "Recognized"), or null. */
    val heard: String? = null,
    /** Non-error status such as "nothing was heard". */
    val hint: String? = null,
    /** "Hands-free", "Continuous" or "Tap to talk": how listening continues after a reply. */
    val listenMode: String = "",
)

private data class EngineView(val state: AssistantState, val listening: Boolean, val partial: String, val lastReply: String?, val handsFree: Boolean)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val engine = c.engine
    private val preview = MutableStateFlow<AssistantState?>(null)

    val speechLevel: StateFlow<Float> = engine.speechLevel

    private val engineView = combine(engine.state, engine.listening, engine.partialTranscript, engine.transcript, engine.handsFreeActive) { s, l, p, t, h ->
        EngineView(s, l, p, t.lastOrNull { it.role == ChatRole.ASSISTANT }?.text, h)
    }

    private val voiceStatus = combine(engine.heard, engine.hint) { h, n -> h to n }

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
                handsFree = ev.handsFree,
                voiceEngine = settings.ttsEngine.label.substringBefore(" ").uppercase(),
                listenEngine = settings.sttEngine.label.substringBefore(" ").uppercase(),
                listenMode = when {
                    ev.handsFree -> "Hands-free"
                    settings.continuousConversation -> "Continuous"
                    else -> "Tap to talk"
                },
            )
        }.combine(voiceStatus) { ui, (heard, hint) -> ui.copy(heard = heard, hint = hint) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun hasMicPermission() = c.voiceInput.hasPermission()
    fun startVoice() {
        preview.value = null
        engine.startVoice()
    }

    /** Mic button: turns hands-free listening on, or off when it is already on. */
    fun toggleHandsFree() {
        preview.value = null
        when {
            engine.state.value == AssistantState.Sleeping -> engine.wakeUp()
            engine.handsFreeActive.value -> c.stopEverything()
            else -> engine.startHandsFree()
        }
    }

    /** Tap on the reactor: stops her talking, or wakes her from sleep mode. */
    fun tapReactor() {
        if (engine.state.value == AssistantState.Sleeping) engine.wakeUp() else engine.interrupt()
    }

    /** "Turn off" on the Safety Mode banner: calls, WhatsApp, SMS, apps and music work again. */
    fun disableSafetyMode() {
        viewModelScope.launch { c.settingsRepository.setSafetyMode(false) }
    }

    /** "Resume" on the paused banner: un-pause, then listen hands-free if that is enabled. */
    fun resume() {
        viewModelScope.launch {
            c.settingsRepository.setAssistantPaused(false)
            val s = c.currentSettings.first { !it.assistantPaused }
            if (s.autoListen && hasMicPermission()) engine.startHandsFree()
        }
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
