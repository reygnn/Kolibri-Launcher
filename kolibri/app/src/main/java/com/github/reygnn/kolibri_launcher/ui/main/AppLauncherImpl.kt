package com.github.reygnn.kolibri_launcher.ui.main

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace
import javax.inject.Inject

/**
 * Production [AppLauncher] using [LauncherApps.startMainActivity] with the
 * system default app-open transition (no [android.app.ActivityOptions]).
 *
 * Passing a null options bundle hands the open animation to the platform, so
 * app launches get the OS-consistent, predictive-back-aware transition instead
 * of a hand-rolled custom animation.
 *
 * Maps each expected failure mode to a typed [AppLaunchResult] so the caller
 * never sees a raw exception. The exception→result taxonomy (package gone →
 * [AppLaunchResult.ComponentGone], denied → [AppLaunchResult.PermissionDenied],
 * everything else → [AppLaunchResult.Failed]) lives in [runLaunchCatching] so it
 * can be pinned in isolation from the un-mockable Activity statics here; this
 * class only supplies the actual system call. It is a genuine System-API
 * boundary — the same triple that used to live inline in `MainActivity.launchApp`.
 */
class AppLauncherImpl @Inject constructor() : AppLauncher {

    override fun launch(activity: Activity, appInfo: AppInfo): AppLaunchResult {
        val launcherApps =
            activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return AppLaunchResult.Failed(
                    IllegalStateException("LauncherApps service unavailable"),
                )

        return runLaunchCatching {
            // Use the normalized (long-form) class name, not the raw one: a
            // leading-dot relative spelling would not resolve against the parsed
            // manifest. Shares AppInfo's single normalization source with
            // componentName (identity), so launch and identity can never diverge.
            val componentName = ComponentName(appInfo.packageName, appInfo.normalizedClassName)
            // Traced: the actual startMainActivity binder call — the last slice
            // the launcher owns before the target app's cold start takes over.
            // Null options bundle → system default open transition.
            LaunchTrace.section(LaunchTrace.Names.START_MAIN_ACTIVITY) {
                launcherApps.startMainActivity(
                    componentName,
                    Process.myUserHandle(),
                    null,
                    null,
                )
            }
        }
    }
}
