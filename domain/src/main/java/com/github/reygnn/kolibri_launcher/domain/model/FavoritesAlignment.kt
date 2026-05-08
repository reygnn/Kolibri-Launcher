package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Horizontal alignment of the favorites list on the home screen.
 *
 * Pure domain enum — the platform-side `Gravity.X` mapping lives in
 * `:app/ui/util/FavoritesAlignmentMapper.kt`, the same split as
 * `WallpaperBlendMode` ↔ `WallpaperBlendModeMapper`.
 *
 * Persisted in DataStore via [name]; `valueOf` on read with a fallback to
 * the default. Default is [START] for backward compatibility — that was
 * the hardcoded behavior before the setting existed.
 */
enum class FavoritesAlignment {
    START,
    CENTER,
    END,
}
