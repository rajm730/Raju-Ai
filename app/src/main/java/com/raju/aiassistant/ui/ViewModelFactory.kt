package com.raju.aiassistant.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.raju.aiassistant.RajuApp
import com.raju.aiassistant.di.AppContainer
import com.raju.aiassistant.ui.chat.ChatViewModel
import com.raju.aiassistant.ui.home.HomeViewModel
import com.raju.aiassistant.ui.settings.SettingsViewModel

private fun CreationExtras.container(): AppContainer =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as RajuApp).container

/** Single factory that wires every ViewModel from the [AppContainer]. */
val RajuViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val c = container()
        HomeViewModel(c.secureKeyStore, c.settingsRepository)
    }
    initializer {
        val c = container()
        ChatViewModel(c.conversationRepository, c.settingsRepository)
    }
    initializer {
        val c = container()
        SettingsViewModel(c.secureKeyStore, c.settingsRepository, c.conversationRepository)
    }
}
