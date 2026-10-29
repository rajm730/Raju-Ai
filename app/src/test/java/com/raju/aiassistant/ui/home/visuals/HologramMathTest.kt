package com.raju.aiassistant.ui.home.visuals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HologramMathTest {
    @Test
    fun pseudoRandomIsDeterministicAndInRange() {
        repeat(1000) { i ->
            val v = pseudoRandom(i)
            assertTrue(v in 0f..1f)
            assertEquals(v, pseudoRandom(i))
        }
    }

    @Test
    fun eyesBlinkOncePerIntervalAndAreMostlyOpen() {
        val interval = 4f
        val samples = (0 until 4000).map { blinkOpenness(it / 1000f * interval, interval) } // one window, 1 ms steps
        val closed = samples.count { it < 0.2f }
        val open = samples.count { it == 1f }
        assertTrue("eyes should close briefly", closed in 1..100)
        assertTrue("eyes should be open most of the time", open > 3700)
        assertTrue(samples.all { it in 0f..1f })
    }

    @Test
    fun mouthOpennessIsClampedAndMonotonic() {
        assertEquals(0f, mouthOpenness(-1f))
        assertEquals(1f, mouthOpenness(2f))
        assertTrue(mouthOpenness(0.2f) > 0.2f) // small sounds are visible
        assertTrue(mouthOpenness(0.5f) < mouthOpenness(0.8f))
    }
}
