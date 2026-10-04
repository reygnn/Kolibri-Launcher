package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.feature.wallpaper.WallpaperDisplayKeys

/**
 * Nyx's names for the wallpaper display settings in its `home_layout` store (3b-2) — unchanged on
 * disk, no migration: the surface mode keeps its name `wallpaper_surface_mode` (Kolibri's is the
 * historical `app_drawer_mode`).
 */
val NyxWallpaperDisplayKeys = WallpaperDisplayKeys(
    scrimAlpha = AppConstants.PrefKeys.WALLPAPER_SCRIM_ALPHA,
    backdrop = AppConstants.PrefKeys.WALLPAPER_BACKDROP,
    surfaceMode = "wallpaper_surface_mode",
)
