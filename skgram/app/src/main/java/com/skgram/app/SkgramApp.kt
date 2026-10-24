package com.skgram.app

import android.app.Application
import com.skgram.app.core.Prefs
import com.skgram.app.core.Tg

class SkgramApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // Start TDLib as early as possible so the cached chat list is ready by the time the UI draws.
        Tg.start(this)
    }
}
