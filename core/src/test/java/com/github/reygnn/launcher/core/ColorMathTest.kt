package com.github.reygnn.launcher.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM pins for [ColorMath]. Deliberately independent of the impl: the
 * masking cases use out-of-range inputs the KDoc promises to clamp, and the
 * luminance cases assert the published WCAG reference values (coefficients +
 * endpoints), not values re-derived from ColorMath itself. This avoids the
 * self-referential trap where a formula drift shifts both input and expectation
 * together. Mid-curve parity with androidx ColorUtils lives in the Robolectric
 * ColorMathLuminanceParityTest.
 */
class ColorMathTest {

    // ---- argb packing + masking (KDoc: "Inputs are masked to 0..255") ----

    @Test
    fun `argb packs components in ARGB order`() {
        assertThat(ColorMath.argb(204, 255, 255, 255)).isEqualTo(0xCCFFFFFF.toInt())
        assertThat(ColorMath.argb(255, 0, 0, 0)).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun `argb masks each component to the low 8 bits`() {
        // Without `and 0xFF` these would bleed into higher bytes.
        assertThat(ColorMath.argb(0, 256, 0, 0)).isEqualTo(0)               // 256 & 0xFF = 0
        assertThat(ColorMath.argb(0, 511, 0, 0)).isEqualTo(0x00FF0000)      // 511 & 0xFF = 255
        assertThat(ColorMath.argb(0, 0, 0, 300)).isEqualTo(44)             // 300 & 0xFF = 44
        assertThat(ColorMath.argb(-1, 0, 0, 0)).isEqualTo(0xFF000000.toInt()) // -1 & 0xFF = 255
    }

    // ---- calculateLuminance: independent WCAG reference values ----

    @Test
    fun `luminance of black is 0 and white is 1`() {
        assertThat(ColorMath.calculateLuminance(ColorMath.BLACK)).isWithin(1e-9).of(0.0)
        assertThat(ColorMath.calculateLuminance(ColorMath.WHITE)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `luminance of pure primaries equals the WCAG coefficients`() {
        // A R<->B coefficient swap or a wrong triple turns these red.
        assertThat(ColorMath.calculateLuminance(0xFFFF0000.toInt())).isWithin(1e-9).of(0.2126)
        assertThat(ColorMath.calculateLuminance(0xFF00FF00.toInt())).isWithin(1e-9).of(0.7152)
        assertThat(ColorMath.calculateLuminance(0xFF0000FF.toInt())).isWithin(1e-9).of(0.0722)
    }

    @Test
    fun `luminance ignores the alpha channel`() {
        // The formula reads only RGB; alpha must not shift the result.
        assertThat(ColorMath.calculateLuminance(0x00FF0000)).isWithin(1e-9).of(ColorMath.calculateLuminance(0xFFFF0000.toInt()))
    }
}
