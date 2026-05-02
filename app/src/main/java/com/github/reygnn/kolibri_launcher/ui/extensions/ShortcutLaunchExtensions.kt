package com.github.reygnn.kolibri_launcher.ui.extensions

import android.content.pm.ShortcutInfo
import android.os.Bundle
import androidx.fragment.app.Fragment
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.usecase.LaunchShortcutUseCase
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import timber.log.Timber

/**
 * Shared extension für Shortcut-Launch-Logik.
 *
 * Extrahiert aus AppDrawerFragment und HomeFragment um DRY zu wahren.
 * Delegiert die eigentliche Logik an LaunchShortcutUseCase für Testbarkeit.
 */
fun Fragment.handleShortcutLaunch(
    bundle: Bundle,
    viewModel: LauncherViewModel,
    launchShortcutUseCase: LaunchShortcutUseCase
) {
    // extractShortcutFromBundle catches BadParcelableException internally
    // and returns null on failure. launchShortcutUseCase.execute returns
    // a sealed Result, never throws. The when-block is exhaustive.
    val shortcut = extractShortcutFromBundle(bundle)
    val result = launchShortcutUseCase.execute(shortcut)

    when (result) {
        is LaunchShortcutUseCase.Result.Success -> {
            Timber.d("Shortcut launched successfully")
        }
        is LaunchShortcutUseCase.Result.Failure -> {
            handleLaunchError(result.error, viewModel)
        }
    }
}

/**
 * Extrahiert ShortcutInfo aus einem Bundle.
 *
 * @return ShortcutInfo oder null bei Fehler
 */
private fun extractShortcutFromBundle(bundle: Bundle): ShortcutInfo? {
    return try {
        bundle.getParcelable(
            AppContextMenuDialogFragment.RESULT_KEY_SHORTCUT,
            ShortcutInfo::class.java
        )
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error getting shortcut from bundle")
        null
    }
}

/**
 * Verarbeitet Launch-Fehler und benachrichtigt das ViewModel.
 */
private fun handleLaunchError(
    error: LaunchShortcutUseCase.Error,
    viewModel: LauncherViewModel
) {
    when (error) {
        is LaunchShortcutUseCase.Error.ShortcutNull -> {
            Timber.w("Shortcut is null")
        }
        is LaunchShortcutUseCase.Error.ServiceUnavailable -> {
            TimberWrapper.silentError("LauncherApps service is not available")
        }
        is LaunchShortcutUseCase.Error.LaunchFailed -> {
            TimberWrapper.silentError(error.cause, "Failed to launch shortcut")
        }
        is LaunchShortcutUseCase.Error.Unknown -> {
            TimberWrapper.silentError(error.cause, "Unknown error launching shortcut")
        }
    }
    viewModel.onAppInfoError()
}