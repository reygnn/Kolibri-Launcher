package com.github.reygnn.kolibri_launcher.core

import kotlin.math.pow

/**
 * Pure-Kotlin colour helpers. ARGB integers use the same packed format as
 * `android.graphics.Color`, so the values round-trip with the platform
 * type without conversion.
 *
 * Lives in `:domain/core/` so use cases that compute colour can stay
 * Android-free; UI consumers can pass the resulting `Int`s straight into
 * any platform API that expects an ARGB colour.
 */
object ColorMath {

    /** Solid white in ARGB. Identical bit pattern to `android.graphics.Color.WHITE`. */
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    /** Solid black in ARGB. Identical bit pattern to `android.graphics.Color.BLACK`. */
    const val BLACK: Int = 0xFF000000.toInt()

    /** Fully transparent. Identical bit pattern to `android.graphics.Color.TRANSPARENT`. */
    const val TRANSPARENT: Int = 0

    /**
     * Packs ARGB byte components into a single `Int` exactly the way
     * `android.graphics.Color.argb(int, int, int, int)` does. Inputs are
     * masked to 0..255 before packing.
     */
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha and 0xFF) shl 24) or
                ((red and 0xFF) shl 16) or
                ((green and 0xFF) shl 8) or
                (blue and 0xFF)

    /**
     * Relative luminance of an ARGB colour, computed per WCAG 2.x. Matches
     * `androidx.core.graphics.ColorUtils.calculateLuminance` to floating-point
     * precision: same sRGB → linear-RGB transform, same coefficient triple
     * (0.2126, 0.7152, 0.0722).
     *
     * Range: 0.0 (black) … 1.0 (white).
     */
    fun calculateLuminance(argb: Int): Double {
        val red = sRgbToLinear(((argb shr 16) and 0xFF) / 255.0)
        val green = sRgbToLinear(((argb shr 8) and 0xFF) / 255.0)
        val blue = sRgbToLinear((argb and 0xFF) / 255.0)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun sRgbToLinear(channel: Double): Double =
        if (channel < 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
}
