package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ChipBackgroundCalculatorTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var calculator: ChipBackgroundCalculator

    // Standard-Farben als ARGB Int (ohne android.graphics.Color)
    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()      // -1
        const val BLACK = 0xFF000000.toInt()      // -16777216
        const val RED = 0xFFFF0000.toInt()        // -65536
        const val BLUE = 0xFF0000FF.toInt()       // -16776961
        const val TRANSPARENT = 0x00000000        // 0
    }

    @Before
    fun setup() {
        calculator = ChipBackgroundCalculator()
    }

    // ========== USER-DEFINED COLOR ==========

    @Test
    fun `user color set - returns user color unchanged`() {
        val result = calculator.calculate(
            chipBackgroundColor = RED,
            textColorInt = WHITE
        )
        assertEquals(RED, result)
    }

    @Test
    fun `user color blue - returns blue unchanged`() {
        val result = calculator.calculate(
            chipBackgroundColor = BLUE,
            textColorInt = BLACK
        )
        assertEquals(BLUE, result)
    }

    @Test
    fun `user color with alpha - preserves alpha`() {
        val semiTransparentRed = 0x80FF0000.toInt()  // Alpha 128
        val result = calculator.calculate(
            chipBackgroundColor = semiTransparentRed,
            textColorInt = WHITE
        )
        assertEquals(semiTransparentRed, result)
        assertEquals(128, calculator.extractAlpha(result))
    }

    @Test
    fun `user color almost transparent - returns unchanged`() {
        val almostTransparent = 0x01000000  // Alpha 1
        val result = calculator.calculate(
            chipBackgroundColor = almostTransparent,
            textColorInt = WHITE
        )
        assertEquals(almostTransparent, result)
    }

    // ========== AUTO MODE (chipBackgroundColor = 0) ==========

    @Test
    fun `no color set - uses text color with default alpha`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = WHITE
        )

        assertEquals(40, calculator.extractAlpha(result))
        assertEquals(255, calculator.extractRed(result))
        assertEquals(255, calculator.extractGreen(result))
        assertEquals(255, calculator.extractBlue(result))
    }

    @Test
    fun `no color set with red text - returns semi-transparent red`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = RED
        )

        assertEquals(40, calculator.extractAlpha(result))
        assertEquals(255, calculator.extractRed(result))
        assertEquals(0, calculator.extractGreen(result))
        assertEquals(0, calculator.extractBlue(result))
    }

    @Test
    fun `no color set with custom text color`() {
        val textColor = (0xFF shl 24) or (100 shl 16) or (150 shl 8) or 200  // ARGB
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = textColor
        )

        assertEquals(40, calculator.extractAlpha(result))
        assertEquals(100, calculator.extractRed(result))
        assertEquals(150, calculator.extractGreen(result))
        assertEquals(200, calculator.extractBlue(result))
    }

    @Test
    fun `no color set with black text`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = BLACK
        )

        assertEquals(40, calculator.extractAlpha(result))
        assertEquals(0, calculator.extractRed(result))
        assertEquals(0, calculator.extractGreen(result))
        assertEquals(0, calculator.extractBlue(result))
    }

    // ========== CUSTOM ALPHA ==========

    @Test
    fun `custom alpha 100`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = WHITE,
            defaultAlpha = 100
        )

        assertEquals(100, calculator.extractAlpha(result))
    }

    @Test
    fun `custom alpha 0 - fully transparent`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = WHITE,
            defaultAlpha = 0
        )

        assertEquals(0, calculator.extractAlpha(result))
    }

    @Test
    fun `custom alpha 255 - fully opaque`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = WHITE,
            defaultAlpha = 255
        )

        assertEquals(255, calculator.extractAlpha(result))
    }

    // ========== DEFENSIVE: ALPHA OUT OF BOUNDS ==========

    @Test
    fun `alpha below 0 coerced to 0`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = WHITE,
            defaultAlpha = -50
        )

        assertEquals(0, calculator.extractAlpha(result))
    }

    @Test
    fun `alpha above 255 coerced to 255`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = WHITE,
            defaultAlpha = 300
        )

        assertEquals(255, calculator.extractAlpha(result))
    }

    @Test
    fun `alpha large negative coerced to 0`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = WHITE,
            defaultAlpha = -1000
        )

        assertEquals(0, calculator.extractAlpha(result))
    }

    // ========== EDGE CASES ==========

    @Test
    fun `text color with alpha - only RGB used`() {
        val textWithAlpha = (200 shl 24) or (100 shl 16) or (150 shl 8) or 200
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = textWithAlpha
        )

        // Alpha from defaultAlpha, not from textColor
        assertEquals(40, calculator.extractAlpha(result))
        assertEquals(100, calculator.extractRed(result))
        assertEquals(150, calculator.extractGreen(result))
        assertEquals(200, calculator.extractBlue(result))
    }

    @Test
    fun `user color exactly 0 triggers auto mode`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = RED
        )

        assertEquals(40, calculator.extractAlpha(result))
        assertEquals(255, calculator.extractRed(result))
    }

    // ========== REAL-WORLD SCENARIOS ==========

    @Test
    fun `scenario - white text on dark wallpaper`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = WHITE
        )

        assertEquals(40, calculator.extractAlpha(result))
        assertEquals(255, calculator.extractRed(result))
        assertEquals(255, calculator.extractGreen(result))
        assertEquals(255, calculator.extractBlue(result))
    }

    @Test
    fun `scenario - black text on light wallpaper`() {
        val result = calculator.calculate(
            chipBackgroundColor = 0,
            textColorInt = BLACK
        )

        assertEquals(40, calculator.extractAlpha(result))
        assertEquals(0, calculator.extractRed(result))
    }

    @Test
    fun `scenario - user prefers solid color chips`() {
        val solidBlue = (255 shl 24) or (50 shl 16) or (100 shl 8) or 200
        val result = calculator.calculate(
            chipBackgroundColor = solidBlue,
            textColorInt = WHITE
        )

        assertEquals(solidBlue, result)
        assertEquals(255, calculator.extractAlpha(result))
    }

    @Test
    fun `scenario - material you extracted color`() {
        val materialYouColor = (180 shl 24) or (103 shl 16) or (80 shl 8) or 164
        val result = calculator.calculate(
            chipBackgroundColor = materialYouColor,
            textColorInt = WHITE
        )

        assertEquals(materialYouColor, result)
    }

    // ========== PARANOID TESTS ==========

    @Test
    fun `idempotent - same input same output`() {
        repeat(100) {
            val result = calculator.calculate(
                chipBackgroundColor = 0,
                textColorInt = WHITE
            )

            assertEquals(40, calculator.extractAlpha(result))
            assertEquals(255, calculator.extractRed(result))
            assertEquals(255, calculator.extractGreen(result))
            assertEquals(255, calculator.extractBlue(result))
        }
    }

    @Test
    fun `user color 1 is not treated as no color`() {
        val almostZero = 1
        val result = calculator.calculate(
            chipBackgroundColor = almostZero,
            textColorInt = WHITE
        )

        assertEquals(almostZero, result)
    }

    // ========== EXTRACT HELPER TESTS ==========

    @Test
    fun `extractAlpha works correctly`() {
        assertEquals(255, calculator.extractAlpha(WHITE))
        assertEquals(255, calculator.extractAlpha(BLACK))
        assertEquals(128, calculator.extractAlpha(0x80FF0000.toInt()))
        assertEquals(0, calculator.extractAlpha(TRANSPARENT))
    }

    @Test
    fun `extractRed works correctly`() {
        assertEquals(255, calculator.extractRed(WHITE))
        assertEquals(0, calculator.extractRed(BLACK))
        assertEquals(255, calculator.extractRed(RED))
        assertEquals(0, calculator.extractRed(BLUE))
    }

    @Test
    fun `extractGreen works correctly`() {
        assertEquals(255, calculator.extractGreen(WHITE))
        assertEquals(0, calculator.extractGreen(BLACK))
        assertEquals(0, calculator.extractGreen(RED))
    }

    @Test
    fun `extractBlue works correctly`() {
        assertEquals(255, calculator.extractBlue(WHITE))
        assertEquals(0, calculator.extractBlue(BLACK))
        assertEquals(255, calculator.extractBlue(BLUE))
        assertEquals(0, calculator.extractBlue(RED))
    }
}