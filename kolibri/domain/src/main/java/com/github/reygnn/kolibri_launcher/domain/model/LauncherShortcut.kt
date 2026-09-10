package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Pure-Kotlin domain representation of an app-provided launcher shortcut.
 *
 * The platform's `android.content.pm.ShortcutInfo` carries roughly two dozen
 * fields plus an opaque internal state. The launcher only needs three of
 * them at the domain boundary:
 *
 * - [id] — the per-package shortcut id, used by `LauncherApps.startShortcut`.
 * - [packageName] — the app whose shortcut this is.
 * - [shortLabel] — the label shown in the context menu.
 *
 * Mapping back to a real `ShortcutInfo` (for actual launching) happens in the
 * `:data` layer, which uses `LauncherApps.startShortcut(packageName, id, …)`
 * — the platform looks the live shortcut up by id, so the launcher does not
 * have to keep the opaque handle around.
 */
data class LauncherShortcut(
    val id: String,
    val packageName: String,
    val shortLabel: String?
)
