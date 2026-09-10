package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.core.ColorMath

/**
 * Effective background of a launcher surface — the abstraction every
 * text-on-surface colour decision routes through.
 *
 * Two shapes:
 * - [SolidColor]: the surface paints a single ARGB colour (e.g., the
 *   AppDrawer's light/dark surface).
 * - [WallpaperSample]: the surface lets the wallpaper through, and
 *   foreground colour is derived from the wallpaper's dominant colour
 *   (e.g., the homescreen's text/clock colour pipeline).
 *
 * Colour values are plain `Int` in ARGB-packed format, identical to
 * `android.graphics.Color`. The `:domain` module is pure-Kotlin JVM and
 * does not depend on `androidx.annotation` for the `@ColorInt` marker;
 * `:data` and `:app` consumers may add the annotation at their layer.
 */
sealed interface ResolvedBackground {

    /** ARGB-packed background colour, identical bit pattern to `android.graphics.Color`. */
    val color: Int

    /**
     * WCAG-luminance-based foreground choice — returns either
     * [ColorMath.WHITE] or [ColorMath.BLACK]. The threshold is `0.5`
     * with strict greater-than (luminance exactly `0.5` resolves to
     * [ColorMath.WHITE], i.e., favours light text on the perceptual
     * midpoint).
     */
    fun foregroundColor(): Int =
        if (ColorMath.calculateLuminance(color) > LUMINANCE_THRESHOLD) ColorMath.BLACK
        else ColorMath.WHITE

    data class SolidColor(override val color: Int) : ResolvedBackground

    data class WallpaperSample(val dominantColor: Int) : ResolvedBackground {
        override val color: Int get() = dominantColor
    }

    companion object {
        const val LUMINANCE_THRESHOLD: Double = 0.5
    }
}
