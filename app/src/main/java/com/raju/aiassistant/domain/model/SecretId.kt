package com.raju.aiassistant.domain.model

/**
 * Every user-supplied secret the app can store. Values are encrypted with an
 * Android Keystore key and never leave the device except to their own provider.
 */
enum class SecretId(val storageKey: String, val displayName: String, val helpUrl: String) {
    GEMINI_API_KEY("gemini_api_key", "Google Gemini API key", "https://aistudio.google.com/apikey"),
    GROK_API_KEY("grok_api_key", "xAI Grok API key", "https://console.x.ai"),
    TAVILY_API_KEY("tavily_api_key", "Tavily web-search API key", "https://app.tavily.com"),
    SARVAM_API_KEY("sarvam_api_key", "Sarvam AI voice (Bulbul) API key", "https://dashboard.sarvam.ai"),
}
