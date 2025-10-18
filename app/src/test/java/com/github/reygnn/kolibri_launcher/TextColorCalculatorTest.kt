package com.github.reygnn.kolibri_launcher

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertNotNull

class TextColorCalculatorTest {

    // ========== EXISTING TESTS ==========

    @Test
    fun getOptimalTextColor_withBlackBackground_returnsWhite() {
        val backgroundColor = Color.BLACK
        val expectedTextColor = Color.WHITE
        val actualTextColor = TextColorCalculator.getOptimalTextColor(backgroundColor)
        assertEquals(expectedTextColor, actualTextColor)
    }

    @Test
    fun getOptimalTextColor_withWhiteBackground_returnsBlack() {
        val backgroundColor = Color.WHITE
        val expectedTextColor = Color.BLACK
        val actualTextColor = TextColorCalculator.getOptimalTextColor(backgroundColor)
        assertEquals(expectedTextColor, actualTextColor)
    }

    @Test
    fun getOptimalTextColor_withRedBackground_returnsWhite() {
        val backgroundColor = Color.RED
        val expectedTextColor = Color.WHITE
        val actualTextColor = TextColorCalculator.getOptimalTextColor(backgroundColor)
        assertEquals(expectedTextColor, actualTextColor)
    }

    @Test
    fun getOptimalTextColor_withGreenBackground_returnsBlack() {
        val backgroundColor = Color.GREEN
        val expectedTextColor = Color.BLACK
        val actualTextColor = TextColorCalculator.getOptimalTextColor(backgroundColor)
        assertEquals(expectedTextColor, actualTextColor)
    }

    @Test
    fun getOptimalTextColor_withBlueBackground_returnsWhite() {
        val backgroundColor = Color.BLUE
        val expectedTextColor = Color.WHITE
        val actualTextColor = TextColorCalculator.getOptimalTextColor(backgroundColor)
        assertEquals(expectedTextColor, actualTextColor)
    }

    @Test
    fun getOptimalTextColor_withYellowBackground_returnsBlack() {
        val backgroundColor = Color.YELLOW
        val expectedTextColor = Color.BLACK
        val actualTextColor = TextColorCalculator.getOptimalTextColor(backgroundColor)
        assertEquals(expectedTextColor, actualTextColor)
    }

