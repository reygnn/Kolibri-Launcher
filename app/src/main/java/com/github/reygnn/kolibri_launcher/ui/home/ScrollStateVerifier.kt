package com.github.reygnn.kolibri_launcher.ui.home

/**
 * INTEGRITY CHECKER - Scroll State Verification
 *
 * This class acts as the "Police Force" for the scrolling logic. It verifies that
 * the logical state (Split vs. Full) matches the technical reality of the Views
 * (Intercept flags, Scroll capability).
 *
 * SAFETY-CRITICAL:
 * Its primary job is to ensure the user is never "trapped" in a state where:
 * 1. They need to scroll, but touch events are intercepted (Locked).
 * 2. They shouldn't scroll, but the UI jitters (Loose).
 * 3. The state is technically valid, but the content has changed (e.g., items removed).
 */
class ScrollStateVerifier {

    /**
     * Inspects the current state for inconsistencies and recommends corrective actions.
     *
     * It enforces a strict hierarchy of truth:
     * - Priority 1: Fix obvious technical contradictions (e.g., Full Mode but Intercept is ON).
     * - Priority 2: Detect "Trapped" states (Split Mode active, but user hit a wall).
     *
     * @param currentSplitState The logical state currently stored in the ViewModel.
     * @param allowIntercept The actual `allowIntercept` flag from the ScrollView.
     * @param canScrollDown Hardware capability to scroll down.
     * @param canScrollUp Hardware capability to scroll up.
     *
     * @return A [VerifyResult] instruction to be executed by the UI layer.
     */
    fun verify(
        currentSplitState: Boolean,
        allowIntercept: Boolean,
        canScrollDown: Boolean,
        canScrollUp: Boolean
    ): VerifyResult {

        // CASE 1: Full-Mode Integrity Violation
        if (!currentSplitState && allowIntercept) {
            return VerifyResult.FixFullMode
        }

        // CASE 2: Split-Mode Integrity Violation
        if (currentSplitState && !allowIntercept) {
            return VerifyResult.FixSplitMode
        }

        // CASE 3: Stale State Detection (Re-Evaluation)
        if (currentSplitState && !canScrollDown && !canScrollUp) {
            return VerifyResult.ReEvaluateNeeded
        }

        // CASE 4: Consistent State
        return VerifyResult.Consistent
    }
}

