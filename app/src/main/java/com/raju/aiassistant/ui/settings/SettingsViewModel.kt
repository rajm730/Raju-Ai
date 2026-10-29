package com.raju.aiassistant.ui.settings

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.core.security.maskSecret
import com.raju.aiassistant.data.search.TavilyClient
import com.raju.aiassistant.data.tts.SarvamTtsClient
import com.raju.aiassistant.data.tts.VoiceOption
import com.raju.aiassistant.di.AppContainer
import com.raju.aiassistant.domain.ai.AiModel
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.ai.pickDefaultModel
import com.raju.aiassistant.domain.model.ConnectionTest
import com.raju.aiassistant.domain.model.MusicService
import com.raju.aiassistant.domain.model.SecretId
import com.raju.aiassistant.domain.model.TtsEngine
import com.raju.aiassistant.domain.model.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SecretStatus(
    val configured: Boolean = false,
    /** Masked preview of the saved key, e.g. "••••••••abcd". Never the full key. */
    val maskedValue: String = "",
    val message: String? = null,
    val isError: Boolean = false,
    val busy: Boolean = false,
)

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val secrets: Map<SecretId, SecretStatus> = emptyMap(),
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.settingsRepository
    private val messages = MutableStateFlow<Map<SecretId, SecretStatus>>(emptyMap())
    private val masked = MutableStateFlow<Map<SecretId, String>>(emptyMap())

    private val _models = MutableStateFlow<Map<ProviderId, List<AiModel>>>(emptyMap())
    val models: StateFlow<Map<ProviderId, List<AiModel>>> = _models.asStateFlow()
    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()
    private val _voiceTest = MutableStateFlow<String?>(null)
    val voiceTest: StateFlow<String?> = _voiceTest.asStateFlow()
    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()
    val activeTtsEngine: StateFlow<String?> = c.speaker.activeEngine

    val uiState: StateFlow<SettingsUiState> = combine(c.currentSettings, c.secureKeyStore.configured, masked, messages) { settings, configured, maskedValues, msgs ->
        SettingsUiState(
            settings = settings,
            secrets = SecretId.entries.associateWith { id ->
                (msgs[id] ?: SecretStatus()).copy(configured = id in configured, maskedValue = maskedValues[id].orEmpty())
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { refreshMasks() }
    }

    // ------------------------------------------------------------ secrets

    fun saveSecret(id: SecretId, value: String) {
        if (value.isBlank()) return setStatus(id, "Enter a key first.", true)
        viewModelScope.launch {
            val ok = runCatching { c.secureKeyStore.save(id, value) }.getOrDefault(false)
            refreshMasks()
            if (ok) setStatus(id, "Saved and encrypted on this device. Tap Test to check it.", false)
            else setStatus(id, "Save failed. The key was not stored.", true)
        }
    }

    fun removeSecret(id: SecretId) {
        viewModelScope.launch {
            val ok = c.secureKeyStore.remove(id)
            refreshMasks()
            setStatus(id, if (ok) "Key removed." else "Could not remove key.", !ok)
        }
    }

    /** Real authenticated request; the result (and only a real success) is shown as Connected. */
    fun testSecret(id: SecretId) {
        viewModelScope.launch {
            val key = c.secureKeyStore.get(id) ?: return@launch setStatus(id, "No key saved.", true)
            setStatus(id, "Testing…", false, busy = true)
            val provider = ProviderId.entries.firstOrNull { it.secret == id }
            val result: AiResult<String> = when {
                provider != null -> when (val r = c.providerFactory.create(provider, key).listModels()) {
                    is AiResult.Success -> {
                        _models.value = _models.value + (provider to r.value)
                        if (c.currentSettings.value.modelFor(provider) == null) {
                            pickDefaultModel(provider, r.value)?.let { repo.setModel(provider, it.id) }
                        }
                        AiResult.Success("Connected — ${r.value.size} models available.")
                    }
                    is AiResult.Failure -> r
                }
                id == SecretId.TAVILY_API_KEY -> when (val r = TavilyClient(c.http, key).search("Raju AI connection test", 1)) {
                    is AiResult.Success -> AiResult.Success("Connected — search returned ${r.value.results.size} result(s).")
                    is AiResult.Failure -> r
                }
                else -> when (val r = SarvamTtsClient(c.http, key).synthesize("Namaste", c.currentSettings.value.sarvamSpeaker, "hi-IN", 1f)) {
                    is AiResult.Success -> AiResult.Success("Connected — received ${r.value.size / 1024} KB of audio.")
                    is AiResult.Failure -> r
                }
            }
            when (result) {
                is AiResult.Success -> setStatus(id, result.value, false)
                is AiResult.Failure -> setStatus(id, result.message, true)
            }
            if (provider != null) {
                repo.setLastTest(provider, ConnectionTest(result is AiResult.Success, (result as? AiResult.Failure)?.message ?: "OK", System.currentTimeMillis()))
            }
        }
    }

    fun loadModels(provider: ProviderId) = testSecret(provider.secret)
    fun selectModel(provider: ProviderId, model: String) = launch { repo.setModel(provider, model) }

    // ------------------------------------------------------------ voice

    fun loadVoices() = launch { _voices.value = c.androidTts.voices() }

    fun testVoice() {
        _voiceTest.value = "Playing test voice…"
        c.speaker.test { r ->
            _voiceTest.value = when {
                !r.completed && r.error != null -> "Voice test failed: ${r.error}"
                !r.completed -> "Stopped."
                r.error != null -> "Played with ${r.engineLabel}. ${r.error}"
                else -> "Played with ${r.engineLabel}."
            }
        }
    }

    fun stopVoice() = c.speaker.stop()

    // ------------------------------------------------------------ preferences

    fun setDefaultProvider(v: ProviderId) = launch { repo.setDefaultProvider(v) }
    fun setTtsEngine(v: TtsEngine) = launch { repo.setTtsEngine(v) }
    fun setSarvamSpeaker(v: String) = launch { repo.setSarvamSpeaker(v) }
    fun setSpeechRate(v: Float) = launch { repo.setSpeechRate(v) }
    fun setAndroidVoice(v: String?) = launch { repo.setAndroidVoice(v) }
    fun setSpeechLanguage(v: String) = launch { repo.setSpeechLanguage(v) }
    fun setAutoSpeak(v: Boolean) = launch { repo.setAutoSpeak(v) }
    fun setContinuous(v: Boolean) = launch { repo.setContinuous(v) }
    fun setAnnounce(v: Boolean) = launch { repo.setAnnounce(v) }
    fun setAnnounceApp(pkg: String, v: Boolean) = launch { repo.setAnnounceApp(pkg, v) }
    fun setDirectCalling(v: Boolean) = launch { repo.setDirectCalling(v) }
    fun setMusicService(v: MusicService) = launch { repo.setMusicService(v) }
    fun setMemoryEnabled(v: Boolean) = launch { repo.setMemoryEnabled(v) }
    fun setSafetyMode(v: Boolean) = launch {
        repo.setSafetyMode(v)
        if (v) c.engine.stop()
    }
    fun setPaused(v: Boolean) = launch {
        repo.setAssistantPaused(v)
        if (v) c.engine.stop()
    }
    fun setLowPowerMode(v: Boolean) = launch { repo.setLowPowerMode(v) }
    fun setReducedMotion(v: Boolean) = launch { repo.setReducedMotion(v) }
    fun setSaveHistory(v: Boolean) = launch { repo.setSaveHistory(v) }
    fun setHistoryLimit(v: Int) = launch { repo.setHistoryLimit(v) }

    fun notificationAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(c.appContext).contains(c.appContext.packageName)

    fun clearChatHistory() = launch {
        _info.value = runCatching { c.conversationRepository.clear(); c.engine.clearTranscript() }
            .fold({ "Chat history deleted." }, { "Could not delete history: ${it.message}" })
    }

    fun clearMemories() = launch {
        _info.value = runCatching { c.memoryRepository.deleteAll() }.fold({ "All memories deleted." }, { "Could not delete memories." })
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun refreshMasks() {
        masked.value = SecretId.entries.mapNotNull { id -> c.secureKeyStore.get(id)?.let { id to maskSecret(it) } }.toMap()
    }

    private fun setStatus(id: SecretId, text: String, isError: Boolean, busy: Boolean = false) {
        messages.value = messages.value + (id to SecretStatus(message = text, isError = isError, busy = busy))
    }
}
