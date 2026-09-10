package com.github.reygnn.kolibri_launcher.ui.appdrawer

import com.github.reygnn.kolibri_launcher.ui.appdrawer.KeyboardShowCoordinator.ShowKeyboardStrategy
import com.github.reygnn.kolibri_launcher.ui.appdrawer.KeyboardShowCoordinator.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests für [KeyboardShowCoordinator].
 *
 * Testet die wichtigsten Kombinationen von:
 * - isViewLaidOut (true/false)
 * - isViewEffectivelyVisible (true/false)
 * - isFragmentAdded (true/false)
 * - isAutoShowEnabled (true/false)
 *
 * Guard-Priorität (von hoch nach niedrig):
 * 1. SETTING_DISABLED (isAutoShowEnabled = false)
 * 2. FRAGMENT_DETACHED (isFragmentAdded = false)
 * 3. VIEW_NOT_VISIBLE (isViewEffectivelyVisible = false)
 * 4. Layout-Check (isViewLaidOut → ShowImmediately vs WaitForLayout)
 */
class KeyboardShowCoordinatorTest {

    private lateinit var coordinator: KeyboardShowCoordinator

    @Before
    fun setUp() {
        coordinator = KeyboardShowCoordinator()
    }

    // ============================================================================
    // HAPPY PATH - Auto-Show enabled, Fragment added, View visible
    // ============================================================================

    @Test
    fun `determineStrategy - view laid out, visible, fragment added, enabled - returns ShowImmediately`() {
        // Der kritische Fix-Case: View ist schon gelayoutet
        val result = coordinator.determineStrategy(
            isViewLaidOut = true,
            isViewEffectivelyVisible = true,
            isFragmentAdded = true,
            isAutoShowEnabled = true
        )

        assertEquals(ShowKeyboardStrategy.ShowImmediately, result)
    }

    @Test
    fun `determineStrategy - view NOT laid out, visible, fragment added, enabled - returns WaitForLayout`() {
        // Normaler Case: View braucht noch Layout-Pass
        val result = coordinator.determineStrategy(
            isViewLaidOut = false,
            isViewEffectivelyVisible = true,
            isFragmentAdded = true,
            isAutoShowEnabled = true
        )

        assertEquals(ShowKeyboardStrategy.WaitForLayout, result)
    }

    // ============================================================================
    // GUARD: Setting disabled (höchste Priorität)
    // ============================================================================

    @Test
    fun `determineStrategy - setting disabled - returns Skip with SETTING_DISABLED`() {
        val result = coordinator.determineStrategy(
            isViewLaidOut = true,
            isViewEffectivelyVisible = true,
            isFragmentAdded = true,
            isAutoShowEnabled = false
        )

        assertTrue(result is ShowKeyboardStrategy.Skip)
        assertEquals(SkipReason.SETTING_DISABLED, (result as ShowKeyboardStrategy.Skip).reason)
    }

    @Test
    fun `determineStrategy - setting disabled, view not laid out - still returns Skip SETTING_DISABLED`() {
        // Setting-Check kommt VOR View-Check
        val result = coordinator.determineStrategy(
            isViewLaidOut = false,
            isViewEffectivelyVisible = true,
            isFragmentAdded = true,
            isAutoShowEnabled = false
        )

        assertTrue(result is ShowKeyboardStrategy.Skip)
        assertEquals(SkipReason.SETTING_DISABLED, (result as ShowKeyboardStrategy.Skip).reason)
    }

    @Test
    fun `determineStrategy - setting disabled AND fragment detached - returns Skip SETTING_DISABLED`() {
        // Setting-Check hat Priorität über Fragment-Check
        val result = coordinator.determineStrategy(
            isViewLaidOut = true,
            isViewEffectivelyVisible = true,
            isFragmentAdded = false,
            isAutoShowEnabled = false
        )

        assertTrue(result is ShowKeyboardStrategy.Skip)
        assertEquals(SkipReason.SETTING_DISABLED, (result as ShowKeyboardStrategy.Skip).reason)
    }

    // ============================================================================
    // GUARD: Fragment detached (zweithöchste Priorität)
    // ============================================================================

    @Test
    fun `determineStrategy - fragment detached, view laid out - returns Skip with FRAGMENT_DETACHED`() {
        val result = coordinator.determineStrategy(
            isViewLaidOut = true,
            isViewEffectivelyVisible = true,
            isFragmentAdded = false,
            isAutoShowEnabled = true
        )

        assertTrue(result is ShowKeyboardStrategy.Skip)
        assertEquals(SkipReason.FRAGMENT_DETACHED, (result as ShowKeyboardStrategy.Skip).reason)
    }

