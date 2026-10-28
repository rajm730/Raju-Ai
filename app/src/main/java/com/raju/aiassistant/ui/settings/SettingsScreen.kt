package com.raju.aiassistant.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raju.aiassistant.BuildConfig
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.model.SecretId
import com.raju.aiassistant.ui.RajuViewModelFactory
import com.raju.aiassistant.ui.components.HudPanel
import com.raju.aiassistant.ui.components.StatusChip
import com.raju.aiassistant.ui.theme.ErrorRed
import com.raju.aiassistant.ui.theme.SuccessGreen
import com.raju.aiassistant.ui.theme.TextSecondary
import com.raju.aiassistant.ui.theme.WarningAmber

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = RajuViewModelFactory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)

        HudPanel(title = "API keys") {
            Text(
                "Keys are encrypted with Android Keystore and stored only on this phone. " +
                    "They are never sent anywhere except the provider they belong to. " +
                    "Connection tests are added in Phase 3.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        SecretId.entries.forEach { id ->
            SecretCard(
                id = id,
                status = state.secrets[id] ?: SecretStatus(),
                onSave = { viewModel.saveSecret(id, it) },
                onRemove = { viewModel.removeSecret(id) },
            )
        }

        HudPanel(title = "Default AI provider") {
            ProviderId.entries.forEach { provider ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.settings.defaultProvider == provider,
                            onClick = { viewModel.setDefaultProvider(provider) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.settings.defaultProvider == provider, onClick = null)
                    Text(provider.displayName, Modifier.padding(start = 12.dp))
                }
            }
        }

        HudPanel(title = "Performance & privacy") {
            ToggleRow("Low-power animation mode", state.settings.lowPowerMode, viewModel::setLowPowerMode)
            ToggleRow("Reduced motion", state.settings.reducedMotion, viewModel::setReducedMotion)
            ToggleRow("Save chat history on device", state.settings.saveHistory, viewModel::setSaveHistory)
            OutlinedButton(onClick = { confirmClear = true }) { Text("Delete chat history") }
            state.historyMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
        }

        HudPanel(title = "About") {
            Text("Raju AI Assistant ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("Phase 1 build: architecture & navigation shell.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete chat history?") },
            text = { Text("All saved conversations on this device will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clearHistory() }) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SecretCard(
    id: SecretId,
    status: SecretStatus,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    HudPanel(title = id.displayName) {
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
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide key" else "Show key",
                    )
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(input); input = ""; visible = false }, enabled = input.isNotBlank()) {
                Text(if (status.configured) "Update" else "Save")
            }
            OutlinedButton(onClick = onRemove, enabled = status.configured) { Text("Remove") }
        }
        status.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (status.isError) ErrorRed else TextSecondary)
        }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(id.helpUrl))) }
        }) { Text("Get a key: ${id.helpUrl}") }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
