package com.raju.aiassistant.domain.voice

/**
 * Decides, frame by frame, whether the user is talking over the assistant. The microphone also
 * hears the assistant's own voice (echo), so the echo level is learned while the assistant plays
 * and the user must be clearly louder than it for [triggerMs] in a row. Frames while nothing is
 * playing (between sentences, during synthesis) are ignored. Pure logic, unit-tested.
 */
class BargeInLogic(
    private val frameMs: Int = 20,
    private val learnMs: Int = 500,
    private val triggerMs: Int = 260,
    private val minLevel: Double = 1_400.0,
    private val factor: Double = 2.2,
) {
    private var playedMs = 0
    private var echo = 0.0
    private var loudMs = 0

    /** @return true once the user is talking over the assistant. */
    fun onFrame(rms: Double, playing: Boolean): Boolean {
        if (!playing) {
            loudMs = 0
            return false
        }
        playedMs += frameMs
        if (playedMs <= learnMs) {
            echo = maxOf(echo, rms) // loudest echo heard so far
            return false
        }
        val threshold = maxOf(minLevel, echo * factor)
        if (rms > threshold) {
            loudMs += frameMs
            return loudMs >= triggerMs
        }
        loudMs = 0
        echo = maxOf(echo * 0.997, rms) // keep following the assistant's own loudness
        return false
    }
}