    @Test
    fun `determineStrategy - fragment detached, view NOT laid out - returns Skip with FRAGMENT_DETACHED`() {
        val result = coordinator.determineStrategy(
            isViewLaidOut = false,
            isViewEffectivelyVisible = true,
            isFragmentAdded = false,
            isAutoShowEnabled = true
        )

        assertTrue(result is ShowKeyboardStrategy.Skip)
        assertEquals(SkipReason.FRAGMENT_DETACHED, (result as ShowKeyboardStrategy.Skip).reason)
    }

    // ============================================================================
    // GUARD: View not visible (dritthöchste Priorität)
    // ============================================================================

    @Test
    fun `determineStrategy - view not visible, all else ok - returns Skip with VIEW_NOT_VISIBLE`() {
        val result = coordinator.determineStrategy(
            isViewLaidOut = true,
            isViewEffectivelyVisible = false,
            isFragmentAdded = true,
            isAutoShowEnabled = true
        )

        assertTrue(result is ShowKeyboardStrategy.Skip)
        assertEquals(SkipReason.VIEW_NOT_VISIBLE, (result as ShowKeyboardStrategy.Skip).reason)
    }

    @Test
    fun `determineStrategy - view not visible AND not laid out - returns Skip with VIEW_NOT_VISIBLE`() {
        // View-Visibility-Check kommt VOR Layout-Check
        val result = coordinator.determineStrategy(
            isViewLaidOut = false,
            isViewEffectivelyVisible = false,
            isFragmentAdded = true,
            isAutoShowEnabled = true
        )

        assertTrue(result is ShowKeyboardStrategy.Skip)
        assertEquals(SkipReason.VIEW_NOT_VISIBLE, (result as ShowKeyboardStrategy.Skip).reason)
    }

    @Test
    fun `determineStrategy - view not visible AND fragment detached - returns Skip with FRAGMENT_DETACHED`() {
        // Fragment-Check hat Priorität über View-Visibility-Check
        val result = coordinator.determineStrategy(
            isViewLaidOut = true,
            isViewEffectivelyVisible = false,
            isFragmentAdded = false,
            isAutoShowEnabled = true
        )

        assertTrue(result is ShowKeyboardStrategy.Skip)
        assertEquals(SkipReason.FRAGMENT_DETACHED, (result as ShowKeyboardStrategy.Skip).reason)
    }

    // ============================================================================
    // EDGE CASE: Alles false
    // ============================================================================

    @Test
    fun `determineStrategy - all conditions false - returns Skip SETTING_DISABLED`() {
        // Setting-Check gewinnt (höchste Priorität)
        val result = coordinator.determineStrategy(
            isViewLaidOut = false,
            isViewEffectivelyVisible = false,
            isFragmentAdded = false,
            isAutoShowEnabled = false
        )

        assertTrue(result is ShowKeyboardStrategy.Skip)
        assertEquals(SkipReason.SETTING_DISABLED, (result as ShowKeyboardStrategy.Skip).reason)
    }

    // ============================================================================
    // REGRESSION TEST: Der ursprüngliche Bug
    // ============================================================================

    @Test
    fun `REGRESSION - view already laid out must NOT wait for callback`() {
        // DER BUG: doOnLayout wird nie aufgerufen wenn View schon gelayoutet ist
        // Dieser Test stellt sicher, dass wir in diesem Fall NICHT WaitForLayout zurückgeben

        val result = coordinator.determineStrategy(
            isViewLaidOut = true,  // <-- View ist schon fertig!
            isViewEffectivelyVisible = true,
            isFragmentAdded = true,
            isAutoShowEnabled = true
        )

        // MUSS ShowImmediately sein, NICHT WaitForLayout!
        assertTrue(
            "Bug regression: When view is already laid out, we must show immediately, not wait!",
            result is ShowKeyboardStrategy.ShowImmediately
        )
    }

    // ============================================================================
    // PRIORITY TEST: Guard-Reihenfolge verifizieren
    // ============================================================================

    @Test
    fun `priority - setting disabled wins over all other conditions`() {
        // Selbst wenn alles andere "gut" aussieht
        val result = coordinator.determineStrategy(
            isViewLaidOut = true,
            isViewEffectivelyVisible = true,
            isFragmentAdded = true,
            isAutoShowEnabled = false  // <-- Das entscheidet
        )

        assertEquals(SkipReason.SETTING_DISABLED, (result as ShowKeyboardStrategy.Skip).reason)
    }

    @Test
    fun `priority - fragment detached wins over view visibility`() {
        val result = coordinator.determineStrategy(
            isViewLaidOut = true,
            isViewEffectivelyVisible = false,  // Auch nicht sichtbar
            isFragmentAdded = false,           // <-- Das entscheidet (höhere Prio)
            isAutoShowEnabled = true
        )

        assertEquals(SkipReason.FRAGMENT_DETACHED, (result as ShowKeyboardStrategy.Skip).reason)
    }
}