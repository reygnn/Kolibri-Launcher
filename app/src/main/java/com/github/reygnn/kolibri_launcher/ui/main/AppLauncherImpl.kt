package com.github.reygnn.kolibri_launcher.ui.main

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace
import javax.inject.Inject

/**
 * Production [AppLauncher] using [LauncherApps.startMainActivity] with the
 * launcher's open-animation.
 *
 * Maps each expected failure mode to a typed [AppLaunchResult] so the caller
 * never sees a raw exception. The exception→result taxonomy (package gone →
 * [AppLaunchResult.ComponentGone], denied → [AppLaunchResult.PermissionDenied],
 * everything else → [AppLaunchResult.Failed]) lives in [runLaunchCatching] so it
 * can be pinned in isolation from the un-mockable Activity/ActivityOptions
 * statics here; this class only supplies the actual system call. It is a genuine
 * System-API boundary — the same triple that used to live inline in
 * `MainActivity.launchApp`.
 */
class AppLauncherImpl @Inject constructor() : AppLauncher {

    override fun launch(activity: Activity, appInfo: AppInfo): AppLaunchResult {
        val launcherApps =
            activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return AppLaunchResult.Failed(
                    IllegalStateException("LauncherApps service unavailable"),
                )

        return runLaunchCatching {
            val componentName = ComponentName(appInfo.packageName, appInfo.className)
            val options = ActivityOptions.makeCustomAnimation(
                activity,
                R.anim.app_open_enter,
                R.anim.app_open_exit,
            )
            // Traced: the actual startMainActivity binder call — the last slice
            // the launcher owns before the target app's cold start takes over.
            LaunchTrace.section(LaunchTrace.Names.START_MAIN_ACTIVITY) {
                launcherApps.startMainActivity(
                    componentName,
                    Process.myUserHandle(),
                    null,
                    options.toBundle(),
                )
            }
        }
    }
}
