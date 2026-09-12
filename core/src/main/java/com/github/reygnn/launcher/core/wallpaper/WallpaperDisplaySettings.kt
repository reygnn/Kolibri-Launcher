package com.github.reygnn.launcher.core.wallpaper

import kotlinx.coroutines.flow.Flow

/**
 * Narrow read/write port for the three wallpaper-display flags the shared render
 * layer needs (WALLPAPER_SHARE_SPEC §5, WSS-INV-4): scrim alpha, backdrop
 * (system-wallpaper vs. opaque — a user choice, WSS-INV-6), and the AUTO/LIGHT/DARK
 * drawer surface mode. Deliberately NOT the whole product settings store — each app
 * adapts its own store onto this port (Kolibri's SettingsRepository implements it
 * directly; Nyx binds a small adapter). The wallpaper STATE itself lives in
 * [WallpaperRepository], not here.
 *
 * Member names mirror Kolibri's existing SettingsRepository members so call sites
 * (the render-near use cases) don't change when they rehang onto this port.
 */
interface WallpaperDisplaySettings {
    val wallpaperScrimAlphaStateFlow: Flow<Float>
    suspend fun setWallpaperScrimAlpha(alpha: Float)

    val wallpaperSurfaceModeFlow: Flow<WallpaperSurfaceMode>
    suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode)

    val wallpaperBackdropFlow: Flow<WallpaperBackdrop>
    suspend fun setWallpaperBackdrop(backdrop: WallpaperBackdrop)
}
