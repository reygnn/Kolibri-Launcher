package com.github.reygnn.kolibri_launcher.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the defining invariant of the event-indicator anchoring (TODO §24): the ALARM's
 * visible top edge is flush with the clock's visible digit top, and the CALENDAR's visible
 * bottom edge is flush with the digit bottom — at any font scale. This is the testable
 * replacement for the old eyeballed `vertical_bias=0.58`.
 *
 * Both icon boxes have their top pinned to the clock top, so a box's top equals its
 * `translationY`. "Visible" edges sit inset inside the box by the measured opaque inset.
 */
class EventIndicatorAnchorTest {

    // Representative 60sp @ 1× px: digits span 30..100 from the view top; each 74px icon
    // box has ~18px of transparent margin before its drawn mark.
    private val digitTop = 30
    private val digitBottom = 100
    private val boxHeight = 74
    private val insetTop = 18
    private val insetBottom = 18

    @Test
    fun `alarm visible top lands flush on the digit top`() {
        val ty = EventIndicatorAnchor.alarmTranslationY(digitTop, insetTop)
        // visible top = boxTop (== translationY) + opaque inset top
        assertThat(ty + insetTop).isEqualTo(digitTop.toFloat())
    }

    @Test
    fun `alarm concrete value matches the hand-computed offset`() {
        // 30 - 18 = 12
        assertThat(EventIndicatorAnchor.alarmTranslationY(digitTop, insetTop)).isEqualTo(12f)
    }

    @Test
    fun `calendar visible bottom lands flush on the digit bottom`() {
        val ty = EventIndicatorAnchor.calendarTranslationY(digitBottom, boxHeight, insetBottom)
        // visible bottom = boxTop (translationY) + boxHeight - opaque inset bottom
        assertThat(ty + boxHeight - insetBottom).isEqualTo(digitBottom.toFloat())
    }

    @Test
    fun `calendar concrete value matches the hand-computed offset`() {
        // 100 - 74 + 18 = 44
        assertThat(EventIndicatorAnchor.calendarTranslationY(digitBottom, boxHeight, insetBottom))
            .isEqualTo(44f)
    }

    @Test
    fun `both edges stay flush under a larger system font (font-scale robust)`() {
        // A bigger clock: the digit band moves down and grows; the fixed-size icons must
        // still land flush at both ends (the gap between them absorbs the extra span).
        val bigTop = 40
        val bigBottom = 130
        val alarmTy = EventIndicatorAnchor.alarmTranslationY(bigTop, insetTop)
        val calTy = EventIndicatorAnchor.calendarTranslationY(bigBottom, boxHeight, insetBottom)
        assertThat(alarmTy + insetTop).isEqualTo(bigTop.toFloat())
        assertThat(calTy + boxHeight - insetBottom).isEqualTo(bigBottom.toFloat())
    }
}
