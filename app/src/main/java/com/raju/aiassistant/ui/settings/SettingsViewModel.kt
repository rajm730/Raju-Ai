package com.raju.aiassistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.core.security.SecureKeyStore
import com.raju.aiassistant.core.security.maskSecret
import com.raju.aiassistant.data.prefs.SettingsRepository
import com.raju.aiassistant.data.prefs.UserSettings
import com.raju.aiassistant.data.repository.ConversationRepository
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.model.SecretId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SecretStatus(
    val configured: Boolean = false,
    /** Masked preview of the saved key, e.g. "••••••••abcd". Never the full key. */
    val maskedValue: String = "",
    val message: String? = null,
    val isError: Boolean = false,
)

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val secrets: Map<SecretId, SecretStatus> = emptyMap(),
    val historyMessage: String? = null,
)

class SettingsViewModel(
    private val keyStore: SecureKeyStore,
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    private val messages = MutableStateFlow<Map<SecretId, Pair<String, Boolean>>>(emptyMap())
    private val masked = MutableStateFlow<Map<SecretId, String>>(emptyMap())
    private val historyMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings, keyStore.configured, masked, messages, historyMessage,
    ) { settings, configured, maskedValues, msgs, historyMsg ->
        SettingsUiState(
            settings = settings,
            secrets = SecretId.entries.associateWith { id ->
                SecretStatus(
                    configured = id in configured,
                    maskedValue = maskedValues[id].orEmpty(),
                    message = msgs[id]?.first,
                    isError = msgs[id]?.second ?: false,
                )
            },
            historyMessage = historyMsg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { refreshMasks() }
    }

    fun saveSecret(id: SecretId, value: String) {
        if (value.isBlank()) {
            setMessage(id, "Enter a key first.", isError = true)
            return
        }
        viewModelScope.launch {
            val ok = try {
                keyStore.save(id, value)
            } catch (e: Exception) {
                false
            }
            refreshMasks()
            if (ok) setMessage(id, "Saved and encrypted on this device. Connection not tested yet.", false)
            else setMessage(id, "Save failed. The key was not stored.", true)
        }
    }

    fun removeSecret(id: SecretId) {
        viewModelScope.launch {
            val ok = keyStore.remove(id)
            refreshMasks()
            setMessage(id, if (ok) "Key removed." else "Could not remove key.", !ok)
        }
    }

    fun setDefaultProvider(provider: ProviderId) {
        viewModelScope.launch { settingsRepository.setDefaultProvider(provider) }
    }

    fun setLowPowerMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setLowPowerMode(enabled) }
    }

    fun setReducedMotion(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setReducedMotion(enabled) }
    }

    fun setSaveHistory(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSaveHistory(enabled) }
    }


    fun clearHistory() {
        viewModelScope.launch {
            historyMessage.value = try {
                conversationRepository.clearAll()
                "Chat history deleted."
            } catch (e: Exception) {
                "Could not delete history: ${e.message}"
            }
        }
    }

    private suspend fun refreshMasks() {
        masked.value = SecretId.entries.mapNotNull { id -> keyStore.get(id)?.let { id to maskSecret(it) } }.toMap()
    }

    private fun setMessage(id: SecretId, text: String, isError: Boolean) {
        messages.value = messages.value + (id to (text to isError))
    }
}
