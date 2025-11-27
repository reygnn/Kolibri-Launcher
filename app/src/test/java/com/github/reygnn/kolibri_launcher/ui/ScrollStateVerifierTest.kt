package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.ui.home.ScrollStateVerifier
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollStateVerifierTest {

    private lateinit var verifier: ScrollStateVerifier

    @Before
    fun setup() {
        verifier = ScrollStateVerifier()
    }

    // ========== KONSISTENTE ZUSTÄNDE ==========

    @Test
    fun `full mode with intercept disabled - consistent`() {
        val result = verifier.verify(
            currentSplitState = false,
            allowIntercept = false,
            canScrollDown = false,
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.Consistent, result)
    }

    @Test
    fun `split mode with intercept enabled and can scroll down - consistent`() {
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = true,
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.Consistent, result)
    }

    @Test
    fun `split mode with intercept enabled and can scroll up - consistent`() {
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = false,
            canScrollUp = true
        )
        assertEquals(ScrollStateVerifier.VerifyResult.Consistent, result)
    }

    @Test
    fun `split mode with intercept enabled and can scroll both - consistent`() {
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = true,
            canScrollUp = true
        )
        assertEquals(ScrollStateVerifier.VerifyResult.Consistent, result)
    }

    // ========== INKONSISTENZ: FULL MODE MIT INTERCEPT ==========

    @Test
    fun `full mode but intercept enabled - fix full mode`() {
        val result = verifier.verify(
            currentSplitState = false,
            allowIntercept = true,  // ← Inkonsistent!
            canScrollDown = false,
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.FixFullMode, result)
    }

    @Test
    fun `full mode but intercept enabled - ignores scroll capability`() {
        // Auch wenn theoretisch scrollbar: Full-Mode = kein Intercept
        val result = verifier.verify(
            currentSplitState = false,
            allowIntercept = true,
            canScrollDown = true,  // Wird ignoriert
            canScrollUp = true
        )
        assertEquals(ScrollStateVerifier.VerifyResult.FixFullMode, result)
    }

    // ========== INKONSISTENZ: SPLIT MODE OHNE INTERCEPT ==========

    @Test
    fun `split mode but intercept disabled - fix split mode`() {
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = false,  // ← Inkonsistent!
            canScrollDown = true,
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.FixSplitMode, result)
    }

    @Test
    fun `split mode but intercept disabled - even without scroll capability`() {
        // Priorität: Erst Intercept fixen, dann Re-evaluate
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = false,
            canScrollDown = false,
            canScrollUp = false
        )
        // FixSplitMode hat Priorität vor ReEvaluateNeeded
        assertEquals(ScrollStateVerifier.VerifyResult.FixSplitMode, result)
    }

    // ========== RE-EVALUATE NEEDED ==========

    @Test
    fun `split mode with intercept but cannot scroll - re-evaluate`() {
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = false,  // ← Kann nicht scrollen!
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.ReEvaluateNeeded, result)
    }

    // ========== PRIORITÄTS-TESTS ==========

    @Test
    fun `fix full mode has priority - intercept true in full mode`() {
        // Full-Mode + allowIntercept=true ist der "schlimmste" Fall
        val result = verifier.verify(
            currentSplitState = false,
            allowIntercept = true,
            canScrollDown = true,
            canScrollUp = true
        )
        assertEquals(ScrollStateVerifier.VerifyResult.FixFullMode, result)
    }

    @Test
    fun `fix split mode has priority over re-evaluate`() {
        // Wenn Intercept falsch UND kein Scroll möglich:
        // Erst Intercept fixen, dann wird Re-evaluate automatisch getriggert
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = false,
            canScrollDown = false,
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.FixSplitMode, result)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `full mode consistent even if could theoretically scroll`() {
        // Full-Mode ist Full-Mode, egal was Android sagt
        val result = verifier.verify(
            currentSplitState = false,
            allowIntercept = false,
            canScrollDown = true,  // Theoretisch scrollbar
            canScrollUp = true
        )
        assertEquals(ScrollStateVerifier.VerifyResult.Consistent, result)
    }

    // ========== REAL-WORLD SZENARIEN ==========

    @Test
    fun `scenario - after rotation content fits - re-evaluate needed`() {
        // Nach Rotation: War Split, jetzt passt alles
        val result = verifier.verify(
            currentSplitState = true,   // Noch im Split-Mode
            allowIntercept = true,
            canScrollDown = false,      // Aber kann nicht mehr scrollen
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.ReEvaluateNeeded, result)
    }

    @Test
    fun `scenario - favorites removed now fits - re-evaluate needed`() {
        // User hat Favoriten entfernt, Content passt jetzt
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = false,
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.ReEvaluateNeeded, result)
    }

    @Test
    fun `scenario - bug caused intercept mismatch in full mode`() {
        // Irgendein Bug hat allowIntercept=true gesetzt
        val result = verifier.verify(
            currentSplitState = false,
            allowIntercept = true,
            canScrollDown = false,
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.FixFullMode, result)
    }

    @Test
    fun `scenario - scroll view reset but split state not updated`() {
        // ScrollView wurde extern zurückgesetzt
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = false,
            canScrollDown = true,
            canScrollUp = false
        )
        assertEquals(ScrollStateVerifier.VerifyResult.FixSplitMode, result)
    }
}