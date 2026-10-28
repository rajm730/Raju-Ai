package com.raju.aiassistant.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raju.aiassistant.ui.components.HudPanel
import com.raju.aiassistant.ui.components.StatusChip
import com.raju.aiassistant.ui.theme.SuccessGreen
import com.raju.aiassistant.ui.theme.TextSecondary
import com.raju.aiassistant.ui.theme.WarningAmber

/** An assistant capability and the phase it is delivered in. */
data class ToolModule(val name: String, val description: String, val phase: Int, val available: Boolean)

val toolModules = listOf(
    ToolModule("Secure key storage", "API keys encrypted with Android Keystore.", 1, available = true),
    ToolModule("Chat history", "Conversations stored locally with Room.", 1, available = true),
    ToolModule("Arc Reactor + 2D hologram", "Animated home screen with state visuals.", 2, available = false),
    ToolModule("AI chat (Gemini / Grok)", "Real provider responses, model selection, connection tests.", 3, available = false),
    ToolModule("Voice", "Speech recognition, female TTS, stop/interrupt.", 4, available = false),
    ToolModule("Phone tools", "Contacts, calls via dialer, open apps, permissions.", 5, available = false),
    ToolModule("Notifications", "Opt-in notification and WhatsApp announcements.", 6, available = false),
    ToolModule("Web search & music", "Tavily search; YouTube Music / Spotify via intents.", 7, available = false),
    ToolModule("Memory & tasks", "Editable memories, planning, safe background work.", 8, available = false),
)

@Composable
fun ToolsScreen() {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Tools & roadmap", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
        items(toolModules, key = { it.name }) { module ->
            HudPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(module.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    StatusChip(
                        label = if (module.available) "Available" else "Phase ${module.phase}",
                        color = if (module.available) SuccessGreen else WarningAmber,
                    )
                }
                Text(module.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}
