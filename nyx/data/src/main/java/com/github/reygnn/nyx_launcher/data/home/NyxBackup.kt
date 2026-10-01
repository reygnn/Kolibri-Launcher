package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import kotlinx.serialization.Serializable

/**
 * The Nyx backup payload — the `@Serializable` data of the one `nyx.backup` section in the
 * E5a container ([NyxBackupSchema]). Lives in `:data` alongside [HomeLayoutDto] (the domain
 * stays annotation-free). Every field is optional/defaulted so a later schema that adds a
 * field still decodes here (kotlinx `ignoreUnknownKeys` + defaults). Producer, app version,
 * time and schema version are the container manifest's, not the payload's.
 */
@Serializable
data class NyxBackup(
    /** Home grid layout (null = not included / not restored). */
    val layout: HomeLayoutDto? = null,
    /** DataStore-backed preferences (null = not included). */
    val prefs: NyxBackupPrefs? = null,
    /**
     * Drawer-folder membership (null = not included). Persisted in its own DataStore blob
     * (separate from [layout], D-5), so it must be carried here explicitly or it is lost on
     * restore. Restored under the layout import toggle.
     */
    val drawerFolders: DrawerFoldersDto? = null,
    /**
     * Apps hidden from the drawer (null = not included). Own DataStore blob, so carried here
     * explicitly or it is lost on restore. Restored under the layout import toggle (drawer
     * organisation, like [drawerFolders]).
     */
    val hiddenApps: List<ComponentKeyDto>? = null,
    /**
     * Wallpaper layers with per-layer transforms. [WallpaperLayerBackup.imageFileName] is the
     * SHA-256 of the layer's image blob in the container; a layer without a blob keeps its
     * `imageUri`.
     */
    val wallpaperLayers: List<WallpaperLayerBackup> = emptyList(),
)

/**
 * Nyx's DataStore preferences in backup form. All nullable: a null means "not in the
 * backup" and the import leaves the current value untouched (skip-on-null).
 */
@Serializable
data class NyxBackupPrefs(
    /** [com.github.reygnn.nyx_launcher.home.model.IconStyle] name (COLOR/MONOCHROME/GRAYSCALE). */
    val iconStyle: String? = null,
    val searchAutoLaunch: Boolean? = null,
    val usageSortEnabled: Boolean? = null,
    val notificationDots: Boolean? = null,
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
