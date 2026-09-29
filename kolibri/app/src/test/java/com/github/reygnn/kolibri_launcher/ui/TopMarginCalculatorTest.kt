package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.TopMarginCalculator
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TopMarginCalculatorTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var calculator: TopMarginCalculator

    @Before
    fun setup() {
        calculator = TopMarginCalculator()
    }

    // ========== SCALE TESTS ==========

    @Test
    fun `scale 0 returns only base margin`() {
        val result = calculator.calculate(
            scale = 0f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        assertThat(result).isEqualTo(16)
    }

    @Test
    fun `scale 1 returns base plus max additional`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (2000 * 0.30 * 1.0) = 16 + 600 = 616
        assertThat(result).isEqualTo(616)
    }

    @Test
    fun `scale 0_5 returns base plus half additional`() {
        val result = calculator.calculate(
            scale = 0.5f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (2000 * 0.30 * 0.5) = 16 + 300 = 316
        assertThat(result).isEqualTo(316)
    }

    @Test
    fun `scale 0_25 returns quarter additional`() {
        val result = calculator.calculate(
            scale = 0.25f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (2000 * 0.30 * 0.25) = 16 + 150 = 166
        assertThat(result).isEqualTo(166)
    }

    // ========== CUSTOM FRACTION TESTS ==========

    @Test
    fun `custom fraction 0_5 doubles additional margin range`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000,
            maxAdditionalFraction = 0.5f
        )
        // 16 + (2000 * 0.50 * 1.0) = 16 + 1000 = 1016
        assertThat(result).isEqualTo(1016)
    }

    @Test
    fun `custom fraction 0_1 limits additional margin`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000,
            maxAdditionalFraction = 0.1f
        )
        // 16 + (2000 * 0.10 * 1.0) = 16 + 200 = 216
        assertThat(result).isEqualTo(216)
    }

    @Test
    fun `custom fraction 0 means no additional margin ever`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000,
            maxAdditionalFraction = 0f
        )
        assertThat(result).isEqualTo(16)
    }

    // ========== DEFENSIVE: SCALE OUT OF BOUNDS ==========

    @Test
    fun `scale below 0 coerced to 0`() {
        val result = calculator.calculate(
            scale = -0.5f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        assertThat(result).isEqualTo(16)
    }

    @Test
    fun `calculate - coerces value above MAX to MAX`() {
        val maxScale = AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX
        val invalidScale = maxScale + 0.1f

        val result = calculator.calculate(
            scale = invalidScale,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )

        // Erwartung: Es wird trotzdem nur mit maxScale gerechnet
        val expectedAddition = (2000 * TopMarginCalculator.DEFAULT_MAX_ADDITIONAL_FRACTION * maxScale).toInt()

        assertThat(result).isEqualTo(16 + expectedAddition)
    }

    @Test
    fun `scale large negative coerced to 0`() {
        val result = calculator.calculate(
            scale = -100f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        assertThat(result).isEqualTo(16)
    }

    // ========== DEFENSIVE: NEGATIVE BASE MARGIN ==========

    @Test
    fun `negative base margin coerced to 0`() {
        val result = calculator.calculate(
            scale = 0f,
            baseMarginPx = -50,
            screenHeightPx = 2000
        )
        assertThat(result).isEqualTo(0)
    }

    @Test
    fun `negative base margin with scale 1`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = -50,
            screenHeightPx = 2000
        )
        // 0 + 600 = 600
        assertThat(result).isEqualTo(600)
    }

    // ========== DEFENSIVE: INVALID SCREEN HEIGHT ==========

    @Test
    fun `zero screen height returns only base margin`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 0
        )
        assertThat(result).isEqualTo(16)
    }

    @Test
    fun `negative screen height coerced to 0`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = -500
        )
        assertThat(result).isEqualTo(16)
    }

    // ========== DEFENSIVE: FRACTION OUT OF BOUNDS ==========

    @Test
    fun `negative fraction coerced to 0`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000,
            maxAdditionalFraction = -0.5f
        )
        assertThat(result).isEqualTo(16)
    }

    @Test
    fun `fraction above 1 coerced to 1`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000,
            maxAdditionalFraction = 2f
        )
        // 16 + (2000 * 1.0 * 1.0) = 2016
        assertThat(result).isEqualTo(2016)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `all zeros returns 0`() {
        val result = calculator.calculate(
            scale = 0f,
            baseMarginPx = 0,
            screenHeightPx = 0
        )
        assertThat(result).isEqualTo(0)
    }

    @Test
    fun `base margin only no screen height`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 100,
            screenHeightPx = 0
        )
        assertThat(result).isEqualTo(100)
    }

    @Test
    fun `rounding down on fractional result`() {
        val result = calculator.calculate(
            scale = 0.33f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (2000 * 0.30 * 0.33) = 16 + 198 = 214
        assertThat(result).isEqualTo(214)
    }

    @Test
    fun `very small scale increments`() {
        val result = calculator.calculate(
            scale = 0.001f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (600 * 0.001) = 16 + 0.6 = 16
        assertThat(result).isEqualTo(16)
    }

    // ========== REAL-WORLD SCENARIOS ==========

    @Test
    fun `scenario - small phone portrait`() {
        val result = calculator.calculate(
            scale = 0.5f,
            baseMarginPx = 16,
            screenHeightPx = 1920
        )
        // 16 + (1920 * 0.30 * 0.5) = 16 + 288 = 304
        assertThat(result).isEqualTo(304)
    }

    @Test
    fun `scenario - large phone portrait`() {
        val result = calculator.calculate(
            scale = 0.5f,
            baseMarginPx = 16,
            screenHeightPx = 2400
        )
        // 16 + (2400 * 0.30 * 0.5) = 16 + 360 = 376
        assertThat(result).isEqualTo(376)
    }

    @Test
    fun `scenario - tablet landscape`() {
        val result = calculator.calculate(
            scale = 0.5f,
            baseMarginPx = 24,
            screenHeightPx = 1200
        )
        // 24 + (1200 * 0.30 * 0.5) = 24 + 180 = 204
        assertThat(result).isEqualTo(204)
    }

    @Test
    fun `scenario - user wants no extra margin`() {
        val result = calculator.calculate(
            scale = 0f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        assertThat(result).isEqualTo(16)
    }

    @Test
    fun `scenario - user wants maximum margin`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // Apps werden 30% der Bildschirmhöhe nach unten geschoben
        assertThat(result).isEqualTo(616)
    }

    // ========== PARANOID TESTS ==========

    @Test
    fun `all negative values result in 0`() {
        val result = calculator.calculate(
            scale = -1f,
            baseMarginPx = -100,
            screenHeightPx = -2000,
            maxAdditionalFraction = -0.5f
        )
        assertThat(result).isEqualTo(0)
    }

    @Test
    fun `max int screen height - no overflow`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = Int.MAX_VALUE,
            maxAdditionalFraction = 0.001f
        )
        // Sollte nicht überlaufen dank Float-Berechnung
        assertThat(result > 16).isTrue()
    }

    @Test
    fun `idempotent - same input same output`() {
        repeat(100) {
            val result = calculator.calculate(
                scale = 0.5f,
                baseMarginPx = 16,
                screenHeightPx = 2000
            )
            assertThat(result).isEqualTo(316)
        }
    }
}