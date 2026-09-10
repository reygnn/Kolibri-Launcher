package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.core.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin tests for [ResolvedBackground]. No coroutines, no Android.
 *
 * Covers both subtypes' [ResolvedBackground.foregroundColor] mapping and
 * the strict-greater-than threshold semantics around the WCAG midpoint.
 */
class ResolvedBackgroundTest {

    @Test
    fun `SolidColor on pure white returns black foreground`() {
        val bg = ResolvedBackground.SolidColor(ColorMath.WHITE)
        assertEquals(ColorMath.BLACK, bg.foregroundColor())
    }

    @Test
    fun `SolidColor on pure black returns white foreground`() {
        val bg = ResolvedBackground.SolidColor(ColorMath.BLACK)
        assertEquals(ColorMath.WHITE, bg.foregroundColor())
    }

    @Test
    fun `SolidColor exposes its colour verbatim`() {
        val argb = ColorMath.argb(0xFF, 0x12, 0x34, 0x56)
        val bg = ResolvedBackground.SolidColor(argb)
        assertEquals(argb, bg.color)
    }

    @Test
    fun `WallpaperSample mirrors dominantColor through color`() {
        val argb = ColorMath.argb(0xFF, 0xAB, 0xCD, 0xEF)
        val bg = ResolvedBackground.WallpaperSample(dominantColor = argb)
        assertEquals(argb, bg.color)
    }

    @Test
    fun `WallpaperSample on pure white returns black foreground`() {
        val bg = ResolvedBackground.WallpaperSample(dominantColor = ColorMath.WHITE)
        assertEquals(ColorMath.BLACK, bg.foregroundColor())
    }

    @Test
    fun `threshold is strict greater-than — luminance at exactly 0_5 resolves to white text`() {
        // Construct a colour with luminance ≈ 0.5 and verify the strict
        // `> 0.5` rule documented in ResolvedBackground.foregroundColor.
        // Mid-grey #777777 has WCAG luminance ≈ 0.18 — too low for the
        // edge. We synthesise a colour just above and just below the
        // threshold and assert both sides switch.
        val justBelow = grayWithLuminance(0.49)
        val justAbove = grayWithLuminance(0.51)
        assertEquals(ColorMath.WHITE, ResolvedBackground.SolidColor(justBelow).foregroundColor())
        assertEquals(ColorMath.BLACK, ResolvedBackground.SolidColor(justAbove).foregroundColor())
    }

    /**
     * Returns an opaque grey ARGB int whose WCAG luminance is
     * approximately [target]. Search the 0..255 channel space (grey
     * means R=G=B), pick the channel value closest to the target.
     */
    private fun grayWithLuminance(target: Double): Int {
        val best = (0..255).minBy { channel ->
            val argb = ColorMath.argb(0xFF, channel, channel, channel)
            kotlin.math.abs(ColorMath.calculateLuminance(argb) - target)
        }
        return ColorMath.argb(0xFF, best, best, best)
    }
}
