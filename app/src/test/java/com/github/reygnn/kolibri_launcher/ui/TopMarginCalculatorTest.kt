package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.ui.home.TopMarginCalculator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TopMarginCalculatorTest {

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
        assertEquals(16, result)
    }

    @Test
    fun `scale 1 returns base plus max additional`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (2000 * 0.30 * 1.0) = 16 + 600 = 616
        assertEquals(616, result)
    }

    @Test
    fun `scale 0_5 returns base plus half additional`() {
        val result = calculator.calculate(
            scale = 0.5f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (2000 * 0.30 * 0.5) = 16 + 300 = 316
        assertEquals(316, result)
    }

    @Test
    fun `scale 0_25 returns quarter additional`() {
        val result = calculator.calculate(
            scale = 0.25f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (2000 * 0.30 * 0.25) = 16 + 150 = 166
        assertEquals(166, result)
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
        assertEquals(1016, result)
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
        assertEquals(216, result)
    }

    @Test
    fun `custom fraction 0 means no additional margin ever`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000,
            maxAdditionalFraction = 0f
        )
        assertEquals(16, result)
    }

    // ========== DEFENSIVE: SCALE OUT OF BOUNDS ==========

    @Test
    fun `scale below 0 coerced to 0`() {
        val result = calculator.calculate(
            scale = -0.5f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        assertEquals(16, result)
    }

    @Test
    fun `scale above 1 coerced to 1`() {
        val result = calculator.calculate(
            scale = 1.5f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        assertEquals(616, result)
    }

    @Test
    fun `scale large negative coerced to 0`() {
        val result = calculator.calculate(
            scale = -100f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        assertEquals(16, result)
    }

    // ========== DEFENSIVE: NEGATIVE BASE MARGIN ==========

    @Test
    fun `negative base margin coerced to 0`() {
        val result = calculator.calculate(
            scale = 0f,
            baseMarginPx = -50,
            screenHeightPx = 2000
        )
        assertEquals(0, result)
    }

    @Test
    fun `negative base margin with scale 1`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = -50,
            screenHeightPx = 2000
        )
        // 0 + 600 = 600
        assertEquals(600, result)
    }

    // ========== DEFENSIVE: INVALID SCREEN HEIGHT ==========

    @Test
    fun `zero screen height returns only base margin`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 0
        )
        assertEquals(16, result)
    }

    @Test
    fun `negative screen height coerced to 0`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = -500
        )
        assertEquals(16, result)
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
        assertEquals(16, result)
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
        assertEquals(2016, result)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `all zeros returns 0`() {
        val result = calculator.calculate(
            scale = 0f,
            baseMarginPx = 0,
            screenHeightPx = 0
        )
        assertEquals(0, result)
    }

    @Test
    fun `base margin only no screen height`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 100,
            screenHeightPx = 0
        )
        assertEquals(100, result)
    }

    @Test
    fun `rounding down on fractional result`() {
        val result = calculator.calculate(
            scale = 0.33f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (2000 * 0.30 * 0.33) = 16 + 198 = 214
        assertEquals(214, result)
    }

    @Test
    fun `very small scale increments`() {
        val result = calculator.calculate(
            scale = 0.001f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // 16 + (600 * 0.001) = 16 + 0.6 = 16
        assertEquals(16, result)
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
        assertEquals(304, result)
    }

    @Test
    fun `scenario - large phone portrait`() {
        val result = calculator.calculate(
            scale = 0.5f,
            baseMarginPx = 16,
            screenHeightPx = 2400
        )
        // 16 + (2400 * 0.30 * 0.5) = 16 + 360 = 376
        assertEquals(376, result)
    }

    @Test
    fun `scenario - tablet landscape`() {
        val result = calculator.calculate(
            scale = 0.5f,
            baseMarginPx = 24,
            screenHeightPx = 1200
        )
        // 24 + (1200 * 0.30 * 0.5) = 24 + 180 = 204
        assertEquals(204, result)
    }

    @Test
    fun `scenario - user wants no extra margin`() {
        val result = calculator.calculate(
            scale = 0f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        assertEquals(16, result)
    }

    @Test
    fun `scenario - user wants maximum margin`() {
        val result = calculator.calculate(
            scale = 1f,
            baseMarginPx = 16,
            screenHeightPx = 2000
        )
        // Apps werden 30% der Bildschirmhöhe nach unten geschoben
        assertEquals(616, result)
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
        assertEquals(0, result)
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
        assertTrue(result > 16)
    }

    @Test
    fun `idempotent - same input same output`() {
        repeat(100) {
            val result = calculator.calculate(
                scale = 0.5f,
                baseMarginPx = 16,
                screenHeightPx = 2000
            )
            assertEquals(316, result)
        }
    }
}