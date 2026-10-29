package com.raju.aiassistant.ui.assistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raju.aiassistant.domain.assistant.AssistantEngine
import com.raju.aiassistant.domain.assistant.Decision
import com.raju.aiassistant.domain.assistant.PendingRequest

/** Shows the engine's pending confirmation / permission request, wherever the user is in the app. */
@Composable
fun AssistantDialogs(engine: AssistantEngine) {
    val pending by engine.pending.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        engine.resolve(Decision.PermissionResult(granted))
    }
    val request = pending ?: return

    when (request.kind) {
        PendingRequest.Kind.PERMISSION -> AlertDialog(
            onDismissRequest = { engine.resolve(Decision.PermissionResult(false)) },
            title = { Text(request.title) },
            text = { Text(request.message) },
            confirmButton = {
                TextButton(onClick = { request.permission?.let { permissionLauncher.launch(it) } ?: engine.resolve(Decision.PermissionResult(false)) }) {
                    Text("Allow")
                }
            },
            dismissButton = { TextButton(onClick = { engine.resolve(Decision.PermissionResult(false)) }) { Text("Not now") } },
        )

        PendingRequest.Kind.CONFIRM -> AlertDialog(
            onDismissRequest = { engine.resolve(Decision.Deny) },
            title = { Text(request.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.message)
                    if (request.options.size > 1) {
                        request.options.forEach { c ->
                            OutlinedButton(onClick = { engine.resolve(Decision.Approve(c)) }, modifier = Modifier.fillMaxWidth()) {
                                Text("${c.name}  •  ${c.number}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (request.options.size <= 1) {
                    TextButton(onClick = { engine.resolve(Decision.Approve(request.options.firstOrNull())) }) { Text("Confirm") }
                }
            },
            dismissButton = { TextButton(onClick = { engine.resolve(Decision.Deny) }) { Text("Cancel") } },
        )
    }
}
