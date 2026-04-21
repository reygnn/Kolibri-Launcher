package com.github.reygnn.kolibri_launcher.ui

import android.content.res.Configuration
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.SplitWeightCalculator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SplitWeightCalculatorTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var calculator: SplitWeightCalculator

    // Typische Werte aus AppConstants
    private val portraitScrollWeight = 0.6f
    private val portraitGestureWeight = 0.4f
    private val landscapeScrollWeight = 0.5f
    private val landscapeGestureWeight = 0.5f

    @Before
    fun setup() {
        calculator = SplitWeightCalculator()
    }

    // ========== FULL MODE ==========

    @Test
    fun `full mode portrait - scrollView gets 100 percent`() {
        val result = calculator.calculate(
            enableSplit = false,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = portraitScrollWeight,
            portraitGestureWeight = portraitGestureWeight,
            landscapeScrollWeight = landscapeScrollWeight,
            landscapeGestureWeight = landscapeGestureWeight
        )

        assertEquals(1f, result.scrollViewWeight, 0.01f)
        assertEquals(0f, result.gestureZoneWeight, 0.01f)
        assertFalse(result.gestureZoneVisible)
    }

    @Test
    fun `full mode landscape - scrollView gets 100 percent`() {
        val result = calculator.calculate(
            enableSplit = false,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            portraitScrollWeight = portraitScrollWeight,
            portraitGestureWeight = portraitGestureWeight,
            landscapeScrollWeight = landscapeScrollWeight,
            landscapeGestureWeight = landscapeGestureWeight
        )

        assertEquals(1f, result.scrollViewWeight, 0.01f)
        assertEquals(0f, result.gestureZoneWeight, 0.01f)
        assertFalse(result.gestureZoneVisible)
    }

    @Test
    fun `full mode undefined orientation - scrollView gets 100 percent`() {
        val result = calculator.calculate(
            enableSplit = false,
            orientation = Configuration.ORIENTATION_UNDEFINED,
            portraitScrollWeight = portraitScrollWeight,
            portraitGestureWeight = portraitGestureWeight,
            landscapeScrollWeight = landscapeScrollWeight,
            landscapeGestureWeight = landscapeGestureWeight
        )

        assertEquals(1f, result.scrollViewWeight, 0.01f)
        assertEquals(0f, result.gestureZoneWeight, 0.01f)
        assertFalse(result.gestureZoneVisible)
    }

    // ========== SPLIT MODE PORTRAIT ==========

    @Test
    fun `split mode portrait - uses portrait weights`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertEquals(0.6f, result.scrollViewWeight, 0.01f)
        assertEquals(0.4f, result.gestureZoneWeight, 0.01f)
        assertTrue(result.gestureZoneVisible)
    }

    @Test
    fun `split mode portrait - different ratio`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 0.7f,
            portraitGestureWeight = 0.3f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertEquals(0.7f, result.scrollViewWeight, 0.01f)
        assertEquals(0.3f, result.gestureZoneWeight, 0.01f)
        assertTrue(result.gestureZoneVisible)
    }

    // ========== SPLIT MODE LANDSCAPE ==========

    @Test
    fun `split mode landscape - uses landscape weights`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertEquals(0.5f, result.scrollViewWeight, 0.01f)
        assertEquals(0.5f, result.gestureZoneWeight, 0.01f)
        assertTrue(result.gestureZoneVisible)
    }

    @Test
    fun `split mode landscape - different ratio`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.8f,
            landscapeGestureWeight = 0.2f
        )

        assertEquals(0.8f, result.scrollViewWeight, 0.01f)
        assertEquals(0.2f, result.gestureZoneWeight, 0.01f)
        assertTrue(result.gestureZoneVisible)
    }

    // ========== SPLIT MODE UNDEFINED ORIENTATION ==========

    @Test
    fun `split mode undefined orientation - falls back to portrait`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_UNDEFINED,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        // ORIENTATION_UNDEFINED != ORIENTATION_LANDSCAPE → Portrait
        assertEquals(0.6f, result.scrollViewWeight, 0.01f)
        assertEquals(0.4f, result.gestureZoneWeight, 0.01f)
        assertTrue(result.gestureZoneVisible)
    }

    // ========== DEFENSIVE: NEGATIVE WEIGHTS ==========

    @Test
    fun `negative portrait scroll weight coerced to 0`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = -0.5f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertEquals(0f, result.scrollViewWeight, 0.01f)
        assertEquals(0.4f, result.gestureZoneWeight, 0.01f)
    }

    @Test
    fun `negative portrait gesture weight coerced to 0`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = -0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertEquals(0.6f, result.scrollViewWeight, 0.01f)
        assertEquals(0f, result.gestureZoneWeight, 0.01f)
    }

    @Test
    fun `negative landscape scroll weight coerced to 0`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = -0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertEquals(0f, result.scrollViewWeight, 0.01f)
        assertEquals(0.5f, result.gestureZoneWeight, 0.01f)
    }

    @Test
    fun `negative landscape gesture weight coerced to 0`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = -0.5f
        )

        assertEquals(0.5f, result.scrollViewWeight, 0.01f)
        assertEquals(0f, result.gestureZoneWeight, 0.01f)
    }

    @Test
    fun `all negative weights coerced to 0`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = -1f,
            portraitGestureWeight = -1f,
            landscapeScrollWeight = -1f,
            landscapeGestureWeight = -1f
        )

        assertEquals(0f, result.scrollViewWeight, 0.01f)
        assertEquals(0f, result.gestureZoneWeight, 0.01f)
        assertTrue(result.gestureZoneVisible)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `zero weights allowed`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 0f,
            portraitGestureWeight = 0f,
            landscapeScrollWeight = 0f,
            landscapeGestureWeight = 0f
        )

        assertEquals(0f, result.scrollViewWeight, 0.01f)
        assertEquals(0f, result.gestureZoneWeight, 0.01f)
        assertTrue(result.gestureZoneVisible)
    }

    @Test
    fun `very large weights allowed`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 100f,
            portraitGestureWeight = 50f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertEquals(100f, result.scrollViewWeight, 0.01f)
        assertEquals(50f, result.gestureZoneWeight, 0.01f)
    }

    @Test
    fun `weights dont need to sum to 1`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 0.3f,
            portraitGestureWeight = 0.3f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        // Summe = 0.6, nicht 1.0 - das ist OK für LinearLayout weights
        assertEquals(0.3f, result.scrollViewWeight, 0.01f)
        assertEquals(0.3f, result.gestureZoneWeight, 0.01f)
    }

    // ========== REAL-WORLD SCENARIOS ==========

    @Test
    fun `scenario - kolibri default portrait split`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertEquals(0.6f, result.scrollViewWeight, 0.01f)
        assertEquals(0.4f, result.gestureZoneWeight, 0.01f)
        assertTrue(result.gestureZoneVisible)
    }

    @Test
    fun `scenario - kolibri default landscape split`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertEquals(0.5f, result.scrollViewWeight, 0.01f)
        assertEquals(0.5f, result.gestureZoneWeight, 0.01f)
        assertTrue(result.gestureZoneVisible)
    }

    @Test
    fun `scenario - rotation from portrait split to landscape split`() {
        val portraitResult = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        val landscapeResult = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        // Landscape hat gleiche Gewichtung für beide Zonen
        assertNotEquals(portraitResult.scrollViewWeight, landscapeResult.scrollViewWeight)
        assertEquals(landscapeResult.scrollViewWeight, landscapeResult.gestureZoneWeight, 0.01f)
    }

    @Test
    fun `scenario - rotation from split to full mode`() {
        val splitResult = calculator.calculate(
            enableSplit = true,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        val fullResult = calculator.calculate(
            enableSplit = false,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        assertTrue(splitResult.gestureZoneVisible)
        assertFalse(fullResult.gestureZoneVisible)
        assertEquals(1f, fullResult.scrollViewWeight, 0.01f)
    }

    // ========== PARANOID TESTS ==========

    @Test
    fun `idempotent - same input same output`() {
        repeat(100) {
            val result = calculator.calculate(
                enableSplit = true,
                orientation = Configuration.ORIENTATION_PORTRAIT,
                portraitScrollWeight = 0.6f,
                portraitGestureWeight = 0.4f,
                landscapeScrollWeight = 0.5f,
                landscapeGestureWeight = 0.5f
            )

            assertEquals(0.6f, result.scrollViewWeight, 0.01f)
            assertEquals(0.4f, result.gestureZoneWeight, 0.01f)
            assertTrue(result.gestureZoneVisible)
        }
    }

    @Test
    fun `unknown orientation value treated as portrait`() {
        val result = calculator.calculate(
            enableSplit = true,
            orientation = 999,  // Ungültiger Wert
            portraitScrollWeight = 0.6f,
            portraitGestureWeight = 0.4f,
            landscapeScrollWeight = 0.5f,
            landscapeGestureWeight = 0.5f
        )

        // Nicht LANDSCAPE → Portrait Fallback
        assertEquals(0.6f, result.scrollViewWeight, 0.01f)
        assertEquals(0.4f, result.gestureZoneWeight, 0.01f)
    }
}