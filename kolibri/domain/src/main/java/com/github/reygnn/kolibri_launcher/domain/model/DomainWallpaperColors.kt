package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Pure-Kotlin domain projection of `android.app.WallpaperColors`.
 *
 * Only the two data points the launcher actually consults end up here:
 *
 * - [supportsDarkText] mirrors the `HINT_SUPPORTS_DARK_TEXT` colour hint —
 *   the system's recommendation that dark text reads better against the
 *   current wallpaper.
 * - [secondaryColorArgb] is the wallpaper's secondary colour as an ARGB
 *   integer (the format `Color.argb` produces); `null` if the system did
 *   not provide one.
 *
 * Mapping from `android.app.WallpaperColors` lives in the UI layer where
 * the platform type is available.
 */
data class DomainWallpaperColors(
    val supportsDarkText: Boolean,
    val secondaryColorArgb: Int?
)
