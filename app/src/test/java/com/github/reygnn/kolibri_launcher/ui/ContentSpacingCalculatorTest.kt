package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.ui.home.ContentSpacingCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentSpacingCalculatorTest {

    private val calculator = ContentSpacingCalculator()

    @Test
    fun `wenn Chips aus sind, wird volle UserMargin genutzt`() {
        val result = calculator.calculateFavoritesTopMargin(
            userPreferredMarginPx = 100,
            chipsHeightPx = 0, // oder irrelevant
            areChipsVisible = false
        )
        assertEquals(100, result)
    }

    @Test
    fun `wenn Chips kleiner als Margin sind, wird der Rest als Margin genutzt`() {
        // Beispiel: Margin 24, Chips 16 -> Rest 8
        val result = calculator.calculateFavoritesTopMargin(
            userPreferredMarginPx = 24,
            chipsHeightPx = 16,
            areChipsVisible = true
        )
        assertEquals(8, result)
    }

    @Test
    fun `wenn Chips groesser als Margin sind, wird Mindestabstand genutzt`() {
        // Beispiel angepasst: Margin 10, Chips 50.
        // Mathematisch: 10 - 50 = -40. Wir wollen aber 0 (oder minGap).
        // Das Layout wächst dadurch natürlich, aber wir verhindern Überlappung.
        val result = calculator.calculateFavoritesTopMargin(
            userPreferredMarginPx = 10,
            chipsHeightPx = 50,
            areChipsVisible = true,
            minGapPx = 0
        )
        assertEquals(0, result)
    }

    @Test
    fun `beruecksichtigt minGap wenn Chips groesser als Margin`() {
        // Margin 10, Chips 50, wir wollen aber immer mindestens 8px Luft haben
        val result = calculator.calculateFavoritesTopMargin(
            userPreferredMarginPx = 10,
            chipsHeightPx = 50,
            areChipsVisible = true,
            minGapPx = 8
        )
        assertEquals(8, result)
    }
}