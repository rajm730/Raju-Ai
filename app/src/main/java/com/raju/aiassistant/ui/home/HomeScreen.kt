package com.raju.aiassistant.ui.home

import android.Manifest
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raju.aiassistant.BuildConfig
import com.raju.aiassistant.domain.model.AssistantState
import com.raju.aiassistant.ui.RajuViewModelFactory
import com.raju.aiassistant.ui.components.StatusChip
import com.raju.aiassistant.ui.home.visuals.ArcReactor
import com.raju.aiassistant.ui.home.visuals.CyberBackground
import com.raju.aiassistant.ui.home.visuals.motionProfileFor
import com.raju.aiassistant.ui.home.visuals.reactorStyleFor
import com.raju.aiassistant.ui.home.visuals.rememberAnimationTime
import com.raju.aiassistant.ui.home.visuals.statusHintFor
import com.raju.aiassistant.ui.theme.ErrorRed
import com.raju.aiassistant.ui.theme.EmeraldBright
import com.raju.aiassistant.ui.theme.HoloCyan
import com.raju.aiassistant.ui.components.DecodingText
import com.raju.aiassistant.ui.components.VoiceWaveform
import com.raju.aiassistant.ui.components.hudFrame
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.raju.aiassistant.ui.theme.SuccessGreen
import com.raju.aiassistant.ui.theme.TextSecondary
import com.raju.aiassistant.ui.theme.WarningAmber
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun HomeScreen(
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = RajuViewModelFactory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val assistantState = state.assistantState
    val style = reactorStyleFor(assistantState)

    val profile = motionProfileFor(state.lowPowerMode, state.reducedMotion, rememberSystemAnimationsEnabled())
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = profile.animate && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val time = rememberAnimationTime(running, profile.maxFps)
    val timeProvider = { time.floatValue }

    // Real speech level comes from the audio being played. In the "Speaking" preview a synthetic
    // level is animated purely so the speech-reactive visuals can be checked.
    val realLevel = viewModel.speechLevel
    val speechLevel = {
        if (state.previewState == AssistantState.Speaking) {
            val t = time.floatValue
            abs(sin(t * 9f) * (0.6f + 0.4f * sin(t * 2.3f)))
        } else realLevel.value
    }

    val handsFreeMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.toggleHandsFree()
    }
    val onVoice = {
        if (viewModel.hasMicPermission()) viewModel.toggleHandsFree() else handsFreeMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val telemetry = {
        listOf(
            "SEEMA//CORE ${BuildConfig.VERSION_NAME.substringBefore('-')}",
            "STATE  ${assistantState.label.uppercase()}",
            "NET    ${if (state.online) "ONLINE" else "OFFLINE"}",
            "AI     ${state.defaultProvider.shortName.uppercase()}${if (state.providerConnected) " OK" else ""}",
            "VOICE  ${state.voiceEngine}",
            "MIC    ${if (state.handsFree) "HANDS-FREE" else "STANDBY"} · ${state.listenEngine}",
        )
    }

    var showPreview by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        CyberBackground(
            color = Color(style.primaryArgb),
            accent = Color(style.paletteArgb[2]),
            time = timeProvider,
            density = if (!profile.animate) 0.35f else if (state.lowPowerMode) 0.3f else 0.62f,
            telemetry = telemetry,
            modifier = Modifier.fillMaxSize(),
            liveClock = running,
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HoloTitle("SEEMA AI", Color(style.primaryArgb))

            if (state.paused) {
                // Paused is easy to forget: show it clearly, with a one-tap way back.
                Row(
                    Modifier.fillMaxWidth().hudFrame(WarningAmber, cut = 8.dp, fillAlpha = 0.08f).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "⏸ Assistant paused — not listening, no announcements",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarningAmber,
                    )
                    FilledTonalButton(onClick = viewModel::resume) { Text("Resume") }
                }
            }

            if (state.safetyMode) {
                // Safety Mode silently blocks calls, WhatsApp, SMS, apps and music: say so where the user looks.
                Row(
                    Modifier.fillMaxWidth().hudFrame(WarningAmber, cut = 8.dp, fillAlpha = 0.08f).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "🛡️ Safety Mode on — calls, WhatsApp, SMS, apps & music are blocked",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarningAmber,
                    )
                    FilledTonalButton(onClick = viewModel::disableSafetyMode) { Text("Turn off") }
                }
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The state tag breathes with the reactor's own (frame-capped) clock.
                StatusChip(assistantState.label, Color(style.primaryArgb), pulse = if (running) ({ 0.65f + 0.35f * sin(timeProvider() * 3f) }) else null)
                StatusChip(if (state.online) "Online" else "Offline", if (state.online) SuccessGreen else ErrorRed)
                StatusChip("Mode: ${state.listenMode}", MaterialTheme.colorScheme.secondary)
                StatusChip(
                    when {
                        state.listening -> "Mic: listening"
                        assistantState == AssistantState.Sleeping -> "Mic: sleep — say \"Seema\""
                        state.handsFree -> "Mic: hands-free"
                        else -> "Mic: off"
                    },
                    if (state.listening || state.handsFree) SuccessGreen else TextSecondary,
                )
                val provider = state.defaultProvider.shortName
                when {
                    state.providerConnected && state.providerKeySaved -> StatusChip("$provider: connected", SuccessGreen)
                    state.providerKeySaved -> StatusChip("$provider: key saved", WarningAmber)
                    else -> StatusChip("$provider: no key", ErrorRed)
                }
                if (state.safetyMode) StatusChip("Safety Mode", WarningAmber)
                if (state.paused) StatusChip("Paused", WarningAmber)
            }

            // Stage: one large, centred, heart-beating reactor
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = state.previewState == null && (assistantState == AssistantState.Speaking || assistantState == AssistantState.Sleeping),
                        onClickLabel = if (assistantState == AssistantState.Sleeping) "Wake up" else "Stop talking",
                    ) { viewModel.tapReactor() }
                    .semantics { contentDescription = "Assistant status: ${assistantState.label}" },
                contentAlignment = Alignment.Center,
            ) {
                ArcReactor(
                    style, profile.particleCount, profile.richEffects, timeProvider, speechLevel,
                    Modifier.size(minOf(maxWidth, maxHeight) * 0.96f),
                )
            }

            val stateColor = Color(style.primaryArgb)
            DecodingText(
                assistantState.label.uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 4.sp, shadow = Shadow(stateColor.copy(alpha = 0.8f), blurRadius = 16f)),
                color = stateColor,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            VoiceWaveform(
                assistantState, stateColor, timeProvider, speechLevel,
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(26.dp),
            )
            val caption = when {
                state.previewState != null -> "Visual preview only — nothing is running."
                state.listening && state.partialTranscript.isNotBlank() -> "“${state.partialTranscript}”"
                // "Listening" is shown only once the microphone is really open, never as proof of anything.
                assistantState == AssistantState.Listening && !state.listening -> "🎙 माइक शुरू हो रहा है… Starting the microphone"
                state.heard != null && (assistantState == AssistantState.Thinking || assistantState == AssistantState.Executing) ->
                    "✓ सुना (Recognized): “${state.heard}” — सोच रही हूँ…"
                assistantState == AssistantState.Idle && state.hint != null -> state.hint!!
                assistantState == AssistantState.Idle && state.lastReply != null -> state.lastReply!!
                assistantState == AssistantState.Idle && !state.providerKeySaved -> "Add your ${state.defaultProvider.shortName} API key in Settings to start."
                else -> statusHintFor(assistantState)
            }
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            // Visual preview is a developer tool: shown only in debug builds, never in a release.
            if (BuildConfig.DEBUG && showPreview) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    items(AssistantState.entries) { s ->
                        FilterChip(
                            selected = state.previewState == s,
                            onClick = { viewModel.setPreview(if (state.previewState == s) null else s) },
                            label = { Text(s.label) },
                        )
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                TextButton(onClick = {
                    showPreview = !showPreview
                    if (!showPreview) viewModel.setPreview(null)
                }) { Text(if (showPreview) "Hide visual preview" else "Preview visual states (debug)") }
            }

            val busy = assistantState.isBusy || state.listening
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlButton(
                    if (state.handsFree) "Mic ON" else "Voice",
                    if (state.handsFree) Icons.Filled.Mic else Icons.Filled.MicOff,
                    enabled = true,
                    onClick = onVoice,
                    modifier = Modifier.weight(1f),
                    highlighted = state.handsFree,
                )
                ControlButton("Chat", Icons.AutoMirrored.Filled.Chat, enabled = true, onClick = onOpenChat, modifier = Modifier.weight(1f))
                ControlButton("Stop", Icons.Filled.Stop, enabled = busy || state.handsFree || state.previewState != null, onClick = viewModel::stop, modifier = Modifier.weight(1f))
                ControlButton("Settings", Icons.Filled.Settings, enabled = true, onClick = onOpenSettings, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val accent = if (highlighted) SuccessGreen else MaterialTheme.colorScheme.primary
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(72.dp),
        shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.6f else 0.2f)),
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = accent.copy(alpha = if (highlighted) 0.22f else 0.08f),
            contentColor = if (highlighted) SuccessGreen else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** The "SEEMA AI" wordmark: wide-spaced holographic letters with a soft glow. */
@Composable
private fun HoloTitle(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium.copy(
            letterSpacing = 8.sp,
            fontWeight = FontWeight.Bold,
            brush = Brush.horizontalGradient(listOf(EmeraldBright, color, HoloCyan)),
            shadow = Shadow(color.copy(alpha = 0.7f), blurRadius = 22f),
        ),
    )
}

/** False when the user disabled animations in Developer options / Accessibility ("Remove animations"). */
@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(resolver, lifecycleState) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
