package com.raju.aiassistant.ui.home.visuals

/** How much animation work the home screen may do. Tuned for budget phones (Galaxy A14). */
data class MotionProfile(
    /** False = draw a single static frame (reduced motion). */
    val animate: Boolean,
    val maxFps: Int,
    val particleCount: Int,
    /** Heartbeat shock-waves and the extra outer rings. */
    val richEffects: Boolean,
)

fun motionProfileFor(lowPowerMode: Boolean, reducedMotion: Boolean, systemAnimationsEnabled: Boolean): MotionProfile = when {
    reducedMotion || !systemAnimationsEnabled -> MotionProfile(animate = false, maxFps = 0, particleCount = 0, richEffects = true)
    lowPowerMode -> MotionProfile(animate = true, maxFps = 30, particleCount = 14, richEffects = false)
    else -> MotionProfile(animate = true, maxFps = 60, particleCount = 48, richEffects = true)
}
