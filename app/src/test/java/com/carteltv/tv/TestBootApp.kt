package com.carteltv.tv

import android.app.Application

/**
 * Robolectric stand-in for [CartelApp]: composes the theme and components without
 * booting device services (WorkManager alerts, PeerSync, tv_input probes) that have
 * no meaning on the JVM and would leak uncaught exceptions into coroutines-test.
 * Referenced only from @Config(application=...) in test classes.
 */
class TestBootApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.carteltv.tv.data.preferences.AppPreferences.init(this)
    }
}
