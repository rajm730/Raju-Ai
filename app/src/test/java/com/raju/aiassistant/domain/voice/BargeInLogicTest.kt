package com.raju.aiassistant.domain.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInLogicTest {
    private fun BargeInLogic.feed(rms: Double, frames: Int, playing: Boolean = true): Boolean {
        var hit = false
        repeat(frames) { if (onFrame(rms, playing)) hit = true }
        return hit
    }

    @Test
    fun ownVoiceEchoDoesNotTrigger() {
        val l = BargeInLogic()
        assertFalse(l.feed(1_500.0, 25)) // learning the echo level
        assertFalse(l.feed(1_800.0, 200)) // echo varies a bit: still the assistant
    }

    @Test
    fun userTalkingClearlyLouderTriggers() {
        val l = BargeInLogic()
        l.feed(1_500.0, 25)
        assertFalse(l.feed(9_000.0, 5)) // a short clap is not enough
        assertTrue(l.feed(9_000.0, 20))
    }

    @Test
    fun nothingTriggersWhileTheAssistantIsSilent() {
        val l = BargeInLogic()
        assertFalse(l.feed(9_000.0, 100, playing = false))
    }
}
