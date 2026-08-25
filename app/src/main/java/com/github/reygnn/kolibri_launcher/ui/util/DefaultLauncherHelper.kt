package com.github.reygnn.kolibri_launcher.ui.util

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import com.github.reygnn.kolibri_launcher.core.TimberWrapper

/**
 * Single source of truth for the default-launcher (ROLE_HOME) flow, shared by
 * [com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingActivity]'s setup
 * button and SettingsFragment's preference.
 *
 * The [ActivityResultLauncher] can't be owned here — it must be registered by
 * the host (Activity/Fragment) as a field before STARTED. The caller registers
 * one StartActivityForResult launcher and hands it in; this object only builds
 * the request and picks the strategy (in-place role dialog, else Home settings).
 */
object DefaultLauncherHelper {

    /** True iff this app currently holds ROLE_HOME. Fail-closed to false. */
    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            context.getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } catch (e: Throwable) {
            // Non-suspend system-API read; no CancellationException to leak.
            TimberWrapper.silentError(e, "Error checking ROLE_HOME")
            false
        }
    }

    /**
     * Prefer the API 29+ in-place role dialog so the user can set Kolibri as the
     * default launcher without leaving the current screen. Falls back to the Home
     * settings screen on older devices, when the role isn't available / already
     * held, or if building the request throws. [onError] fires only if even the
     * settings fallback fails, so the caller can surface a toast.
     */
    fun requestDefault(
        activity: Activity,
        roleLauncher: ActivityResultLauncher<Intent>,
        onError: (Throwable) -> Unit = {}
    ) {
        val launchedDialog = try {
            tryLaunchRoleDialog(activity, roleLauncher)
        } catch (e: Throwable) {
            // RoleManager fetch / availability check / intent build are all
            // synchronous Android calls that can throw on some OEMs.
            TimberWrapper.silentError(e, "Error building ROLE_HOME request")
            false
        }
        if (launchedDialog) return

        // Single fallback path — the Home settings screen.
        try {
            activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error opening home settings")
            onError(e)
        }
    }

    /** @return true if the in-place dialog was launched; false → caller falls back. */
    private fun tryLaunchRoleDialog(
        activity: Activity,
        roleLauncher: ActivityResultLauncher<Intent>
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = activity.getSystemService(RoleManager::class.java) ?: return false
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) return false
        if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) return false
        roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
        return true
    }
}
