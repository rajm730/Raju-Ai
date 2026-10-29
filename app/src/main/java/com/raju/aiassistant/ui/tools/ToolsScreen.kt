package com.raju.aiassistant.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raju.aiassistant.domain.assistant.ActionStatus
import com.raju.aiassistant.domain.assistant.Memory
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
fun ToolsScreen(onOpenChat: () -> Unit = {}, viewModel: ToolsViewModel = viewModel(factory = RajuViewModelFactory)) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Memory?>(null) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Tools", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary) }
        message?.let { m -> item { Text(m, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) } }

        item {
            HudPanel(title = "Quick actions") {
                InputAction("Search the web", "e.g. aaj ka cricket score") { viewModel.askWeb(it); onOpenChat() }
                InputAction("Play music", "Song or artist") { viewModel.playMusic(it) }
                InputAction("Open app", "e.g. Camera, YouTube") { viewModel.openApp(it) }
                OutlinedButton(onClick = viewModel::battery) { Text("Battery status") }
            }
        }

        item {
            HudPanel(title = "Memory") {
                Text(
                    "Things Raju AI remembers about you. Only saved when you ask or approve. Passwords, PINs, OTPs, card numbers and keys are never saved.",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                )
                InputAction("Add memory", "e.g. Mom's birthday is 5 May", buttonLabel = "Save") { viewModel.addMemory(it) }
                if (memories.isEmpty()) Text("No memories yet.", color = TextSecondary)
            }
        }
        items(memories, key = { "m${it.id}" }) { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("• ${m.text}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { editing = m }) { Icon(Icons.Filled.Edit, contentDescription = "Edit memory") }
                IconButton(onClick = { viewModel.deleteMemory(m.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete memory") }
            }
        }

        item {
            HudPanel(title = "Action history") {
                Text("Every phone action the assistant tried, with its real result.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                if (history.isEmpty()) Text("Nothing yet.", color = TextSecondary)
                else TextButton(onClick = { viewModel.clearHistory() }) { Text("Clear history") }
            }
        }
        items(history, key = { "h${it.id}" }) { h ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    h.status.name.lowercase(),
                    when (h.status) {
                        ActionStatus.SUCCESS -> SuccessGreen
                        ActionStatus.FAILED -> ErrorRed
                        else -> WarningAmber
                    },
                )
                Column1(h.summary, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.createdAt)), Modifier.weight(1f))
            }
        }
    }

    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit memory") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { viewModel.updateMemory(m.id, text); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Column1(title: String, subtitle: String, modifier: Modifier) {
    androidx.compose.foundation.layout.Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun InputAction(label: String, hint: String, buttonLabel: String = "Go", onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            placeholder = { Text(hint) },
            singleLine = true,
        )
        Button(onClick = { onSubmit(value.trim()); value = "" }, enabled = value.isNotBlank()) { Text(buttonLabel) }
    }
}
