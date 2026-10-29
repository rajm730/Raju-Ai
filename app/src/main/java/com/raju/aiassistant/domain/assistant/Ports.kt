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
    /** Speech chunks (see chunkForSpeech) of the last utterance that finished playing. */
    val playedChunks: Int get() = 0
}

interface VoiceInput {
    val listening: StateFlow<Boolean>
    /** Live partial transcript while listening. */
    val partial: StateFlow<String>
    fun isAvailable(): Boolean
    fun start(languageTag: String, onResult: (String) -> Unit, onError: (VoiceError) -> Unit)
    fun stop()
    /**
     * True when this app itself records the microphone (e.g. Deepgram). False for the system
     * recogniser, which runs in another app: Android silences other apps' microphones while this
     * app holds call-style audio, so call-style audio must be off while it listens.
     */
    fun capturesInApp(): Boolean = false
}

/**
 * Barge-in: notices the user starting to talk while the assistant is speaking, so the user can cut
 * an answer short just by speaking — like interrupting a person.
 */
interface BargeIn {
    /**
     * Starts watching the microphone. [onInterrupt] is called (at most once) as soon as the user
     * talks over the assistant. When [capture] is true the recording continues until the user
     * pauses, and the whole utterance — including the words spoken before detection — is passed to
     * [onUtterance] as 16 kHz mono 16-bit PCM (null if nothing usable was recorded).
     */
    fun start(capture: Boolean, onInterrupt: () -> Unit, onUtterance: (ByteArray?) -> Unit)
    fun stop()
}

object NoBargeIn : BargeIn {
    override fun start(capture: Boolean, onInterrupt: () -> Unit, onUtterance: (ByteArray?) -> Unit) {}
    override fun stop() {}
}

/** Call-style audio (echo cancellation) for hands-free conversations; see ConversationAudio. */
interface ConversationMode {
    val active: Boolean
    fun enter()
    fun exit()
}

object NoConversationMode : ConversationMode {
    override val active = false
    override fun enter() {}
    override fun exit() {}
}

/**
 * Sleep-mode listening: waits on the phone (nothing is sent anywhere) until someone speaks, then
 * delivers that one utterance — with a short pre-roll, so the first word is kept — as 16 kHz PCM.
 */
interface WakeListener {
    fun start(onUtterance: (ByteArray) -> Unit, onError: () -> Unit)
    fun stop()
}

object NoWakeListener : WakeListener {
    override fun start(onUtterance: (ByteArray) -> Unit, onError: () -> Unit) {}
    override fun stop() {}
}

/** Speech-to-text for audio the app recorded itself (the interrupting utterance). */
interface Transcriber {
    fun isAvailable(): Boolean
    /** [pcm16k] is 16 kHz mono 16-bit PCM. Returns the text, "" for silence, or null on failure. */
    suspend fun transcribe(pcm16k: ByteArray): String?
}

/**
 * [unclear]: speech was heard but recognised with low confidence — ask the user to repeat.
 * [transient]: the recogniser was busy/restarting — harmless, just listen again.
 */
data class VoiceError(
    val message: String,
    val noSpeech: Boolean = false,
    val permissionDenied: Boolean = false,
    val unclear: Boolean = false,
    val transient: Boolean = false,
)

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
