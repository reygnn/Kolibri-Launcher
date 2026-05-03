package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Domain enum mirroring the subset of `android.graphics.BlendMode` values that
 * this launcher persists for wallpaper layers. Kept Android-free so the domain
 * module can stay pure Kotlin.
 *
 * The enum names match `android.graphics.BlendMode`'s names exactly, so a
 * legacy `blendModeName: String?` round-trips with `valueOf` / `name` without
 * a translation table. Mapping to the platform enum lives in the UI layer.
 */
enum class WallpaperBlendMode {
    MULTIPLY,
    SCREEN,
    OVERLAY,
    SOFT_LIGHT,
    HARD_LIGHT,
    DARKEN,
    LIGHTEN,
    DIFFERENCE,
    EXCLUSION,
    COLOR_DODGE,
    COLOR_BURN
}
