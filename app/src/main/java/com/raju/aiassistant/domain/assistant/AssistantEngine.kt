package com.raju.aiassistant.domain.assistant

import com.raju.aiassistant.data.search.WebSearch
import com.raju.aiassistant.domain.ai.AiProvider
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import com.raju.aiassistant.domain.ai.ModelAdvice
import com.raju.aiassistant.domain.ai.pickDefaultModel
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.model.AssistantState
import com.raju.aiassistant.domain.model.ChatMessage
import com.raju.aiassistant.domain.model.ChatRole
import com.raju.aiassistant.domain.model.SecretId
import com.raju.aiassistant.domain.model.UserSettings
import com.raju.aiassistant.domain.tools.Contact
import com.raju.aiassistant.domain.tools.ContactLookup
import com.raju.aiassistant.domain.tools.DeviceTools
import com.raju.aiassistant.domain.tools.ToolOutcome
import com.raju.aiassistant.domain.tools.distinctContacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/** A question the user must answer before the assistant continues. */
data class PendingRequest(
    val kind: Kind,
    val title: String,
    val message: String,
    val options: List<Contact> = emptyList(),
    val permission: String? = null,
) {
    enum class Kind { PERMISSION, CONFIRM }
}

private const val DAY_MS = 24 * 60 * 60_000L
/** Consecutive recogniser errors (not silence) tolerated in hands-free mode before it stops. */
private const val MAX_LISTEN_FAILURES = 3

sealed interface Decision {
    /** Approve; [option] is the chosen contact when [PendingRequest.options] is not empty. */
    data class Approve(val option: Contact? = null) : Decision
    data object Deny : Decision
    data class PermissionResult(val granted: Boolean) : Decision
}

/**
 * Orchestrates: user text/voice → AI provider → reply (+ speech) → requested tool actions
 * (with confirmation and permissions) → honest result reports. Single source of truth for the
 * assistant's visible state and the chat transcript.
 */
