package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.feature.wallpaper.WallpaperDisplayKeys

/**
 * Kolibri's names for the wallpaper display settings in its `settings` store (3a-4, D3) —
 * unchanged on disk, no migration: the surface mode keeps its old name `app_drawer_mode`.
 */
val KolibriWallpaperDisplayKeys = WallpaperDisplayKeys(
    scrimAlpha = AppConstants.PrefKeys.WALLPAPER_SCRIM_ALPHA,
    backdrop = AppConstants.PrefKeys.WALLPAPER_BACKDROP,
    surfaceMode = AppConstants.PrefKeys.APP_DRAWER_MODE,
)
