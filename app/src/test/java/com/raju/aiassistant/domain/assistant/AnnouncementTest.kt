package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.domain.tools.Contact
import com.raju.aiassistant.domain.tools.distinctContacts
import com.raju.aiassistant.domain.tools.normalizeForWhatsApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementTest {
    @Test
    fun duplicatesAreSuppressedWithinWindow() {
        val d = AnnouncementDeduper(windowMs = 1_000)
        assertTrue(d.shouldAnnounce("com.whatsapp", "Mom", "Khana kha liya?", 0))
        assertFalse(d.shouldAnnounce("com.whatsapp", "Mom", "Khana kha liya?", 500))
        assertTrue(d.shouldAnnounce("com.whatsapp", "Mom", "Ghar kab aaoge?", 600))
        assertTrue(d.shouldAnnounce("com.whatsapp", "Mom", "Khana kha liya?", 2_000)) // window expired
    }

    @Test
    fun capacityIsBounded() {
        val d = AnnouncementDeduper(capacity = 2)
        assertTrue(d.shouldAnnounce("p", "a", "1", 0))
        assertTrue(d.shouldAnnounce("p", "b", "2", 0))
        assertTrue(d.shouldAnnounce("p", "c", "3", 0))
        assertTrue(d.shouldAnnounce("p", "a", "1", 0)) // evicted, so announced again
    }

    @Test
    fun announcementTruncatesLongPreviews() {
        assertEquals("WhatsApp par Mom ka message: Hi", announcementText("WhatsApp", "Mom", "Hi"))
        assertTrue(announcementText("WhatsApp", "Mom", "x".repeat(500)).length < 170)
        assertEquals("Telegram notification", announcementText("Telegram", null, null))
    }

    @Test
    fun whatsappNumberNormalisation() {
        assertEquals("919876543210", normalizeForWhatsApp("98765 43210"))
        assertEquals("919876543210", normalizeForWhatsApp("09876543210"))
        assertEquals("919876543210", normalizeForWhatsApp("+91 98765-43210"))
        assertNull(normalizeForWhatsApp("123"))
    }

    @Test
    fun contactsDedupedByNumber() {
        val list = distinctContacts(listOf(Contact("Mom", "+91 98765 43210"), Contact("Mom", "9876543210"), Contact("Dad", "9123456789")))
        assertEquals(2, list.size)
    }
}
