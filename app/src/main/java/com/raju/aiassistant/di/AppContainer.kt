package com.raju.aiassistant.di

import android.content.Context
import com.raju.aiassistant.core.audio.AudioPlayer
import com.raju.aiassistant.core.network.ConnectivityObserver
import com.raju.aiassistant.core.security.SecureKeyStore
import com.raju.aiassistant.data.ai.AiProviderFactory
import com.raju.aiassistant.data.ai.defaultHttpClient
import com.raju.aiassistant.data.db.RajuDatabase
import com.raju.aiassistant.data.prefs.SettingsRepository
import com.raju.aiassistant.data.repository.ActionLogRepository
import com.raju.aiassistant.data.repository.ConversationRepository
import com.raju.aiassistant.data.repository.MemoryRepository
import com.raju.aiassistant.data.search.TavilyClient
import com.raju.aiassistant.data.tools.AndroidDeviceTools
import com.raju.aiassistant.data.tts.AndroidTts
import com.raju.aiassistant.data.tts.AndroidTtsProvider
import com.raju.aiassistant.data.tts.SarvamTtsProvider
import com.raju.aiassistant.data.tts.TtsManager
import com.raju.aiassistant.data.voice.VoiceInputManager
import com.raju.aiassistant.domain.assistant.AnnouncementDeduper
import com.raju.aiassistant.domain.notify.AnnouncementCoordinator
import com.raju.aiassistant.domain.notify.CallDeduper
import com.raju.aiassistant.notifications.ConsentNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import com.raju.aiassistant.domain.assistant.AssistantEngine
import com.raju.aiassistant.domain.model.UserSettings
import com.raju.aiassistant.domain.voice.SpeechPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Manual dependency container: creates and wires every service once per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Lives as long as the process; main-thread by default (SpeechRecognizer requires it). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val database by lazy { RajuDatabase.create(appContext) }
    val http by lazy { defaultHttpClient() }

    val secureKeyStore by lazy { SecureKeyStore(appContext) }
    val settingsRepository by lazy { SettingsRepository(appContext) }
    val currentSettings: StateFlow<UserSettings> by lazy {
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, UserSettings())
    }

    val conversationRepository by lazy { ConversationRepository(database.chatDao()) }
    val memoryRepository by lazy { MemoryRepository(database.memoryDao()) }
    val actionLogRepository by lazy { ActionLogRepository(database.actionLogDao()) }

    val connectivityObserver by lazy { ConnectivityObserver(appContext) }
    val online: StateFlow<Boolean> by lazy { connectivityObserver.isOnline.stateIn(appScope, SharingStarted.Eagerly, true) }

    val providerFactory by lazy { AiProviderFactory(http) }
    val androidTts by lazy { AndroidTts(appContext) }
    val speaker by lazy {
        val providers = listOf(
            SarvamTtsProvider(http, { secureKeyStore.get(it) }, { online.value }, { currentSettings.value.sarvamSpeaker }),
            AndroidTtsProvider(androidTts) { currentSettings.value.androidVoice },
        )
        TtsManager(appScope, SpeechPipeline(AudioPlayer(appContext)), providers, androidTts, currentSettings)
    }
    val voiceInput by lazy { VoiceInputManager(appContext) }
    val deviceTools by lazy { AndroidDeviceTools(appContext) }
    val announcementDeduper = AnnouncementDeduper()
    val callDeduper = CallDeduper()

    /** True while Android has the notification listener connected (not just permission granted). */
    val listenerConnected = MutableStateFlow(false)

    val consentNotifier by lazy { ConsentNotifier(appContext) }
    val announcements by lazy {
        AnnouncementCoordinator(appScope, speaker, currentSettings).also { coordinator ->
            appScope.launch { coordinator.pending.collect { consentNotifier.show(it) } }
        }
    }

    /** Stop button: assistant, speech and any pending message announcement. */
    fun stopEverything() {
        engine.stop()
        announcements.stopAll()
        speaker.stop()
    }

    val engine by lazy {
        AssistantEngine(
            scope = appScope,
            settings = currentSettings,
            secret = { secureKeyStore.get(it) },
            providerFor = { id, key -> providerFactory.create(id, key) },
            searchFor = { key -> TavilyClient(http, key) },
            history = conversationRepository,
            memories = memoryRepository,
            actionLog = actionLogRepository,
            tools = deviceTools,
            speaker = speaker,
            voice = voiceInput,
            isOnline = { online.value },
            nowDescription = { SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date()) },
        ).also { e -> appScope.launch { e.load() } }
    }
}
