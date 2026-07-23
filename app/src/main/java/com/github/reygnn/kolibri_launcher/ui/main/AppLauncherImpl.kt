package com.github.reygnn.kolibri_launcher.ui.main

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import javax.inject.Inject

/**
 * Production [AppLauncher] using [LauncherApps.startMainActivity] with the
 * launcher's open-animation.
 *
 * Maps each expected failure mode to a typed [AppLaunchResult] so the caller
 * never sees a raw exception. The three-way catch (four-category frame:
 * package gone → [AppLaunchResult.ComponentGone], denied →
 * [AppLaunchResult.PermissionDenied], everything else → [AppLaunchResult.Failed])
 * is a genuine System-API boundary — the same triple that used to live inline
 * in `MainActivity.launchApp`.
 */
class AppLauncherImpl @Inject constructor() : AppLauncher {

    override fun launch(activity: Activity, appInfo: AppInfo): AppLaunchResult {
        val launcherApps =
            activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return AppLaunchResult.Failed(
                    IllegalStateException("LauncherApps service unavailable"),
                )

        return try {
            val componentName = ComponentName(appInfo.packageName, appInfo.className)
            val options = ActivityOptions.makeCustomAnimation(
                activity,
                R.anim.app_open_enter,
                R.anim.app_open_exit,
            )
            launcherApps.startMainActivity(
                componentName,
                Process.myUserHandle(),
                null,
                options.toBundle(),
            )
            AppLaunchResult.Launched
        } catch (e: ActivityNotFoundException) {
            AppLaunchResult.ComponentGone
        } catch (e: SecurityException) {
            AppLaunchResult.PermissionDenied
        } catch (e: Throwable) {
            AppLaunchResult.Failed(e)
        }
    }
}
