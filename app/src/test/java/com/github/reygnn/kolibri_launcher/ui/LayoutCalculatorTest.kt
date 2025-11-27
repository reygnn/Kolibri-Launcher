package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.ui.home.LayoutCalculator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LayoutCalculatorTest {

    private lateinit var calculator: LayoutCalculator

    @Before
    fun setup() {
        calculator = LayoutCalculator()
    }

    // ========== SCALE TESTS ==========

    @Test
    fun `scale 0 returns minimum text size`() {
        val result = calculator.calculate(
            scale = 0f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(24f, result.textSizePx, 0.01f)
    }

    @Test
    fun `scale 1 returns maximum text size`() {
        val result = calculator.calculate(
            scale = 1f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(48f, result.textSizePx, 0.01f)
    }

    @Test
    fun `scale 0_5 returns middle text size`() {
        val result = calculator.calculate(
            scale = 0.5f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(36f, result.textSizePx, 0.01f)
    }

    @Test
    fun `scale 0_25 returns quarter interpolation`() {
        val result = calculator.calculate(
            scale = 0.25f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        // 24 + (24 * 0.25) = 30
        assertEquals(30f, result.textSizePx, 0.01f)
    }

    // ========== PADDING TESTS ==========

    @Test
    fun `padding factor 0 returns zero padding`() {
        val result = calculator.calculate(
            scale = 0.5f,
            paddingFactor = 0f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(0, result.verticalPaddingPx)
    }

    @Test
    fun `padding factor 1 returns full text size as padding`() {
        val result = calculator.calculate(
            scale = 0f,
            paddingFactor = 1f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(24, result.verticalPaddingPx)
    }

    @Test
    fun `padding factor 0_5 returns half text size as padding`() {
        val result = calculator.calculate(
            scale = 0f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(12, result.verticalPaddingPx)
    }

    @Test
    fun `padding rounds down to int`() {
        val result = calculator.calculate(
            scale = 0f,
            paddingFactor = 0.33f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        // 24 * 0.33 = 7.92 → 7
        assertEquals(7, result.verticalPaddingPx)
    }

    // ========== BOLD FLAG ==========

    @Test
    fun `bold true passed through`() {
        val result = calculator.calculate(0.5f, 0.5f, true, 24f, 48f)
        assertTrue(result.isBold)
    }

    @Test
    fun `bold false passed through`() {
        val result = calculator.calculate(0.5f, 0.5f, false, 24f, 48f)
        assertFalse(result.isBold)
    }

    // ========== DEFENSIVE: SCALE OUT OF BOUNDS ==========

    @Test
    fun `scale below 0 coerced to 0`() {
        val result = calculator.calculate(
            scale = -0.5f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(24f, result.textSizePx, 0.01f)
    }

    @Test
    fun `scale above 1 coerced to 1`() {
        val result = calculator.calculate(
            scale = 1.5f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(48f, result.textSizePx, 0.01f)
    }

    @Test
    fun `scale negative large value coerced to 0`() {
        val result = calculator.calculate(
            scale = -100f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(24f, result.textSizePx, 0.01f)
    }

    // ========== DEFENSIVE: PADDING OUT OF BOUNDS ==========

    @Test
    fun `padding factor below 0 coerced to 0`() {
        val result = calculator.calculate(
            scale = 0.5f,
            paddingFactor = -1f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(0, result.verticalPaddingPx)
    }

    @Test
    fun `padding factor above 1 coerced to 1`() {
        val result = calculator.calculate(
            scale = 0f,
            paddingFactor = 2f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(24, result.verticalPaddingPx)
    }

    // ========== DEFENSIVE: INVALID DIMENSIONS ==========

    @Test
    fun `min size 0 coerced to 1`() {
        val result = calculator.calculate(
            scale = 0f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 0f,
            maxTextSizePx = 48f
        )
        assertEquals(1f, result.textSizePx, 0.01f)
    }

    @Test
    fun `negative min size coerced to 1`() {
        val result = calculator.calculate(
            scale = 0f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = -10f,
            maxTextSizePx = 48f
        )
        assertEquals(1f, result.textSizePx, 0.01f)
    }

    @Test
    fun `max size below min size coerced to min`() {
        val result = calculator.calculate(
            scale = 1f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 48f,
            maxTextSizePx = 24f
        )
        assertEquals(48f, result.textSizePx, 0.01f)
    }

    @Test
    fun `both sizes equal - scale has no effect`() {
        val result0 = calculator.calculate(0f, 0.5f, false, 36f, 36f)
        val result1 = calculator.calculate(1f, 0.5f, false, 36f, 36f)

        assertEquals(36f, result0.textSizePx, 0.01f)
        assertEquals(36f, result1.textSizePx, 0.01f)
    }

    @Test
    fun `negative max size - both coerced to 1`() {
        val result = calculator.calculate(
            scale = 0.5f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = -50f,
            maxTextSizePx = -20f
        )
        // min → 1, max → coerceAtLeast(min) → 1
        assertEquals(1f, result.textSizePx, 0.01f)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `very small scale increments`() {
        val result = calculator.calculate(
            scale = 0.001f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        assertEquals(24.024f, result.textSizePx, 0.01f)
    }

    @Test
    fun `very large text sizes`() {
        val result = calculator.calculate(
            scale = 0.5f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 100f,
            maxTextSizePx = 200f
        )
        assertEquals(150f, result.textSizePx, 0.01f)
        assertEquals(75, result.verticalPaddingPx)
    }

    @Test
    fun `tiny text sizes`() {
        val result = calculator.calculate(
            scale = 0.5f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 1f,
            maxTextSizePx = 3f
        )
        assertEquals(2f, result.textSizePx, 0.01f)
        assertEquals(1, result.verticalPaddingPx)
    }

    // ========== REAL-WORLD SCENARIOS ==========

    @Test
    fun `scenario - default kolibri settings`() {
        // Typische Werte aus der App
        val result = calculator.calculate(
            scale = 0.5f,  // DEFAULT_LAYOUT_SCALE
            paddingFactor = 0.5f,  // DEFAULT_VERTICAL_PADDING_FACTOR
            isBold = false,
            minTextSizePx = 14f,  // text_size_secondary_info
            maxTextSizePx = 64f   // text_size_time * MAX_SCALE
        )
        assertEquals(39f, result.textSizePx, 0.01f)
        assertEquals(19, result.verticalPaddingPx)
    }

    @Test
    fun `scenario - minimum readable size`() {
        val result = calculator.calculate(
            scale = 0f,
            paddingFactor = 0f,
            isBold = false,
            minTextSizePx = 12f,
            maxTextSizePx = 48f
        )
        assertEquals(12f, result.textSizePx, 0.01f)
        assertEquals(0, result.verticalPaddingPx)
    }

    @Test
    fun `scenario - maximum comfortable size`() {
        val result = calculator.calculate(
            scale = 1f,
            paddingFactor = 1f,
            isBold = true,
            minTextSizePx = 14f,
            maxTextSizePx = 64f
        )
        assertEquals(64f, result.textSizePx, 0.01f)
        assertEquals(64, result.verticalPaddingPx)
        assertTrue(result.isBold)
    }
}