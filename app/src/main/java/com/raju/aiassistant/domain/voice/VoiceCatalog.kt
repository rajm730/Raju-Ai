package com.raju.aiassistant.domain.voice

/** A model the user can pick for a cloud voice. [note] says in one line when to choose it. */
data class VoiceModelOption(val id: String, val title: String, val note: String, val recommended: Boolean = false)

/** A voice offered by a cloud provider (loaded from the user's account). */
data class CloudVoice(val id: String, val name: String, val detail: String = "", val female: Boolean = false, val hindi: Boolean = false) {
    val label: String get() = name + (if (hindi) " · Hindi" else "") + (if (detail.isNotBlank()) " — $detail" else "")
}

/** Deepgram speech-to-text choice: "model:language", e.g. "nova-3:multi". */
data class SttModelOption(val id: String, val title: String, val note: String, val recommended: Boolean = false) {
    val model: String get() = id.substringBefore(':')
    val language: String get() = id.substringAfter(':', "multi")
}

/**
 * Models, voices and recommendations for the cloud voice providers. Recommendations are for this app:
 * a Hindi/Hinglish assistant that speaks short replies sentence by sentence, often on a free plan.
 */
object VoiceCatalog {
    // ---------------------------------------------------------------- ElevenLabs
    const val ELEVEN_DEFAULT_MODEL = "eleven_multilingual_v2"
    /** "Sarah", one of ElevenLabs' default (premade) female voices; used until the user picks one. */
    const val ELEVEN_FALLBACK_VOICE = "EXAVITQu4vr4xnSDxMaL"

    val elevenModels = listOf(
        VoiceModelOption("eleven_multilingual_v2", "Multilingual v2", "सबसे प्राकृतिक और मीठी Hindi आवाज़ · 1 credit/अक्षर", recommended = true),
        VoiceModelOption("eleven_flash_v2_5", "Flash v2.5", "सबसे तेज़, आधे credits · free plan पर ज़्यादा देर चलेगा", recommended = true),
        VoiceModelOption("eleven_v3_conversational", "v3 Conversational", "भावपूर्ण बातचीत, तेज़ · आधे credits"),
        VoiceModelOption("eleven_turbo_v2_5", "Turbo v2.5", "तेज़, आधे credits"),
        VoiceModelOption("eleven_v3", "v3", "सबसे भावपूर्ण, पर धीमा · 1 credit/अक्षर"),
        VoiceModelOption("eleven_v4_turbo", "v4 Turbo", "नया, बहुत तेज़ · plan के हिसाब से"),
        VoiceModelOption("eleven_v4", "v4", "नया · plan के हिसाब से"),
    )

    /**
     * Known models first (in recommended order), then any other text-to-speech model the account offers.
     * [available] is (id, name) from the API; when it is empty (not loaded yet) the known list is shown.
     */
    fun mergeElevenModels(available: List<Pair<String, String>>): List<VoiceModelOption> {
        if (available.isEmpty()) return elevenModels
        val ids = available.map { it.first }.toSet()
        val known = elevenModels.filter { it.id in ids }
        val extra = available.filter { (id, _) -> elevenModels.none { it.id == id } }
            .map { (id, name) -> VoiceModelOption(id, name.ifBlank { id }, "आपके account में उपलब्ध") }
        return known + extra
    }

    // ---------------------------------------------------------------- Cartesia
    const val CARTESIA_DEFAULT_MODEL = "sonic-3"
    val cartesiaModels = listOf(
        VoiceModelOption("sonic-3", "Sonic 3", "Hindi समझता है, भाव (mood) के साथ · 1 credit/अक्षर", recommended = true),
        VoiceModelOption("sonic-3.5", "Sonic 3.5", "सबसे नया, ज़्यादा प्राकृतिक"),
        VoiceModelOption("sonic-latest", "Sonic latest", "हमेशा Cartesia का सबसे नया model"),
    )

    /** Cartesia Sonic 3 emotions that suit a friendly assistant. "" = natural (no emotion hint). */
    val cartesiaMoods = listOf("" to "Natural", "content" to "Khush", "calm" to "Shaant", "affectionate" to "Pyaar bhari", "happy" to "Excited")

    // ---------------------------------------------------------------- Deepgram
    const val DEEPGRAM_DEFAULT_VOICE = "aura-2-thalia-en"
    /** Deepgram Aura-2 female voices. Aura has no Hindi voice, so these read English only. */
    val deepgramVoices = listOf(
        VoiceModelOption("aura-2-thalia-en", "Thalia", "साफ़, गर्मजोशी भरी", recommended = true),
        VoiceModelOption("aura-2-andromeda-en", "Andromeda", "नरम, सहज", recommended = true),
        VoiceModelOption("aura-2-helena-en", "Helena", "देखभाल करने वाली, मधुर"),
        VoiceModelOption("aura-2-asteria-en", "Asteria", "स्पष्ट, भरोसेमंद"),
        VoiceModelOption("aura-2-luna-en", "Luna", "हल्की, दोस्ताना"),
        VoiceModelOption("aura-2-cora-en", "Cora", "शांत"),
        VoiceModelOption("aura-2-iris-en", "Iris", "खुशमिज़ाज"),
        VoiceModelOption("aura-2-aurora-en", "Aurora", "ऊर्जावान"),
        VoiceModelOption("aura-2-delia-en", "Delia", "मीठी, बातचीत वाली"),
        VoiceModelOption("aura-2-harmonia-en", "Harmonia", "सौम्य"),
    )

    const val DEEPGRAM_DEFAULT_STT = "nova-3:multi"
    val deepgramSttModels = listOf(
        SttModelOption("nova-3:multi", "Nova-3 Hinglish", "Hindi + English मिलाकर बोलें तो सबसे अच्छा", recommended = true),
        SttModelOption("nova-3:hi", "Nova-3 Hindi", "सिर्फ़ Hindi बोलते हों तो"),
        SttModelOption("nova-2:hi", "Nova-2 Hindi", "पुराना model, backup के लिए"),
        SttModelOption("nova-3:en", "Nova-3 English", "सिर्फ़ English"),
    )

    fun sttOption(id: String): SttModelOption = deepgramSttModels.firstOrNull { it.id == id } ?: deepgramSttModels.first()

    /** Female voices first, Hindi-capable ones before others, then by name. */
    fun sortVoices(voices: List<CloudVoice>): List<CloudVoice> =
        voices.sortedWith(compareByDescending<CloudVoice> { it.female && it.hindi }.thenByDescending { it.female }.thenByDescending { it.hindi }.thenBy { it.name.lowercase() })

    /** The voice to use when the user has not picked one yet. */
    fun bestDefault(voices: List<CloudVoice>): CloudVoice? = sortVoices(voices).firstOrNull()
}
