package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import kotlinx.serialization.Serializable

/**
 * The full Nyx backup payload — the `@Serializable` envelope written into the ZIP
 * container's `backup.json`. Lives in `:data` alongside [HomeLayoutDto] (the domain
 * stays annotation-free). Every field is optional/defaulted so an older backup still
 * decodes forward-compatibly (kotlinx `ignoreUnknownKeys` + defaults).
 *
 * Unlike Kolibri's paranoid multi-pass serializer, Nyx starts lean: a straight
 * kotlinx round-trip, no org.json strict-recovery (a fresh product with no
 * hand-edited-backup legacy to defend).
 */
@Serializable
data class NyxBackup(
    val schemaVersion: Int = 1,
    val timestamp: Long = 0L,
    val appVersion: String = "",
    /** Home grid layout (null = not included / not restored). */
    val layout: HomeLayoutDto? = null,
    /** DataStore-backed preferences (null = not included). */
    val prefs: NyxBackupPrefs? = null,
    /**
     * Wallpaper layers with per-layer transforms. Each [WallpaperLayerBackup.imageFileName]
     * points at a blob inside the ZIP's `wallpapers/` dir (restored to internal storage).
     */
    val wallpaperLayers: List<WallpaperLayerBackup> = emptyList(),
)

/**
 * Nyx's DataStore preferences in backup form. All nullable: a null means "not in the
 * backup" and the import leaves the current value untouched (skip-on-null).
 */
/** Selective-import toggles (not persisted — a runtime choice from the restore UI). */
data class NyxBackupOptions(
    val importLayout: Boolean = true,
    val importSettings: Boolean = true,
    val importWallpaper: Boolean = true,
)

@Serializable
data class NyxBackupPrefs(
    val monochromeIcons: Boolean? = null,
    val showAlarm: Boolean? = null,
    val showCalendarEvent: Boolean? = null,
    val scrimAlpha: Float? = null,
    /** [com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop] name. */
    val backdrop: String? = null,
    /** [com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode] name. */
    val surfaceMode: String? = null,
    val fabXFraction: Float? = null,
    val fabYFraction: Float? = null,
)
