package com.expuz.ai

import android.app.Application
import android.webkit.WebView

class ExpuzApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Debug-only WebView inspection. Release builds never expose a
            // remote debugging socket.
            WebView.setWebContentsDebuggingEnabled(true)
        }
        container = AppContainer(this)
        container.updateManager.checkOnProcessStart()
    }
}
