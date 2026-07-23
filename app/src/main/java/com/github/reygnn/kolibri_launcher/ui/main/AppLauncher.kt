package com.github.reygnn.kolibri_launcher.ui.main

import android.app.Activity
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

/**
 * Launches another app's main activity and maps the system call's outcome to a
 * typed [AppLaunchResult].
 *
 * Extracted from `MainActivity.launchApp` so the `LauncherApps` /
 * `ActivityOptions` runtime glue lives behind an injectable seam and the
 * caller's reaction (toast + conditional reconcile) is driven by a typed
 * result rather than raw try/catch. A test fake returns a chosen result
 * without touching the real system service (which Robolectric cannot make
 * throw `ActivityNotFoundException` — its `ShadowLauncherApps` does not
 * implement `startMainActivity`).
 */
interface AppLauncher {
    fun launch(activity: Activity, appInfo: AppInfo): AppLaunchResult
}
