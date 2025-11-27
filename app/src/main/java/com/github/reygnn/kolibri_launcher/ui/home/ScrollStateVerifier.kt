package com.github.reygnn.kolibri_launcher.ui.home

/**
 * Verifiziert und korrigiert Inkonsistenzen zwischen Split-State und ScrollView.
 *
 * SICHERHEITSKRITISCH: Stellt sicher, dass der User nie "eingesperrt" wird.
 */
class ScrollStateVerifier {

    /**
     * Prüft auf Inkonsistenzen und gibt notwendige Korrekturen zurück.
     *
     * @param currentSplitState Aktueller Split-Mode Status
     * @param allowIntercept ScrollView's allowIntercept Flag
     * @param canScrollDown Kann nach unten gescrollt werden?
     * @param canScrollUp Kann nach oben gescrollt werden?
     * @return Korrektur-Aktion die ausgeführt werden soll
     */
    fun verify(
        currentSplitState: Boolean,
        allowIntercept: Boolean,
        canScrollDown: Boolean,
        canScrollUp: Boolean
    ): VerifyResult {

        // Fall 1: Full-Mode aber Intercept aktiv → Inkonsistenz!
        if (!currentSplitState && allowIntercept) {
            return VerifyResult.FixFullMode
        }

        // Fall 2: Split-Mode aber Intercept deaktiviert → Inkonsistenz!
        if (currentSplitState && !allowIntercept) {
            return VerifyResult.FixSplitMode
        }

        // Fall 3: Split-Mode aktiv aber kein Scroll möglich → Re-evaluate!
        if (currentSplitState && !canScrollDown && !canScrollUp) {
            return VerifyResult.ReEvaluateNeeded
        }

        // Alles konsistent
        return VerifyResult.Consistent
    }

    sealed class VerifyResult {
        /** Alles in Ordnung, keine Aktion nötig */
        object Consistent : VerifyResult()

        /** Full-Mode Inkonsistenz: allowIntercept=false setzen, scroll reset */
        object FixFullMode : VerifyResult()

        /** Split-Mode Inkonsistenz: allowIntercept=true setzen */
        object FixSplitMode : VerifyResult()

        /** Split-Mode aktiv aber Scroll unmöglich: State neu berechnen */
        object ReEvaluateNeeded : VerifyResult()
    }
}