package com.github.reygnn.kolibri_launcher.core

import org.junit.Assert.assertEquals
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
        assertEquals(0xCCFFFFFF.toInt(), ColorMath.argb(204, 255, 255, 255))
        assertEquals(0xFF000000.toInt(), ColorMath.argb(255, 0, 0, 0))
    }

    @Test
    fun `argb masks each component to the low 8 bits`() {
        // Without `and 0xFF` these would bleed into higher bytes.
        assertEquals(0, ColorMath.argb(0, 256, 0, 0))               // 256 & 0xFF = 0
        assertEquals(0x00FF0000, ColorMath.argb(0, 511, 0, 0))      // 511 & 0xFF = 255
        assertEquals(44, ColorMath.argb(0, 0, 0, 300))             // 300 & 0xFF = 44
        assertEquals(0xFF000000.toInt(), ColorMath.argb(-1, 0, 0, 0)) // -1 & 0xFF = 255
    }

    // ---- calculateLuminance: independent WCAG reference values ----

    @Test
    fun `luminance of black is 0 and white is 1`() {
        assertEquals(0.0, ColorMath.calculateLuminance(ColorMath.BLACK), 1e-9)
        assertEquals(1.0, ColorMath.calculateLuminance(ColorMath.WHITE), 1e-9)
    }

    @Test
    fun `luminance of pure primaries equals the WCAG coefficients`() {
        // A R<->B coefficient swap or a wrong triple turns these red.
        assertEquals(0.2126, ColorMath.calculateLuminance(0xFFFF0000.toInt()), 1e-9)
        assertEquals(0.7152, ColorMath.calculateLuminance(0xFF00FF00.toInt()), 1e-9)
        assertEquals(0.0722, ColorMath.calculateLuminance(0xFF0000FF.toInt()), 1e-9)
    }

    @Test
    fun `luminance ignores the alpha channel`() {
        // The formula reads only RGB; alpha must not shift the result.
        assertEquals(
            ColorMath.calculateLuminance(0xFFFF0000.toInt()),
            ColorMath.calculateLuminance(0x00FF0000),
            1e-9,
        )
    }
}
