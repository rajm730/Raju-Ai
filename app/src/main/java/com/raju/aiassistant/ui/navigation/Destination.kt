package com.raju.aiassistant.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Top-level destinations shown in the bottom navigation bar. */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.Home),
    Chat("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
    Tools("tools", "Tools", Icons.Filled.Build),
    Settings("settings", "Settings", Icons.Filled.Settings);

    companion object {
        val start = Home
        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}
