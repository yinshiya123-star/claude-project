package com.puppetlight.oneclick

import android.app.Application
import com.puppetlight.oneclick.service.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
