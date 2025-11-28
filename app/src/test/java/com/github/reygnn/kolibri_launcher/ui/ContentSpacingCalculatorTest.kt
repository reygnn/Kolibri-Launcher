package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.ui.home.ContentSpacingCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ContentSpacingCalculatorTest {

    private lateinit var calculator: ContentSpacingCalculator

    @Before
    fun setUp() {
        // Vor jedem Test eine frische Instanz, damit der Cache leer ist
        calculator = ContentSpacingCalculator()
    }

    // =================================================================================================
    // Tests: Mathematische Logik (Erster Aufruf)
    // =================================================================================================

    @Test
    fun `wenn Chips aus sind, wird volle UserMargin genutzt`() {
        // Erster Aufruf -> Cache leer -> Muss Ergebnis liefern
        val result = calculator.calculate(
            userPreferredMarginPx = 100,
            chipsHeightPx = 50, // irrelevant
            areChipsVisible = false
        )

        assertNotNull("Erster Aufruf darf nicht null sein", result)
        assertEquals(100, result)
    }

    @Test
    fun `wenn Chips an sind und kleiner als Margin, wird die Differenz genutzt`() {
        // Margin 100 - Chips 40 = 60
        val result = calculator.calculate(
            userPreferredMarginPx = 100,
            chipsHeightPx = 40,
            areChipsVisible = true
        )

        assertEquals(60, result)
    }

    @Test
    fun `wenn Chips groesser als Margin sind, greift der MinGap`() {
        // Margin 10 - Chips 50 = -40 -> Clamp auf minGap (0)
        val result = calculator.calculate(
            userPreferredMarginPx = 10,
            chipsHeightPx = 50,
            areChipsVisible = true,
            minGapPx = 0
        )

        assertEquals(0, result)
    }

    @Test
    fun `beruecksichtigt expliziten minGap parameter`() {
        // Margin 10 - Chips 50 = -40 -> Clamp auf minGap (8)
        val result = calculator.calculate(
            userPreferredMarginPx = 10,
            chipsHeightPx = 50,
            areChipsVisible = true,
            minGapPx = 8
        )

        assertEquals(8, result)
    }

    // =================================================================================================
    // Tests: Caching & State Management
    // =================================================================================================

    @Test
    fun `gibt NULL zurueck bei exakt gleichen Eingaben (Caching)`() {
        // 1. Initialer Aufruf
        val run1 = calculator.calculate(100, 50, true)
        assertEquals(50, run1) // 100 - 50 = 50

        // 2. Zweiter Aufruf mit identischen Werten
        val run2 = calculator.calculate(100, 50, true)
        assertNull("Sollte null zurückgeben, da Inputs identisch sind", run2)

        // 3. Dritter Aufruf mit geändertem Wert
        val run3 = calculator.calculate(100, 60, true) // Chips höher
        assertNotNull("Sollte neu berechnen, da Input geändert", run3)
        assertEquals(40, run3) // 100 - 60 = 40
    }

    @Test
    fun `rechnet neu nach cleanup`() {
        // 1. Initial
        calculator.calculate(100, 50, true)

        // 2. Identisch (Cache greift)
        assertNull(calculator.calculate(100, 50, true))

        // 3. Cleanup ausführen
        calculator.cleanup()

        // 4. Identisch (Cache sollte leer sein -> Neuberechnung)
        val resultAfterCleanup = calculator.calculate(100, 50, true)
        assertNotNull("Nach cleanup() muss neu berechnet werden", resultAfterCleanup)
        assertEquals(50, resultAfterCleanup)
    }

    @Test
    fun `behandelt chipsHeight 0 wie chipsHidden`() {
        // Wenn Höhe 0 ist, soll nichts abgezogen werden, auch wenn visible = true
        val result = calculator.calculate(
            userPreferredMarginPx = 100,
            chipsHeightPx = 0,
            areChipsVisible = true
        )
        assertEquals(100, result)
    }
}