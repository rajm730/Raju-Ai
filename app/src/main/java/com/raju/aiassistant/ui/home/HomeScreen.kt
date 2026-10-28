package com.raju.aiassistant.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raju.aiassistant.ui.RajuViewModelFactory
import com.raju.aiassistant.ui.components.StatusChip
import com.raju.aiassistant.ui.theme.ReactorBlue
import com.raju.aiassistant.ui.theme.ReactorCore
import com.raju.aiassistant.ui.theme.ReactorCyan
import com.raju.aiassistant.ui.theme.SuccessGreen
import com.raju.aiassistant.ui.theme.TextSecondary
import com.raju.aiassistant.ui.theme.WarningAmber

@Composable
fun HomeScreen(
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = RajuViewModelFactory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("RAJU AI ASSISTANT", style = MaterialTheme.typography.headlineMedium, color = ReactorCyan)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(state.assistantState.label, ReactorCyan)
            StatusChip(
                label = if (state.providerKeySaved) "${state.defaultProvider.displayName}: key saved"
                else "${state.defaultProvider.displayName}: no key",
                color = if (state.providerKeySaved) SuccessGreen else WarningAmber,
            )
        }

        // Static Phase 1 reactor. Animated reactor + 2D hologram fallback arrive in Phase 2.
        StaticReactor(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .aspectRatio(1f)
        )

        Text(
            "Voice and AI replies are not active yet — they are built in later phases. " +
                "You can already save API keys in Settings and use the chat history.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeAction("Voice", Icons.Filled.Mic, enabled = false, onClick = {}, modifier = Modifier.weight(1f))
            HomeAction("Chat", Icons.AutoMirrored.Filled.Chat, enabled = true, onClick = onOpenChat, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeAction(
                "Stop", Icons.Filled.Stop,
                enabled = state.assistantState.isBusy, onClick = {},
                modifier = Modifier.weight(1f),
            )
            HomeAction("Settings", Icons.Filled.Settings, enabled = true, onClick = onOpenSettings, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HomeAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.height(64.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StaticReactor(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(ReactorCyan.copy(alpha = 0.35f), ReactorCyan.copy(alpha = 0f)),
                    center = center, radius = r,
                ),
                radius = r,
            )
            drawCircle(ReactorCyan, radius = r * 0.78f, style = Stroke(width = r * 0.04f))
            drawCircle(ReactorBlue.copy(alpha = 0.8f), radius = r * 0.58f, style = Stroke(width = r * 0.025f))
            drawCircle(
                brush = Brush.radialGradient(listOf(ReactorCore, ReactorCyan.copy(alpha = 0.6f)), center = center, radius = r * 0.3f),
                radius = r * 0.3f,
            )
        }
    }
}
