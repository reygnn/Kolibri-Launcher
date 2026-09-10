package com.github.reygnn.kolibri_launcher.domain.model

/**
 * User-facing setting for the AppDrawer surface style.
 *
 * - [AUTO] follows a wallpaper-luminance classification resolved by
 *   `ResolveWallpaperSurfaceUseCase` (backed by `ClassifyWallpaperUseCase`).
 *   [DARK] is used only as the fallback when neither the wallpaper nor the
 *   system-colour signal is available.
 * - [LIGHT] / [DARK] are explicit overrides that ignore the wallpaper
 *   signal entirely.
 */
enum class WallpaperSurfaceMode {
    AUTO,
    LIGHT,
    DARK,
}
