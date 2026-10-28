package com.raju.aiassistant.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.core.security.SecureKeyStore
import com.raju.aiassistant.data.prefs.SettingsRepository
import com.raju.aiassistant.data.prefs.UserSettings
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.model.AssistantState
import com.raju.aiassistant.domain.model.SecretId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val assistantState: AssistantState = AssistantState.Idle,
    val defaultProvider: ProviderId = ProviderId.GEMINI,
    /** True only when an API key for the default provider has been saved. Not a connection test. */
    val providerKeySaved: Boolean = false,
    val reducedMotion: Boolean = false,
)

class HomeViewModel(
    keyStore: SecureKeyStore,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(keyStore.configured, settingsRepository.settings) { configured, settings ->
            HomeUiState(
                assistantState = AssistantState.Idle,
                defaultProvider = settings.defaultProvider,
                providerKeySaved = settings.providerSecret() in configured,
                reducedMotion = settings.reducedMotion || settings.lowPowerMode,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun UserSettings.providerSecret(): SecretId = when (defaultProvider) {
        ProviderId.GEMINI -> SecretId.GEMINI_API_KEY
        ProviderId.GROK -> SecretId.GROK_API_KEY
    }
}
