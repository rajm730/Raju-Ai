package com.raju.aiassistant.ui.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.raju.aiassistant.RajuApp
import com.raju.aiassistant.ui.LaunchRequest
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

    // Hands-free: listen as soon as the app is on screen, release the microphone when it is not.
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var askedMic by rememberSaveable { mutableStateOf(false) }
    val handsFreeMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) container.engine.startHandsFree()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> scope.launch {
                    val s = container.settingsRepository.settings.first() // stored values, not the startup default
                    if (s.autoListen && !s.assistantPaused) {
                        if (container.voiceInput.hasPermission()) container.engine.startHandsFree()
                        else if (!askedMic) {
                            askedMic = true
                            handsFreeMic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> container.engine.suspendListening()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Home-screen shortcuts and text shared from other apps (see MainActivity).
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) container.engine.startHandsFree()
    }
    val launch by container.launchRequests.collectAsStateWithLifecycle()
    LaunchedEffect(launch) {
        val request = launch ?: return@LaunchedEffect
        container.launchRequests.value = null
        runCatching {
            when (request) {
                LaunchRequest.Voice -> {
                    navigateTo(Destination.Home)
                    if (container.voiceInput.hasPermission()) container.engine.startHandsFree()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                LaunchRequest.Chat -> navigateTo(Destination.Chat)
                is LaunchRequest.Ask -> {
                    navigateTo(Destination.Chat)
                    container.engine.submit(request.text)
                }
            }
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
