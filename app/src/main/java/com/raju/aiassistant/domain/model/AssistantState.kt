package com.raju.aiassistant.domain.model

/**
 * Visual / behavioural state of the assistant. The home screen (Phase 2) renders a
 * different reactor + hologram look for each state.
 */
enum class AssistantState(val label: String) {
    Idle("Ready"),
    Listening("Listening"),
    Thinking("Thinking"),
    Speaking("Speaking"),
    Executing("Executing"),
    Success("Done"),
    Error("Error"),
    Offline("Offline");

    /** States in which a Stop action is meaningful. */
    val isBusy: Boolean
        get() = this == Listening || this == Thinking || this == Speaking || this == Executing
}
