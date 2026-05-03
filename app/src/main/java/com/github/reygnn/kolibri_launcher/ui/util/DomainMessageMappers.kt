package com.github.reygnn.kolibri_launcher.ui.util

import androidx.annotation.StringRes
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherActionLabel
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase

/**
 * Mappers from domain identifiers to UI string-resource ids.
 *
 * The domain layer is pure Kotlin and exposes sealed-type identifiers
 * (instead of `@StringRes Int`). Each mapper here lives in `:app` so the
 * `R.string.*` references stay on the UI side of the layer boundary.
 *
 * If a new identifier is added in the domain, the corresponding `when`
 * exhaustiveness check fails at compile time — no silent drift possible.
 */

@StringRes
fun LauncherActionLabel.toStringResId(): Int = when (this) {
    LauncherActionLabel.AddToFavorites -> R.string.add_to_favorites
    LauncherActionLabel.RemoveFromFavorites -> R.string.remove_from_favorites
    LauncherActionLabel.RestoreOriginalName -> R.string.restore_original_name
    LauncherActionLabel.RenameApp -> R.string.rename_app
    LauncherActionLabel.UnhideAppInDrawer -> R.string.unhide_app_in_drawer
    LauncherActionLabel.HideAppFromDrawer -> R.string.hide_app_from_drawer
    LauncherActionLabel.ResetUsage -> R.string.action_reset_sorting
    LauncherActionLabel.AppInfo -> R.string.app_info
}

@StringRes
fun AppLoadResult.Failure.toStringResId(): Int = when (this) {
    AppLoadResult.Failure.NotLoaded -> R.string.error_app_list_not_loaded
}

@StringRes
fun ToggleFavoriteUseCase.Result.Success.toStringResId(): Int = when (this) {
    ToggleFavoriteUseCase.Result.Success.Added -> R.string.app_added_to_favorites
    ToggleFavoriteUseCase.Result.Success.Removed -> R.string.app_removed_from_favorites
}

@StringRes
fun ToggleFavoriteUseCase.Result.Error.toStringResId(): Int = when (this) {
    is ToggleFavoriteUseCase.Result.Error.LimitReached -> R.string.favorites_limit_reached
}
