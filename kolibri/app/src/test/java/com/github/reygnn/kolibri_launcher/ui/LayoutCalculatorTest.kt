package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.LayoutCalculator
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LayoutCalculatorTest {

    @get:Rule
    val timberRule = TimberRule()

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
        assertThat(result.textSizePx).isWithin(0.01f).of(24f)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(36f)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(30f)
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
        assertThat(result.verticalPaddingPx).isEqualTo(0)
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
        assertThat(result.verticalPaddingPx).isEqualTo(24)
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
        assertThat(result.verticalPaddingPx).isEqualTo(12)
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
        assertThat(result.verticalPaddingPx).isEqualTo(7)
    }

    // ========== BOLD FLAG ==========

    @Test
    fun `bold true passed through`() {
        val result = calculator.calculate(0.5f, 0.5f, true, 24f, 48f)
        assertThat(result.isBold).isTrue()
    }

    @Test
    fun `bold false passed through`() {
        val result = calculator.calculate(0.5f, 0.5f, false, 24f, 48f)
        assertThat(result.isBold).isFalse()
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
        assertThat(result.textSizePx).isWithin(0.01f).of(24f)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(24f)
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
        assertThat(result.verticalPaddingPx).isEqualTo(0)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(1f)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(1f)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(48f)
    }

    @Test
    fun `both sizes equal - scale has no effect`() {
        val result0 = calculator.calculate(0f, 0.5f, false, 36f, 36f)
        val result1 = calculator.calculate(1f, 0.5f, false, 36f, 36f)

        assertThat(result0.textSizePx).isWithin(0.01f).of(36f)
        assertThat(result1.textSizePx).isWithin(0.01f).of(36f)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(1f)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(24.024f)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(150f)
        assertThat(result.verticalPaddingPx).isEqualTo(75)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(2f)
        assertThat(result.verticalPaddingPx).isEqualTo(1)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(39f)
        assertThat(result.verticalPaddingPx).isEqualTo(19)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(12f)
        assertThat(result.verticalPaddingPx).isEqualTo(0)
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
        assertThat(result.textSizePx).isWithin(0.01f).of(64f)
        assertThat(result.verticalPaddingPx).isEqualTo(64)
        assertThat(result.isBold).isTrue()
    }

    @Test
    fun `scale 1 returns calculated size for factor 1`() {
        // HINWEIS: Scale 1.0 ist jetzt Referenz, aber nicht mehr zwingend das Maximum
        val result = calculator.calculate(
            scale = 1f,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )
        // 24 + (48 - 24) * 1.0 = 48
        assertThat(result.textSizePx).isWithin(0.01f).of(48f)
    }

    @Test
    fun `scale above MAX coerced to MAX`() {
        // Wir testen einen Wert ÜBER dem erlaubten Maximum (z.B. 3.0)
        val aboveMax = AppConstants.LAYOUT_SCALE_MAX + 1.0f

        val result = calculator.calculate(
            scale = aboveMax,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )

        // Erwartung: Berechnung stoppt bei LAYOUT_SCALE_MAX (z.B. 2.0)
        // Formel: 24 + (24 * 2.0) = 72
        val expectedScale = AppConstants.LAYOUT_SCALE_MAX
        val expectedSize = 24f + (48f - 24f) * expectedScale

        assertThat(result.textSizePx).isWithin(0.01f).of(expectedSize)
    }

    @Test
    fun `padding factor above MAX coerced to MAX`() {
        // Wir testen einen Wert ÜBER dem erlaubten Maximum
        val aboveMax = AppConstants.VERTICAL_PADDING_SCALE_MAX + 1.0f

        val result = calculator.calculate(
            scale = 0f, // bei Scale 0 ist TextSize = 24f
            paddingFactor = aboveMax,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )

        // Erwartung: Padding stoppt bei VERTICAL_PADDING_SCALE_MAX
        val expectedPadding = (24f * AppConstants.VERTICAL_PADDING_SCALE_MAX).toInt()
        assertThat(result.verticalPaddingPx).isEqualTo(expectedPadding)
    }

    @Test
    fun `scale below MIN coerced to MIN`() {
        val belowMin = AppConstants.LAYOUT_SCALE_MIN - 0.1f

        val result = calculator.calculate(
            scale = belowMin,
            paddingFactor = 0.5f,
            isBold = false,
            minTextSizePx = 24f,
            maxTextSizePx = 48f
        )

        val expectedScale = AppConstants.LAYOUT_SCALE_MIN
        val expectedSize = 24f + (48f - 24f) * expectedScale

        assertThat(result.textSizePx).isWithin(0.01f).of(expectedSize)
    }
}