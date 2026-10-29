package com.raju.aiassistant.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class EchoAwareVadTest {
    /** Speech-like playback level: syllables with short dips, 0..0.8. */
    private fun ttsLevel(frame: Int) = (0.45f + 0.35f * sin(frame * 0.9f)).coerceAtLeast(0f) * if (frame % 23 < 3) 0.1f else 1f

    /**
     * Microphone = room noise + echo ([echoGain], 6 frames late) + optional user voice.
     * 5000 ≈ loud echo without echo cancellation; 800 ≈ what is left with the phone's echo canceller.
     */
    private fun run(frames: Int, userVoice: (Int) -> Double = { 0.0 }, silentFrom: Int = Int.MAX_VALUE, echoGain: Double = 5000.0): Int? {
        val vad = EchoAwareVad()
        val levels = FloatArray(frames) { if (it < 10 || it >= silentFrom) 0f else ttsLevel(it) }
        for (f in 0 until frames) {
            val echo = if (f >= 6) echoGain * levels[f - 6] else 0.0
            val noise = 150.0 + 30 * abs(sin(f * 1.7))
            val mic = sqrt(noise * noise + echo * echo + userVoice(f) * userVoice(f))
            if (vad.onFrame(mic, levels[f])) return f
        }
        return null
    }

    @Test
    fun herOwnVoiceDoesNotInterruptHer() {
        assertEquals(null, run(500)) // loud echo, no echo canceller
        assertEquals(null, run(500, echoGain = 800.0)) // with echo canceller
    }

    @Test
    fun userTalkingOverHerIsNoticedQuickly() {
        val start = 200
        val hit = run(500, userVoice = { f -> if (f >= start) 3000.0 * (0.6 + 0.4 * abs(sin(f * 0.5))) else 0.0 }, echoGain = 800.0)
        assertTrue("not detected", hit != null)
        assertTrue("too slow: ${hit!! - start} frames", hit - start <= 15) // within 0.3 s
    }

    @Test
    fun userTalkingInAPauseIsNoticed() {
        val hit = run(400, userVoice = { f -> if (f >= 300) 2500.0 else 0.0 }, silentFrom = 280)
        assertTrue(hit != null && hit >= 300)
    }

    @Test
    fun aShortClickIsIgnored() {
        assertEquals(null, run(400, userVoice = { f -> if (f in 200..203) 20000.0 else 0.0 }))
    }

    @Test
    fun stopCommandsAreRecognised() {
        listOf("ruko", "Ruko!", "bas karo", "एक मिनट", "रुको", "stop stop", "ek minute ruko").forEach { assertTrue(it, isStopCommand(it)) }
        listOf("Delhi ka mausam batao", "bas stop par kab aayegi aur kitne baje", "").forEach { assertFalse(it, isStopCommand(it)) }
    }

    @Test
    fun herOwnWordsAreRemovedFromTheStartOfTheRecording() {
        val spoken = "Aaj Delhi mein dhoop rahegi aur shaam ko halki hawa chalegi."
        assertEquals("ruko ek minute", stripEcho("halki hawa ruko ek minute", spoken))
        assertEquals("", stripEcho("shaam ko halki", spoken))
        assertEquals("Mumbai ka batao", stripEcho("Mumbai ka batao", spoken))
    }
}
