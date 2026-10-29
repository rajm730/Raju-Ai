package com.raju.aiassistant.domain.notify

import com.raju.aiassistant.domain.assistant.SpeakResult
import com.raju.aiassistant.domain.assistant.Speaker
import com.raju.aiassistant.domain.model.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationFlowTest {
    // ---------------------------------------------------------------- parsing

    private fun f(vararg pairs: Pair<String?, String?>, title: String? = null, text: String? = null, group: String? = null, isGroup: Boolean = false) =
        NotificationFields("com.whatsapp", "k1", title = title, text = text, conversationTitle = group, isGroupConversation = isGroup, messages = pairs.toList())

    @Test
    fun usesLatestMessagingStyleSenderNotGroupName() {
        val item = parseNotification(f("Mom" to "Khana?", "Papa" to "Main aa raha hoon", title = "Family", group = "Family", isGroup = true), "WhatsApp")!!
        assertEquals("Papa", item.sender)
        assertEquals("Family", item.group)
        assertEquals("Main aa raha hoon", item.preview)
        assertEquals("Papa (Family group)", whoText(item))
    }

    @Test
    fun fallsBackToTitleAndText() {
        val item = parseNotification(f(title = "Mom", text = "Ghar kab aaoge?"), "WhatsApp")!!
        assertEquals("Mom", item.sender)
        assertEquals("Ghar kab aaoge?", item.preview)
    }

    @Test
    fun oldStyleGroupTitleIsSplit() {
        val item = parseNotification(f(title = "Family: Mom", text = "Hi"), "WhatsApp")!!
        assertEquals("Mom", item.sender)
        assertEquals("Family", item.group)
    }

    @Test
    fun placeholderTextMeansHiddenPreview() {
        listOf("2 new messages", "New message", "Contents hidden", "5 messages from 2 chats").forEach {
            assertNull(it, parseNotification(f(title = "Mom", text = it), "WhatsApp")!!.preview)
        }
    }

    @Test
    fun summariesAndOngoingNonCallsAreIgnored() {
        assertNull(parseNotification(NotificationFields("com.whatsapp", "k", title = "WhatsApp", text = "3 new messages", isGroupSummary = true), "WhatsApp"))
        assertNull(parseNotification(NotificationFields("com.whatsapp", "k", title = "Backup", text = "Uploading", isOngoing = true), "WhatsApp"))
    }

    @Test
    fun callNotificationIsDetectedWithoutPreview() {
        val item = parseNotification(NotificationFields("com.whatsapp", "k", title = "Mom", text = "Incoming voice call", category = "call", isOngoing = true), "WhatsApp")!!
        assertTrue(item.isCall)
        assertNull(item.preview)
        assertEquals("WhatsApp par Mom ka call aa raha hai.", voipCallText(item))
    }

    @Test
    fun invisibleCharactersAreRemoved() {
        val item = parseNotification(f(title = "‪Mom‬", text = "Hi​"), "WhatsApp")!!
        assertEquals("Mom", item.sender)
        assertEquals("Hi", item.preview)
    }

    @Test
    fun announcementTexts() {
        val item = IncomingItem("k", "com.whatsapp", "WhatsApp", "Mom", null, "Khana kha liya?")
        assertEquals("WhatsApp par Mom ka message aaya hai. Kya main padhkar sunaun?", askToReadText(item))
        assertFalse(askToReadText(item).contains("Khana")) // the question never contains the content
        assertEquals("Mom ne likha: Khana kha liya?", readPreviewText(item))
        assertTrue(readPreviewText(item.copy(preview = "x".repeat(1000))).length < 330)
        assertTrue(hiddenPreviewText(item.copy(preview = null)).contains("Preview chhupa hua hai"))
    }

    // ---------------------------------------------------------------- calls

    @Test
    fun callTextsNeverInventIdentity() {
        assertEquals("Mom ka call aa raha hai.", callAnnouncementText("Mom", "9876543210", speakNumber = false))
        assertEquals("Unknown number se call aa raha hai.", callAnnouncementText(null, "9876543210", speakNumber = false))
        assertEquals("Number 9 8 7 6 5 se call aa raha hai.", callAnnouncementText(null, "98765", speakNumber = true))
        assertEquals("Ek call aa raha hai. Number chhupa hua hai.", callAnnouncementText(null, null, speakNumber = true))
    }

    @Test
    fun callAnnouncedOncePerRing() {
        val d = CallDeduper(windowMs = 60_000)
        assertTrue(d.shouldAnnounce("+91 98765 43210", 0))
        assertFalse(d.shouldAnnounce("9876543210", 10_000))
        assertTrue(d.shouldAnnounce("9876543210", 70_000))
    }

    // ---------------------------------------------------------------- consent flow

    private class FakeSpeaker : Speaker {
        override val speaking = MutableStateFlow(false)
        override val level = MutableStateFlow(0f)
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String): SpeakResult { spoken += text; return SpeakResult(true, "fake") }
        override fun stop() {}
    }

    private val settings = MutableStateFlow(UserSettings(announceNotifications = true))
    private val speaker = FakeSpeaker()
    private fun TestScope.coordinator() = AnnouncementCoordinator(backgroundScope, speaker, settings, consentTimeoutMs = 30_000)
    private fun msg(sender: String, text: String?, key: String = "k-$sender") = IncomingItem(key, "com.whatsapp", "WhatsApp", sender, null, text)

    @Test
    fun asksFirstAndReadsOnlyAfterRead() = runTest {
        val c = coordinator()
        c.onMessage(msg("Mom", "Khana kha liya?"))
        runCurrent()
        assertEquals(listOf("WhatsApp par Mom ka message aaya hai. Kya main padhkar sunaun?"), speaker.spoken)
        val p = c.pending.value
        assertNotNull(p)
        c.read(p!!.id)
        runCurrent()
        assertEquals("Mom ne likha: Khana kha liya?", speaker.spoken.last())
        assertNull(c.pending.value)
    }

    @Test
    fun skipNeverReadsContent() = runTest {
        val c = coordinator()
        c.onMessage(msg("Mom", "Secret"))
        runCurrent()
        c.skip(c.pending.value!!.id)
        runCurrent()
        assertTrue(speaker.spoken.none { it.contains("Secret") })
    }

    @Test
    fun timeoutMeansNo() = runTest {
        val c = coordinator()
        c.onMessage(msg("Mom", "Secret"))
        runCurrent()
        val id = c.pending.value!!.id
        advanceTimeBy(31_000)
        runCurrent()
        assertNull(c.pending.value)
        c.read(id) // too late: ignored
        runCurrent()
        assertTrue(speaker.spoken.none { it.contains("Secret") })
    }

    @Test
    fun stopAndDismissCancelPending() = runTest {
        val c = coordinator()
        c.onMessage(msg("Mom", "Secret", key = "a"))
        c.onMessage(msg("Papa", "Also secret", key = "b"))
        runCurrent()
        c.onRemoved("a") // read in WhatsApp → forget it
        runCurrent()
        assertEquals("Papa", c.pending.value!!.item.sender) // next one is asked about
        c.stopAll()
        runCurrent()
        assertNull(c.pending.value)
        assertTrue(speaker.spoken.none { it.contains("secret", ignoreCase = true) })
    }

    @Test
    fun messagesInSameChatAreMergedNotAskedTwice() = runTest {
        val c = coordinator()
        c.onMessage(msg("Mom", "Pehla"))
        runCurrent()
        c.onMessage(msg("Mom", "Doosra"))
        runCurrent()
        assertEquals(1, speaker.spoken.size)
        val p = c.pending.value!!
        assertEquals(2, p.item.count)
        c.read(p.id)
        runCurrent()
        assertEquals("Mom ne likha: Doosra", speaker.spoken.last())
    }

    @Test
    fun hiddenPreviewOnlyAnnouncesSource() = runTest {
        val c = coordinator()
        c.onMessage(msg("Mom", null))
        runCurrent()
        assertNull(c.pending.value)
        assertTrue(speaker.spoken.single().contains("Preview chhupa hua hai"))
    }

    @Test
    fun askOffReadsDirectlyAndPausedSaysNothing() = runTest {
        settings.value = UserSettings(announceNotifications = true, askBeforeReading = false)
        val c = coordinator()
        c.onMessage(msg("Mom", "Hi"))
        runCurrent()
        assertTrue(speaker.spoken.single().endsWith("Hi"))
        settings.value = UserSettings(announceNotifications = true, assistantPaused = true)
        c.onMessage(msg("Papa", "Hello"))
        runCurrent()
        assertEquals(1, speaker.spoken.size)
    }

    @Test
    fun hindiPlaceholdersMeanHiddenPreview() {
        listOf("नया संदेश", "2 नए संदेश", "संदेश छिपा हुआ है").forEach {
            assertNull(it, parseNotification(f(title = "Mom", text = it), "WhatsApp")!!.preview)
        }
        assertEquals("घर कब आओगे?", parseNotification(f(title = "Mom", text = "घर कब आओगे?"), "WhatsApp")!!.preview)
    }

    @Test
    fun hiddenNumbersAreAlwaysAnnounced() {
        val d = CallDeduper(windowMs = 60_000)
        assertTrue(d.shouldAnnounce(null, 0))
        assertTrue(d.shouldAnnounce(null, 1_000))
        assertTrue(d.shouldAnnounce("", 2_000))
    }

    @Test
    fun readCallbackAlwaysFires() = runTest {
        val c = coordinator()
        var done = 0
        c.read(999) { done++ } // nothing pending: fires immediately
        assertEquals(1, done)
        c.onMessage(msg("Mom", "Hi"))
        runCurrent()
        c.read(c.pending.value!!.id) { done++ }
        runCurrent()
        assertEquals(2, done)
    }
}
