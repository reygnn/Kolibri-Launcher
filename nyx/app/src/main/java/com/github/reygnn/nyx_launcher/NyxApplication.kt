package com.github.reygnn.nyx_launcher

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Nyx application root. `@HiltAndroidApp` triggers Hilt's aggregating codegen and
 * member-injects [packageEvents] before [onCreate] returns. Debug-only Timber;
 * full crash/ACRA infra is carried over from Kolibri-Launcher later (rules 7–9).
 */
@HiltAndroidApp
class NyxApplication : Application() {

    @Inject
    lateinit var packageEvents: PackageEventCoordinator

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        packageEvents.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        packageEvents.onTrimMemory(level)
    }
}
