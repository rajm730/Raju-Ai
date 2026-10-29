package com.raju.aiassistant.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.model.ConnectionTest
import com.raju.aiassistant.domain.model.MusicService
import com.raju.aiassistant.domain.model.TtsEngine
import com.raju.aiassistant.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "raju_settings")

/** Non-secret user preferences. Secrets go to [com.raju.aiassistant.core.security.SecureKeyStore]. */
class SettingsRepository(private val context: Context) {

    val settings: Flow<UserSettings> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> p.toSettings() }

    private fun Preferences.toSettings(): UserSettings {
        val d = UserSettings()
        return UserSettings(
            defaultProvider = enumOr(this[Keys.DEFAULT_PROVIDER], d.defaultProvider),
            models = ProviderId.entries.mapNotNull { id -> this[modelKey(id)]?.let { id to it } }.toMap(),
            lastTests = ProviderId.entries.mapNotNull { id -> this[testKey(id)]?.let { decodeTest(it) }?.let { id to it } }.toMap(),
            lowPowerMode = this[Keys.LOW_POWER] ?: d.lowPowerMode,
            reducedMotion = this[Keys.REDUCED_MOTION] ?: d.reducedMotion,
            saveHistory = this[Keys.SAVE_HISTORY] ?: d.saveHistory,
            historyLimit = this[Keys.HISTORY_LIMIT] ?: d.historyLimit,
            ttsEngine = enumOr(this[Keys.TTS_ENGINE], d.ttsEngine),
            sarvamSpeaker = this[Keys.SARVAM_SPEAKER] ?: d.sarvamSpeaker,
            speechRate = this[Keys.SPEECH_RATE] ?: d.speechRate,
            androidVoice = this[Keys.ANDROID_VOICE],
            speechLanguage = this[Keys.SPEECH_LANGUAGE] ?: d.speechLanguage,
            autoSpeakReplies = this[Keys.AUTO_SPEAK] ?: d.autoSpeakReplies,
            continuousConversation = this[Keys.CONTINUOUS] ?: d.continuousConversation,
            announceNotifications = this[Keys.ANNOUNCE] ?: d.announceNotifications,
            announceApps = this[Keys.ANNOUNCE_APPS] ?: d.announceApps,
            askBeforeReading = this[Keys.ASK_BEFORE_READING] ?: d.askBeforeReading,
            announceCalls = this[Keys.ANNOUNCE_CALLS] ?: d.announceCalls,
            speakCallerNumber = this[Keys.SPEAK_CALLER_NUMBER] ?: d.speakCallerNumber,
            announceAppCalls = this[Keys.ANNOUNCE_APP_CALLS] ?: d.announceAppCalls,
            directCalling = this[Keys.DIRECT_CALLING] ?: d.directCalling,
            musicService = enumOr(this[Keys.MUSIC_SERVICE], d.musicService),
            memoryEnabled = this[Keys.MEMORY] ?: d.memoryEnabled,
            safetyMode = this[Keys.SAFETY] ?: d.safetyMode,
            assistantPaused = this[Keys.PAUSED] ?: d.assistantPaused,
        )
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }

    suspend fun setDefaultProvider(v: ProviderId) = edit { it[Keys.DEFAULT_PROVIDER] = v.name }
    suspend fun setModel(provider: ProviderId, model: String) = edit { it[modelKey(provider)] = model }
    suspend fun setLastTest(provider: ProviderId, test: ConnectionTest) = edit { it[testKey(provider)] = encodeTest(test) }
    suspend fun setLowPowerMode(v: Boolean) = edit { it[Keys.LOW_POWER] = v }
    suspend fun setReducedMotion(v: Boolean) = edit { it[Keys.REDUCED_MOTION] = v }
    suspend fun setSaveHistory(v: Boolean) = edit { it[Keys.SAVE_HISTORY] = v }
    suspend fun setHistoryLimit(v: Int) = edit { it[Keys.HISTORY_LIMIT] = v.coerceIn(4, 60) }
    suspend fun setTtsEngine(v: TtsEngine) = edit { it[Keys.TTS_ENGINE] = v.name }
    suspend fun setSarvamSpeaker(v: String) = edit { it[Keys.SARVAM_SPEAKER] = v }
    suspend fun setSpeechRate(v: Float) = edit { it[Keys.SPEECH_RATE] = v.coerceIn(0.5f, 2f) }
    suspend fun setAndroidVoice(v: String?) = edit { if (v == null) it.remove(Keys.ANDROID_VOICE) else it[Keys.ANDROID_VOICE] = v }
    suspend fun setSpeechLanguage(v: String) = edit { it[Keys.SPEECH_LANGUAGE] = v }
    suspend fun setAutoSpeak(v: Boolean) = edit { it[Keys.AUTO_SPEAK] = v }
    suspend fun setContinuous(v: Boolean) = edit { it[Keys.CONTINUOUS] = v }
    suspend fun setAnnounce(v: Boolean) = edit { it[Keys.ANNOUNCE] = v }
    suspend fun setAnnounceApp(pkg: String, enabled: Boolean) = edit {
        val current = it[Keys.ANNOUNCE_APPS] ?: UserSettings().announceApps
        it[Keys.ANNOUNCE_APPS] = if (enabled) current + pkg else current - pkg
    }
    suspend fun setAskBeforeReading(v: Boolean) = edit { it[Keys.ASK_BEFORE_READING] = v }
    suspend fun setAnnounceCalls(v: Boolean) = edit { it[Keys.ANNOUNCE_CALLS] = v }
    suspend fun setSpeakCallerNumber(v: Boolean) = edit { it[Keys.SPEAK_CALLER_NUMBER] = v }
    suspend fun setAnnounceAppCalls(v: Boolean) = edit { it[Keys.ANNOUNCE_APP_CALLS] = v }
    suspend fun setDirectCalling(v: Boolean) = edit { it[Keys.DIRECT_CALLING] = v }
    suspend fun setMusicService(v: MusicService) = edit { it[Keys.MUSIC_SERVICE] = v.name }
    suspend fun setMemoryEnabled(v: Boolean) = edit { it[Keys.MEMORY] = v }
    suspend fun setSafetyMode(v: Boolean) = edit { it[Keys.SAFETY] = v }
    suspend fun setAssistantPaused(v: Boolean) = edit { it[Keys.PAUSED] = v }

    private fun modelKey(p: ProviderId) = stringPreferencesKey("model_${p.name.lowercase()}")
    private fun testKey(p: ProviderId) = stringPreferencesKey("last_test_${p.name.lowercase()}")

    private fun encodeTest(t: ConnectionTest) = "${if (t.success) 1 else 0}|${t.timestamp}|${t.message.replace('|', '/')}"
    private fun decodeTest(s: String): ConnectionTest? {
        val parts = s.split('|', limit = 3)
        if (parts.size != 3) return null
        return ConnectionTest(parts[0] == "1", parts[2], parts[1].toLongOrNull() ?: return null)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private object Keys {
        val DEFAULT_PROVIDER = stringPreferencesKey("default_provider")
        val LOW_POWER = booleanPreferencesKey("low_power_mode")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val HISTORY_LIMIT = intPreferencesKey("history_limit")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val SARVAM_SPEAKER = stringPreferencesKey("sarvam_speaker")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val ANDROID_VOICE = stringPreferencesKey("android_voice")
        val SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        val CONTINUOUS = booleanPreferencesKey("continuous_conversation")
        val ANNOUNCE = booleanPreferencesKey("announce_notifications")
        val ANNOUNCE_APPS = stringSetPreferencesKey("announce_apps")
        val DIRECT_CALLING = booleanPreferencesKey("direct_calling")
        val ASK_BEFORE_READING = booleanPreferencesKey("ask_before_reading")
        val ANNOUNCE_CALLS = booleanPreferencesKey("announce_calls")
        val SPEAK_CALLER_NUMBER = booleanPreferencesKey("speak_caller_number")
        val ANNOUNCE_APP_CALLS = booleanPreferencesKey("announce_app_calls")
        val MUSIC_SERVICE = stringPreferencesKey("music_service")
        val MEMORY = booleanPreferencesKey("memory_enabled")
        val SAFETY = booleanPreferencesKey("safety_mode")
        val PAUSED = booleanPreferencesKey("assistant_paused")
    }
}
