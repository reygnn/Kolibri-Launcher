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
        // Logic says "Full Mode" (Static), but the View is trying to intercept touches.
        // DANGER: This makes the UI feel broken/unresponsive. Must disable intercept immediately.
        if (!currentSplitState && allowIntercept) {
            return VerifyResult.FixFullMode
        }

        // CASE 2: Split-Mode Integrity Violation
        // Logic says "Split Mode" (Scrollable), but the View has disabled intercept.
        // CRITICAL DANGER: User cannot reach their apps. This is a "Jail" state.
        // Must enable intercept immediately to free the user.
        if (currentSplitState && !allowIntercept) {
            return VerifyResult.FixSplitMode
        }

        // CASE 3: Stale State Detection (Re-Evaluation)
        // Logic says "Split Mode", inputs are correct, BUT the View reports it cannot move anywhere.
        // Causes: Content was removed, screen rotated, or layout changed.
        // Action: Trigger a full re-calculation via SplitModeCalculator.
        if (currentSplitState && !canScrollDown && !canScrollUp) {
            return VerifyResult.ReEvaluateNeeded
        }

        // CASE 4: Consistent State
        // All flags are aligned with the logical state. No action required.
        return VerifyResult.Consistent
    }

    /**
     * Represents the required corrective action to restore state integrity.
     */
    sealed class VerifyResult {
        /**
         * State is healthy. No action needed.
         */
        object Consistent : VerifyResult()

        /**
         * CORRECTION: Force `allowIntercept = false`.
         * Ensures the view behaves statically (Full Mode).
         */
        object FixFullMode : VerifyResult()

        /**
         * CORRECTION: Force `allowIntercept = true`.
         * Ensures the view accepts touch events (Split Mode).
         */
        object FixSplitMode : VerifyResult()

        /**
         * CORRECTION: The current state might be obsolete.
         * The UI should ask `SplitModeCalculator` for a fresh decision.
         */
        object ReEvaluateNeeded : VerifyResult()
    }
}