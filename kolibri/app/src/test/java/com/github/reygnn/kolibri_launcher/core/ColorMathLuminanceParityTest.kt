package com.github.reygnn.kolibri_launcher.core

import androidx.core.graphics.ColorUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins ColorMath.calculateLuminance's KDoc claim — "Matches
 * androidx.core.graphics.ColorUtils.calculateLuminance to floating-point
 * precision" — against the real library (the independent oracle), across the
 * sRGB curve including mid-tones and the 0.03928 linear-segment cutoff.
 *
 * The pure-JVM ColorMathTest already pins the endpoints, primaries and masking;
 * this catches a gamma or cutoff drift that primaries can't (channel 0 or 1 give
 * the same linear value under any exponent). Robolectric because ColorUtils is
 * an Android-SDK class.
 */
@RunWith(RobolectricTestRunner::class)
class ColorMathLuminanceParityTest {

    @Test
    fun `luminance matches androidx ColorUtils across the sRGB curve`() {
        val colors = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),        // endpoints
            0xFF808080.toInt(), 0xFF404040.toInt(), 0xFFC0C0C0.toInt(), // mid-greys
            0xFF0A0A0A.toInt(), 0xFF0B0B0B.toInt(),        // straddle the 0.03928 cutoff (10/255, 11/255)
            0xFFFF8040.toInt(), 0xFF123456.toInt(), 0xFF7F3FBF.toInt(), // arbitrary mixes
        )
        for (c in colors) {
            assertThat(ColorMath.calculateLuminance(c))
                .isWithin(1e-9)
                .of(ColorUtils.calculateLuminance(c))
        }
    }
}
