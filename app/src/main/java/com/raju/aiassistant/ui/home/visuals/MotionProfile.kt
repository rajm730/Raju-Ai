package com.raju.aiassistant.ui.home.visuals

/** How much animation work the home screen may do. Tuned for budget phones (Galaxy A14). */
data class MotionProfile(
    /** False = draw a single static frame (reduced motion). */
    val animate: Boolean,
    val maxFps: Int,
    val particleCount: Int,
    val scanlines: Boolean,
)

fun motionProfileFor(lowPowerMode: Boolean, reducedMotion: Boolean, systemAnimationsEnabled: Boolean): MotionProfile = when {
    reducedMotion || !systemAnimationsEnabled -> MotionProfile(animate = false, maxFps = 0, particleCount = 0, scanlines = true)
    lowPowerMode -> MotionProfile(animate = true, maxFps = 30, particleCount = 12, scanlines = false)
    else -> MotionProfile(animate = true, maxFps = 60, particleCount = 36, scanlines = true)
}
