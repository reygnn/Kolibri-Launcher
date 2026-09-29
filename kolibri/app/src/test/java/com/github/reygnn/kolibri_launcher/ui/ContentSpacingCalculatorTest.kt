package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.ContentSpacingCalculator
import com.google.common.truth.Truth.assertThat
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
        assertThat(result).isEqualTo(100)
    }

    @Test
    fun `calculate returns full margin when chips height is zero`() {
        val result = calculator.calculate(
            userPreferredMarginPx = 100,
            chipsHeightPx = 0,
            areChipsVisible = true
        )
        assertThat(result).isEqualTo(100)
    }

    @Test
    fun `calculate subtracts chips height from margin`() {
        val result = calculator.calculate(
            userPreferredMarginPx = 100,
            chipsHeightPx = 30,
            areChipsVisible = true
        )
        assertThat(result).isEqualTo(70)
    }

    @Test
    fun `calculate respects minGap when chips exceed margin`() {
        val result = calculator.calculate(
            userPreferredMarginPx = 50,
            chipsHeightPx = 80,
            areChipsVisible = true,
            minGapPx = 10
        )
        assertThat(result).isEqualTo(10)
    }

    @Test
    fun `calculate returns zero when chips exceed margin and no minGap`() {
        val result = calculator.calculate(
            userPreferredMarginPx = 50,
            chipsHeightPx = 80,
            areChipsVisible = true
        )
        assertThat(result).isEqualTo(0)
    }
}