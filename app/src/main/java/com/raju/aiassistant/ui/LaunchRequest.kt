package com.raju.aiassistant.ui

import android.content.Intent
import com.raju.aiassistant.domain.assistant.buildSharedTextRequest

/** Something the app was opened for: a home-screen shortcut or text shared from another app. */
sealed interface LaunchRequest {
    data object Voice : LaunchRequest
    data object Chat : LaunchRequest
    data class Ask(val text: String) : LaunchRequest

    companion object {
        const val ACTION_VOICE = "com.raju.aiassistant.action.VOICE"
        const val ACTION_CHAT = "com.raju.aiassistant.action.CHAT"

        fun from(intent: Intent?): LaunchRequest? = when (intent?.action) {
            ACTION_VOICE -> Voice
            ACTION_CHAT -> Chat
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Ask(buildSharedTextRequest(it, intent.getStringExtra(Intent.EXTRA_SUBJECT))) }
            else -> null
        }
    }
}
