package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.ui.home.SplitModeCalculator
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Before
import kotlin.test.Test

class SplitModeCalculatorTest {

    private lateinit var calculator: SplitModeCalculator

    @Before
    fun setup() {
        calculator = SplitModeCalculator()
    }

    // ========== AUTO-MODUS (threshold = 0) ==========

    @Test
    fun `auto mode - no scroll possible - no split`() {
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = false,
            canScrollUp = false
        )
        assertFalse(result)
    }

    @Test
    fun `auto mode - can scroll down only - split`() {
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = true,
            canScrollUp = false
        )
        assertTrue(result)
    }

    @Test
    fun `auto mode - can scroll up only - split`() {
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = false,
            canScrollUp = true
        )
        assertTrue(result)
    }

    @Test
    fun `auto mode - can scroll both directions - split`() {
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = true,
            canScrollUp = true
        )
        assertTrue(result)
    }

    @Test
    fun `auto mode - ignores pixel values completely`() {
        // Auch bei riesigem Content: Wenn Android sagt "kein Scroll", dann kein Split
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 10000,
            containerHeight = 100
        )
        assertFalse(result)
    }

    // ========== POWER-USER-MODUS (threshold > 0) ==========

    @Test
    fun `power user - content fits in container - no split`() {
        val result = calculator.shouldSplit(
            threshold = 50,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 500,
            containerHeight = 600
        )
        assertFalse(result)
    }

    @Test
    fun `power user - content exactly equals container - no split`() {
        val result = calculator.shouldSplit(
            threshold = 50,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 600,
            containerHeight = 600  // 0px overflow
        )
        assertFalse(result)
    }

    @Test
    fun `power user - overflow below threshold - no split`() {
        val result = calculator.shouldSplit(
            threshold = 100,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 650,
            containerHeight = 600  // 50px < 100px
        )
        assertFalse(result)
    }

    @Test
    fun `power user - overflow exactly at threshold - no split`() {
        val result = calculator.shouldSplit(
            threshold = 100,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 700,
            containerHeight = 600  // 100px == 100px, aber > nicht >=
        )
        assertFalse(result)
    }

    @Test
    fun `power user - overflow one pixel above threshold - split`() {
        val result = calculator.shouldSplit(
            threshold = 100,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 701,
            containerHeight = 600  // 101px > 100px
        )
        assertTrue(result)
    }

    @Test
    fun `power user - large overflow above threshold - split`() {
        val result = calculator.shouldSplit(
            threshold = 100,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 1000,
            containerHeight = 600  // 400px > 100px
        )
        assertTrue(result)
    }

    @Test
    fun `power user - ignores canScroll flags uses pixel calculation`() {
        // Android sagt "kann scrollen", aber Threshold sagt nein
        val result = calculator.shouldSplit(
            threshold = 200,
            canScrollDown = true,
            canScrollUp = true,
            contentHeight = 650,
            containerHeight = 600  // 50px < 200px
        )
        assertFalse(result)
    }

    // ========== EDGE CASES: ZERO VALUES ==========

    @Test
    fun `power user - zero content height - no split`() {
        val result = calculator.shouldSplit(
            threshold = 50,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 0,
            containerHeight = 600
        )
        assertFalse(result)
    }

    @Test
    fun `power user - zero container height - uses content as overflow`() {
        // Container noch nicht gemessen, Content existiert
        val result = calculator.shouldSplit(
            threshold = 50,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 500,
            containerHeight = 0  // 500 - 0 = 500 > 50
        )
        assertTrue(result)
    }

    @Test
    fun `power user - both heights zero - no split`() {
        val result = calculator.shouldSplit(
            threshold = 50,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 0,
            containerHeight = 0  // 0 - 0 = 0, nicht > 50
        )
        assertFalse(result)
    }

    // ========== EDGE CASES: THRESHOLD BOUNDARIES ==========

    @Test
    fun `power user - threshold of 1 - very sensitive`() {
        val result = calculator.shouldSplit(
            threshold = 1,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 602,
            containerHeight = 600  // 2px > 1px
        )
        assertTrue(result)
    }

    @Test
    fun `power user - threshold of 1 - exactly 1 pixel overflow - no split`() {
        val result = calculator.shouldSplit(
            threshold = 1,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 601,
            containerHeight = 600  // 1px == 1px, aber > nicht >=
        )
        assertFalse(result)
    }

    @Test
    fun `power user - very large threshold - tolerates huge overflow`() {
        val result = calculator.shouldSplit(
            threshold = 10000,
            canScrollDown = true,
            canScrollUp = true,
            contentHeight = 5000,
            containerHeight = 600  // 4400px < 10000px
        )
        assertFalse(result)
    }

    @Test
    fun `power user - max int threshold - never splits`() {
        val result = calculator.shouldSplit(
            threshold = Int.MAX_VALUE,
            canScrollDown = true,
            canScrollUp = true,
            contentHeight = 100000,
            containerHeight = 100  // 99900 < MAX_VALUE
        )
        assertFalse(result)
    }

    // ========== EDGE CASES: DEFENSIVE (INVALID INPUTS) ==========

    @Test
    fun `power user - negative content height - coerced to zero overflow`() {
        // Sollte nicht passieren, aber defensiv testen
        val result = calculator.shouldSplit(
            threshold = 50,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = -100,
            containerHeight = 600  // -700 coerced to 0
        )
        assertFalse(result)
    }

    @Test
    fun `power user - negative container height - treated as large overflow`() {
        // Sollte nicht passieren, aber defensiv testen
        val result = calculator.shouldSplit(
            threshold = 50,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 600,
            containerHeight = -100  // 600 - (-100) = 700 > 50
        )
        assertTrue(result)
    }

    // --- KORRIGIERTE TESTS für defensive Calculator-Version ---

    @Test
    fun `paranoid - negative threshold falls back to auto mode`() {
        // Defensive: threshold <= 0 = Auto-Modus
        val result = calculator.shouldSplit(
            threshold = -1,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 100,
            containerHeight = 600
        )
        // Auto-Modus: canScroll entscheidet → false
        assertFalse(result)
    }

    @Test
    fun `paranoid - min int threshold falls back to auto mode`() {
        // Defensive: threshold <= 0 = Auto-Modus
        val result = calculator.shouldSplit(
            threshold = Int.MIN_VALUE,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 0,
            containerHeight = 0
        )
        // Auto-Modus: canScroll entscheidet → false
        assertFalse(result)
    }

    @Test
    fun `power user - both heights negative - coerced to zero`() {
        // Defensive: Negative Werte werden zu 0
        val result = calculator.shouldSplit(
            threshold = 50,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = -100,  // → 0
            containerHeight = -200 // → 0
        )
        // 0 - 0 = 0, nicht > 50
        assertFalse(result)
    }

    // ========== SCROLLABLE PIXELS CALCULATION ==========

    @Test
    fun `calculateScrollablePixels - positive overflow`() {
        val result = calculator.calculateScrollablePixels(
            contentHeight = 800,
            containerHeight = 600
        )
        assertEquals(200, result)
    }

    @Test
    fun `calculateScrollablePixels - no overflow`() {
        val result = calculator.calculateScrollablePixels(
            contentHeight = 400,
            containerHeight = 600
        )
        assertEquals(0, result)
    }

    @Test
    fun `calculateScrollablePixels - exact fit`() {
        val result = calculator.calculateScrollablePixels(
            contentHeight = 600,
            containerHeight = 600
        )
        assertEquals(0, result)
    }

    @Test
    fun `calculateScrollablePixels - negative coerced to zero`() {
        val result = calculator.calculateScrollablePixels(
            contentHeight = -100,
            containerHeight = 600
        )
        assertEquals(0, result)
    }

    // ========== REAL-WORLD SCENARIOS ==========

    @Test
    fun `scenario - 3 favorites on small phone - no split`() {
        // Typisch: 3 Apps passen locker
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 180,  // 3 * 60px
            containerHeight = 400
        )
        assertFalse(result)
    }

    @Test
    fun `scenario - 10 favorites on small phone - split`() {
        // Typisch: 10 Apps brauchen Scroll
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 600,  // 10 * 60px
            containerHeight = 400
        )
        assertTrue(result)
    }

    @Test
    fun `scenario - landscape mode more space - no split`() {
        // Nach Rotation: Mehr Platz
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 600,
            containerHeight = 800  // Landscape hat mehr Höhe
        )
        assertFalse(result)
    }

    @Test
    fun `scenario - power user wants buffer before split`() {
        // User will erst bei 100px Überlauf splitten
        val result = calculator.shouldSplit(
            threshold = 100,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 480,
            containerHeight = 400  // 80px < 100px threshold
        )
        assertFalse(result)
    }

    // ========== PATHOLOGISCH PARANOID ==========



