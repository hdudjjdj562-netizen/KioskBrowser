package com.enterprise.kioskbrowser

import android.app.Application
import android.webkit.WebView

class KioskApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }
}
