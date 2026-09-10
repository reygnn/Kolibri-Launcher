package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.DoubleClickDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Saubere Unit-Tests für die Double-Click-Logik - ohne Thread.sleep, ohne System-Clock.
 * Ersetzt inhaltlich den alten [DoubleClickListenerTest] (dort: Thread.sleep(310)-Hack).
 * Der alte Test bleibt als Legacy-Coverage für die View-OnClickListener-Integration bestehen.
 */
class DoubleClickDetectorTest {

    @get:Rule
    val timberRule = TimberRule()

    private var fakeTime: Long = 0L
    private val clock: () -> Long = { fakeTime }

    @Test
    fun `first click never counts as double click`() {
        val detector = DoubleClickDetector(thresholdMillis = 300L, clock = clock)
        fakeTime = 1_000L
        assertFalse(detector.registerClick())
    }

    @Test
    fun `second click within threshold counts as double click`() {
        val detector = DoubleClickDetector(thresholdMillis = 300L, clock = clock)
        fakeTime = 1_000L
        detector.registerClick()
        fakeTime = 1_200L // 200ms später
        assertTrue(detector.registerClick())
    }

    @Test
    fun `second click after threshold does not count as double click`() {
        val detector = DoubleClickDetector(thresholdMillis = 300L, clock = clock)
        fakeTime = 1_000L
        detector.registerClick()
        fakeTime = 1_400L // 400ms später
        assertFalse(detector.registerClick())
    }

    // ========== BOUNDARY ==========

    @Test
    fun `second click at exactly threshold is NOT a double click`() {
        // Implementierung: (now - last) < threshold. 300 ist nicht < 300 -> kein DC.
        val detector = DoubleClickDetector(thresholdMillis = 300L, clock = clock)
        fakeTime = 1_000L
        detector.registerClick()
        fakeTime = 1_300L // exakt am Limit
        assertFalse(detector.registerClick())
    }

    @Test
    fun `second click one ms below threshold IS a double click`() {
        val detector = DoubleClickDetector(thresholdMillis = 300L, clock = clock)
        fakeTime = 1_000L
        detector.registerClick()
        fakeTime = 1_299L
        assertTrue(detector.registerClick())
    }

    // ========== SEQUENCE BEHAVIOR ==========

    @Test
    fun `slow-then-fast sequence resets and then detects`() {
        val detector = DoubleClickDetector(thresholdMillis = 300L, clock = clock)
        fakeTime = 1_000L
        detector.registerClick()

        // zu spät -> kein DC, aber lastClickTime wird auf 2_000L gesetzt
        fakeTime = 2_000L
        assertFalse(detector.registerClick())

        // direkt danach -> DC
        fakeTime = 2_100L
        assertTrue(detector.registerClick())
    }

    @Test
    fun `three rapid clicks fire the double click exactly once`() {
        // AUDIT-6 #3 regression guard. A detected double-click resets the
        // pairing, so the second tap's timestamp cannot ALSO seed a pair with
        // the third tap. Without the reset a fast triple-tap fired twice
        // (N rapid taps → N-1 hits), double-emitting the open-app action on
        // clock/date/battery double-tap.
        val detector = DoubleClickDetector(thresholdMillis = 300L, clock = clock)
        fakeTime = 1_000L
        assertFalse(detector.registerClick())
        fakeTime = 1_100L
        assertTrue(detector.registerClick())
        fakeTime = 1_200L
        assertFalse("third rapid tap must NOT re-fire the double click", detector.registerClick())
    }

    @Test
    fun `four rapid clicks fire on the second and fourth tap`() {
        // After the reset, a fresh pair starts: tap3 becomes the new "first",
        // tap4 completes the second pair. N rapid taps → floor(N/2) hits.
        val detector = DoubleClickDetector(thresholdMillis = 300L, clock = clock)
        fakeTime = 1_000L
        assertFalse(detector.registerClick())
        fakeTime = 1_100L
        assertTrue(detector.registerClick())
        fakeTime = 1_200L
        assertFalse(detector.registerClick())
        fakeTime = 1_300L
        assertTrue("fourth tap completes the second pair", detector.registerClick())
    }

    // ========== DEFAULTS ==========

    @Test
    fun `uses default threshold from AppConstants when not overridden`() {
        // Default ist AppConstants.DOUBLE_CLICK_THRESHOLD = 300L
        val detector = DoubleClickDetector(clock = clock)
        fakeTime = 1_000L
        detector.registerClick()
        fakeTime = 1_299L
        assertTrue(detector.registerClick())
    }
}
