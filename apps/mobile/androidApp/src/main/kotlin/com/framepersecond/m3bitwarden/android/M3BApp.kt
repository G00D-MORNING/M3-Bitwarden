package com.framepersecond.m3bitwarden.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.framepersecond.m3bitwarden.android.vault.AndroidVault
import com.framepersecond.m3bitwarden.platform.AndroidPlatform

/** Wires the platform layer before any activity or the autofill service runs. */
class M3BApp : Application() {
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.context = this
        val vault = AndroidVault.get(this)
        val proton = com.framepersecond.m3bitwarden.android.proton.ProtonService(this).also { it.install() }
        vault.onUnlocked = { proton.refresh() }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) vault.onAppForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) vault.onAppBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
