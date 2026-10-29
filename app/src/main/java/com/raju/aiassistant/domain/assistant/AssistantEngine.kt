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
import kotlinx.coroutines.withTimeoutOrNull
import com.raju.aiassistant.domain.voice.afterWakeWord
import com.raju.aiassistant.domain.voice.isStopCommand
import com.raju.aiassistant.domain.voice.stripEcho
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
private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
/** Consecutive recogniser errors (not silence) tolerated in hands-free mode before it stops. */
private const val MAX_LISTEN_FAILURES = 3
/** A voice message ending in one of these was cut off by a pause: wait for the rest. */
private val CONTINUATION = Regex("(^|\\s)(aur|lekin|par|ki|ke|to|toh|matlab|jaise|and|but|or|so|because|और|लेकिन|पर|कि|के|तो|मतलब|जैसे|क्योंकि)[\\s,.…]*$", RegexOption.IGNORE_CASE)
/** How long to wait for the rest of a sentence that ended in "aur…" / "lekin…". */
private const val CONTINUATION_WAIT_MS = 6_000L

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
    private val transcriber: Transcriber? = null,
    private val conversationMode: ConversationMode = NoConversationMode,
    private val wakeListener: WakeListener = NoWakeListener,
    private val hourOfDay: () -> Int = { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) },
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

    /** Sleep mode (hands-free only): the recogniser is off and a light on-phone detector waits for her name. */
    private var asleep = false
    /** Last time the user spoke or Seema finished a turn; the sleep timer counts from here. */
    private var lastActivityAt = 0L
    /** Low-confidence recognitions in a row; she asks to repeat only for the first. */
    private var unclearInARow = 0
    /** First half of a sentence the user paused in ("… aur"), waiting for the rest. */
    private var heldVoiceText: String? = null
    private var heldJob: Job? = null
    /** The model's reply text for this turn was spoken (so a search needs no "one second…"). */
    private var replySpoken = false

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
        if (fromVoice) { listenFailures = 0; unclearInARow = 0 }
        leaveSleep()
        lastActivityAt = clock()
        cancelWork()
        lastUserText = trimmed
        job = scope.launch { handleUserMessage(trimmed, fromVoice) }
    }

    /**
     * A voice result. People pause mid-sentence ("Mummy ko call karo aur…"): when the words end in a
     * joining word, the assistant keeps listening a few seconds and answers the whole sentence.
     */
    private fun onHeard(heard: String) {
        val text = heldVoiceText?.let { "$it $heard" } ?: heard
        heldVoiceText = null
        heldJob?.cancel()
        if (CONTINUATION.containsMatchIn(text.trim()) && text.trim().split(Regex("\\s+")).size >= 2) {
            heldVoiceText = text
            lastActivityAt = clock()
            heldJob = scope.launch {
                delay(CONTINUATION_WAIT_MS)
                // Nothing more came: answer what was said.
                heldVoiceText?.let { heldVoiceText = null; submit(it, fromVoice = true) }
            }
            listen()
            return
        }
        submit(text, fromVoice = true)
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
        leaveSleep()
        lastActivityAt = clock()
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
        // Opening the app always finds her awake; the sleep timer starts again.
        lastActivityAt = clock()
        if (asleep) {
            leaveSleep()
            _state.value = AssistantState.Idle
        }
        if (settings.value.assistantPaused) return
        // Call-style audio turns on the phone's echo canceller, so the user can talk over Seema.
        if (settings.value.bargeIn) conversationMode.enter()
        val busy = _state.value == AssistantState.Thinking || _state.value == AssistantState.Speaking || _state.value == AssistantState.Executing
        if (!_voiceSession.value && !busy && _pending.value == null) startVoice()
    }

    /** App went to the background: release the microphone. Hands-free resumes with [startHandsFree]. */
    fun suspendListening() {
        conversationMode.exit()
        if (asleep) {
            // No microphone in the background, not even the wake detector.
            leaveSleep()
            _state.value = AssistantState.Idle
        }
        heldJob?.cancel()
        heldVoiceText = null
        if (!_voiceSession.value) return
        _voiceSession.value = false
        voice.stop()
        if (_state.value == AssistantState.Listening) _state.value = AssistantState.Idle
    }

    private fun listen() {
        _state.value = AssistantState.Listening
        voice.start(
            settings.value.speechLanguage,
            onResult = { heard -> onHeard(heard) },
            onError = { err ->
                val keepGoing = handsFree && _voiceSession.value && !settings.value.assistantPaused && !err.permissionDenied
                val held = heldVoiceText
                if (held != null && (err.noSpeech || err.unclear)) {
                    // The pause after "… aur" was the end after all: answer what was said.
                    heldVoiceText = null
                    heldJob?.cancel()
                    submit(held, fromVoice = true)
                } else if (err.unclear && _voiceSession.value && unclearInARow == 0) {
                    // Better to ask again than to act on a misheard word.
                    unclearInARow++
                    job = scope.launch {
                        say(if (settings.value.speechLanguage.startsWith("hi")) "माफ़ कीजिए, ठीक से सुनाई नहीं दिया — एक बार फिर बोलिए?" else "Sorry, I didn't catch that clearly — could you say it again?", allowBargeIn = false)
                        if (_voiceSession.value) listen()
                    }
                } else if (keepGoing && (err.noSpeech || err.unclear)) {
                    // Silence is normal in hands-free mode: listen again, or doze off after a quiet while.
                    if (sleepDue()) goToSleep() else relisten(300)
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
        leaveSleep()
        heldJob?.cancel()
        heldVoiceText = null
        conversationMode.exit()
        _voiceSession.value = false
        voice.stop()
        speaker.stop()
        cancelWork()
        _state.value = AssistantState.Idle
    }

    // ---------------------------------------------------------------- sleep mode

    private fun sleepDue(): Boolean {
        val after = settings.value.sleepAfterSeconds
        return handsFree && after > 0 && _pending.value == null && clock() - lastActivityAt >= after * 1_000L
    }

    /** Quiet for a while: release the recogniser (no beeps, no data) and wait on the phone for her name. */
    private fun goToSleep() {
        voice.stop()
        _voiceSession.value = false
        conversationMode.exit()
        asleep = true
        _state.value = AssistantState.Sleeping
        armWakeListener()
    }

    private fun armWakeListener() {
        if (!asleep) return
        wakeListener.start(
            onUtterance = { pcm -> scope.launch { onWakeAudio(pcm) } },
            onError = { scope.launch { delay(3_000); armWakeListener() } },
        )
    }

    private suspend fun onWakeAudio(pcm: ByteArray) {
        if (!asleep) return
        val t = transcriber
        if (t == null || !t.isAvailable()) {
            // No speech-to-text for the recording: any voice wakes her, and she listens properly.
            wakeUp(greet = false)
            // If it was only the TV, she dozes off again after a short while.
            lastActivityAt = clock() - settings.value.sleepAfterSeconds * 1_000L + 12_000L
            return
        }
        val heard = runCatching { t.transcribe(pcm) }.getOrNull()?.trim().orEmpty()
        if (!asleep) return
        val request = if (settings.value.wakeWord) afterWakeWord(heard) else heard.takeIf { it.length >= 2 }
        when {
            request == null -> armWakeListener() // someone else talking, TV, noise: keep sleeping
            request.isBlank() -> wakeUp() // "Seema?" → "Haan ji, boliye?"
            else -> {
                // "Seema, kal ka mausam batao": wake and answer straight away.
                leaveSleep()
                _voiceSession.value = true
                if (settings.value.bargeIn) conversationMode.enter()
                submit(request, fromVoice = true)
            }
        }
    }

    /** Wakes her from sleep mode (her name, or a tap on the reactor) and listens. */
    fun wakeUp(greet: Boolean = true) {
        if (!asleep) return
        leaveSleep()
        lastActivityAt = clock()
        unclearInARow = 0
        _voiceSession.value = true
        if (settings.value.bargeIn) conversationMode.enter()
        if (!greet) { listen(); return }
        cancelWork()
        job = scope.launch {
            say(if (settings.value.speechLanguage.startsWith("hi")) "हाँ जी, बोलिए?" else "Yes, tell me?", allowBargeIn = false)
            if (_voiceSession.value) listen()
        }
    }

    private fun leaveSleep() {
        if (!asleep) return
        asleep = false
        wakeListener.stop()
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
        val recentOpenings = _transcript.value.asReversed().asSequence()
            .filter { it.role == ChatRole.ASSISTANT }.take(3).map { openingOf(it.text) }.filter { it.isNotBlank() }.toList()
        val context = PromptContext(voiceTurn = fromVoice, recentOpenings = recentOpenings, partOfDay = partOfDay(hourOfDay()))
        val system = buildSystemPrompt(memoryTexts, nowDescription(), s.personality, context)
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
        replySpoken = speak && parsed.text.isNotBlank()
        if (replySpoken) say(parsed.text)

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
        lastActivityAt = clock() // the sleep timer counts from the end of her answer
        if (_voiceSession.value && (s.continuousConversation || handsFree) && !s.assistantPaused) {
            listen()
        } else {
            _voiceSession.value = false
            if (_state.value == AssistantState.Error) scheduleErrorReset()
            else if (_state.value != AssistantState.Offline) _state.value = AssistantState.Idle
        }
    }

    private suspend fun say(text: String, allowBargeIn: Boolean = true, depth: Int = 0) {
        _state.value = AssistantState.Speaking
        val spoken = speakableText(text)
        // In a voice conversation the user can talk over Seema, like interrupting a person.
        val canBargeIn = allowBargeIn && settings.value.bargeIn && (handsFree || _voiceSession.value)
        val capture = canBargeIn && transcriber?.isAvailable() == true
        val utterance = CompletableDeferred<ByteArray?>()
        var interrupted = false
        if (canBargeIn) {
            bargeIn.start(
                capture = capture,
                onInterrupt = { interrupted = true; speaker.stop() },
                onUtterance = { utterance.complete(it) },
            )
        }
        val result = try {
            speaker.speak(spoken)
        } finally {
            if (!interrupted) bargeIn.stop()
        }
        if (interrupted) {
            onInterrupted(spoken, capture, utterance, depth)
            return
        }
        // The same fallback reason (e.g. "ElevenLabs failed …") is shown once, not under every reply.
        if (result.error != null && result.error != lastVoiceNote) note("Voice: ${result.error}")
        lastVoiceNote = result.error
        _state.value = AssistantState.Thinking
    }

    /**
     * The user talked over Seema. Like a person she stops at once and listens, then:
     * - "ruko" / "bas" / "stop" → she stays quiet ("Haan, boliye?") and waits for the user;
     * - a new question or remark → she answers that instead (the old answer is dropped);
     * - nothing understandable (a cough, a noise) → she continues where she stopped.
     * Without a speech-to-text key the normal recogniser takes over right away.
     */
    private suspend fun onInterrupted(spoken: String, capture: Boolean, utterance: CompletableDeferred<ByteArray?>, depth: Int) {
        val played = speaker.playedChunks
        _state.value = AssistantState.Listening
        if (!capture) {
            bargeIn.stop()
            _voiceSession.value = true
            listen()
            throw CancellationException("Interrupted by the user")
        }
        val audio = withTimeoutOrNull(20_000) { utterance.await() }
        bargeIn.stop()
        _state.value = AssistantState.Thinking
        val raw = audio?.let { runCatching { transcriber?.transcribe(it) }.getOrNull() }?.trim().orEmpty()
        val heard = stripEcho(raw, spoken)
        when {
            heard.length < 2 -> {
                // False alarm: carry on from the sentence she was saying.
                val rest = chunkForSpeech(spoken).drop(played).joinToString(" ")
                if (rest.isNotBlank() && depth < 2) say(resumePrefix(rest) + rest, depth = depth + 1)
            }
            isStopCommand(heard) -> {
                record(ChatRole.USER, heard)
                say(if (DEVANAGARI.containsMatchIn(heard)) "हाँ, बोलिए?" else "Haan, boliye?", allowBargeIn = false)
                _voiceSession.value = true
                listen()
                throw CancellationException("Stopped by the user")
            }
            else -> {
                // A new question: answer it now (this cancels the rest of the current turn).
                submit(heard, fromVoice = true)
                throw CancellationException("Interrupted with a new question")
            }
        }
    }

    private fun resumePrefix(rest: String) = if (DEVANAGARI.containsMatchIn(rest)) "हाँ, तो… " else "Haan, toh… "

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
            report(action, ActionStatus.BLOCKED, "Safety Mode is on, so \"${action.label}\" was not performed. Turn it off with \"Turn off\" on the Home screen (or Settings → Safety Mode) to allow it.")
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
        // Searching takes a few seconds: like a person, say so instead of going silent.
        if (speak && !replySpoken) say(if (DEVANAGARI.containsMatchIn(action.query) || settings.value.speechLanguage.startsWith("hi")) "एक सेकंड, देखती हूँ…" else "One second, let me check…", allowBargeIn = false)
        _state.value = AssistantState.Executing
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
