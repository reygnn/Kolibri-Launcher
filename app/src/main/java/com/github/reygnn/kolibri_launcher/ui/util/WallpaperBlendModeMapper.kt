package com.github.reygnn.kolibri_launcher.ui.util

import android.graphics.BlendMode
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperBlendMode

/**
 * Maps the domain [WallpaperBlendMode] to the platform `android.graphics.BlendMode`.
 *
 * The mapping is total (each domain value has exactly one platform counterpart).
 * Lives in `:app` so the domain layer can stay free of `android.graphics`.
 */
fun WallpaperBlendMode.toAndroidBlendMode(): BlendMode = when (this) {
    WallpaperBlendMode.MULTIPLY -> BlendMode.MULTIPLY
    WallpaperBlendMode.SCREEN -> BlendMode.SCREEN
    WallpaperBlendMode.OVERLAY -> BlendMode.OVERLAY
    WallpaperBlendMode.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
    WallpaperBlendMode.HARD_LIGHT -> BlendMode.HARD_LIGHT
    WallpaperBlendMode.DARKEN -> BlendMode.DARKEN
    WallpaperBlendMode.LIGHTEN -> BlendMode.LIGHTEN
    WallpaperBlendMode.DIFFERENCE -> BlendMode.DIFFERENCE
    WallpaperBlendMode.EXCLUSION -> BlendMode.EXCLUSION
    WallpaperBlendMode.COLOR_DODGE -> BlendMode.COLOR_DODGE
    WallpaperBlendMode.COLOR_BURN -> BlendMode.COLOR_BURN
}
