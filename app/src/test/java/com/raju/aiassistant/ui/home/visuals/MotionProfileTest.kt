package com.raju.aiassistant.ui.home.visuals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionProfileTest {
    @Test
    fun reducedMotionFreezesAnimation() {
        assertFalse(motionProfileFor(lowPowerMode = false, reducedMotion = true, systemAnimationsEnabled = true).animate)
    }

    @Test
    fun systemRemoveAnimationsIsRespected() {
        assertFalse(motionProfileFor(lowPowerMode = false, reducedMotion = false, systemAnimationsEnabled = false).animate)
    }

    @Test
    fun lowPowerCapsFrameRateAndParticles() {
        val normal = motionProfileFor(false, false, true)
        val low = motionProfileFor(true, false, true)
        assertTrue(low.animate)
        assertEquals(20, low.maxFps)
        assertTrue(low.maxFps < normal.maxFps)
        assertTrue(low.particleCount < normal.particleCount)
        assertFalse(low.richEffects)
    }
}
