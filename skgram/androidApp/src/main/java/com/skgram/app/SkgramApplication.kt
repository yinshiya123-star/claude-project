package com.skgram.app

import android.app.Application
import com.skgram.core.Accounts
import com.skgram.core.AppInfo
import com.skgram.core.Platform
import com.skgram.core.Prefs

class SkgramApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Platform.init(this)
        AppInfo.version = BuildConfig.VERSION_NAME
        Prefs.builtInApiId = BuildConfig.TG_API_ID
        Prefs.builtInApiHash = BuildConfig.TG_API_HASH
        Prefs.load()
        // Start TDLib as early as possible so cached chats are ready by the time the UI draws.
        Accounts.start()
    }
}