    @Test
    fun getOptimalTextColor_withDarkGrayBackground_returnsWhite() {
        // KORRIGIERT: Color.rgb(100, 100, 100) ersetzt durch direkten Hex-Wert
        val backgroundColor = 0xFF646464.toInt()
        val expectedTextColor = Color.WHITE
        val actualTextColor = TextColorCalculator.getOptimalTextColor(backgroundColor)
        assertEquals(expectedTextColor, actualTextColor)
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `getOptimalTextColor - with transparent color - handles correctly`() {
        // KORRIGIERT: Color.argb(0, 255, 255, 255) ersetzt durch direkten Hex-Wert
        val transparentColor = 0x00FFFFFF

        val result = TextColorCalculator.getOptimalTextColor(transparentColor)

        assertNotNull(result)
        // Transparent white should be treated as bright (luminance calculation ignores alpha)
        assertEquals(Color.BLACK, result)
    }

    @Test
    fun `getOptimalTextColor - with semi-transparent dark color - handles correctly`() {
        // KORRIGIERT: Color.argb(128, 50, 50, 50) ersetzt durch direkten Hex-Wert
        val semiTransparentDark = 0x80323232.toInt()

        val result = TextColorCalculator.getOptimalTextColor(semiTransparentDark)

        assertNotNull(result)
        assertEquals(Color.WHITE, result)
    }

    @Test
    fun `getOptimalTextColor - with semi-transparent bright color - handles correctly`() {
        // KORRIGIERT: Color.argb(128, 200, 200, 200) ersetzt durch direkten Hex-Wert
        val semiTransparentBright = 0x80C8C8C8.toInt()

        val result = TextColorCalculator.getOptimalTextColor(semiTransparentBright)

        assertNotNull(result)
        assertEquals(Color.BLACK, result)
    }

    @Test
    fun `getOptimalTextColor - with color value 0 - handles correctly`() {
        val result = TextColorCalculator.getOptimalTextColor(0)

        // 0 is transparent black, should be treated as dark
        assertEquals(Color.WHITE, result)
    }

    @Test
    fun `getOptimalTextColor - with color value -1 - handles correctly`() {
        val result = TextColorCalculator.getOptimalTextColor(-1)

        // -1 is opaque white in Android
        assertEquals(Color.BLACK, result)
    }

    @Test
    fun `getOptimalTextColor - with Int MAX_VALUE - handles correctly`() {
        val result = TextColorCalculator.getOptimalTextColor(Int.MAX_VALUE)

        assertNotNull(result)
        // Should not crash
    }

    @Test
    fun `getOptimalTextColor - with Int MIN_VALUE - handles correctly`() {
        val result = TextColorCalculator.getOptimalTextColor(Int.MIN_VALUE)

        assertNotNull(result)
        // Should not crash
    }

    @Test
    fun `getOptimalTextColor - with all RGB components at 0 - returns white`() {
        // KORRIGIERT: Color.rgb(0, 0, 0) durch Konstante ersetzt (die bereits korrekt war)
        val black = Color.BLACK

        val result = TextColorCalculator.getOptimalTextColor(black)

        assertEquals(Color.WHITE, result)
    }

    @Test
    fun `getOptimalTextColor - with all RGB components at 255 - returns black`() {
        // KORRIGIERT: Color.rgb(255, 255, 255) durch Konstante ersetzt
        val white = Color.WHITE

        val result = TextColorCalculator.getOptimalTextColor(white)

        assertEquals(Color.BLACK, result)
    }

    @Test
    fun `getOptimalTextColor - with luminance exactly at threshold - consistent result`() {
        // KORRIGIERT: Color.rgb(128, 128, 128) ersetzt durch direkten Hex-Wert
        val midGray = 0xFF808080.toInt()

        val result = TextColorCalculator.getOptimalTextColor(midGray)

        assertNotNull(result)
        // Should consistently return either BLACK or WHITE
        assert(result == Color.BLACK || result == Color.WHITE)
    }

    @Test
    fun `getOptimalTextColor - with very dark red - returns white`() {
        // KORRIGIERT: Color.rgb(50, 0, 0) ersetzt durch direkten Hex-Wert
        val darkRed = 0xFF320000.toInt()

        val result = TextColorCalculator.getOptimalTextColor(darkRed)

        assertEquals(Color.WHITE, result)
    }

    @Test
    fun `getOptimalTextColor - with very bright cyan - returns black`() {
        // KORRIGIERT: Color.rgb(0, 255, 255) durch Konstante ersetzt
        val brightCyan = Color.CYAN

        val result = TextColorCalculator.getOptimalTextColor(brightCyan)

        assertEquals(Color.BLACK, result)
    }

    @Test
    fun `getOptimalTextColor - called repeatedly with same color - returns consistent result`() {
        // KORRIGIERT: Color.rgb(100, 150, 200) ersetzt durch direkten Hex-Wert
        val testColor = 0xFF6496C8.toInt()

        val result1 = TextColorCalculator.getOptimalTextColor(testColor)
        val result2 = TextColorCalculator.getOptimalTextColor(testColor)
        val result3 = TextColorCalculator.getOptimalTextColor(testColor)

        assertEquals(result1, result2)
        assertEquals(result2, result3)
    }

    @Test
    fun `getOptimalTextColorForWallpaper - with ColorDrawable - handles correctly`() {
        val drawable = ColorDrawable(Color.BLUE)

        val result = TextColorCalculator.getOptimalTextColorForWallpaper(drawable)

        assertNotNull(result)
        // Should return WHITE for dark blue
        assertEquals(Color.WHITE, result)
    }

    @Test
    fun `getOptimalTextColorForWallpaper - with drawable that throws on draw - returns WHITE`() {
        val mockDrawable = mock<Drawable>()
        whenever(mockDrawable.setBounds(0, 0, 50, 50)).thenAnswer { }
        whenever(mockDrawable.draw(org.mockito.kotlin.any())).doAnswer {
            throw RuntimeException("Cannot draw")
        }

        val result = TextColorCalculator.getOptimalTextColorForWallpaper(mockDrawable)

        // Should fallback to WHITE on error
        assertEquals(Color.WHITE, result)
    }

    @Test
    fun `getOptimalTextColorForWallpaper - with drawable that throws on setBounds - returns WHITE`() {
        val mockDrawable = mock<Drawable>()
        whenever(mockDrawable.setBounds(0, 0, 50, 50)).doAnswer {
            throw IllegalArgumentException("Invalid bounds")
        }

        val result = TextColorCalculator.getOptimalTextColorForWallpaper(mockDrawable)

        assertEquals(Color.WHITE, result)
    }

    @Test
    fun `getOptimalTextColorForWallpaper - with transparent drawable - handles correctly`() {
        val transparentDrawable = ColorDrawable(Color.TRANSPARENT)

        val result = TextColorCalculator.getOptimalTextColorForWallpaper(transparentDrawable)

        assertNotNull(result)
        // Should not crash
    }

    @Test
    fun `getOptimalTextColor - with colors at luminance boundary - stable results`() {
        // KORRIGIERT: Color.rgb(...) ersetzt durch direkte Hex-Werte
        val testColors = listOf(
            0xFF767676.toInt(), // rgb(118, 118, 118)
            0xFF777777.toInt(), // rgb(119, 119, 119)
            0xFF808080.toInt(), // rgb(128, 128, 128)
            0xFFBABABA.toInt(), // rgb(186, 186, 186)
            0xFFBBBBBB.toInt(), // rgb(187, 187, 187)
            0xFFBCBCBC.toInt()  // rgb(188, 188, 188)
        )

        for (color in testColors) {
            val result = TextColorCalculator.getOptimalTextColor(color)
            assertNotNull(result)
            assert(result == Color.BLACK || result == Color.WHITE)
        }
    }

    @Test
    fun `getOptimalTextColor - with extreme RGB values - handles correctly`() {
        // KORRIGIERT: Color.rgb(...) ersetzt durch Farbkonstanten
        val extremeColors = listOf(
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW,
            Color.MAGENTA,
            Color.CYAN
        )

        for (color in extremeColors) {
            val result = TextColorCalculator.getOptimalTextColor(color)
            assertNotNull(result)
            assert(result == Color.BLACK || result == Color.WHITE)
        }
    }
}