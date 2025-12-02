package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.ui.home.ScrollStateVerifier
import com.github.reygnn.kolibri_launcher.ui.home.VerifyResult
import org.junit.Before
import kotlin.test.DefaultAsserter.assertTrue
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
        assertEquals(VerifyResult.Consistent, result)
    }

    @Test
    fun `split mode with intercept enabled and can scroll down - consistent`() {
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = true,
            canScrollUp = false
        )
        assertEquals(VerifyResult.Consistent, result)
    }

    @Test
    fun `split mode with intercept enabled and can scroll up - consistent`() {
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = false,
            canScrollUp = true
        )
        assertEquals(VerifyResult.Consistent, result)
    }

    @Test
    fun `split mode with intercept enabled and can scroll both - consistent`() {
        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = true,
            canScrollUp = true
        )
        assertEquals(VerifyResult.Consistent, result)
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
        assertEquals(VerifyResult.FixFullMode, result)
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
        assertEquals(VerifyResult.FixFullMode, result)
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
        assertEquals(VerifyResult.FixSplitMode, result)
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
        assertEquals(VerifyResult.FixSplitMode, result)
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
        assertEquals(VerifyResult.ReEvaluateNeeded, result)
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
        assertEquals(VerifyResult.FixFullMode, result)
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
        assertEquals(VerifyResult.FixSplitMode, result)
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
        assertEquals(VerifyResult.Consistent, result)
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
        assertEquals(VerifyResult.ReEvaluateNeeded, result)
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
        assertEquals(VerifyResult.ReEvaluateNeeded, result)
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
        assertEquals(VerifyResult.FixFullMode, result)
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
        assertEquals(VerifyResult.FixSplitMode, result)
    }

    // ========== ULTRA-PARANOID: GRENZÜBERSCHREITUNG VERMEIDEN ==========

    @Test
    fun `paranoid - verifier does NOT act as calculator - full mode with scrollable content`() {
        // KRITISCHER TEST:
        // Wir sind im Full-Mode, aber der Content ist eigentlich zu lang (canScroll = true).
        // Der Verifier darf hier NICHT "Fix" oder "ReEvaluate" schreien!
        // Warum? Weil der Calculator vielleicht entschieden hat, dass der Threshold noch nicht
        // erreicht ist. Der Verifier prüft nur technische Integrität, nicht Business Logic.

        val result = verifier.verify(
            currentSplitState = false, // Full Mode
            allowIntercept = false,    // Korrekt für Full Mode
            canScrollDown = true,      // "Eigentlich" scrollbar
            canScrollUp = true
        )

        // Muss Consistent bleiben! Eine Änderung hier würde die Hoheit des Calculators verletzen.
        assertEquals(VerifyResult.Consistent, result)
    }

    // ========== ULTRA-PARANOID: PARTIAL SCROLL STATES ==========

    @Test
    fun `paranoid - split mode at the very bottom - still consistent`() {
        // User hat ganz nach unten gescrollt.
        // canScrollDown = false, canScrollUp = true.
        // Das darf NICHT als "ReEvaluateNeeded" (beide false) gewertet werden.

        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = false, // Unten angekommen
            canScrollUp = true     // Kann noch hoch
        )

        assertEquals(VerifyResult.Consistent, result)
    }

    @Test
    fun `paranoid - split mode at the very top - still consistent`() {
        // User ist ganz oben.
        // canScrollDown = true, canScrollUp = false.

        val result = verifier.verify(
            currentSplitState = true,
            allowIntercept = true,
            canScrollDown = true,  // Kann runter
            canScrollUp = false    // Oben angekommen
        )

        assertEquals(VerifyResult.Consistent, result)
    }

    // ========== ULTRA-PARANOID: TRUTH TABLE VALIDATION ==========

    @Test
    fun `paranoid - object identity check - singletons are used`() {
        // Sicherstellen, dass wir keine neuen Instanzen erzeugen (Memory),
        // sondern die Singleton-Objects verwenden.

        val result1 = verifier.verify(false, false, false, false)
        val result2 = verifier.verify(false, false, true, true)

        // Referenz-Gleichheit (===) prüfen
        assertTrue("Muss exakt dieselbe Instanz sein", result1 === VerifyResult.Consistent)
        assertTrue("Muss exakt dieselbe Instanz sein", result1 === result2)
    }

    @Test
    fun `paranoid - re-evaluate triggers ONLY when completely stuck`() {
        // ReEvaluateNeeded ist teuer (Layout pass).
        // Es darf NUR passieren, wenn der User WIRKLICH eingesperrt ist (Split Mode aber 0px Bewegung möglich).

        // 1. Fall: Nur unten blockiert -> Consistent
        assertEquals(
            VerifyResult.Consistent,
            verifier.verify(true, true, canScrollDown = false, canScrollUp = true)
        )

        // 2. Fall: Nur oben blockiert -> Consistent
        assertEquals(
            VerifyResult.Consistent,
            verifier.verify(true, true, canScrollDown = true, canScrollUp = false)
        )

        // 3. Fall: Beide blockiert -> RE-EVALUATE
        assertEquals(
            VerifyResult.ReEvaluateNeeded,
            verifier.verify(true, true, canScrollDown = false, canScrollUp = false)
        )
    }
}