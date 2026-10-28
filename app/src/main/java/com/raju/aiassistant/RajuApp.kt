package com.raju.aiassistant

import android.app.Application
import com.raju.aiassistant.di.AppContainer

class RajuApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
