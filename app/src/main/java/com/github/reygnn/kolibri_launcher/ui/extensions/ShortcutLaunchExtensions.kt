package com.github.reygnn.kolibri_launcher.ui.extensions

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import androidx.fragment.app.Fragment
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import timber.log.Timber

/**
 * Shared extension für Shortcut-Launch-Logik.
 *
 * Extrahiert aus AppDrawerFragment und HomeFragment um DRY zu wahren.
 * Beide Fragments haben identische Launch-Logik für App-Shortcuts.
 */
fun Fragment.handleShortcutLaunch(
    bundle: Bundle,
    viewModel: LauncherViewModel
) {
    try {
        val shortcut = try {
            bundle.getParcelable(
                AppContextMenuDialogFragment.RESULT_KEY_SHORTCUT,
                ShortcutInfo::class.java
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting shortcut from bundle")
            null
        }

        if (shortcut == null) {
            Timber.w("Shortcut is null")
            viewModel.onAppInfoError()
            return
        }

        try {
            val launcherApps = requireContext()
                .getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

            if (launcherApps == null) {
                TimberWrapper.silentError("LauncherApps service is null")
                viewModel.onAppInfoError()
                return
            }

            launcherApps.startShortcut(shortcut, null, null)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error launching shortcut")
            viewModel.onAppInfoError()
        }
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error in handleShortcutLaunch")
    }
}