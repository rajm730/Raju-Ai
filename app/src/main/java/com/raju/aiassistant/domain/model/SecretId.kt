package com.raju.aiassistant.domain.model

/**
 * Every user-supplied secret the app can store. Values are encrypted with an
 * Android Keystore key and never leave the device except to their own provider.
 */
enum class SecretId(val storageKey: String, val displayName: String, val helpUrl: String) {
    GEMINI_API_KEY("gemini_api_key", "Google Gemini API key", "https://aistudio.google.com/apikey"),
    GROQ_API_KEY("groq_api_key", "Groq API key", "https://console.groq.com/keys"),
    TAVILY_API_KEY("tavily_api_key", "Tavily web-search API key", "https://app.tavily.com"),
    SARVAM_API_KEY("sarvam_api_key", "Sarvam AI voice (Bulbul) API key", "https://dashboard.sarvam.ai"),
    ELEVENLABS_API_KEY("elevenlabs_api_key", "ElevenLabs voice API key", "https://elevenlabs.io/app/settings/api-keys"),
    CARTESIA_API_KEY("cartesia_api_key", "Cartesia voice API key", "https://play.cartesia.ai/keys"),
    DEEPGRAM_API_KEY("deepgram_api_key", "Deepgram API key", "https://console.deepgram.com"),
}
