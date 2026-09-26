package com.github.reygnn.kolibri_launcher.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the defining invariant of the event-indicator anchoring (TODO §24): the
 * indicator pair is vertically CENTRED on the clock's visible digit band, at any font
 * scale. This is the testable replacement for the old eyeballed `vertical_bias=0.58`.
 */
class EventIndicatorAnchorTest {

    // A representative 60sp @ 1× clock: baseline 100px down from the view top, digits
    // reach 70px above the baseline (glyph top) and sit on it (no descender), and the
    // 2×28dp icon pair measures ~56px tall.
    private val baseline = 100
    private val glyphTop = -70
    private val glyphBottom = 0
    private val pairHeight = 56

    private fun bandCentre(base: Int, top: Int, bottom: Int) = base + (top + bottom) / 2f

    @Test
    fun `translationY places the pair centre exactly on the digit-band centre`() {
        val ty = EventIndicatorAnchor.translationY(baseline, glyphTop, glyphBottom, pairHeight)
        val pairCentreAfter = ty + pairHeight / 2f
        assertThat(pairCentreAfter).isEqualTo(bandCentre(baseline, glyphTop, glyphBottom))
    }

    @Test
    fun `concrete value matches the hand-computed offset`() {
        // digitCentre = 100 + (-70+0)/2 = 65 ; translationY = 65 - 56/2 = 37
        assertThat(EventIndicatorAnchor.translationY(baseline, glyphTop, glyphBottom, pairHeight))
            .isEqualTo(37f)
    }

    @Test
    fun `when the pair spans the band, top and bottom are flush with the digits`() {
        // The original design intent (alarm top = digit top, calendar bottom = digit
        // bottom) falls out for free when the pair height equals the band height.
        val bandHeight = glyphBottom - glyphTop // 70
        val ty = EventIndicatorAnchor.translationY(baseline, glyphTop, glyphBottom, bandHeight)
        val pairTop = ty                       // container top is pinned to clock top
        val pairBottom = ty + bandHeight
        assertThat(pairTop).isEqualTo((baseline + glyphTop).toFloat())     // flush with digit top
        assertThat(pairBottom).isEqualTo((baseline + glyphBottom).toFloat()) // flush with digit bottom
    }

    @Test
    fun `stays centred under a larger system font (font-scale robust)`() {
        // Scale the clock up ~1.3× (bigger baseline + taller glyphs) while the icon pair
        // keeps its fixed dp size — the pair must still centre on the (lower, taller) band.
        val bigBaseline = 130
        val bigGlyphTop = -91
        val ty = EventIndicatorAnchor.translationY(bigBaseline, bigGlyphTop, glyphBottom, pairHeight)
        val pairCentreAfter = ty + pairHeight / 2f
        assertThat(pairCentreAfter).isEqualTo(bandCentre(bigBaseline, bigGlyphTop, glyphBottom))
    }
}
