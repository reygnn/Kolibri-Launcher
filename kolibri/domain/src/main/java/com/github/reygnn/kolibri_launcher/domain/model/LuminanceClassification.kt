package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Resolved AppDrawer surface choice — the output of
 * `ResolveWallpaperSurfaceUseCase` after combining the user's
 * [WallpaperSurfaceMode] with the wallpaper classifier.
 *
 * Two values only: the AppDrawer is binary by design (one of two
 * pre-defined surface colours), not a wallpaper-tinted gradient. UI
 * layers map this to actual `@ColorInt Int`s via resources — the
 * domain stays free of `R`-references.
 */
enum class LuminanceClassification {
    LIGHT,
    DARK,
}
