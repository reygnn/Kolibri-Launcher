package com.github.reygnn.kolibri_launcher.ui

import android.view.View
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.HomeFragment
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * LEGACY TEST - behalten als Integration-Coverage für
 * HomeFragment.DoubleClickListener (View.OnClickListener-Wrapper).
 *
 * Die eigentliche Threshold-/Timing-Logik wird nun deterministisch durch
 * [DoubleClickDetectorTest] abgedeckt (ohne Thread.sleep, ohne System-Clock).
 *
 * Dieser Test dokumentiert weiterhin, dass die DoubleClickListener-Klasse
 * existiert, instanziert werden kann und grob erwartungsgemäß funktioniert,
 * wenn man System.currentTimeMillis() aus Echtzeit nutzt.
 *
 * NICHT erweitern. Neue Timing-/Grenzwert-Fälle gehören in
 * DoubleClickDetectorTest.
 * ============================================================================
 */
class DoubleClickListenerTest {

    @get:Rule
    val timberRule = TimberRule()

    @Test
    fun `click - fast sequence triggers double click`() {
        val listener = object : HomeFragment.DoubleClickListener() {
            var calls = 0
            override fun onDoubleClick() { calls++ }
        }
        val view = mockk<View>(relaxed = true)

        // 1. Klick
        listener.onClick(view)

        // Kein Warten (sofortiger 2. Klick -> 0ms Diff)
        listener.onClick(view)

        assertEquals("Sollte Double Click feuern bei < 300ms", 1, listener.calls)
    }

    @Test
    fun `click - slow sequence ignores double click`() {
        val listener = object : HomeFragment.DoubleClickListener() {
            var calls = 0
            override fun onDoubleClick() { calls++ }
        }
        val view = mockk<View>(relaxed = true)

        // 1. Klick
        listener.onClick(view)

        // Wir warten 310ms (Grenzwert ist 300ms)
        try {
            Thread.sleep(310)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        // 2. Klick
        listener.onClick(view)

        assertEquals("Sollte KEIN Double Click feuern bei > 300ms", 0, listener.calls)
    }

    @Test
    fun `click - boundary test sequence`() {
        val listener = object : HomeFragment.DoubleClickListener() {
            var calls = 0
            override fun onDoubleClick() { calls++ }
        }
        val view = mockk<View>(relaxed = true)

        // Klick 1
        listener.onClick(view)
        Thread.sleep(310)

        // Klick 2 (zu spät -> Reset Timer)
        listener.onClick(view)
        assertEquals(0, listener.calls)

        // Klick 3 (sofort nach Klick 2 -> Double Click!)
        listener.onClick(view)
        assertEquals(1, listener.calls)
    }
}
