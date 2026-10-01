package com.github.reygnn.nyx_launcher.settings

import com.github.reygnn.nyx_launcher.home.model.BackupPreview
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure logic of the restore dialog (2b-3b): offered ⇔ the backup has something for it. */
class ImportOptionsUiStateTest {

    private val full = BackupPreview(
        appVersion = "0.2.0", timestamp = 1_700_000_000_000L, homeItemCount = 5, drawerFolderCount = 1,
        hiddenAppCount = 2, hasSettings = true, wallpaperLayerCount = 2,
    )

    @Test
    fun a_full_backup_offers_every_choice_in_dialog_order() {
        assertThat(ImportOptionsUiState.from(full).choices)
            .containsExactly(ImportChoice.LAYOUT, ImportChoice.HIDDEN_APPS, ImportChoice.SETTINGS, ImportChoice.WALLPAPER)
            .inOrder()
    }

    @Test
    fun hidden_apps_are_offered_only_with_at_least_one_app() {
        // An empty or missing list is never offered, so it can't replace the current set (B13).
        assertThat(ImportOptionsUiState.from(full.copy(hiddenAppCount = 0)).choices).doesNotContain(ImportChoice.HIDDEN_APPS)
        assertThat(ImportOptionsUiState.from(full.copy(hiddenAppCount = null)).choices).doesNotContain(ImportChoice.HIDDEN_APPS)
    }

    @Test
    fun layout_is_offered_for_a_layout_or_for_drawer_folders() {
        val neither = full.copy(homeItemCount = null, drawerFolderCount = 0)
        assertThat(ImportOptionsUiState.from(neither).choices).doesNotContain(ImportChoice.LAYOUT)
        assertThat(ImportOptionsUiState.from(neither.copy(homeItemCount = 0)).choices).contains(ImportChoice.LAYOUT)
        assertThat(ImportOptionsUiState.from(neither.copy(drawerFolderCount = 1)).choices).contains(ImportChoice.LAYOUT)
    }

    @Test
    fun settings_and_wallpaper_follow_the_preview() {
        val bare = full.copy(hasSettings = false, wallpaperLayerCount = 0)
        assertThat(ImportOptionsUiState.from(bare).choices).containsNoneOf(ImportChoice.SETTINGS, ImportChoice.WALLPAPER)
    }

    @Test
    fun the_date_is_known_only_for_a_positive_timestamp() {
        assertThat(ImportOptionsUiState.from(full).dateHasTimestamp).isTrue()
        assertThat(ImportOptionsUiState.from(full.copy(timestamp = 0L)).dateHasTimestamp).isFalse()
        assertThat(ImportOptionsUiState.from(full.copy(timestamp = -1L)).dateHasTimestamp).isFalse()
    }

    @Test
    fun the_selection_becomes_the_import_options() {
        val ui = ImportOptionsUiState.from(full)

        assertThat(ui.toImportOptions(setOf(ImportChoice.LAYOUT, ImportChoice.WALLPAPER))).isEqualTo(
            ImportOptions(importLayout = true, importHiddenApps = false, importSettings = false, importWallpaper = true),
        )
        assertThat(ui.toImportOptions(emptySet()).importNothing).isTrue()
    }

    @Test
    fun a_choice_that_was_not_offered_is_never_imported() {
        val ui = ImportOptionsUiState.from(full.copy(hiddenAppCount = 0))

        assertThat(ui.toImportOptions(ImportChoice.entries.toSet()).importHiddenApps).isFalse()
    }
}
