package com.raju.aiassistant.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raju.aiassistant.BuildConfig
import com.raju.aiassistant.data.tts.LanguageSupport
import com.raju.aiassistant.domain.ai.ModelAdvice
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.model.AnnounceApp
import com.raju.aiassistant.domain.model.MusicService
import com.raju.aiassistant.domain.model.SARVAM_FEMALE_SPEAKERS
import com.raju.aiassistant.domain.model.SPEECH_LANGUAGES
import com.raju.aiassistant.domain.model.SecretId
import com.raju.aiassistant.domain.model.Personality
import com.raju.aiassistant.domain.model.SttEngine
import com.raju.aiassistant.domain.model.TtsEngine
import com.raju.aiassistant.domain.voice.VoiceCatalog
import com.raju.aiassistant.ui.RajuViewModelFactory
import com.raju.aiassistant.ui.components.HudPanel
import com.raju.aiassistant.ui.components.StatusChip
import com.raju.aiassistant.ui.theme.ErrorRed
import com.raju.aiassistant.ui.theme.SuccessGreen
import com.raju.aiassistant.ui.theme.TextSecondary
import com.raju.aiassistant.ui.theme.WarningAmber
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = RajuViewModelFactory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val voiceTest by viewModel.voiceTest.collectAsStateWithLifecycle()
    val activeEngine by viewModel.activeTtsEngine.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val elevenModels by viewModel.elevenModels.collectAsStateWithLifecycle()
    val elevenVoices by viewModel.elevenVoices.collectAsStateWithLifecycle()
    val cartesiaVoices by viewModel.cartesiaVoices.collectAsStateWithLifecycle()
    val voiceListMessage by viewModel.voiceListMessage.collectAsStateWithLifecycle()
    val s = state.settings
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    // Re-check the real permission/access state every time the user returns from system settings.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycleState) { if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.refreshDiagnostics() }
    val diag by viewModel.diagnostics.collectAsStateWithLifecycle()
    val notificationAccess = diag.listenerEnabled

    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setDirectCalling(granted)
    }
    val refreshAfter = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshDiagnostics() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshDiagnostics() }
    val callRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshDiagnostics()
    }
    fun open(intent: Intent) = runCatching { context.startActivity(intent) }
    fun appInfo() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    fun appNotificationSettings() = open(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )
    fun askPostNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else appNotificationSettings()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        info?.let { Text(it, color = TextSecondary) }

        // ---------------------------------------------------------------- diagnostics
        HudPanel(title = "Status & permissions") {
            Text("Each item shows the real current state. Tap a button to fix it in Android settings.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            val test = s.lastTests[s.defaultProvider]
            StatusRow(
                "AI (${s.defaultProvider.shortName})",
                when {
                    test == null -> "not tested — tap Test below"
                    test.success -> "last test connected"
                    else -> "last test failed"
                },
                ok = test?.success == true,
            )
            StatusRow("Microphone", if (diag.micGranted) "allowed" else "not allowed", diag.micGranted, if (diag.micGranted) null else "Allow" to { refreshAfter.launch(Manifest.permission.RECORD_AUDIO) })
            StatusRow("Speech recognition", if (diag.speechRecognitionAvailable) "available" else "not available — install Google speech services", diag.speechRecognitionAvailable)
            StatusRow("Contacts", if (diag.contactsGranted) "allowed" else "not allowed (needed for calls by name)", diag.contactsGranted, if (diag.contactsGranted) null else "Allow" to { refreshAfter.launch(Manifest.permission.READ_CONTACTS) })
            StatusRow(
                "Notification access",
                when {
                    diag.listenerEnabled && diag.listenerConnected -> "on and connected"
                    diag.listenerEnabled -> "on, waiting for Android to connect"
                    else -> "off"
                },
                diag.listenerEnabled,
                "Open" to { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
            if (!diag.listenerEnabled) {
                Text(
                    "If the Seema AI switch is greyed out or won't turn on: Android blocks this for apps installed from an APK file. Open App info → ⋮ (top right) → \"Allow restricted settings\" → confirm, then come back and switch it on.",
                    style = MaterialTheme.typography.bodyMedium, color = WarningAmber,
                )
                OutlinedButton(onClick = { appInfo() }) { Text("Open App info") }
            }
            StatusRow(
                "App notifications",
                if (diag.appNotificationsAllowed) "allowed" else "blocked (needed for Read/Skip while the app is closed)",
                diag.appNotificationsAllowed,
                if (diag.appNotificationsAllowed) null else "Allow" to { askPostNotifications() },
            )
            StatusRow(
                "Call announcements",
                when {
                    !diag.callRoleSupported -> "not supported on this phone"
                    diag.callRoleHeld -> "call-screening role granted"
                    else -> "role not granted"
                },
                diag.callRoleHeld,
                if (diag.callRoleSupported && !diag.callRoleHeld) "Grant" to { viewModel.callRoleRequestIntent()?.let { callRole.launch(it) } } else null,
            )
            val tts = diag.tts
            StatusRow(
                "Android voice (Hindi)",
                if (tts == null) "checking…" else "${tts.hindi.label} · engine ${tts.engine ?: "none"}",
                tts?.hindi == LanguageSupport.AVAILABLE,
                if (tts != null && tts.hindi != LanguageSupport.AVAILABLE) "Install" to { open(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } else null,
            )
            val voiceKey = when (s.ttsEngine) {
                TtsEngine.SARVAM -> SecretId.SARVAM_API_KEY
                TtsEngine.ELEVENLABS -> SecretId.ELEVENLABS_API_KEY
                TtsEngine.CARTESIA -> SecretId.CARTESIA_API_KEY
                TtsEngine.DEEPGRAM -> SecretId.DEEPGRAM_API_KEY
                TtsEngine.ANDROID -> null
            }
            val voiceKeySaved = voiceKey == null || state.secrets[voiceKey]?.configured == true
            StatusRow(
                "Seema's voice",
                s.ttsEngine.label + if (voiceKeySaved) " · ready" else " · key not set (Android voice is used)",
                voiceKeySaved,
            )
            val deepgramSaved = state.secrets[SecretId.DEEPGRAM_API_KEY]?.configured == true
            StatusRow(
                "Listening",
                when {
                    s.sttEngine == SttEngine.DEEPGRAM && deepgramSaved -> "Deepgram ${VoiceCatalog.sttOption(s.deepgramSttModel).title}" + if (s.autoListen) " · hands-free" else ""
                    s.sttEngine == SttEngine.DEEPGRAM -> "Deepgram key not set — Google recogniser is used"
                    else -> "Android / Google" + if (s.autoListen) " · hands-free" else ""
                },
                s.sttEngine != SttEngine.DEEPGRAM || deepgramSaved,
            )
            if (s.assistantPaused) StatusRow("Assistant", "paused — no listening or announcements", false, "Resume" to { viewModel.setPaused(false) })
            OutlinedButton(onClick = { open(Intent("com.android.settings.TTS_SETTINGS")) }) { Text("Android text-to-speech settings") }
        }

        // ---------------------------------------------------------------- AI
        HudPanel(title = "AI provider") {
            Text("Keys are encrypted with Android Keystore and stored only on this phone. They are sent only to their own provider over HTTPS.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            ProviderId.entries.forEach { p ->
                RadioRow(p.displayName, s.defaultProvider == p) { viewModel.setDefaultProvider(p) }
            }
        }
        ProviderId.entries.forEach { p ->
            SecretCard(p.secret, state.secrets[p.secret] ?: SecretStatus(), viewModel) {
                val test = s.lastTests[p]
                if (test != null) {
                    Text(
                        (if (test.success) "Last test: connected" else "Last test failed: ${test.message}") +
                            " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(test.timestamp)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (test.success) SuccessGreen else ErrorRed,
                    )
                }
                ModelPicker(
                    provider = p,
                    selected = s.modelFor(p),
                    options = models[p].orEmpty().map { it.id },
                    onLoad = { viewModel.loadModels(p) },
                    onSelect = { viewModel.selectModel(p, it) },
                )
            }
        }

        // ---------------------------------------------------------------- search
        SecretCard(SecretId.TAVILY_API_KEY, state.secrets[SecretId.TAVILY_API_KEY] ?: SecretStatus(), viewModel, title = "Web search (Tavily)") {
            Text("Used when you ask about news, prices, weather or anything current. Sources are shown with links.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }

        // ---------------------------------------------------------------- voice
        HudPanel(title = "Voice") {
            Text("Speech output (Seema's voice)", style = MaterialTheme.typography.titleMedium)
            Text("If the chosen voice fails (no key, no internet, credits used up), the next one is used automatically.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            TtsEngine.entries.forEach { e ->
                val keyMissing = when (e) {
                    TtsEngine.SARVAM -> state.secrets[SecretId.SARVAM_API_KEY]?.configured != true
                    TtsEngine.ELEVENLABS -> state.secrets[SecretId.ELEVENLABS_API_KEY]?.configured != true
                    TtsEngine.CARTESIA -> state.secrets[SecretId.CARTESIA_API_KEY]?.configured != true
                    TtsEngine.DEEPGRAM -> state.secrets[SecretId.DEEPGRAM_API_KEY]?.configured != true
                    TtsEngine.ANDROID -> false
                }
                HintRadioRow(e.label, e.hint + if (keyMissing) " · key not set" else "", s.ttsEngine == e) { viewModel.setTtsEngine(e) }
            }
            if (s.ttsEngine == TtsEngine.SARVAM) {
                Text("Sarvam voice (female)", style = MaterialTheme.typography.bodyMedium)
                ChipRow(SARVAM_FEMALE_SPEAKERS, s.sarvamSpeaker, { it.replaceFirstChar(Char::uppercase) }) { viewModel.setSarvamSpeaker(it) }
                Text("Needs internet and a Sarvam key (below). Suggestion: Priya or Kavya for a sweet tone.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            } else if (s.ttsEngine == TtsEngine.ANDROID) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Android voice: " + (voices.firstOrNull { it.name == s.androidVoice }?.label ?: s.androidVoice ?: "Default"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    VoicePicker(voices, onLoad = viewModel::loadVoices, onSelect = viewModel::setAndroidVoice)
                }
            }
            EngineDetails(s, viewModel, elevenModels, elevenVoices, cartesiaVoices, voiceListMessage)
            var rate by remember(s.speechRate) { mutableFloatStateOf(s.speechRate) }
            Text("Speaking speed: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f, steps = 14, onValueChangeFinished = { viewModel.setSpeechRate(rate) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::testVoice) { Text("Test voice") }
                OutlinedButton(onClick = viewModel::stopVoice) { Text("Stop") }
            }
            voiceTest?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
            activeEngine?.let { Text("Active: $it", style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }

            SpeechInputSettings(s, viewModel, state.secrets[SecretId.DEEPGRAM_API_KEY]?.configured == true)
            Text("Speech input language", style = MaterialTheme.typography.titleMedium)
            ChipRow(SPEECH_LANGUAGES.map { it.first }, s.speechLanguage, { tag -> SPEECH_LANGUAGES.first { it.first == tag }.second }) { viewModel.setSpeechLanguage(it) }
            ToggleRow("Speak replies aloud", s.autoSpeakReplies, viewModel::setAutoSpeak)
            ToggleRow("Hands-free: start listening when the app opens and keep listening until you press Stop", s.autoListen, viewModel::setAutoListen)
            Text(
                "The microphone is used only while Seema AI is on screen; it stops when you leave the app. Tip: Deepgram listening (above) is silent — the Google recogniser may beep each time it restarts.",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
            )
            ToggleRow("Continuous conversation (keeps listening after each reply while the app is open)", s.continuousConversation, viewModel::setContinuous)

            Text("Personality", style = MaterialTheme.typography.titleMedium)
            Personality.entries.forEach { p -> HintRadioRow(p.label, p.hint, s.personality == p) { viewModel.setPersonality(p) } }
        }
        SecretCard(SecretId.SARVAM_API_KEY, state.secrets[SecretId.SARVAM_API_KEY] ?: SecretStatus(), viewModel, title = "Sarvam AI voice key")
        SecretCard(SecretId.ELEVENLABS_API_KEY, state.secrets[SecretId.ELEVENLABS_API_KEY] ?: SecretStatus(), viewModel, title = "ElevenLabs voice key") {
            Text("ElevenLabs → Developers → API keys. Allow \"Text to Speech\" (and \"Voices: read\" + \"Models: read\" so the lists load). Test plays nothing; it checks a real request and loads the voices.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        SecretCard(SecretId.CARTESIA_API_KEY, state.secrets[SecretId.CARTESIA_API_KEY] ?: SecretStatus(), viewModel, title = "Cartesia voice key") {
            Text("Cartesia playground → API keys. Test checks a real request and picks a Hindi female voice if none is chosen.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        SecretCard(SecretId.DEEPGRAM_API_KEY, state.secrets[SecretId.DEEPGRAM_API_KEY] ?: SecretStatus(), viewModel, title = "Deepgram key (listening + English voice)") {
            Text("Use a key that does not expire (the \"Auto-generated Playground\" key expires in hours). Console → API keys → Create a new API key.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }

        // ---------------------------------------------------------------- phone
        HudPanel(title = "Phone & music") {
            ToggleRow(
                "Direct calling (otherwise the dialer opens and you tap call)",
                s.directCalling,
            ) { on -> if (on) callPermission.launch(Manifest.permission.CALL_PHONE) else viewModel.setDirectCalling(false) }
            Text("Every call is confirmed first, with the contact's name and number.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text("Music app", style = MaterialTheme.typography.titleMedium)
            MusicService.entries.forEach { m -> RadioRow(m.label, s.musicService == m) { viewModel.setMusicService(m) } }
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) {
                Text("Manage app permissions")
            }
        }

        // ---------------------------------------------------------------- notifications
        HudPanel(title = "Notifications & WhatsApp") {
            StatusChip(if (notificationAccess) "Notification access: on" else "Notification access: off", if (notificationAccess) SuccessGreen else WarningAmber)
            Text(
                "With access on, Seema AI can read aloud the sender and preview that a notification shows, only for the apps you pick. It cannot read WhatsApp's chats or send messages by itself; replies open WhatsApp for you to send. Message text is never logged or saved.",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
            )
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text(if (notificationAccess) "Manage notification access" else "Enable notification access")
            }
            ToggleRow("Announce notifications", s.announceNotifications) { on ->
                viewModel.setAnnounce(on)
                if (on && !diag.appNotificationsAllowed) askPostNotifications()
            }
            ToggleRow("Ask before reading (say who wrote, read only after you tap Read)", s.askBeforeReading, viewModel::setAskBeforeReading)
            ToggleRow("Announce WhatsApp/Telegram calls (best effort, from their notifications)", s.announceAppCalls, viewModel::setAnnounceAppCalls)
            AnnounceApp.entries.forEach { app ->
                ToggleRow("   ${app.label}", app.packageName in s.announceApps) { viewModel.setAnnounceApp(app.packageName, it) }
            }
        }

        // ---------------------------------------------------------------- calls
        HudPanel(title = "Incoming calls") {
            Text(
                "Seema AI can say who is calling on your SIM. Android allows this only for the app you choose as \"call screening\" app. Seema AI never blocks, answers, rejects or records calls. The name comes from your Contacts; unknown callers are not guessed.",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
            )
            ToggleRow("Announce incoming calls", s.announceCalls) { on ->
                viewModel.setAnnounceCalls(on)
                if (on && diag.callRoleSupported && !diag.callRoleHeld) viewModel.callRoleRequestIntent()?.let { callRole.launch(it) }
            }
            ToggleRow("Speak the number of unknown callers", s.speakCallerNumber, viewModel::setSpeakCallerNumber)
            if (s.announceCalls && !diag.callRoleHeld) {
                Text(
                    if (diag.callRoleSupported) "Not active yet: grant the call-screening role (Status & permissions → Call announcements)." else "Not available: this phone does not offer the call-screening role.",
                    style = MaterialTheme.typography.bodyMedium, color = WarningAmber,
                )
            }
        }

        // ---------------------------------------------------------------- control
        HudPanel(title = "Assistant control") {
            ToggleRow("Pause assistant (stops announcements and continuous listening)", s.assistantPaused, viewModel::setPaused)
            ToggleRow("Safety Mode (for payment & banking apps)", s.safetyMode, viewModel::setSafetyMode)
            Text(
                "Safety Mode pauses notification announcements and blocks assistant-started calls, SMS, WhatsApp, app launches and music. Seema AI never reads or touches Google Pay, PhonePe or banking screens either way. Turn it off here to resume.",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
            )
        }

        // ---------------------------------------------------------------- memory & privacy
        HudPanel(title = "Memory & privacy") {
            ToggleRow("Memory (assistant may remember facts you approve)", s.memoryEnabled, viewModel::setMemoryEnabled)
            ToggleRow("Save chat history on device", s.saveHistory, viewModel::setSaveHistory)
            var limit by remember(s.historyLimit) { mutableFloatStateOf(s.historyLimit.toFloat()) }
            Text("Conversation context: last ${limit.toInt()} exchanges", style = MaterialTheme.typography.bodyMedium)
            Slider(value = limit, onValueChange = { limit = it }, valueRange = 4f..60f, onValueChangeFinished = { viewModel.setHistoryLimit(limit.toInt()) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirm = "Delete all chat history?" to viewModel::clearChatHistory }) { Text("Delete chat history") }
                OutlinedButton(onClick = { confirm = "Delete all memories?" to viewModel::clearMemories }) { Text("Delete memories") }
            }
            Text("Edit individual memories in Tools.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }

        HudPanel(title = "Performance") {
            ToggleRow("Low-power animation mode", s.lowPowerMode, viewModel::setLowPowerMode)
            ToggleRow("Reduced motion", s.reducedMotion, viewModel::setReducedMotion)
        }

        HudPanel(title = "About") {
            Text("Seema AI Assistant ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("AI answers can be wrong — check important information.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }

    confirm?.let { (question, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(question) },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Delete", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SecretCard(
    id: SecretId,
    status: SecretStatus,
    viewModel: SettingsViewModel,
    title: String = id.displayName,
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    HudPanel(title = title) {
        StatusChip(
            label = if (status.configured) "Saved ${status.maskedValue}" else "Not set",
            color = if (status.configured) SuccessGreen else WarningAmber,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (status.configured) "Replace key" else "Paste key") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (visible) "Hide key" else "Show key")
                }
            },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.saveSecret(id, input); input = ""; visible = false }, enabled = input.isNotBlank()) {
                Text(if (status.configured) "Update" else "Save")
            }
            OutlinedButton(onClick = { viewModel.testSecret(id) }, enabled = status.configured && !status.busy) { Text("Test") }
            OutlinedButton(onClick = { viewModel.removeSecret(id) }, enabled = status.configured) { Text("Remove") }
        }
        status.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (status.isError) ErrorRed else TextSecondary)
        }
        extra()
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(id.helpUrl))) } }) {
            Text("Get a key: ${id.helpUrl}")
        }
    }
}

