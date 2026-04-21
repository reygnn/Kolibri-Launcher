package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.SwipeGestureAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Test
import com.github.reygnn.kolibri_launcher.ui.home.SwipeGestureAnalyzer.SwipeResult
import org.junit.Rule

class SwipeGestureAnalyzerTest {

    @get:Rule
    val timberRule = TimberRule()

    private val analyzer = SwipeGestureAnalyzer()

    // Die Konstanten aus dem AppConstants File (zur Referenz im Test)
    // SWIPE_THRESHOLD = 50
    // SWIPE_VELOCITY_THRESHOLD = 50

    // ========== GRENZWERT-TESTS (BOUNDARY TESTING) ==========

    @Test
    fun `analyze - boundary - diff 51 is valid (just above threshold)`() {
        // Exakt 1 Pixel über dem Limit
        val result = analyzer.analyze(
            diffX = 51f,
            diffY = 0f,
            velocityX = 51f,
            velocityY = 0f
        )
        assertEquals(SwipeResult.TOWARDS_RIGHT, result)
    }

    @Test
    fun `analyze - boundary - diff 50 is ignored (exact threshold)`() {
        // Die Logik ist gewöhnlich `> THRESHOLD`.
        // 50 ist nicht GRÖSSER als 50, also Ignored.
        val result = analyzer.analyze(
            diffX = 50f,
            diffY = 0f,
            velocityX = 51f, // Velocity wäre okay
            velocityY = 0f
        )
        assertEquals(SwipeResult.IGNORED, result)
    }

    @Test
    fun `analyze - boundary - velocity 50 is ignored`() {
        val result = analyzer.analyze(
            diffX = 51f, // Distanz wäre okay
            diffY = 0f,
            velocityX = 50f, // Zu langsam (exakt auf Grenze)
            velocityY = 0f
        )
        assertEquals(SwipeResult.IGNORED, result)
    }

    @Test
    fun `analyze - boundary - diff 49 is ignored (just below threshold)`() {
        val result = analyzer.analyze(
            diffX = 49f,
            diffY = 0f,
            velocityX = 100f,
            velocityY = 0f
        )
        assertEquals(SwipeResult.IGNORED, result)
    }

    // ========== RICHTUNGS- UND DOMINANZ-TESTS ==========

    @Test
    fun `analyze - clear swipe LEFT`() {
        val result = analyzer.analyze(-60f, 0f, 60f, 0f)
        assertEquals(SwipeResult.TOWARDS_LEFT, result)
    }

    @Test
    fun `analyze - clear swipe UP`() {
        val result = analyzer.analyze(0f, -60f, 0f, 60f)
        assertEquals(SwipeResult.UP, result)
    }

    @Test
    fun `analyze - diagonal favors dominant axis (X wins)`() {
        // Beide über 50, aber X ist stärker
        val result = analyzer.analyze(
            diffX = 100f,
            diffY = 60f,
            velocityX = 100f,
            velocityY = 100f
        )
        assertEquals(SwipeResult.TOWARDS_RIGHT, result)
    }

    @Test
    fun `analyze - diagonal favors dominant axis (Y wins)`() {
        // Beide über 50, aber Y ist stärker
        val result = analyzer.analyze(
            diffX = 60f,
            diffY = 100f,
            velocityX = 100f,
            velocityY = 100f
        )
        assertEquals(SwipeResult.DOWN, result)
    }
}