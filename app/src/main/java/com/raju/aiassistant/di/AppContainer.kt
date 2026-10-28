package com.raju.aiassistant.di

import android.content.Context
import com.raju.aiassistant.core.security.SecureKeyStore
import com.raju.aiassistant.data.db.RajuDatabase
import com.raju.aiassistant.data.prefs.SettingsRepository
import com.raju.aiassistant.data.repository.ConversationRepository

/**
 * Manual dependency container. Kept deliberately simple; every later phase adds its
 * services (AI providers, TTS, phone tools, search, music) here.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database by lazy { RajuDatabase.create(appContext) }

    val secureKeyStore by lazy { SecureKeyStore(appContext) }
    val settingsRepository by lazy { SettingsRepository(appContext) }
    val conversationRepository by lazy { ConversationRepository(database.chatDao()) }
}
