package com.raju.aiassistant.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.model.AnnounceApp
import com.raju.aiassistant.domain.model.MusicService
import com.raju.aiassistant.domain.model.SARVAM_FEMALE_SPEAKERS
import com.raju.aiassistant.domain.model.SPEECH_LANGUAGES
import com.raju.aiassistant.domain.model.SecretId
import com.raju.aiassistant.domain.model.TtsEngine
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
    val s = state.settings
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    // Re-check notification access whenever the user returns from system settings.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val notificationAccess = remember(lifecycleState) { lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && viewModel.notificationAccessEnabled() }

    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setDirectCalling(granted)
    }
    fun open(intent: Intent) = runCatching { context.startActivity(intent) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        info?.let { Text(it, color = TextSecondary) }

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
            Text("Speech output", style = MaterialTheme.typography.titleMedium)
            TtsEngine.entries.forEach { e -> RadioRow(e.label, s.ttsEngine == e) { viewModel.setTtsEngine(e) } }
            if (s.ttsEngine == TtsEngine.SARVAM) {
                Text("Sarvam voice (female)", style = MaterialTheme.typography.bodyMedium)
                ChipRow(SARVAM_FEMALE_SPEAKERS, s.sarvamSpeaker, { it.replaceFirstChar(Char::uppercase) }) { viewModel.setSarvamSpeaker(it) }
                Text("Needs internet and a Sarvam key (below). If it fails, the offline Android voice is used and the reason is shown.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Android voice: " + (voices.firstOrNull { it.name == s.androidVoice }?.label ?: s.androidVoice ?: "Default"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    VoicePicker(voices, onLoad = viewModel::loadVoices, onSelect = viewModel::setAndroidVoice)
                }
            }
            var rate by remember(s.speechRate) { mutableFloatStateOf(s.speechRate) }
            Text("Speaking speed: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f, steps = 14, onValueChangeFinished = { viewModel.setSpeechRate(rate) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::testVoice) { Text("Test voice") }
                OutlinedButton(onClick = viewModel::stopVoice) { Text("Stop") }
            }
            voiceTest?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
            activeEngine?.let { Text("Active: $it", style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }

            Text("Speech input language", style = MaterialTheme.typography.titleMedium)
            ChipRow(SPEECH_LANGUAGES.map { it.first }, s.speechLanguage, { tag -> SPEECH_LANGUAGES.first { it.first == tag }.second }) { viewModel.setSpeechLanguage(it) }
            ToggleRow("Speak replies aloud", s.autoSpeakReplies, viewModel::setAutoSpeak)
            ToggleRow("Continuous conversation (keeps listening after each reply while the app is open)", s.continuousConversation, viewModel::setContinuous)
        }
        SecretCard(SecretId.SARVAM_API_KEY, state.secrets[SecretId.SARVAM_API_KEY] ?: SecretStatus(), viewModel, title = "Sarvam AI voice key")

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
                "With access on, Raju AI can read aloud the sender and preview that a notification shows, only for the apps you pick. It cannot read WhatsApp's chats or send messages by itself; replies open WhatsApp for you to send. Message text is never logged or saved.",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
            )
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text(if (notificationAccess) "Manage notification access" else "Enable notification access")
            }
            ToggleRow("Announce notifications", s.announceNotifications, viewModel::setAnnounce)
            AnnounceApp.entries.forEach { app ->
                ToggleRow("   ${app.label}", app.packageName in s.announceApps) { viewModel.setAnnounceApp(app.packageName, it) }
            }
        }

        // ---------------------------------------------------------------- control
        HudPanel(title = "Assistant control") {
            ToggleRow("Pause assistant (stops announcements and continuous listening)", s.assistantPaused, viewModel::setPaused)
            ToggleRow("Safety Mode (for payment & banking apps)", s.safetyMode, viewModel::setSafetyMode)
            Text(
                "Safety Mode pauses notification announcements and blocks assistant-started calls, WhatsApp, app launches and music. Raju AI never reads or touches Google Pay, PhonePe or banking screens either way. Turn it off here to resume.",
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
            Text("Raju AI Assistant ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
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
private fun ModelPicker(selected: String?, options: List<String>, onLoad: () -> Unit, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open && options.isEmpty()) onLoad() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Model: ${selected ?: "auto (chosen on first use)"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { open = true }) { Text("Change") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (options.isEmpty()) DropdownMenuItem(text = { Text("Loading models… (save a valid key first)") }, onClick = { open = false })
                options.forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = { onSelect(m); open = false })
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
private fun ChipRow(options: List<String>, selected: String, label: (String) -> String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
