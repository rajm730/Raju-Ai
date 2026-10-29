package com.raju.aiassistant.ui.home.visuals

import com.raju.aiassistant.domain.model.AssistantState

/**
 * How the Arc Reactor and hologram look for a given [AssistantState].
 * Pure data (ARGB longs, no Compose types) so the mapping is unit-testable.
 */
data class ReactorStyle(
    val primaryArgb: Long,
    val secondaryArgb: Long,
    /** 0..1 strength of the outer glow. */
    val glow: Float,
    /** Outer ring speed in revolutions per second (0 = still). */
    val rotationSpeed: Float,
    /** Core pulse frequency in Hz. */
    val pulseHz: Float,
    /** Expanding sound-wave ripples (listening). */
    val ripples: Boolean = false,
    /** Fast spinning arc (thinking). */
    val spinner: Boolean = false,
    /** Progress sweep around the rim (executing). */
    val sweep: Boolean = false,
    /** Irregular brightness flicker (error). */
    val flicker: Boolean = false,
    /** Core brightness follows speech level (speaking). */
    val speechReactive: Boolean = false,
)

private const val CYAN = 0xFF00E5FF
private const val BLUE = 0xFF4FC3F7
private const val VIOLET = 0xFF9A7BFF
private const val GREEN = 0xFF4CE0A0
private const val AMBER = 0xFFFFC857
private const val RED = 0xFFFF5C7A
private const val GREY = 0xFF5D7083

fun reactorStyleFor(state: AssistantState): ReactorStyle = when (state) {
    AssistantState.Idle -> ReactorStyle(CYAN, BLUE, glow = 0.55f, rotationSpeed = 0.05f, pulseHz = 0.4f)
    AssistantState.Listening -> ReactorStyle(CYAN, 0xFFB2FFFF, glow = 0.8f, rotationSpeed = 0.08f, pulseHz = 0.9f, ripples = true)
    AssistantState.Thinking -> ReactorStyle(VIOLET, CYAN, glow = 0.7f, rotationSpeed = 0.25f, pulseHz = 1.4f, spinner = true)
    AssistantState.Speaking -> ReactorStyle(CYAN, VIOLET, glow = 0.85f, rotationSpeed = 0.1f, pulseHz = 0f, speechReactive = true)
    AssistantState.Executing -> ReactorStyle(AMBER, CYAN, glow = 0.7f, rotationSpeed = 0.18f, pulseHz = 1.0f, sweep = true)
    AssistantState.Success -> ReactorStyle(GREEN, CYAN, glow = 0.75f, rotationSpeed = 0.06f, pulseHz = 0.5f)
    AssistantState.Error -> ReactorStyle(RED, AMBER, glow = 0.6f, rotationSpeed = 0.02f, pulseHz = 2.0f, flicker = true)
    AssistantState.Offline -> ReactorStyle(GREY, GREY, glow = 0.15f, rotationSpeed = 0f, pulseHz = 0.2f)
}

/** Short bilingual hint shown under the reactor. */
fun statusHintFor(state: AssistantState): String = when (state) {
    AssistantState.Idle -> "Ready — tap Chat to type (voice comes in Phase 4)"
    AssistantState.Listening -> "सुन रही हूँ… Listening"
    AssistantState.Thinking -> "सोच रही हूँ… Thinking"
    AssistantState.Speaking -> "बोल रही हूँ… Speaking"
    AssistantState.Executing -> "काम कर रही हूँ… Executing a task"
    AssistantState.Success -> "हो गया! Task completed"
    AssistantState.Error -> "कुछ गड़बड़ हुई — Something went wrong"
    AssistantState.Offline -> "No internet connection — AI features unavailable"
}