class AssistantEngine(
    private val scope: CoroutineScope,
    private val settings: StateFlow<UserSettings>,
    private val secret: suspend (SecretId) -> String?,
    private val providerFor: (ProviderId, String) -> AiProvider,
    private val searchFor: (String) -> WebSearch,
    private val history: ChatHistory,
    private val memories: MemoryStore,
    private val actionLog: ActionLog,
    private val tools: DeviceTools,
    private val speaker: Speaker,
    private val voice: VoiceInput,
    private val isOnline: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nowDescription: () -> String = { java.util.Date(clock()).toString() },
    private val bargeIn: BargeIn = NoBargeIn,
) {
    private val _state = MutableStateFlow(AssistantState.Idle)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow<List<ChatMessage>>(emptyList())
    val transcript: StateFlow<List<ChatMessage>> = _transcript.asStateFlow()

    private val _pending = MutableStateFlow<PendingRequest?>(null)
    val pending: StateFlow<PendingRequest?> = _pending.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _voiceSession = MutableStateFlow(false)
    /** True while a hands-free voice session is running. */
    val voiceSession: StateFlow<Boolean> = _voiceSession.asStateFlow()

    val speechLevel: StateFlow<Float> get() = speaker.level
    val listening: StateFlow<Boolean> get() = voice.listening
    val partialTranscript: StateFlow<String> get() = voice.partial

    private var job: Job? = null
    private val submitGuard = SubmitGuard()
    private var lastUserText: String? = null
    private val warnedModels = mutableSetOf<String>()
    private var decision: CompletableDeferred<Decision>? = null
    private var localId = -1L
    private var lastVoiceNote: String? = null

    /** Hands-free mode: keep listening (also after silence) until stop() is called. */
    private var handsFree = false
    private var listenFailures = 0
    private val _handsFreeActive = MutableStateFlow(false)
    /** True while the assistant is in hands-free (always listening) mode. */
    val handsFreeActive: StateFlow<Boolean> = _handsFreeActive.asStateFlow()

    /** Loads saved chat history into the transcript (call once at startup). */
    suspend fun load() {
        if (!settings.value.saveHistory) return
        runCatching { history.recent(100) }.onSuccess { saved ->
            _transcript.value = saved + _transcript.value
        }
    }

    // ---------------------------------------------------------------- input

    fun submit(text: String, fromVoice: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // One user submission → one request: ignore an identical message sent again within a moment.
        if (!submitGuard.accept(trimmed, clock())) return
        if (!fromVoice) voice.stop() // typed while listening: don't also take a voice result
        if (fromVoice) listenFailures = 0
        cancelWork()
        lastUserText = trimmed
        job = scope.launch { handleUserMessage(trimmed, fromVoice) }
    }

    /** True when the last request failed and can be sent again. */
    val canRetry: Boolean get() = lastUserText != null && _lastError.value != null

    /** Re-sends the last message after a failure, without adding it to the chat again. */
    fun retry() {
        val text = lastUserText ?: return
        cancelWork()
        job = scope.launch { handleUserMessage(text, fromVoice = false, recordUser = false) }
    }

    /** Starts listening (tap-to-talk). In continuous mode, listening resumes after each reply. */
    fun startVoice() {
        if (!voice.isAvailable()) {
            failAsync("Speech recognition is not available on this phone. Install/enable Google speech services.")
            return
        }
        cancelWork()
        speaker.stop()
        _voiceSession.value = true
        listen()
    }

    /**
     * Hands-free mode: listen now and keep listening after every reply and after silence, until
     * [stop] is pressed (or the app goes to the background → [suspendListening]).
     */
    fun startHandsFree() {
        handsFree = true
        _handsFreeActive.value = true
        listenFailures = 0
        if (settings.value.assistantPaused) return
        val busy = _state.value == AssistantState.Thinking || _state.value == AssistantState.Speaking || _state.value == AssistantState.Executing
        if (!_voiceSession.value && !busy && _pending.value == null) startVoice()
    }

    /** App went to the background: release the microphone. Hands-free resumes with [startHandsFree]. */
    fun suspendListening() {
        if (!_voiceSession.value) return
        _voiceSession.value = false
        voice.stop()
        if (_state.value == AssistantState.Listening) _state.value = AssistantState.Idle
    }

    private fun listen() {
        _state.value = AssistantState.Listening
        voice.start(
            settings.value.speechLanguage,
            onResult = { heard -> submit(heard, fromVoice = true) },
            onError = { err ->
                val keepGoing = handsFree && _voiceSession.value && !settings.value.assistantPaused && !err.permissionDenied
                if (keepGoing && err.noSpeech) {
                    // Silence is normal in hands-free mode: just listen again.
                    relisten(300)
                } else if (keepGoing && listenFailures < MAX_LISTEN_FAILURES) {
                    listenFailures++
                    relisten(2_000)
                } else {
                    _voiceSession.value = false
                    if (!err.noSpeech) {
                        // A real problem (no permission, recogniser keeps failing): leave hands-free mode.
                        handsFree = false
                        _handsFreeActive.value = false
                    }
                    if (err.noSpeech) _state.value = AssistantState.Idle else failAsync(err.message)
                }
            },
        )
    }

    private fun relisten(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            if (handsFree && _voiceSession.value && _state.value == AssistantState.Listening) listen()
        }
    }

    /** Stop button: immediately stops listening, speech, pending questions and any running request. */
    fun stop() {
        handsFree = false
        _handsFreeActive.value = false
        _voiceSession.value = false
        voice.stop()
        speaker.stop()
        cancelWork()
        _state.value = AssistantState.Idle
    }

    fun resolve(d: Decision) {
        decision?.complete(d)
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
    }

    // ---------------------------------------------------------------- core flow

    private suspend fun handleUserMessage(text: String, fromVoice: Boolean, recordUser: Boolean = true) {
        _lastError.value = null
        if (recordUser) record(ChatRole.USER, text)
        val s = settings.value

        if (!isOnline()) {
            fail("No internet connection. AI replies need internet.", AssistantState.Offline)
            return
        }
        val providerId = s.defaultProvider
        val key = secret(providerId.secret)
        if (key.isNullOrBlank()) {
            fail("${providerId.displayName} API key is not set. Add it in Settings.")
            return
        }
        val provider = providerFor(providerId, key)
        val model = s.modelFor(providerId) ?: when (val models = provider.listModels()) {
            is AiResult.Success -> pickDefaultModel(providerId, models.value)?.id
            is AiResult.Failure -> { fail(models.message); return }
        }
        if (model == null) {
            fail("No chat model is available for this ${providerId.shortName} key.")
            return
        }

        ModelAdvice.warning(model)?.let { w ->
            if (warnedModels.add(model)) note("⚠️ ${providerId.shortName} model \"$model\": $w")
        }
        _state.value = AssistantState.Thinking
        val memoryTexts = if (s.memoryEnabled) runCatching { memories.all().map { it.text } }.getOrDefault(emptyList()) else emptyList()
        val system = buildSystemPrompt(memoryTexts, nowDescription(), s.personality)
        val turns = toTurns(_transcript.value, s.historyLimit * 2, ModelAdvice.historyCharBudget(model))

        val raw = when (val r = provider.chat(model, system, turns)) {
            is AiResult.Success -> r.value
            is AiResult.Failure -> { fail(r.message, if (r.kind == FailureKind.NETWORK) AssistantState.Offline else AssistantState.Error); return }
        }
        val parsedRaw = parseReply(raw)
        val repetition = collapseRepetition(parsedRaw.text)
        val parsed = parsedRaw.copy(text = repetition.text)
        if (parsed.text.isBlank() && parsed.actions.isEmpty()) {
            fail("${providerId.shortName} returned an empty reply (model $model). Tap Retry or choose another model.")
            return
        }
        if (parsed.text.isNotBlank()) record(ChatRole.ASSISTANT, parsed.text)
        if (repetition.degenerate) {
            note("⚠️ The reply from model \"$model\" was repeating itself, so the repeats were removed. A recommended model in Settings usually fixes this.")
        }

        val speak = fromVoice || s.autoSpeakReplies
        if (speak && parsed.text.isNotBlank()) say(parsed.text)

        var hadActions = false
        for (action in parsed.actions) {
            hadActions = true
            execute(action, provider, model, system, speak)
        }

        if (hadActions && _state.value != AssistantState.Error) {
            _state.value = AssistantState.Success
            delay(1_200)
        }
        finishTurn()
    }

    private fun finishTurn() {
        val s = settings.value
        if (_voiceSession.value && (s.continuousConversation || handsFree) && !s.assistantPaused) {
            listen()
        } else {
            _voiceSession.value = false
            if (_state.value == AssistantState.Error) scheduleErrorReset()
            else if (_state.value != AssistantState.Offline) _state.value = AssistantState.Idle
        }
    }

    private suspend fun say(text: String) {
        _state.value = AssistantState.Speaking
        // In a voice conversation the user can talk over Seema to stop her (barge-in).
        var interrupted = false
        val canBargeIn = settings.value.bargeIn && (handsFree || _voiceSession.value)
        if (canBargeIn) bargeIn.start { interrupted = true; speaker.stop() }
        val result = try {
            speaker.speak(speakableText(text))
        } finally {
            bargeIn.stop()
        }
        // The same fallback reason (e.g. "ElevenLabs failed …") is shown once, not under every reply.
        if (!interrupted && result.error != null && result.error != lastVoiceNote) note("Voice: ${result.error}")
        if (!interrupted) lastVoiceNote = result.error
        _state.value = AssistantState.Thinking
    }

    /** "Ruko": stop speaking right now but keep the conversation (hands-free listens again next). */
    fun interrupt() {
        speaker.stop()
    }

    // ---------------------------------------------------------------- actions

    private suspend fun execute(action: AssistantAction, provider: AiProvider, model: String, system: String, speak: Boolean) {
        val s = settings.value
        val sensitive = action is AssistantAction.Call || action is AssistantAction.WhatsApp ||
            action is AssistantAction.OpenApp || action is AssistantAction.PlayMusic || action is AssistantAction.Sms
        if (s.safetyMode && sensitive) {
            report(action, ActionStatus.BLOCKED, "Safety Mode is on, so \"${action.label}\" was not performed. Turn Safety Mode off in Settings to allow it.")
            return
        }
        _state.value = AssistantState.Executing
        when (action) {
            is AssistantAction.Call -> callContact(action)
            is AssistantAction.WhatsApp -> whatsApp(action)
            is AssistantAction.OpenApp -> report(action, tools.openApp(action.name))
            is AssistantAction.OpenSettings -> report(action, tools.openSettings(action.page))
            is AssistantAction.Battery -> {
                val outcome = tools.batteryStatus()
                report(action, outcome)
                // Informational result: in a voice conversation the user expects to hear it.
                if (speak && outcome is ToolOutcome.Done) say(outcome.message)
            }
            is AssistantAction.PlayMusic -> report(action, tools.playMusic(action.query, action.service ?: s.musicService))
            is AssistantAction.Remember -> remember(action)
            is AssistantAction.SetTimer -> report(action, tools.setTimer(action.seconds, action.note))
            is AssistantAction.SetAlarm -> report(action, tools.setAlarm(action.hour, action.minute, action.note))
            is AssistantAction.Flashlight -> report(action, tools.setFlashlight(action.on))
            is AssistantAction.Volume -> report(action, tools.setVolume(action.percent))
            is AssistantAction.Media -> report(action, tools.mediaControl(action.command))
            is AssistantAction.Sms -> sendSms(action)
            is AssistantAction.AddEvent -> addEvent(action)
            is AssistantAction.Navigate -> report(action, tools.navigate(action.destination, action.mode))
            is AssistantAction.CopyText -> report(action, tools.copyToClipboard(action.text))
            is AssistantAction.SearchWeb -> searchWeb(action, provider, model, system, speak)
        }
    }

    private suspend fun callContact(action: AssistantAction.Call) {
        val candidates: List<Contact> = if (action.number != null && action.contact == null) {
            listOf(Contact(action.number, action.number))
        } else {
            when (val found = lookup(action.contact ?: action.number.orEmpty())) {
                null -> { report(action, ActionStatus.CANCELLED, "Contacts permission was not granted, so I could not find ${action.contact}."); return }
                else -> found
            }
        }
        if (candidates.isEmpty()) {
            report(action, ActionStatus.FAILED, "No contact named \"${action.contact}\" was found.")
            return
        }
        val chosen = confirmContact(
            title = "Call?",
            message = if (candidates.size == 1) "Call ${candidates[0].name} (${candidates[0].number})?" else "Multiple contacts match \"${action.contact}\". Choose one to call:",
            candidates = candidates,
        ) ?: run { report(action, ActionStatus.CANCELLED, "Call cancelled."); return }
        report(action, tools.dial(chosen, settings.value.directCalling))
    }

    private suspend fun sendSms(action: AssistantAction.Sms) {
        val candidates: List<Contact> = if (action.number != null && action.contact == null) {
            listOf(Contact(action.number, action.number))
        } else {
            when (val found = lookup(action.contact ?: action.number.orEmpty())) {
                null -> { report(action, ActionStatus.CANCELLED, "Contacts permission was not granted, so I could not find ${action.contact}."); return }
                else -> found
            }
        }
        if (candidates.isEmpty()) {
            report(action, ActionStatus.FAILED, "No contact named \"${action.contact}\" was found.")
            return
        }
        val chosen = confirmContact(
            title = "SMS bhejein?",
            message = if (candidates.size == 1) "${candidates[0].name} (${candidates[0].number}) ko yeh SMS bheja jayega:\n\n\"${action.text}\""
            else "Kis contact ko SMS bhejna hai?\n\n\"${action.text}\"",
            candidates = candidates,
        ) ?: run { report(action, ActionStatus.CANCELLED, "SMS cancelled."); return }
        val smsPermission = "android.permission.SEND_SMS"
        if (!tools.hasPermission(smsPermission)) {
            val d = ask(PendingRequest(PendingRequest.Kind.PERMISSION, "SMS permission", "SMS bhejne ke liye permission chahiye. SMS aapke SIM se jayega (carrier charges lag sakte hain).", permission = smsPermission))
            if (d !is Decision.PermissionResult || !d.granted) { report(action, ActionStatus.CANCELLED, "SMS permission was not granted, so nothing was sent."); return }
        }
        report(action, tools.sendSms(chosen, action.text))
    }

    private suspend fun addEvent(action: AssistantAction.AddEvent) {
        val zone = ZoneId.systemDefault()
        var start = action.start
        fun millis() = runCatching { start.atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        // "8 baje class" said after 8 o'clock today means tomorrow: a time that passed in the last day moves forward one day.
        val first = millis()
        if (first != null && first < clock() - 5 * 60_000L && first > clock() - DAY_MS) start = start.plusDays(1)
        val startMillis = millis()
        if (startMillis == null || startMillis < clock() - 5 * 60_000L) {
            report(action, ActionStatus.FAILED, "The event time ${action.start} is in the past or invalid, so the calendar was not opened. Please say the date and time again.")
            return
        }
        report(action, tools.addCalendarEvent(action.title, startMillis, action.durationMinutes, action.location, action.repeat?.rrule))
    }

    private suspend fun whatsApp(action: AssistantAction.WhatsApp) {
        var target: Contact? = null
        if (action.contact != null) {
            val found = lookup(action.contact)
            if (found.isNullOrEmpty()) {
                note(if (found == null) "Contacts permission not granted; WhatsApp will open without a recipient." else "No contact named \"${action.contact}\"; WhatsApp will open without a recipient.")
            } else {
                target = confirmContact("Send on WhatsApp?", "Open WhatsApp with this message for you to review and send:\n\n\"${action.text}\"", found)
                    ?: run { report(action, ActionStatus.CANCELLED, "WhatsApp draft cancelled."); return }
            }
        }
        if (target == null) {
            val ok = ask(PendingRequest(PendingRequest.Kind.CONFIRM, "Open WhatsApp?", "Open WhatsApp with this message? You choose the chat and tap send yourself.\n\n\"${action.text}\""))
            if (ok !is Decision.Approve) { report(action, ActionStatus.CANCELLED, "WhatsApp draft cancelled."); return }
        }
        report(action, tools.openWhatsApp(target?.number, action.text))
    }

    /** Returns contacts, or null if permission was refused. Handles the permission prompt. */
    private suspend fun lookup(query: String): List<Contact>? {
        repeat(2) {
            when (val r = tools.findContacts(query)) {
                is ContactLookup.Found -> return distinctContacts(r.contacts)
                is ContactLookup.Failed -> { note(r.message); return emptyList() }
                is ContactLookup.NeedsPermission -> {
                    val d = ask(PendingRequest(PendingRequest.Kind.PERMISSION, "Contacts permission", r.reason, permission = r.permission))
                    if (d !is Decision.PermissionResult || !d.granted) return null
                }
            }
        }
        return null
    }

    private suspend fun confirmContact(title: String, message: String, candidates: List<Contact>): Contact? {
        val d = ask(PendingRequest(PendingRequest.Kind.CONFIRM, title, message, options = candidates.take(8)))
        return when {
            d !is Decision.Approve -> null
            d.option != null -> d.option
            candidates.size == 1 -> candidates[0]
            else -> null
        }
    }

    private suspend fun remember(action: AssistantAction.Remember) {
        val s = settings.value
        when {
            !s.memoryEnabled -> report(action, ActionStatus.BLOCKED, "Memory is turned off in Settings, so nothing was saved.")
            else -> when (val check = validateMemory(action.text, runCatching { memories.all().map { it.text } }.getOrDefault(emptyList()))) {
                is MemoryCheck.Rejected -> report(action, ActionStatus.BLOCKED, check.reason)
                is MemoryCheck.Ok -> {
                    val d = ask(PendingRequest(PendingRequest.Kind.CONFIRM, "Remember this?", check.text))
                    if (d !is Decision.Approve) report(action, ActionStatus.CANCELLED, "Not saved.")
                    else {
                        val saved = runCatching { memories.add(check.text) }.isSuccess
                        report(action, if (saved) ActionStatus.SUCCESS else ActionStatus.FAILED, if (saved) "Saved to memory: \"${check.text}\"" else "Could not save the memory.")
                    }
                }
            }
        }
    }

    private suspend fun searchWeb(action: AssistantAction.SearchWeb, provider: AiProvider, model: String, system: String, speak: Boolean) {
        val key = secret(SecretId.TAVILY_API_KEY)
        if (key.isNullOrBlank()) {
            report(action, ActionStatus.FAILED, "Web search needs a Tavily API key (Settings → Web search).")
            return
        }
        val response = when (val r = searchFor(key).search(action.query, topic = action.topic)) {
            is AiResult.Success -> r.value
            is AiResult.Failure -> { report(action, ActionStatus.FAILED, "Web search failed: ${r.message}"); return }
        }
        if (response.results.isEmpty()) {
            report(action, ActionStatus.FAILED, "Web search returned no results for \"${action.query}\".")
            return
        }
        actionLog.safeLog(action, ActionStatus.SUCCESS, "${response.results.size} results")
        _state.value = AssistantState.Thinking
        val followUp = buildSearchFollowUp(action.query, response.results.map { it.title to it.url }, response.results.map { it.snippet }, response.answer)
        val turns = toTurns(_transcript.value, settings.value.historyLimit * 2, ModelAdvice.historyCharBudget(model)) +
            com.raju.aiassistant.domain.ai.Turn(com.raju.aiassistant.domain.ai.TurnRole.USER, followUp)
        val sources = response.results.mapIndexed { i, r ->
            "[${i + 1}] ${r.title} — ${r.domain}" + (r.publishedDate?.let { " ($it)" } ?: "") + "\n${r.url}"
        }.joinToString("\n")
        when (val r = provider.chat(model, system, turns)) {
            is AiResult.Success -> {
                val summary = parseReply(r.value).text // actions in follow-ups are ignored on purpose
                record(ChatRole.ASSISTANT, "$summary\n\nSources:\n$sources")
                if (speak) say(summary)
            }
            is AiResult.Failure -> {
                record(ChatRole.SYSTEM, "Found sources but the AI summary failed (${r.message}).\n$sources")
                _state.value = AssistantState.Error
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun ask(request: PendingRequest): Decision {
        val d = CompletableDeferred<Decision>()
        decision = d
        _pending.value = request
        return try {
            d.await()
        } finally {
            if (decision === d) {
                _pending.value = null
                decision = null
            }
        }
    }

    private suspend fun report(action: AssistantAction, outcome: ToolOutcome) {
        when (outcome) {
            is ToolOutcome.Done -> report(action, ActionStatus.SUCCESS, outcome.message)
            is ToolOutcome.Failed -> report(action, ActionStatus.FAILED, outcome.message)
            is ToolOutcome.NeedsPermission -> report(action, ActionStatus.FAILED, outcome.reason)
        }
    }

    private suspend fun report(action: AssistantAction, status: ActionStatus, message: String) {
        val icon = when (status) {
            ActionStatus.SUCCESS -> "✅"
            ActionStatus.FAILED -> "❌"
            ActionStatus.CANCELLED -> "⏹️"
            ActionStatus.BLOCKED -> "🛡️"
        }
        record(ChatRole.SYSTEM, "$icon $message")
        actionLog.safeLog(action, status, message)
        if (status == ActionStatus.FAILED) _state.value = AssistantState.Error
    }

    private suspend fun ActionLog.safeLog(action: AssistantAction, status: ActionStatus, message: String) {
        runCatching { log(action.javaClass.simpleName, "${action.label} — $message".take(300), status) }
    }

    private suspend fun note(text: String) = record(ChatRole.SYSTEM, text)

    private suspend fun record(role: ChatRole, text: String) {
        val saved = if (settings.value.saveHistory) runCatching { history.add(role, text) }.getOrNull() else null
        val message = ChatMessage(id = saved ?: localId--, conversationId = 0, role = role, text = text, createdAt = clock())
        _transcript.value = _transcript.value + message
    }

    private suspend fun fail(message: String, state: AssistantState = AssistantState.Error) {
        _lastError.value = message
        record(ChatRole.SYSTEM, "⚠️ $message")
        _state.value = state
        _voiceSession.value = false
        scheduleErrorReset()
        if (handsFree && !settings.value.assistantPaused && voice.isAvailable()) {
            // Hands-free: show the error, then go back to listening.
            scope.launch {
                delay(4_500)
                if (handsFree && !_voiceSession.value && job?.isActive != true && _pending.value == null) startVoice()
            }
        }
    }

    /** Error is shown briefly, then the reactor returns to Ready. */
    private fun scheduleErrorReset() {
        scope.launch {
            delay(4_000)
            if (_state.value == AssistantState.Error) _state.value = AssistantState.Idle
        }
    }

    private fun failAsync(message: String) {
        scope.launch { fail(message, AssistantState.Error) }
    }

    private fun cancelWork() {
        decision?.complete(Decision.Deny)
        job?.cancel(CancellationException("Stopped by user"))
        job = null
    }
}
