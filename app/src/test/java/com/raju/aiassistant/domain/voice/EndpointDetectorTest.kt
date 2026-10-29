package com.raju.aiassistant.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointDetectorTest {
    private fun EndpointDetector.feed(rms: Double, frames: Int): EndpointDetector.State {
        var s = state
        repeat(frames) { s = onFrame(rms) }
        return s
    }

    @Test
    fun speechThenPauseEndsTheTurn() {
        val d = EndpointDetector(frameMs = 20, silenceMs = 1_000)
        d.feed(100.0, 15) // calibration: quiet room
        assertEquals(EndpointDetector.State.SPEAKING, d.feed(3_000.0, 10))
        assertEquals(EndpointDetector.State.SPEAKING, d.feed(100.0, 40)) // 800 ms pause: still talking
        assertEquals(EndpointDetector.State.DONE, d.feed(100.0, 10))
    }

    @Test
    fun silenceOnlyMeansNoSpeech() {
        val d = EndpointDetector(frameMs = 20, noSpeechTimeoutMs = 2_000)
        assertEquals(EndpointDetector.State.NO_SPEECH, d.feed(100.0, 120))
    }

    @Test
    fun aSingleClickIsNotSpeechAndLongTalkIsCut() {
        val d = EndpointDetector(frameMs = 20, maxMs = 3_000)
        d.feed(100.0, 15)
        assertEquals(EndpointDetector.State.LISTENING, d.feed(5_000.0, 2))
        assertEquals(EndpointDetector.State.DONE, d.feed(5_000.0, 200))
    }
}
