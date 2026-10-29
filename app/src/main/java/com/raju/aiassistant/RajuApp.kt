package com.raju.aiassistant

import android.app.Application
import com.raju.aiassistant.di.AppContainer

class RajuApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.consentNotifier.createChannel()
        com.raju.aiassistant.notifications.BusinessNotifier(this).createChannel()
        // Morning briefing and weekly report; they check the setting themselves each time.
        runCatching { com.raju.aiassistant.notifications.BusinessScheduler.schedule(this) }
    }
}
