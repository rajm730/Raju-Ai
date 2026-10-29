package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.domain.model.ChatMessage
import com.raju.aiassistant.domain.model.ChatRole
import kotlinx.coroutines.flow.StateFlow

/** Outcome of a speak request. [engineLabel] tells the user whether cloud or offline TTS was used. */
data class SpeakResult(val completed: Boolean, val engineLabel: String, val error: String? = null)

interface Speaker {
    val speaking: StateFlow<Boolean>
    /** 0..1 loudness of the audio currently playing (drives the reactor). */
    val level: StateFlow<Float>
    /** Speaks [text], replacing anything already playing. Returns when finished, stopped or failed. */
    suspend fun speak(text: String): SpeakResult
    fun stop()
}

interface VoiceInput {
    val listening: StateFlow<Boolean>
    /** Live partial transcript while listening. */
    val partial: StateFlow<String>
    fun isAvailable(): Boolean
    fun start(languageTag: String, onResult: (String) -> Unit, onError: (VoiceError) -> Unit)
    fun stop()
}

data class VoiceError(val message: String, val noSpeech: Boolean = false, val permissionDenied: Boolean = false)

interface ChatHistory {
    suspend fun recent(limit: Int): List<ChatMessage>
    suspend fun add(role: ChatRole, text: String): Long
    suspend fun clear()
}

data class Memory(val id: Long, val text: String, val createdAt: Long)

interface MemoryStore {
    suspend fun all(): List<Memory>
    suspend fun add(text: String): Long
}

enum class ActionStatus { SUCCESS, FAILED, CANCELLED, BLOCKED }

interface ActionLog {
    suspend fun log(type: String, summary: String, status: ActionStatus)
}
