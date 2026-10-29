package com.raju.aiassistant.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.core.security.SecureKeyStore
import com.raju.aiassistant.data.prefs.SettingsRepository
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.model.AssistantState
import com.raju.aiassistant.domain.model.SecretId
import kotlinx.coroutines.flow.Flow
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
    val defaultProvider: ProviderId = ProviderId.GEMINI,
    /** True only when an API key for the default provider has been saved. Not a connection test. */
    val providerKeySaved: Boolean = false,
    val lowPowerMode: Boolean = false,
    val reducedMotion: Boolean = false,
)

class HomeViewModel(
    keyStore: SecureKeyStore,
    settingsRepository: SettingsRepository,
    isOnline: Flow<Boolean>,
) : ViewModel() {

    private val preview = MutableStateFlow<AssistantState?>(null)

    val uiState: StateFlow<HomeUiState> =
        combine(keyStore.configured, settingsRepository.settings, isOnline, preview) { configured, settings, online, previewState ->
            HomeUiState(
                assistantState = previewState ?: if (online) AssistantState.Idle else AssistantState.Offline,
                previewState = previewState,
                online = online,
                defaultProvider = settings.defaultProvider,
                providerKeySaved = secretFor(settings.defaultProvider) in configured,
                lowPowerMode = settings.lowPowerMode,
                reducedMotion = settings.reducedMotion,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Shows a state's visuals for testing the UI. Pass null to return to the real state. */
    fun setPreview(state: AssistantState?) {
        preview.value = state
    }

    /** Stop: ends whatever the assistant is doing. Until Phase 3/4 that is only a visual preview. */
    fun stop() {
        preview.value = null
    }

    private fun secretFor(provider: ProviderId): SecretId = when (provider) {
        ProviderId.GEMINI -> SecretId.GEMINI_API_KEY
        ProviderId.GROK -> SecretId.GROK_API_KEY
    }
}
