package com.raju.aiassistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.raju.aiassistant.ui.LaunchRequest
import com.raju.aiassistant.ui.navigation.RajuNavHost
import com.raju.aiassistant.ui.theme.RajuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch carries a new request; after rotation the same intent must not run again.
        if (savedInstanceState == null) handle(intent)
        publishShortcuts()
        setContent {
            RajuTheme {
                RajuNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        LaunchRequest.from(intent)?.let { (application as RajuApp).container.launchRequests.value = it }
    }

    /** Long-press the app icon → "Voice" / "Chat". Dynamic, so they work for both debug and release package names. */
    private fun publishShortcuts() {
        runCatching {
            val icon = IconCompat.createWithResource(this, R.mipmap.ic_launcher)
            fun shortcut(id: String, label: String, action: String) = ShortcutInfoCompat.Builder(this, id)
                .setShortLabel(label)
                .setIcon(icon)
                .setIntent(Intent(this, MainActivity::class.java).setAction(action))
                .build()
            ShortcutManagerCompat.setDynamicShortcuts(
                this,
                listOf(
                    shortcut("voice", "Seema se bolo", LaunchRequest.ACTION_VOICE),
                    shortcut("chat", "Chat", LaunchRequest.ACTION_CHAT),
                ),
            )
        }
    }
}
