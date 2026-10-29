package com.raju.aiassistant.domain.model

import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.voice.VoiceCatalog

/**
 * Speech output engines. The order is also the fallback order after the chosen one (see providerOrder):
 * cloud voices first, the offline Android voice always last.
 */
enum class TtsEngine(val label: String, val hint: String) {
    SARVAM("Sarvam AI — Bulbul", "Indian voices, best Hindi pronunciation, cheap"),
    ELEVENLABS("ElevenLabs", "★ most natural and sweet voice; Hindi + English"),
    CARTESIA("Cartesia Sonic", "Natural voice with moods; Hindi + English"),
    DEEPGRAM("Deepgram Aura", "English only — Hindi replies use the next voice"),
    ANDROID("Android TTS (offline)", "Free, works without internet"),
}

/** Speech input (listening) engines. */
enum class SttEngine(val label: String, val hint: String) {
    ANDROID("Android / Google (free)", "Built in; works well for clear Hindi or English"),
    DEEPGRAM("Deepgram Nova-3", "★ better for Hinglish (Hindi + English mixed); needs a Deepgram key"),
}

enum class MusicService(val label: String) { YOUTUBE_MUSIC("YouTube Music"), SPOTIFY("Spotify") }

/** Apps whose notifications can be announced. */
enum class AnnounceApp(val packageName: String, val label: String) {
    WHATSAPP("com.whatsapp", "WhatsApp"),
    WHATSAPP_BUSINESS("com.whatsapp.w4b", "WhatsApp Business"),
    TELEGRAM("org.telegram.messenger", "Telegram"),
    GOOGLE_MESSAGES("com.google.android.apps.messaging", "Google Messages"),
    SAMSUNG_MESSAGES("com.samsung.android.messaging", "Samsung Messages"),
}

/** Result of the last real connection test for a provider. */
data class ConnectionTest(val success: Boolean, val message: String, val timestamp: Long)

/** Non-secret user preferences (persisted in DataStore). */
data class UserSettings(
    val defaultProvider: ProviderId = ProviderId.GEMINI,
    val models: Map<ProviderId, String> = emptyMap(),
    val lastTests: Map<ProviderId, ConnectionTest> = emptyMap(),
    val lowPowerMode: Boolean = false,
    val reducedMotion: Boolean = false,
    val saveHistory: Boolean = true,
    val historyLimit: Int = 20,
    val ttsEngine: TtsEngine = TtsEngine.SARVAM,
    val sarvamSpeaker: String = SARVAM_FEMALE_SPEAKERS.first(),
    val elevenModel: String = VoiceCatalog.ELEVEN_DEFAULT_MODEL,
    /** null = not chosen yet: the best female voice of the account is picked on first use. */
    val elevenVoiceId: String? = null,
    val elevenVoiceName: String? = null,
    val cartesiaModel: String = VoiceCatalog.CARTESIA_DEFAULT_MODEL,
    val cartesiaVoiceId: String? = null,
    val cartesiaVoiceName: String? = null,
    /** Cartesia Sonic 3 emotion, "" = natural. */
    val cartesiaMood: String = "",
    val deepgramVoice: String = VoiceCatalog.DEEPGRAM_DEFAULT_VOICE,
    val sttEngine: SttEngine = SttEngine.ANDROID,
    val deepgramSttModel: String = VoiceCatalog.DEEPGRAM_DEFAULT_STT,
    val speechRate: Float = 1.0f,
    val androidVoice: String? = null,
    val speechLanguage: String = "hi-IN",
    val autoSpeakReplies: Boolean = true,
    val continuousConversation: Boolean = false,
    val announceNotifications: Boolean = false,
    val announceApps: Set<String> = setOf(AnnounceApp.WHATSAPP.packageName, AnnounceApp.WHATSAPP_BUSINESS.packageName),
    /** Say only who wrote, and read the message only after the user taps Read. */
    val askBeforeReading: Boolean = true,
    /** Announce incoming phone (SIM) calls. Needs the call-screening role. Off until the user opts in. */
    val announceCalls: Boolean = false,
    /** Speak the number of callers who are not in Contacts (otherwise "unknown number"). */
    val speakCallerNumber: Boolean = false,
    /** Best effort: announce WhatsApp/Telegram calls from their call notifications. */
    val announceAppCalls: Boolean = false,
    val directCalling: Boolean = false,
    val musicService: MusicService = MusicService.YOUTUBE_MUSIC,
    val memoryEnabled: Boolean = true,
    val safetyMode: Boolean = false,
    val assistantPaused: Boolean = false,
) {
    fun modelFor(provider: ProviderId): String? = models[provider]

    /** Notification announcements are allowed right now. */
    val announcementsActive: Boolean get() = announceNotifications && !assistantPaused && !safetyMode
}

/** Female voices of Sarvam Bulbul v3 (names are lowercase and case-sensitive). The first one is the default. */
val SARVAM_FEMALE_SPEAKERS = listOf("priya", "ritu", "kavya", "neha", "simran", "ishita", "shreya", "pooja", "roopa")

/** A saved Bulbul v2 voice (anushka, manisha, ...) is rejected by v3, so it falls back to the default. */
fun validSarvamSpeaker(saved: String?): String = saved?.takeIf { it in SARVAM_FEMALE_SPEAKERS } ?: SARVAM_FEMALE_SPEAKERS.first()
val SPEECH_LANGUAGES = listOf("hi-IN" to "Hindi / Hinglish", "en-IN" to "English (India)")
