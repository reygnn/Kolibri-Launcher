package com.github.reygnn.kolibri_launcher.ui.home

/**
 * Represents the required corrective action to restore state integrity.
 */
sealed interface VerifyResult {
    /** State is healthy. No action needed. */
    data object Consistent : VerifyResult

    /** CORRECTION: Force `allowIntercept = false`. Ensures Full Mode. */
    data object FixFullMode : VerifyResult

    /** CORRECTION: Force `allowIntercept = true`. Ensures Split Mode. */
    data object FixSplitMode : VerifyResult

    /** CORRECTION: State might be obsolete. Request fresh calculation. */
    data object ReEvaluateNeeded : VerifyResult
}