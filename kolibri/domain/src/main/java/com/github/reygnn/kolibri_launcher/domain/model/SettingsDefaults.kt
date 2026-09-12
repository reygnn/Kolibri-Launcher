package com.github.reygnn.kolibri_launcher.domain.model
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop

/**
 * Default values for settings whose type is a domain model enum.
 *
 * Split out of `core/AppConstants` (Phase 1 / MONOREPO_MERGE_SPEC): AppConstants
 * is otherwise product-neutral and lives in the shared `:core` module, but these
 * four defaults carry domain-model types ([SortOrder], [FavoritesAlignment],
 * [WallpaperSurfaceMode], [WallpaperBackdrop]) and therefore belong to the
 * product domain. Keeping them here lets AppConstants stay free of any domain
 * import. Same package as the enums, so no imports are needed.
 */
object SettingsDefaults {
    val DEFAULT_SORT_ORDER = SortOrder.TIME_WEIGHTED_USAGE
    val DEFAULT_FAVORITES_ALIGNMENT = FavoritesAlignment.START
    val DEFAULT_WALLPAPER_SURFACE_MODE = WallpaperSurfaceMode.AUTO

    // Preserve historical behaviour: the launcher window has always been
    // transparent + FLAG_SHOW_WALLPAPER, so existing users (and legacy backups
    // with no stored value) keep seeing the system wallpaper behind the collage.
    // Product may flip this to BLACK for fresh multi-layer installs.
    val DEFAULT_WALLPAPER_BACKDROP = WallpaperBackdrop.SYSTEM_WALLPAPER
}
