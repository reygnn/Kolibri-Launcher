package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey

/**
 * A single, DERIVED drawer display row (DRAWER_FOLDERS_SPEC §3) — never persisted.
 * Produced by the projection in `GetDrawerContentUseCase`: a pinned block of
 * [Folder]s (alpha by title) on top, then the loose [App]s (alpha by display name).
 *
 * A [Folder] carries its already-RECONCILED members (intersected with the live app
 * set), so the UI renders the folder icon from apps that actually exist.
 */
sealed interface DrawerEntry {
    /**
     * A loose drawer app. [hidden] is a display-only flag set by the projection ONLY in
     * reveal mode (overflow "show hidden") — normally a hidden app is filtered out entirely,
     * so [hidden] is false; when revealed it is true so the UI can dim the tile.
     */
    data class App(val app: LauncherApp, val hidden: Boolean = false) : DrawerEntry
    data class Folder(
        val id: DrawerFolderId,
        val title: String,
        val members: List<ComponentKey>,
    ) : DrawerEntry
}
