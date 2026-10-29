package com.raju.aiassistant.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raju.aiassistant.data.tts.TtsDiagnostics
import com.raju.aiassistant.domain.ai.ModelAdvice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.core.security.maskSecret
import com.raju.aiassistant.data.search.TavilyClient
import com.raju.aiassistant.data.tts.CartesiaClient
import com.raju.aiassistant.data.tts.DeepgramClient
import com.raju.aiassistant.data.tts.ElevenLabsClient
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
import com.raju.aiassistant.domain.model.Personality
import com.raju.aiassistant.domain.model.SttEngine
import com.raju.aiassistant.domain.model.TtsEngine
import com.raju.aiassistant.domain.voice.CloudVoice
import com.raju.aiassistant.domain.voice.VoiceCatalog
import com.raju.aiassistant.domain.voice.VoiceModelOption
import com.raju.aiassistant.domain.model.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

/** Real, current state of everything the assistant depends on. Refreshed every time Settings resumes. */
data class Diagnostics(
    val micGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val speechRecognitionAvailable: Boolean = false,
    /** The user switched Seema AI on in Android's Notification access list. */
    val listenerEnabled: Boolean = false,
    /** Android actually connected the listener (can lag behind or fail even when enabled). */
    val listenerConnected: Boolean = false,
    /** The app may show its own notifications (POST_NOTIFICATIONS on Android 13+ and not blocked in App info). */
    val appNotificationsAllowed: Boolean = false,
    val callRoleSupported: Boolean = false,
    val callRoleHeld: Boolean = false,
    val tts: TtsDiagnostics? = null,
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
    private val _elevenModels = MutableStateFlow(VoiceCatalog.elevenModels)
    val elevenModels: StateFlow<List<VoiceModelOption>> = _elevenModels.asStateFlow()
    private val _elevenVoices = MutableStateFlow<List<CloudVoice>>(emptyList())
    val elevenVoices: StateFlow<List<CloudVoice>> = _elevenVoices.asStateFlow()
    private val _cartesiaVoices = MutableStateFlow<List<CloudVoice>>(emptyList())
    val cartesiaVoices: StateFlow<List<CloudVoice>> = _cartesiaVoices.asStateFlow()
    /** Result of the last voice/model list load (errors are shown under the picker). */
    private val _voiceListMessage = MutableStateFlow<String?>(null)
    val voiceListMessage: StateFlow<String?> = _voiceListMessage.asStateFlow()
    private val _diagnostics = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val ctx = c.appContext
            fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
            // RoleManager exists only on Android 10+ (API 29); keep every use inside the version check.
            var roleSupported = false
            var roleHeld = false
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = ctx.getSystemService(RoleManager::class.java)
                roleSupported = rm?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
                roleHeld = rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
            }
            _diagnostics.value = Diagnostics(
                micGranted = granted(Manifest.permission.RECORD_AUDIO),
                contactsGranted = granted(Manifest.permission.READ_CONTACTS),
                speechRecognitionAvailable = c.voiceInput.isAvailable(),
                listenerEnabled = notificationAccessEnabled(),
                listenerConnected = c.listenerConnected.value,
                appNotificationsAllowed = c.consentNotifier.canPost(),
                callRoleSupported = roleSupported,
                callRoleHeld = roleHeld,
                tts = runCatching { c.androidTts.diagnostics() }.getOrNull(),
            )
        }
    }

    /** System dialog that asks the user to make Seema AI the call-screening app (Android 10+). */
    fun callRoleRequestIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= 29) c.appContext.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING) else null

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
                        _models.value = _models.value + (provider to ModelAdvice.rank(provider, r.value))
                        if (c.currentSettings.value.modelFor(provider) == null) {
                            pickDefaultModel(provider, r.value)?.let { repo.setModel(provider, it.id) }
                        }
                        AiResult.Success("Connected — ${r.value.size} models available.")
                    }
                    is AiResult.Failure -> r
                }
                id == SecretId.TAVILY_API_KEY -> when (val r = TavilyClient(c.http, key).search("Seema AI connection test", 1)) {
                    is AiResult.Success -> AiResult.Success("Connected — search returned ${r.value.results.size} result(s).")
                    is AiResult.Failure -> r
                }
                id == SecretId.ELEVENLABS_API_KEY -> testElevenLabs(key)
                id == SecretId.CARTESIA_API_KEY -> testCartesia(key)
                id == SecretId.DEEPGRAM_API_KEY -> when (val r = DeepgramClient(c.http, key).synthesize("Hello", c.currentSettings.value.deepgramVoice)) {
                    is AiResult.Success -> AiResult.Success("Connected — received ${r.value.size / 1024} KB of audio. Deepgram can now listen (Speech input) and speak English.")
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

    /** Real request with the chosen model and voice; also fills the model and voice pickers. */
    private suspend fun testElevenLabs(key: String): AiResult<String> {
        val client = ElevenLabsClient(c.http, key)
        loadElevenLists(client)
        val s = c.currentSettings.value
        val voice = s.elevenVoiceId ?: VoiceCatalog.bestDefault(_elevenVoices.value)?.also { repo.setElevenVoice(it.id, it.name) }?.id
            ?: VoiceCatalog.ELEVEN_FALLBACK_VOICE
        return when (val r = client.synthesize("Namaste", voice, s.elevenModel, 1f)) {
            is AiResult.Success -> AiResult.Success("Connected — model ${s.elevenModel}, received ${r.value.size / 1024} KB of audio.")
            is AiResult.Failure -> r
        }
    }

    private suspend fun testCartesia(key: String): AiResult<String> {
        val client = CartesiaClient(c.http, key)
        loadCartesiaList(client)
        val s = c.currentSettings.value
        val voice = s.cartesiaVoiceId ?: VoiceCatalog.bestDefault(_cartesiaVoices.value)?.also { repo.setCartesiaVoice(it.id, it.name) }?.id
            ?: return AiResult.Failure(com.raju.aiassistant.domain.ai.FailureKind.UNKNOWN, _voiceListMessage.value ?: "No Cartesia voice found.")
        return when (val r = client.synthesize("Namaste", voice, s.cartesiaModel, "hi", 1f, s.cartesiaMood)) {
            is AiResult.Success -> AiResult.Success("Connected — model ${s.cartesiaModel}, received ${r.value.size / 1024} KB of audio.")
            is AiResult.Failure -> r
        }
    }

    private suspend fun loadElevenLists(client: ElevenLabsClient) {
        val models = client.listModels()
        if (models is AiResult.Success) _elevenModels.value = VoiceCatalog.mergeElevenModels(models.value)
        when (val voices = client.listVoices()) {
            is AiResult.Success -> {
                _elevenVoices.value = VoiceCatalog.sortVoices(voices.value)
                _voiceListMessage.value = "ElevenLabs: ${voices.value.size} voices loaded (female voices first)."
            }
            is AiResult.Failure -> _voiceListMessage.value = "ElevenLabs voices could not be loaded: ${voices.message}"
        }
    }

    private suspend fun loadCartesiaList(client: CartesiaClient) {
        when (val voices = client.listVoices()) {
            is AiResult.Success -> {
                _cartesiaVoices.value = VoiceCatalog.sortVoices(voices.value)
                _voiceListMessage.value = "Cartesia: ${voices.value.size} female voices loaded (Hindi voices first)."
            }
            is AiResult.Failure -> _voiceListMessage.value = "Cartesia voices could not be loaded: ${voices.message}"
        }
    }

    fun loadElevenLabs() = launch {
        val key = c.secureKeyStore.get(SecretId.ELEVENLABS_API_KEY)
        if (key == null) {
            _voiceListMessage.value = "Save an ElevenLabs key first (card below)."
            return@launch
        }
        loadElevenLists(ElevenLabsClient(c.http, key))
    }

    fun loadCartesia() = launch {
        val key = c.secureKeyStore.get(SecretId.CARTESIA_API_KEY)
        if (key == null) {
            _voiceListMessage.value = "Save a Cartesia key first (card below)."
            return@launch
        }
        loadCartesiaList(CartesiaClient(c.http, key))
    }
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
    fun setElevenModel(v: String) = launch { repo.setElevenModel(v) }
    fun setElevenVoice(v: CloudVoice) = launch { repo.setElevenVoice(v.id, v.name) }
    fun setCartesiaModel(v: String) = launch { repo.setCartesiaModel(v) }
    fun setCartesiaVoice(v: CloudVoice) = launch { repo.setCartesiaVoice(v.id, v.name) }
    fun setCartesiaMood(v: String) = launch { repo.setCartesiaMood(v) }
    fun setDeepgramVoice(v: String) = launch { repo.setDeepgramVoice(v) }
    fun setSttEngine(v: SttEngine) = launch { repo.setSttEngine(v) }
    fun setDeepgramSttModel(v: String) = launch { repo.setDeepgramSttModel(v) }
    fun setSpeechRate(v: Float) = launch { repo.setSpeechRate(v) }
    fun setAndroidVoice(v: String?) = launch { repo.setAndroidVoice(v) }
    fun setSpeechLanguage(v: String) = launch { repo.setSpeechLanguage(v) }
    fun setAutoSpeak(v: Boolean) = launch { repo.setAutoSpeak(v) }
    fun setContinuous(v: Boolean) = launch { repo.setContinuous(v) }
    fun setAutoListen(v: Boolean) = launch {
        repo.setAutoListen(v)
        if (!v) c.engine.stop()
    }
    fun setPersonality(v: Personality) = launch { repo.setPersonality(v) }
    fun setAnnounce(v: Boolean) = launch { repo.setAnnounce(v) }
    fun setAnnounceApp(pkg: String, v: Boolean) = launch { repo.setAnnounceApp(pkg, v) }
    fun setAskBeforeReading(v: Boolean) = launch { repo.setAskBeforeReading(v) }
    fun setAnnounceCalls(v: Boolean) = launch { repo.setAnnounceCalls(v) }
    fun setSpeakCallerNumber(v: Boolean) = launch { repo.setSpeakCallerNumber(v) }
    fun setAnnounceAppCalls(v: Boolean) = launch { repo.setAnnounceAppCalls(v) }
    fun setDirectCalling(v: Boolean) = launch { repo.setDirectCalling(v) }
    fun setMusicService(v: MusicService) = launch { repo.setMusicService(v) }
    fun setMemoryEnabled(v: Boolean) = launch { repo.setMemoryEnabled(v) }
    fun setSafetyMode(v: Boolean) = launch {
        repo.setSafetyMode(v)
        if (v) c.stopEverything()
    }
    fun setPaused(v: Boolean) = launch {
        repo.setAssistantPaused(v)
        if (v) {
            c.stopEverything()
        } else {
            val s = c.currentSettings.first { !it.assistantPaused }
            if (s.autoListen && c.voiceInput.hasPermission()) c.engine.startHandsFree()
        }
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
