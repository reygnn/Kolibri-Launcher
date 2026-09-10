package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.ContentSpacingCalculator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContentSpacingCalculatorTest {

    @get:Rule
    val timberRule = TimberRule()

    private val calculator = ContentSpacingCalculator()

    @Test
    fun `calculate returns full margin when chips not visible`() {
        val result = calculator.calculate(
            userPreferredMarginPx = 100,
            chipsHeightPx = 50,
            areChipsVisible = false
        )
        assertEquals(100, result)
    }

    @Test
    fun `calculate returns full margin when chips height is zero`() {
        val result = calculator.calculate(
            userPreferredMarginPx = 100,
            chipsHeightPx = 0,
            areChipsVisible = true
        )
        assertEquals(100, result)
    }

    @Test
    fun `calculate subtracts chips height from margin`() {
        val result = calculator.calculate(
            userPreferredMarginPx = 100,
            chipsHeightPx = 30,
            areChipsVisible = true
        )
        assertEquals(70, result)
    }

    @Test
    fun `calculate respects minGap when chips exceed margin`() {
        val result = calculator.calculate(
            userPreferredMarginPx = 50,
            chipsHeightPx = 80,
            areChipsVisible = true,
            minGapPx = 10
        )
        assertEquals(10, result)
    }

    @Test
    fun `calculate returns zero when chips exceed margin and no minGap`() {
        val result = calculator.calculate(
            userPreferredMarginPx = 50,
            chipsHeightPx = 80,
            areChipsVisible = true
        )
        assertEquals(0, result)
    }
}