@Composable
private fun ModelPicker(provider: ProviderId, selected: String?, options: List<String>, onLoad: () -> Unit, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open && options.isEmpty()) onLoad() }
    ModelAdvice.warning(selected)?.let { Text("⚠ $it", style = MaterialTheme.typography.bodyMedium, color = WarningAmber) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Model: ${selected ?: "auto (chosen on first use)"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { open = true }) { Text("Change") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (options.isEmpty()) DropdownMenuItem(text = { Text("Loading models… (save a valid key first)") }, onClick = { open = false })
                options.forEach { m ->
                    val tag = when {
                        ModelAdvice.isRecommended(provider, m) -> "  ★ recommended"
                        ModelAdvice.warning(m) != null -> "  ⚠ not for Hindi"
                        else -> ""
                    }
                    DropdownMenuItem(text = { Text(m + tag) }, onClick = { onSelect(m); open = false })
                }
            }
        }
    }
}

@Composable
private fun VoicePicker(voices: List<com.raju.aiassistant.data.tts.VoiceOption>, onLoad: () -> Unit, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open && voices.isEmpty()) onLoad() }
    Box {
        TextButton(onClick = { open = true }) { Text("Change") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Default") }, onClick = { onSelect(null); open = false })
            if (voices.isEmpty()) DropdownMenuItem(text = { Text("No Hindi/English-India voices found yet") }, onClick = { open = false })
            voices.forEach { v -> DropdownMenuItem(text = { Text(v.label) }, onClick = { onSelect(v.name); open = false }) }
        }
    }
}

@Composable
internal fun ChipRow(options: List<String>, selected: String, label: (String) -> String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
internal fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun StatusRow(label: String, status: String, ok: Boolean, action: Pair<String, () -> Unit>? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = if (ok) SuccessGreen else WarningAmber)
        }
        action?.let { (text, onClick) -> TextButton(onClick = onClick) { Text(text) } }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