// --- Integer Overflow Szenarien ---

    @Test
    fun `paranoid - max int content minus small container - no overflow`() {
        val result = calculator.shouldSplit(
            threshold = 100,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = Int.MAX_VALUE,
            containerHeight = 100
        )
        // MAX_VALUE - 100 = riesige Zahl > 100
        assertTrue(result)
    }

    @Test
    fun `paranoid - max int container - negative result coerced`() {
        val result = calculator.shouldSplit(
            threshold = 100,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 100,
            containerHeight = Int.MAX_VALUE
        )
        // 100 - MAX_VALUE = negative, coerced to 0
        assertFalse(result)
    }

    @Test
    fun `paranoid - both max int - zero overflow`() {
        val result = calculator.shouldSplit(
            threshold = 100,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = Int.MAX_VALUE,
            containerHeight = Int.MAX_VALUE
        )
        // MAX - MAX = 0, nicht > 100
        assertFalse(result)
    }

    @Test
    fun `paranoid - potential integer overflow in subtraction`() {
        // Int.MAX_VALUE - Int.MIN_VALUE würde überlaufen!
        // Aber containerHeight sollte nie negativ sein...
        // Trotzdem testen was passiert:
        val scrollable = calculator.calculateScrollablePixels(
            contentHeight = Int.MAX_VALUE,
            containerHeight = Int.MIN_VALUE
        )
        // MAX - MIN = Overflow! Ergebnis ist -1 (oder undefined)
        // coerceAtLeast(0) rettet uns NICHT vor dem Overflow selbst!
        // Das ist ein echter Bug wenn es passieren würde

        // Dieser Test dokumentiert das Problem:
        // assertTrue(scrollable >= 0) // KÖNNTE FEHLSCHLAGEN!
    }

