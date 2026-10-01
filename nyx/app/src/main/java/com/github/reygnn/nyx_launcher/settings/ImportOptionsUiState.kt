package com.github.reygnn.nyx_launcher.settings

import com.github.reygnn.nyx_launcher.home.model.BackupPreview
import com.github.reygnn.nyx_launcher.home.model.ImportOptions

/** One switch of the restore dialog. Drawer folders are restored with the home layout. */
enum class ImportChoice { LAYOUT, HIDDEN_APPS, SETTINGS, WALLPAPER }

/**
 * PURE LOGIC — what the restore dialog offers for a [BackupPreview] (2b-3b), like Kolibri's
 * `ImportOptionsUiState`. No Android: strings and the dialog are the fragment's.
 *
 * Convention (as in Kolibri): a choice is offered — and then checked — exactly when the backup
 * has something for it. Hidden apps only with at least one app in the backup, so an empty list
 * never silently replaces the current set (B13 applies when the switch is on).
 */
data class ImportOptionsUiState(
    val dateHasTimestamp: Boolean,
    /** Offered choices in dialog order; each starts checked. */
    val choices: List<ImportChoice>,
) {
    /** The options for the [selected] choices; a choice that was not offered is never imported. */
    fun toImportOptions(selected: Set<ImportChoice>): ImportOptions {
        fun on(choice: ImportChoice) = choice in selected && choice in choices
        return ImportOptions(
            importLayout = on(ImportChoice.LAYOUT),
            importHiddenApps = on(ImportChoice.HIDDEN_APPS),
            importSettings = on(ImportChoice.SETTINGS),
            importWallpaper = on(ImportChoice.WALLPAPER),
        )
    }

    companion object {
        fun from(preview: BackupPreview) = ImportOptionsUiState(
            dateHasTimestamp = preview.timestamp > 0L,
            choices = buildList {
                if (preview.homeItemCount != null || preview.drawerFolderCount > 0) add(ImportChoice.LAYOUT)
                if ((preview.hiddenAppCount ?: 0) > 0) add(ImportChoice.HIDDEN_APPS)
                if (preview.hasSettings) add(ImportChoice.SETTINGS)
                if (preview.wallpaperLayerCount > 0) add(ImportChoice.WALLPAPER)
            },
        )
    }
}
