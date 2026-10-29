package com.raju.aiassistant.ui.home.visuals

import com.raju.aiassistant.domain.model.AssistantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactorStyleTest {
    @Test
    fun everyStateHasADistinctLook() {
        val styles = AssistantState.entries.map { reactorStyleFor(it) }
        assertEquals(styles.size, styles.toSet().size)
    }

    @Test
    fun stateSpecificEffects() {
        assertTrue(reactorStyleFor(AssistantState.Listening).ripples)
        assertTrue(reactorStyleFor(AssistantState.Thinking).spinner)
        assertTrue(reactorStyleFor(AssistantState.Speaking).speechReactive)
        assertTrue(reactorStyleFor(AssistantState.Executing).sweep)
        assertTrue(reactorStyleFor(AssistantState.Error).flicker)
    }

    @Test
    fun offlineReactorIsStillAndDim() {
        val offline = reactorStyleFor(AssistantState.Offline)
        assertEquals(0f, offline.rotationSpeed)
        assertTrue(offline.glow < reactorStyleFor(AssistantState.Idle).glow)
    }

    @Test
    fun everyStateHasAHint() {
        AssistantState.entries.forEach { assertTrue(statusHintFor(it).isNotBlank()) }
    }
}
