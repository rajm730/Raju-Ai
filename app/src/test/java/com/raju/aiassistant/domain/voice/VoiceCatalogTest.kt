package com.raju.aiassistant.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogTest {
    @Test
    fun knownModelsComeFirstAndUnknownOnesAreKept() {
        val merged = VoiceCatalog.mergeElevenModels(listOf("eleven_flash_v2_5" to "Flash", "eleven_new_x" to "New X", "eleven_multilingual_v2" to "Multi"))
        assertEquals(listOf("eleven_multilingual_v2", "eleven_flash_v2_5", "eleven_new_x"), merged.map { it.id })
        assertTrue(merged.first().recommended)
        assertEquals(VoiceCatalog.elevenModels, VoiceCatalog.mergeElevenModels(emptyList()))
    }

    @Test
    fun femaleHindiVoicesAreSuggestedFirst() {
        val voices = listOf(
            CloudVoice("1", "Adam"),
            CloudVoice("2", "Zara", female = true),
            CloudVoice("3", "Meera", female = true, hindi = true),
            CloudVoice("4", "Raj", hindi = true),
        )
        assertEquals(listOf("3", "2", "4", "1"), VoiceCatalog.sortVoices(voices).map { it.id })
        assertEquals("3", VoiceCatalog.bestDefault(voices)?.id)
    }

    @Test
    fun sttOptionSplitsModelAndLanguage() {
        val o = VoiceCatalog.sttOption("nova-3:multi")
        assertEquals("nova-3", o.model)
        assertEquals("multi", o.language)
        assertEquals(VoiceCatalog.deepgramSttModels.first(), VoiceCatalog.sttOption("unknown"))
        assertTrue(VoiceCatalog.deepgramVoices.all { it.id.startsWith("aura-2-") && it.id.endsWith("-en") })
    }

    @Test
    fun paidOnlyVoicesAreNeverTheAutomaticChoice() {
        val voices = listOf(CloudVoice("lib", "Tara", female = true, hindi = true, paidOnly = true), CloudVoice("free", "Sarah", female = true))
        assertEquals("free", VoiceCatalog.bestDefault(voices)?.id)
        assertTrue(VoiceCatalog.isPaidVoiceError("Unexpected error (HTTP 402): payment_required: Free users cannot use library voices via the API."))
        assertTrue(!VoiceCatalog.isPaidVoiceError("API key was rejected (HTTP 401)"))
    }
}
