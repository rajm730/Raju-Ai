package com.raju.aiassistant.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyMaskingTest {
    @Test
    fun emptyOrBlankSecretMasksToEmpty() {
        assertEquals("", maskSecret(""))
        assertEquals("", maskSecret("   "))
    }

    @Test
    fun shortSecretIsFullyMasked() {
        assertEquals("••••••", maskSecret("abc123"))
    }

    @Test
    fun longSecretShowsOnlyLastFour() {
        val masked = maskSecret("AIzaSyD-very-secret-key-WXYZ")
        assertEquals("••••••••WXYZ", masked)
        assertFalse(masked.contains("AIza"))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals("••••••••6789", maskSecret("  sk-123456789  "))
    }
}