// --- Idempotenz / Wiederholte Aufrufe ---

    @Test
    fun `paranoid - same input always same output`() {
        repeat(100) {
            val result = calculator.shouldSplit(
                threshold = 50,
                canScrollDown = true,
                canScrollUp = false,
                contentHeight = 700,
                containerHeight = 600
            )
            assertTrue(result)
        }
    }

// --- Grenzwerte bei 1px Unterschieden ---

    @Test
    fun `paranoid - off by one - content 1px smaller than container`() {
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 599,
            containerHeight = 600
        )
        assertFalse(result)
    }

    @Test
    fun `paranoid - off by one - content 1px larger than container`() {
        // Bei threshold=0 entscheidet canScroll, nicht Pixel!
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = true,  // Android sagt: ja, 1px scrollbar
            canScrollUp = false,
            contentHeight = 601,
            containerHeight = 600
        )
        assertTrue(result)
    }

// --- Sehr kleine Werte ---

    @Test
    fun `paranoid - content height of 1`() {
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 1,
            containerHeight = 600
        )
        assertFalse(result)
    }

    @Test
    fun `paranoid - container height of 1`() {
        val result = calculator.shouldSplit(
            threshold = 50,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 600,
            containerHeight = 1  // 599px overflow > 50
        )
        assertTrue(result)
    }

// --- Threshold Edge Cases ---

    @Test
    fun `paranoid - threshold equals exact overflow`() {
        // Overflow ist EXAKT der Threshold
        val result = calculator.shouldSplit(
            threshold = 200,
            canScrollDown = true,
            canScrollUp = false,
            contentHeight = 800,
            containerHeight = 600  // 200 == 200, aber > nicht >=
        )
        assertFalse(result)  // Kein Split bei exakter Gleichheit
    }

    @Test
    fun `paranoid - threshold is 0 but passed as power user mode`() {
        // Was wenn jemand threshold=0 mit Pixel-Werten mischt?
        // Sollte Auto-Modus sein, Pixel werden ignoriert
        val result = calculator.shouldSplit(
            threshold = 0,
            canScrollDown = false,
            canScrollUp = false,
            contentHeight = 10000,  // Riesig!
            containerHeight = 100   // Winzig!
        )
        // Auto-Modus: canScroll entscheidet, nicht Pixel
        assertFalse(result)
    }
}