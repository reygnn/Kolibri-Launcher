package com.github.reygnn.kolibri_launcher.ui

import android.view.View
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.HomeFragment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

class DoubleClickListenerTest {

    @get:Rule
    val timberRule = TimberRule()

    // Helper Klasse um Zeit zu simulieren
    private class TestableDoubleClickListener(
        private val timeProvider: () -> Long
    ) : HomeFragment.DoubleClickListener() {

        var triggerCount = 0

        // Wir überschreiben onClick leicht, um unsere simulierte Zeit einzuschleusen
        // Da 'lastClickTime' private ist, können wir es nicht direkt setzen.
        // TRICK: Wir müssen uns darauf verlassen, dass System.currentTimeMillis()
        // im echten Code verwendet wird.
        //
        // ALTERNATIVE FÜR UNIT TEST OHNE MOCKK/REFLECTION:
        // Wir extrahieren die Zeit-Logik oder testen die Logik basierend auf realem Thread.sleep (dirty).
        //
        // SAUBERSTE LÖSUNG: Wir kopieren die Logik hier rein um sie zu testen,
        // da wir die System-Zeit in der inner class nicht mocken können ohne Refactoring.
        //
        // Aber für diesen Test nehmen wir an, wir refactorn den Listener minimal
        // oder nutzen Thread.sleep (für 300ms ist das im Unit Test akzeptabel).

        override fun onDoubleClick() {
            triggerCount++
        }
    }

    // Da wir System.currentTimeMillis() nicht mocken wollen ohne PowerMock,
    // nutzen wir hier echte Zeitabstände. 300ms sind kurz genug für einen Test.

    @Test
    fun `click - fast sequence triggers double click`() {
        val listener = object : HomeFragment.DoubleClickListener() {
            var calls = 0
            override fun onDoubleClick() { calls++ }
        }
        val view = Mockito.mock(View::class.java)

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
        val view = Mockito.mock(View::class.java)

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
        val view = Mockito.mock(View::class.java)

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