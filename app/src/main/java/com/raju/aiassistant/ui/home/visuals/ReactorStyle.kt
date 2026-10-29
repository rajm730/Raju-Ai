package com.raju.aiassistant.ui.home.visuals

import com.raju.aiassistant.domain.model.AssistantState

/**
 * How the Arc Reactor looks for a given [AssistantState].
 * Pure data (ARGB longs, no Compose types) so the mapping is unit-testable.
 */
data class ReactorStyle(
    /** [PALETTE_SIZE] colours of the rotating sweep gradient (first colour is also the main tint). */
    val paletteArgb: List<Long>,
    /** 0..1 strength of the outer glow. */
    val glow: Float,
    /** Outer ring speed in revolutions per second (0 = still). */
    val rotationSpeed: Float,
    /** Heartbeat rate in beats per minute. */
    val bpm: Float,
    /** Expanding sound-wave ripples (listening). */
    val ripples: Boolean = false,
    /** Fast spinning arc (thinking). */
    val spinner: Boolean = false,
    /** Progress sweep around the rim (executing). */
    val sweep: Boolean = false,
    /** Irregular brightness flicker (error). */
    val flicker: Boolean = false,
    /** Beat follows the speech level instead of the heartbeat (speaking). */
    val speechReactive: Boolean = false,
) {
    val primaryArgb: Long get() = paletteArgb.first()
}

const val PALETTE_SIZE = 5

private const val CYAN = 0xFF00E5FF
private const val BLUE = 0xFF2979FF
private const val VIOLET = 0xFF9A7BFF
private const val MAGENTA = 0xFFFF4FD8
private const val TEAL = 0xFF1DE9B6
private const val GREEN = 0xFF4CE0A0
private const val AMBER = 0xFFFFC857
private const val ORANGE = 0xFFFF8A3D
private const val RED = 0xFFFF5C7A
private const val GREY = 0xFF5D7083
private const val GREY_LIGHT = 0xFF8FA9BF

fun reactorStyleFor(state: AssistantState): ReactorStyle = when (state) {
    AssistantState.Idle -> ReactorStyle(listOf(CYAN, BLUE, VIOLET, MAGENTA, CYAN), glow = 0.7f, rotationSpeed = 0.06f, bpm = 64f)
    AssistantState.Listening -> ReactorStyle(listOf(CYAN, TEAL, GREEN, BLUE, CYAN), glow = 0.85f, rotationSpeed = 0.09f, bpm = 76f, ripples = true)
    AssistantState.Thinking -> ReactorStyle(listOf(VIOLET, MAGENTA, BLUE, CYAN, VIOLET), glow = 0.8f, rotationSpeed = 0.25f, bpm = 96f, spinner = true)
    AssistantState.Speaking -> ReactorStyle(listOf(CYAN, VIOLET, MAGENTA, BLUE, CYAN), glow = 0.9f, rotationSpeed = 0.12f, bpm = 72f, speechReactive = true)
    AssistantState.Executing -> ReactorStyle(listOf(AMBER, ORANGE, MAGENTA, CYAN, AMBER), glow = 0.8f, rotationSpeed = 0.18f, bpm = 90f, sweep = true)
    AssistantState.Success -> ReactorStyle(listOf(GREEN, TEAL, CYAN, BLUE, GREEN), glow = 0.85f, rotationSpeed = 0.07f, bpm = 68f)
    AssistantState.Error -> ReactorStyle(listOf(RED, ORANGE, MAGENTA, ORANGE, RED), glow = 0.7f, rotationSpeed = 0.03f, bpm = 112f, flicker = true)
    AssistantState.Offline -> ReactorStyle(listOf(GREY, GREY_LIGHT, GREY, GREY_LIGHT, GREY), glow = 0.2f, rotationSpeed = 0f, bpm = 40f)
    // Slow, dim breathing: asleep but listening for the user's voice.
    AssistantState.Sleeping -> ReactorStyle(listOf(VIOLET, BLUE, GREY_LIGHT, VIOLET, BLUE), glow = 0.35f, rotationSpeed = 0.015f, bpm = 34f)
}

/** Short bilingual hint shown under the reactor. */
fun statusHintFor(state: AssistantState): String = when (state) {
    AssistantState.Idle -> "Ready — tap Voice to speak or Chat to type"
    AssistantState.Listening -> "सुन रही हूँ… Listening"
    AssistantState.Thinking -> "सोच रही हूँ… Thinking"
    AssistantState.Speaking -> "बोल रही हूँ… (बीच में बोलें या reactor दबाएँ तो रुक जाऊँगी)"
    AssistantState.Executing -> "काम कर रही हूँ… Executing a task"
    AssistantState.Success -> "हो गया! Task completed"
    AssistantState.Error -> "कुछ गड़बड़ हुई — Something went wrong"
    AssistantState.Offline -> "No internet connection — AI features unavailable"
    AssistantState.Sleeping -> "😴 सो रही हूँ… \"सीमा\" बोलकर जगाइए या reactor छुएँ"
}
