package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import androidx.core.content.ContextCompat
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.model.ResolvedBackground

/**
 * Maps a [LuminanceClassification] to the wallpaper-following surface colour
 * as a [ResolvedBackground.SolidColor], from which callers derive the body
 * colour ([ResolvedBackground.color]) and the WCAG-based text colour
 * ([ResolvedBackground.foregroundColor]).
 *
 * The `LIGHT/DARK → app_drawer_surface_light/_dark → SolidColor` mapping was
 * previously copied into the long-press menu, the colour-customisation dialog
 * and the layout-customisation dialog. The [ResolveWallpaperSurfaceUseCase]
 * flow is central; only this final colour lookup had leaked. Keeping it here
 * makes the three dialogs share one surface definition.
 */
fun LuminanceClassification.toSurface(context: Context): ResolvedBackground.SolidColor {
    val colorRes = when (this) {
        LuminanceClassification.LIGHT -> R.color.app_drawer_surface_light
        LuminanceClassification.DARK -> R.color.app_drawer_surface_dark
    }
    return ResolvedBackground.SolidColor(ContextCompat.getColor(context, colorRes))
}
