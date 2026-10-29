package com.raju.aiassistant.domain.voice

/**
 * Decides when the user has finished speaking, from the loudness (RMS) of short audio frames.
 * The first frames calibrate the room's noise level; speech is louder than that, and a pause
 * of [silenceMs] after speech ends the turn. Pure logic, so it is unit-tested without a microphone.
 */
class EndpointDetector(
    private val frameMs: Int = 20,
    private val silenceMs: Int = 1_200,
    private val noSpeechTimeoutMs: Int = 7_000,
    private val maxMs: Int = 15_000,
    private val calibrationMs: Int = 300,
    private val minThreshold: Double = 400.0,
) {
    enum class State { LISTENING, SPEAKING, DONE, NO_SPEECH }

    var state = State.LISTENING
        private set
    private var elapsed = 0
    private var noise = 0.0
    private var noiseFrames = 0
    private var loudRun = 0
    private var quietMs = 0

    val heardSpeech: Boolean get() = state == State.SPEAKING || state == State.DONE

    fun onFrame(rms: Double): State {
        if (state == State.DONE || state == State.NO_SPEECH) return state
        elapsed += frameMs
        if (elapsed <= calibrationMs) {
            noise = (noise * noiseFrames + rms) / (noiseFrames + 1)
            noiseFrames++
            return state
        }
        val threshold = maxOf(minThreshold, noise * 3)
        val loud = rms > threshold
        when (state) {
            State.LISTENING -> {
                loudRun = if (loud) loudRun + 1 else 0
                if (loudRun >= 3) state = State.SPEAKING // ~60 ms of voice, not a click
                else if (elapsed >= noSpeechTimeoutMs) state = State.NO_SPEECH
            }
            State.SPEAKING -> {
                quietMs = if (loud) 0 else quietMs + frameMs
                if (quietMs >= silenceMs) state = State.DONE
            }
            else -> Unit
        }
        if (elapsed >= maxMs && state != State.DONE) state = if (heardSpeech) State.DONE else State.NO_SPEECH
        return state
    }
}
