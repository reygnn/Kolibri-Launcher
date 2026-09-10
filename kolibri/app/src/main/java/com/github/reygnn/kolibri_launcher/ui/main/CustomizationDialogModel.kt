package com.github.reygnn.kolibri_launcher.ui.main

/**
 * Pure-logic model for the "Customize" dialog `MainActivity` shows when the
 * user long-presses on the home screen. Two axes are encoded:
 *
 *  1. Whether the dialog should appear at all (it is suppressed while the
 *     wallpaper edit mode is active — the user already has the inline
 *     edit-mode buttons visible and a competing dialog would be confusing).
 *  2. Which options appear, in which order — `Edit` and `Remove` for the
 *     wallpaper are conditional on a wallpaper being present.
 *
 * String resolution and click-handler dispatch stay in `MainActivity`: the
 * model only enumerates *which* options are present, not what they say or
 * what they do. Pattern matches the existing pure-decision extracts in this
 * codebase (`AppLaunchAction`, `RenameDecision`, `WallpaperSaveAction`).
 */
sealed interface CustomizationDialogModel {

    /**
     * Suppress the dialog entirely. Currently only happens when the
     * wallpaper edit mode is active.
     */
    data object Hidden : CustomizationDialogModel

    /**
     * Show the dialog with [options] in the given order. The list is
     * always non-empty in practice — the four non-wallpaper entries are
     * unconditional — but no caller should rely on that.
     */
    data class Visible(val options: List<CustomizationOption>) : CustomizationDialogModel

    companion object {
        /**
         * @param hasWallpaper whether `WallpaperState.hasWallpaper` is
         *   true at decision time. Drives whether `EditWallpaper` and
         *   `RemoveWallpaper` are included.
         * @param isWallpaperEditMode whether the wallpaper-edit overlay
         *   is currently active. If true, the result is [Hidden] and
         *   `hasWallpaper` is irrelevant.
         */
        fun build(
            hasWallpaper: Boolean,
            isWallpaperEditMode: Boolean,
        ): CustomizationDialogModel {
            if (isWallpaperEditMode) return Hidden

            val options = buildList {
                add(CustomizationOption.ChooseWallpaper)
                if (hasWallpaper) {
                    add(CustomizationOption.EditWallpaper)
                    add(CustomizationOption.RemoveWallpaper)
                }
                add(CustomizationOption.CustomizeColors)
                add(CustomizationOption.CustomizeLayout)
                add(CustomizationOption.MoreSettings)
            }
            return Visible(options)
        }
    }
}

/**
 * Each entry the customization dialog may surface. The Activity maps each
 * variant to a localized string and a click action; this enum carries
 * neither — it just identifies the slot.
 */
sealed interface CustomizationOption {
    data object ChooseWallpaper : CustomizationOption
    data object EditWallpaper : CustomizationOption
    data object RemoveWallpaper : CustomizationOption
    data object CustomizeColors : CustomizationOption
    data object CustomizeLayout : CustomizationOption
    data object MoreSettings : CustomizationOption
}
