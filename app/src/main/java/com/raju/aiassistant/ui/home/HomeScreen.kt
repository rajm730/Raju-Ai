package com.raju.aiassistant.ui.home

import android.Manifest
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import com.raju.aiassistant.ui.home.visuals.motionProfileFor
import com.raju.aiassistant.ui.home.visuals.reactorStyleFor
import com.raju.aiassistant.ui.home.visuals.rememberAnimationTime
import com.raju.aiassistant.ui.home.visuals.statusHintFor
import com.raju.aiassistant.ui.theme.ErrorRed
import com.raju.aiassistant.ui.theme.ReactorCyan
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

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoice()
    }
    val onVoice = {
        if (viewModel.hasMicPermission()) viewModel.startVoice() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    var showPreview by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        HudBackground(ReactorCyan)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("SEEMA AI", style = MaterialTheme.typography.headlineMedium, color = ReactorCyan)

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(assistantState.label, Color(style.primaryArgb))
                StatusChip(if (state.online) "Online" else "Offline", if (state.online) SuccessGreen else ErrorRed)
                StatusChip(if (state.listening) "Mic: listening" else "Mic: off", if (state.listening) SuccessGreen else TextSecondary)
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
                    .semantics { contentDescription = "Assistant status: ${assistantState.label}" },
                contentAlignment = Alignment.Center,
            ) {
                ArcReactor(
                    style, profile.particleCount, profile.richEffects, timeProvider, speechLevel,
                    Modifier.size(minOf(maxWidth, maxHeight) * 0.96f),
                )
            }

            Text(
                assistantState.label.uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = Color(style.primaryArgb),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val caption = when {
                state.previewState != null -> "Visual preview only — nothing is running."
                state.listening && state.partialTranscript.isNotBlank() -> "“${state.partialTranscript}”"
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
                ControlButton("Voice", Icons.Filled.Mic, enabled = !state.listening, onClick = onVoice, modifier = Modifier.weight(1f))
                ControlButton("Chat", Icons.AutoMirrored.Filled.Chat, enabled = true, onClick = onOpenChat, modifier = Modifier.weight(1f))
                ControlButton("Stop", Icons.Filled.Stop, enabled = busy || state.previewState != null, onClick = viewModel::stop, modifier = Modifier.weight(1f))
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
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(72.dp),
        contentPadding = PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Static HUD backdrop: soft vignette and a faint grid. Drawn once (no animation cost). */
@Composable
private fun HudBackground(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                listOf(color.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.45f),
                radius = size.maxDimension * 0.6f,
            )
        )
        val step = 32.dp.toPx()
        val grid = color.copy(alpha = 0.04f)
        var x = 0f
        while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height)); x += step }
        var y = 0f
        while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y)); y += step }
    }
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
