package com.raju.aiassistant.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.raju.aiassistant.RajuApp
import com.raju.aiassistant.ui.assistant.AnnouncementPrompt
import com.raju.aiassistant.ui.assistant.AssistantDialogs
import com.raju.aiassistant.ui.chat.ChatScreen
import com.raju.aiassistant.ui.home.HomeScreen
import com.raju.aiassistant.ui.settings.SettingsScreen
import com.raju.aiassistant.ui.tools.ToolsScreen

@Composable
fun RajuNavHost(navController: NavHostController = rememberNavController()) {
    val container = (LocalContext.current.applicationContext as RajuApp).container
    AssistantDialogs(container.engine)
    AnnouncementPrompt(container.announcements)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = Destination.fromRoute(backStackEntry?.destination?.route)

    val navigateTo: (Destination) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Destination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest,
                        onClick = { navigateTo(dest) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.start.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    onOpenChat = { navigateTo(Destination.Chat) },
                    onOpenSettings = { navigateTo(Destination.Settings) },
                )
            }
            composable(Destination.Chat.route) { ChatScreen(onOpenSettings = { navigateTo(Destination.Settings) }) }
            composable(Destination.Tools.route) { ToolsScreen(onOpenChat = { navigateTo(Destination.Chat) }) }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
