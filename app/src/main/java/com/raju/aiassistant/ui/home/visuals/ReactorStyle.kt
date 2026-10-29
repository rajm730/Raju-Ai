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

// Emerald Matrix palette: emerald is Seema's identity; each state adds its own accent so states
// stay easy to tell apart (listening = cyan, thinking = violet, working = amber, error = red).
private const val EMERALD = 0xFF19F59A
private const val EMERALD_BRIGHT = 0xFF7DFFC8
private const val EMERALD_DEEP = 0xFF00A86B
private const val MINT = 0xFFB8FFE0
private const val CYAN = 0xFF4DF3FF
private const val TEAL = 0xFF1DE9B6
private const val VIOLET = 0xFF9A7BFF
private const val LIME = 0xFFB6FF5C
private const val AMBER = 0xFFFFC857
private const val ORANGE = 0xFFFF8A3D
private const val RED = 0xFFFF5C7A
private const val MAGENTA = 0xFFFF4FD8
private const val GREY = 0xFF4E6B5F
private const val GREY_LIGHT = 0xFF8DB8A6
private const val NIGHT = 0xFF2E6BFF

fun reactorStyleFor(state: AssistantState): ReactorStyle = when (state) {
    AssistantState.Idle -> ReactorStyle(listOf(EMERALD, CYAN, EMERALD_DEEP, MINT, EMERALD), glow = 0.7f, rotationSpeed = 0.06f, bpm = 64f)
    AssistantState.Listening -> ReactorStyle(listOf(CYAN, EMERALD, TEAL, MINT, CYAN), glow = 0.85f, rotationSpeed = 0.09f, bpm = 76f, ripples = true)
    AssistantState.Thinking -> ReactorStyle(listOf(VIOLET, EMERALD, CYAN, MAGENTA, VIOLET), glow = 0.8f, rotationSpeed = 0.25f, bpm = 96f, spinner = true)
    AssistantState.Speaking -> ReactorStyle(listOf(EMERALD_BRIGHT, EMERALD, CYAN, MINT, EMERALD_BRIGHT), glow = 0.9f, rotationSpeed = 0.12f, bpm = 72f, speechReactive = true)
    AssistantState.Executing -> ReactorStyle(listOf(AMBER, EMERALD, ORANGE, CYAN, AMBER), glow = 0.8f, rotationSpeed = 0.18f, bpm = 90f, sweep = true)
    AssistantState.Success -> ReactorStyle(listOf(LIME, EMERALD_BRIGHT, EMERALD, TEAL, LIME), glow = 0.85f, rotationSpeed = 0.07f, bpm = 68f)
    AssistantState.Error -> ReactorStyle(listOf(RED, ORANGE, MAGENTA, ORANGE, RED), glow = 0.7f, rotationSpeed = 0.03f, bpm = 112f, flicker = true)
    AssistantState.Offline -> ReactorStyle(listOf(GREY, GREY_LIGHT, GREY, GREY_LIGHT, GREY), glow = 0.2f, rotationSpeed = 0f, bpm = 40f)
    // Slow, dim breathing: asleep but listening for the user's voice.
    AssistantState.Sleeping -> ReactorStyle(listOf(EMERALD_DEEP, NIGHT, GREY_LIGHT, EMERALD_DEEP, NIGHT), glow = 0.35f, rotationSpeed = 0.015f, bpm = 34f)
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
