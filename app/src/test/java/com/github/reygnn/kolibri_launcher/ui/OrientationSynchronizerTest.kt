package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.ui.home.OrientationSynchronizer
import org.junit.Assert.*
import org.junit.Test

class OrientationSynchronizerTest {

    @Test
    fun `verifyAndSync SHOULD update AND cleanup WHEN states differ`() {
        // GIVEN
        var updatedOrientation: Int? = null
        var cleanupCalled = false

        // Simuliertes System sagt: Wir sind im LANDSCAPE (2)
        val systemOrientation = 2

        val synchronizer = OrientationSynchronizer(
            getSystemOrientation = { systemOrientation },
            onUpdateNeeded = { newValue -> updatedOrientation = newValue },
            onCleanupNeeded = { cleanupCalled = true }
        )

        // Simulierter interner State sagt: Wir sind im PORTRAIT (1)
        val internalState = 1

        // WHEN
        val result = synchronizer.verifyAndSync(internalState)

        // THEN
        assertTrue("Sollte true zurückgeben, da Fix nötig war", result)
        assertEquals("Sollte auf System-Wert (2) geupdatet haben", 2, updatedOrientation)
        assertTrue("Sollte Cleanup aufgerufen haben", cleanupCalled)
    }

    @Test
    fun `verifyAndSync SHOULD do nothing WHEN states are equal`() {
        // GIVEN
        var updatedOrientation: Int? = null
        var cleanupCalled = false

        // System und Intern sind beide PORTRAIT (1)
        val systemOrientation = 1

        val synchronizer = OrientationSynchronizer(
            getSystemOrientation = { systemOrientation },
            onUpdateNeeded = { updatedOrientation = it },
            onCleanupNeeded = { cleanupCalled = true }
        )

        // WHEN
        val result = synchronizer.verifyAndSync(1)

        // THEN
        assertFalse("Sollte false zurückgeben, da alles okay war", result)
        assertNull("Sollte State nicht ändern", updatedOrientation)
        assertFalse("Sollte Cleanup NICHT aufrufen", cleanupCalled)
    }

    @Test
    fun `verifyAndSync SHOULD sync WHEN internal state is UNDEFINED (0) and system is PORTRAIT`() {
        // GIVEN
        val undefinedState = 0 // Configuration.ORIENTATION_UNDEFINED
        val systemPortrait = 1
        var updateCalled = false

        val synchronizer = OrientationSynchronizer(
            getSystemOrientation = { systemPortrait },
            onUpdateNeeded = { updateCalled = true },
            onCleanupNeeded = { }
        )

        // WHEN
        val result = synchronizer.verifyAndSync(undefinedState)

        // THEN
        assertTrue("Sollte von UNDEFINED auf PORTRAIT syncen", result)
        assertTrue(updateCalled)
    }

    @Test
    fun `verifyAndSync SHOULD execute update BEFORE cleanup`() {
        // GIVEN
        val executionOrder = mutableListOf<String>()

        val synchronizer = OrientationSynchronizer(
            getSystemOrientation = { 2 },
            onUpdateNeeded = { executionOrder.add("UPDATE") },
            onCleanupNeeded = { executionOrder.add("CLEANUP") }
        )

        // WHEN
        synchronizer.verifyAndSync(1) // 1 != 2 -> Sync triggers

        // THEN
        assertEquals("Sollte genau 2 Aktionen haben", 2, executionOrder.size)
        assertEquals("Erste Aktion muss UPDATE sein", "UPDATE", executionOrder[0])
        assertEquals("Zweite Aktion muss CLEANUP sein", "CLEANUP", executionOrder[1])
    }

    @Test
    fun `verifyAndSync SHOULD NOT crash or sync weirdly on same arbitrary integers`() {
        // GIVEN - Ein theoretischer Edge Case (z.B. Square Screens = 3)
        // Wir testen, ob es wirklich nur auf Ungleichheit prüft, egal welche Zahl.
        val weirdState = 99
        var callbackCount = 0

        val synchronizer = OrientationSynchronizer(
            getSystemOrientation = { weirdState },
            onUpdateNeeded = { callbackCount++ },
            onCleanupNeeded = { callbackCount++ }
        )

        // WHEN
        val result = synchronizer.verifyAndSync(weirdState)

        // THEN
        assertFalse("Sollte false sein, da 99 == 99", result)
        assertEquals("Callbacks dürften gar nicht aufgerufen werden", 0, callbackCount)
    }
}