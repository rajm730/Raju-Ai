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
        const val ACTION_BRIEFING = "com.raju.aiassistant.action.BRIEFING"
        const val ACTION_WEEKLY_REPORT = "com.raju.aiassistant.action.WEEKLY_REPORT"

        /** What Seema is asked when the user taps the morning or weekly notification. */
        const val BRIEFING_REQUEST = "Mujhe aaj ki briefing do: aaj aur kal ki yatraayein, kal ka kharcha, aur aaj ke din ke liye ek chhota sa kaam ka tip."
        const val WEEKLY_REQUEST = "Is hafte ka kharcha category-wise samjhao aur agle hafte bachat ka ek practical tip do."

        fun from(intent: Intent?): LaunchRequest? = when (intent?.action) {
            ACTION_VOICE -> Voice
            ACTION_CHAT -> Chat
            ACTION_BRIEFING -> Ask(BRIEFING_REQUEST)
            ACTION_WEEKLY_REPORT -> Ask(WEEKLY_REQUEST)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Ask(buildSharedTextRequest(it, intent.getStringExtra(Intent.EXTRA_SUBJECT))) }
            else -> null
        }
    }
}
