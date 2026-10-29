package com.raju.aiassistant.ui.home.visuals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactorMathTest {
    @Test
    fun pseudoRandomIsDeterministicAndInRange() {
        repeat(1000) { i ->
            val v = pseudoRandom(i)
            assertTrue(v in 0f..1f)
            assertEquals(v, pseudoRandom(i))
        }
    }

    @Test
    fun heartbeatHasStrongThenSoftBeatThenRest() {
        val bpm = 60f // 1 beat per second
        val lub = heartbeat(0.06f, bpm)
        val dub = heartbeat(0.26f, bpm)
        val rest = heartbeat(0.7f, bpm)
        assertEquals(1f, lub, 0.01f)
        assertTrue("second beat is softer", dub in 0.4f..0.8f)
        assertTrue("heart rests between beats", rest < 0.01f)
    }

    @Test
    fun heartbeatRepeatsEveryBeatAndStaysInRange() {
        val bpm = 75f
        val period = 60f / bpm
        assertEquals(heartbeat(0.1f, bpm), heartbeat(0.1f + 3 * period, bpm), 0.001f)
        repeat(500) { assertTrue(heartbeat(it * 0.013f, bpm) in 0f..1f) }
    }

    @Test
    fun beatPhaseIsZeroWhenStopped() {
        assertEquals(0f, beatPhase(5f, 0f))
    }
}
