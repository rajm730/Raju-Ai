package com.raju.aiassistant.ui.assistant

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raju.aiassistant.domain.notify.AnnouncementCoordinator
import com.raju.aiassistant.domain.notify.whoText

/** In-app "Read / Skip" question for an incoming message. The message text is not shown or spoken until Read. */
@Composable
fun AnnouncementPrompt(coordinator: AnnouncementCoordinator) {
    val pending by coordinator.pending.collectAsStateWithLifecycle()
    val p = pending ?: return
    val who = whoText(p.item)
    AlertDialog(
        onDismissRequest = { coordinator.skip(p.id) },
        title = { Text("${p.item.appLabel}: new message") },
        text = {
            Text(
                if (p.item.count > 1) "$who ke ${p.item.count} messages aaye hain. Padhkar sunaun?"
                else "$who ka message aaya hai. Padhkar sunaun?"
            )
        },
        confirmButton = { TextButton(onClick = { coordinator.read(p.id) }) { Text("Read") } },
        dismissButton = { TextButton(onClick = { coordinator.skip(p.id) }) { Text("Skip") } },
    )
}
