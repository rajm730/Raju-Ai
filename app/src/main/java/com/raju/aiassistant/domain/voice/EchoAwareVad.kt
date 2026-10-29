package com.raju.aiassistant.domain.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detects the user talking while the assistant itself is speaking through the phone speaker.
 *
 * The microphone hears the assistant's own voice (echo). Because the app knows how loud its own
 * playback is at every moment, it learns how that playback shows up in the microphone — the delay
 * (speaker/buffer latency) and the gain — during the first second of each reply. From then on it
 * predicts the echo frame by frame (including a short room-reverb tail) and flags frames where the
 * microphone is clearly louder than the predicted echo. Speech is assumed when enough frames in a
 * short window are flagged; in pauses of the playback (between sentences) any voice above the
 * room's noise floor counts.
 *
 * It works best together with the phone's acoustic echo canceller (call-style audio, see
 * ConversationAudio), which removes most of the echo before this model sees it; the thresholds were
 * tuned in simulation for zero false triggers with realistic timing jitter and room reverb.
 *
 * Frames are 20 ms. Mic loudness is RMS of 16-bit PCM; playback level is 0..1 RMS of the samples
 * coming out of the speaker right now (AudioPlayer.levelNow). Pure logic, unit-tested.
 */
class EchoAwareVad(
    private val maxDelayFrames: Int = 20,
    private val learnFrames: Int = 45,
    private val ratio: Double = 1.45,
    private val windowFrames: Int = 15,
    private val needFrames: Int = 8,
    private val minLevel: Double = 450.0,
    /** Timing tolerance in frames around the learned delay. */
    private val tolerance: Int = 1,
) {
    private val history = FloatArray(64)
    private var t = 0
    private var noise = -1.0
    private val sxy = DoubleArray(maxDelayFrames + 1)
    private val sxx = DoubleArray(maxDelayFrames + 1)
    private val syy = DoubleArray(maxDelayFrames + 1)
    private var learned = 0
    private var delay = 6
    private var gain = 0.0
    private val window = BooleanArray(windowFrames)

    /** Room noise level (mic RMS when nothing is playing); 0 until measured. */
    val noiseFloor: Double get() = max(noise, 0.0)

    /** @return true when the user is talking over the assistant (or into a playback pause). */
    fun onFrame(micRms: Double, playbackLevel: Float): Boolean {
        t++
        history[t and 63] = playbackLevel
        val playingRecently = recentPlayback() > 0.02f
        if (!playingRecently) {
            // Nothing audible from the speaker: this is the room's noise (or the user).
            noise = when {
                noise < 0 -> micRms
                micRms < noise -> noise * 0.8 + micRms * 0.2
                else -> noise * 0.995 + micRms * 0.005
            }
        }
        val floor = if (noise < 0) 300.0 else max(noise, 80.0)
        val y = max(0.0, micRms - floor)

        if (playingRecently && learned < learnFrames) {
            for (d in 0..maxDelayFrames) {
                // The exact delayed level (not the widened echoInput) gives an unbiased gain.
                val x = level(d).toDouble()
                sxy[d] += x * y; sxx[d] += x * x; syy[d] += y * y
            }
            learned++
            if (learned == learnFrames) chooseDelayAndGain()
        }

        val predicted = when {
            !playingRecently -> floor
            learned < learnFrames -> Double.MAX_VALUE // still learning this reply's echo: only pauses count
            else -> floor + gain * echoInput(delay)
        }
        val loud = micRms > predicted * ratio && micRms > floor * 3 && micRms > minLevel
        if (!loud && playingRecently && learned >= learnFrames) {
            // Follow slow changes of the echo (volume, phone position) while the user is quiet.
            val x = level(delay)
            if (x > 0.1f) gain = gain * 0.98 + 0.02 * (y / x)
        }
        window[t % windowFrames] = loud
        return window.count { it } >= needFrames
    }

    private fun chooseDelayAndGain() {
        var best = 0
        var bestCorr = -1.0
        for (d in 0..maxDelayFrames) {
            if (sxx[d] <= 0 || syy[d] <= 0) continue
            val corr = sxy[d] / sqrt(sxx[d] * syy[d])
            if (corr > bestCorr) { bestCorr = corr; best = d }
        }
        delay = best
        gain = if (sxx[best] > 0) sxy[best] / sxx[best] else 0.0
    }

    /** Playback that can be heard now at delay [d]: the frames around it plus a decaying reverb tail. */
    private fun echoInput(d: Int): Float {
        var x = 0f
        for (k in -tolerance..tolerance) x = maxOf(x, level(d + k))
        return maxOf(x, 0.45f * level(d + 3), 0.25f * level(d + 6))
    }

    private fun level(framesAgo: Int): Float = if (framesAgo < 0 || framesAgo >= 60 || framesAgo >= t) 0f else history[(t - framesAgo) and 63]

    private fun recentPlayback(): Float {
        var m = 0f
        for (k in 0..maxDelayFrames + 8) m = maxOf(m, level(k))
        return m
    }
}

/** Short phrases that mean "stop talking" (Hindi, Hinglish, English). */
private val STOP_PHRASES = listOf(
    "ruko", "ruk", "rukiye", "ruk jao", "rukja", "bas", "bas karo", "stop", "chup", "chup raho", "shant",
    "wait", "ek minute", "ek second", "hold on", "enough", "thamo", "theek hai bas",
    "रुको", "रुक", "रुकिए", "रुक जाओ", "बस", "बस करो", "चुप", "चुप रहो", "ठहरो", "एक मिनट", "स्टॉप",
)

/** True when a short utterance is just a request to stop talking, not a new question. */
fun isStopCommand(heard: String): Boolean {
    val clean = heard.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty() || clean.split(' ').size > 4) return false
    return STOP_PHRASES.any { p -> clean == p || clean.startsWith("$p ") || clean.endsWith(" $p") || clean.contains(" $p ") }
}

private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

/**
 * The recording of an interruption starts a little before the user was detected, so it can begin
 * with the assistant's own words (echo). Leading words that the assistant was saying are removed;
 * what is left is what the user said ("" if it was only echo).
 */
fun stripEcho(heard: String, spoken: String): String {
    val own = WORD.findAll(spoken.lowercase()).map { it.value }.toSet()
    val words = WORD.findAll(heard).toList()
    val firstUserWord = words.indexOfFirst { it.value.lowercase() !in own }
    if (firstUserWord < 0) return ""
    return heard.substring(words[firstUserWord].range.first).trim()
}
