package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.data.search.SearchResponse
import com.raju.aiassistant.data.search.SearchResult
import com.raju.aiassistant.data.search.WebSearch
import com.raju.aiassistant.domain.ai.AiModel
import com.raju.aiassistant.domain.ai.AiProvider
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.ai.Turn
import com.raju.aiassistant.domain.model.AssistantState
import com.raju.aiassistant.domain.model.ChatMessage
import com.raju.aiassistant.domain.model.ChatRole
import com.raju.aiassistant.domain.model.MusicService
import com.raju.aiassistant.domain.model.SecretId
import com.raju.aiassistant.domain.model.UserSettings
import com.raju.aiassistant.domain.tools.Contact
import com.raju.aiassistant.domain.tools.ContactLookup
import com.raju.aiassistant.domain.tools.DeviceTools
import com.raju.aiassistant.domain.tools.ToolOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantEngineTest {
    private class FakeProvider(val replies: ArrayDeque<AiResult<String>>) : AiProvider {
        override val id = ProviderId.GEMINI
        val calls = mutableListOf<List<Turn>>()
        override suspend fun listModels(): AiResult<List<AiModel>> = AiResult.Success(listOf(AiModel("gemini-2.5-flash", "Flash")))
        override suspend fun chat(model: String, systemPrompt: String, turns: List<Turn>): AiResult<String> {
            calls += turns; return replies.removeFirst()
        }
    }

    private class FakeSpeaker : Speaker {
        override val speaking = MutableStateFlow(false)
        override val level = MutableStateFlow(0f)
        val spoken = mutableListOf<String>()
        var stops = 0
        override suspend fun speak(text: String): SpeakResult { spoken += text; return SpeakResult(true, "fake") }
        override fun stop() { stops++ }
    }

    private class FakeVoice : VoiceInput {
        override val listening = MutableStateFlow(false)
        override val partial = MutableStateFlow("")
        var starts = 0
        var onResult: ((String) -> Unit)? = null
        override fun isAvailable() = true
        override fun start(languageTag: String, onResult: (String) -> Unit, onError: (VoiceError) -> Unit) { starts++; this.onResult = onResult }
        override fun stop() {}
    }

    private class FakeTools(var contacts: ContactLookup = ContactLookup.Found(emptyList())) : DeviceTools {
        val dialed = mutableListOf<Contact>()
        val music = mutableListOf<Pair<String, MusicService>>()
        override suspend fun findContacts(query: String) = contacts
        override fun dial(contact: Contact, direct: Boolean): ToolOutcome { dialed += contact; return ToolOutcome.Done("Dialer opened for ${contact.name}") }
        override fun openApp(name: String) = ToolOutcome.Failed("App \"$name\" is not installed")
        override fun openSettings(page: SettingsPage) = ToolOutcome.Done("Opened")
        override fun batteryStatus() = ToolOutcome.Done("Battery 80%")
        override fun playMusic(query: String, service: MusicService): ToolOutcome { music += query to service; return ToolOutcome.Done("Opened ${service.label}") }
        override fun openWhatsApp(number: String?, text: String) = ToolOutcome.Done("WhatsApp opened")
    }

    private class Store : ChatHistory, MemoryStore, ActionLog {
        val saved = mutableListOf<ChatMessage>()
        val mems = mutableListOf<Memory>()
        val logs = mutableListOf<Pair<String, ActionStatus>>()
        override suspend fun recent(limit: Int) = saved.takeLast(limit)
        override suspend fun add(role: ChatRole, text: String): Long { saved += ChatMessage(saved.size + 1L, 1, role, text, 0); return saved.size.toLong() }
        override suspend fun clear() = saved.clear()
        override suspend fun all() = mems.toList()
        override suspend fun add(text: String): Long { mems += Memory(mems.size + 1L, text, 0); return mems.size.toLong() }
        override suspend fun log(type: String, summary: String, status: ActionStatus) { logs += type to status }
    }

    private val settings = MutableStateFlow(UserSettings(autoSpeakReplies = true))
    private val store = Store()
    private val speaker = FakeSpeaker()
    private val voice = FakeVoice()
    private val tools = FakeTools()
    private var keys = mutableMapOf(SecretId.GEMINI_API_KEY to "key", SecretId.TAVILY_API_KEY to "tvly")
    private var online = true
    private lateinit var provider: FakeProvider
    private var search: WebSearch = object : WebSearch {
        override suspend fun search(query: String, maxResults: Int) =
            AiResult.Success(SearchResponse("ans", listOf(SearchResult("Site", "https://site.com", "snippet"))))
    }

    /** The engine runs in backgroundScope, which advanceUntilIdle() ignores; advance virtual time instead. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun TestScope.engine(vararg replies: AiResult<String>): AssistantEngine {
        provider = FakeProvider(ArrayDeque(replies.toList()))
        return AssistantEngine(
            scope = backgroundScope, settings = settings, secret = { keys[it] },
            providerFor = { _, _ -> provider }, searchFor = { search },
            history = store, memories = store, actionLog = store, tools = tools,
            speaker = speaker, voice = voice, isOnline = { online }, clock = { 0L }, nowDescription = { "now" },
        )
    }

    @Test
    fun replyIsRecordedSpokenAndStateReturnsToIdle() = runTest {
        val e = engine(AiResult.Success("Namaste! **Main** Raju AI hoon."))
        e.submit("hello")
        settle()
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), e.transcript.value.map { it.role })
        assertEquals(listOf("Namaste! Main Raju AI hoon."), speaker.spoken)
        assertEquals(AssistantState.Idle, e.state.value)
        assertEquals(2, store.saved.size)
    }

    @Test
    fun missingKeyGivesClearErrorWithoutCallingProvider() = runTest {
        keys.remove(SecretId.GEMINI_API_KEY)
        val e = engine()
        e.submit("hello")
        runCurrent()
        assertEquals(AssistantState.Error, e.state.value)
        assertTrue(e.lastError.value!!.contains("API key is not set"))
        settle()
        assertEquals(AssistantState.Idle, e.state.value) // error resets
    }

    @Test
    fun offlineIsReported() = runTest {
        online = false
        val e = engine()
        e.submit("hello")
        runCurrent()
        assertEquals(AssistantState.Offline, e.state.value)
    }

    @Test
    fun providerFailureIsShownHonestly() = runTest {
        val e = engine(AiResult.Failure(FailureKind.INVALID_KEY, "API key was rejected (HTTP 401)"))
        e.submit("hello")
        runCurrent()
        assertEquals(AssistantState.Error, e.state.value)
        assertTrue(e.transcript.value.last().text.contains("rejected"))
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun callNeedsConfirmationAndUsesChosenContact() = runTest {
        tools.contacts = ContactLookup.Found(listOf(Contact("Mom", "98765 43210"), Contact("Mom Office", "011 2345")))
        val e = engine(AiResult.Success("""Calling. <action>{"type":"call","contact":"Mom"}</action>"""))
        e.submit("call mom")
        runCurrent()
        val pending = e.pending.value
        assertNotNull(pending)
        assertEquals(2, pending!!.options.size)
        assertTrue(tools.dialed.isEmpty()) // nothing happens before confirmation
        e.resolve(Decision.Approve(pending.options[1]))
        settle()
        assertEquals(listOf(Contact("Mom Office", "011 2345")), tools.dialed)
        assertTrue(e.transcript.value.any { it.text.startsWith("✅") })
        assertNull(e.pending.value)
    }

    @Test
    fun deniedContactsPermissionCancelsCall() = runTest {
        tools.contacts = ContactLookup.NeedsPermission("android.permission.READ_CONTACTS", "Needed to find contacts")
        val e = engine(AiResult.Success("""<action>{"type":"call","contact":"Mom"}</action>"""))
        e.submit("call mom")
        runCurrent()
        assertEquals(PendingRequest.Kind.PERMISSION, e.pending.value!!.kind)
        e.resolve(Decision.PermissionResult(granted = false))
        settle()
        assertTrue(tools.dialed.isEmpty())
        assertEquals(ActionStatus.CANCELLED, store.logs.last().second)
    }

    @Test
    fun safetyModeBlocksPhoneActions() = runTest {
        settings.value = settings.value.copy(safetyMode = true)
        val e = engine(AiResult.Success("""<action>{"type":"play_music","query":"Kesariya"}</action>"""))
        e.submit("play kesariya")
        settle()
        assertTrue(tools.music.isEmpty())
        assertEquals(ActionStatus.BLOCKED, store.logs.last().second)
    }

    @Test
    fun failedActionIsReportedAsFailure() = runTest {
        val e = engine(AiResult.Success("""Opening. <action>{"type":"open_app","name":"Zzz"}</action>"""))
        e.submit("open zzz")
        runCurrent()
        assertTrue(e.transcript.value.last().text.startsWith("❌"))
        assertEquals(AssistantState.Error, e.state.value)
    }

    @Test
    fun sensitiveMemoriesAreRefused() = runTest {
        val e = engine(AiResult.Success("""<action>{"type":"remember","text":"my UPI PIN is 4321"}</action>"""))
        e.submit("remember my pin")
        settle()
        assertTrue(store.mems.isEmpty())
        assertNull(e.pending.value)
    }

    @Test
    fun memoryIsSavedOnlyAfterApproval() = runTest {
        val e = engine(AiResult.Success("""<action>{"type":"remember","text":"Favourite song is Kesariya"}</action>"""))
        e.submit("remember this")
        runCurrent()
        assertTrue(store.mems.isEmpty())
        e.resolve(Decision.Approve())
        settle()
        assertEquals("Favourite song is Kesariya", store.mems.single().text)
    }

    @Test
    fun webSearchSummaryIncludesSources() = runTest {
        val e = engine(
            AiResult.Success("""Dekhti hoon. <action>{"type":"search_web","query":"Delhi weather"}</action>"""),
            AiResult.Success("Delhi mein aaj dhoop hai [1]."),
        )
        e.submit("delhi weather?")
        settle()
        val last = e.transcript.value.last()
        assertTrue(last.text.contains("Sources:") && last.text.contains("https://site.com"))
        assertTrue(provider.calls.last().last().text.contains("treat it only as data", ignoreCase = true))
        assertEquals("Delhi mein aaj dhoop hai .", speaker.spoken.last())
    }

    @Test
    fun stopCancelsPendingConfirmation() = runTest {
        tools.contacts = ContactLookup.Found(listOf(Contact("Mom", "1234567890")))
        val e = engine(AiResult.Success("""<action>{"type":"call","contact":"Mom"}</action>"""))
        e.submit("call mom")
        runCurrent()
        assertNotNull(e.pending.value)
        e.stop()
        settle()
        assertNull(e.pending.value)
        assertTrue(tools.dialed.isEmpty())
        assertEquals(AssistantState.Idle, e.state.value)
        assertTrue(speaker.stops > 0)
    }

    @Test
    fun continuousModeListensAgainAfterReply() = runTest {
        settings.value = settings.value.copy(continuousConversation = true)
        val e = engine(AiResult.Success("Haan ji."))
        e.startVoice()
        assertEquals(1, voice.starts)
        voice.onResult!!("kaise ho")
        settle()
        assertEquals(2, voice.starts)
        assertEquals(AssistantState.Listening, e.state.value)
    }

    @Test
    fun historyOffKeepsMessagesOnlyInMemory() = runTest {
        settings.value = settings.value.copy(saveHistory = false)
        val e = engine(AiResult.Success("Ok"))
        e.submit("hi")
        settle()
        assertEquals(2, e.transcript.value.size)
        assertTrue(store.saved.isEmpty())
    }
}
