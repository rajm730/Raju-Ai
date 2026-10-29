package com.raju.aiassistant.domain

import com.raju.aiassistant.domain.model.AssistantState
import com.raju.aiassistant.domain.model.SecretId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStateTest {
    @Test
    fun busyStatesAllowStop() {
        listOf(AssistantState.Listening, AssistantState.Thinking, AssistantState.Speaking, AssistantState.Executing)
            .forEach { assertTrue("$it should be busy", it.isBusy) }
    }

    @Test
    fun idleAndTerminalStatesAreNotBusy() {
        listOf(AssistantState.Idle, AssistantState.Success, AssistantState.Error, AssistantState.Offline)
            .forEach { assertFalse("$it should not be busy", it.isBusy) }
    }

    @Test
    fun secretStorageKeysAreUnique() {
        val keys = SecretId.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
    }
}
