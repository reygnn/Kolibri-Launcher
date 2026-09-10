package com.github.reygnn.nyx_launcher.home.model

/** What a reconcile pass changed — for observability (no silent prune, RHL-INV-5). */
data class ReconcileReport(
    val prunedApps: Int,
    val dedupedApps: Int,
    val dissolvedFolders: Int,
    val removedEmptyFolders: Int,
    val trimmedPages: Int,
    val dockTrimmed: Int,
)

/** Pure-policy output of the reconciler. */
sealed interface ReconcileOutcome {
    data class Changed(val layout: HomeLayout, val report: ReconcileReport) : ReconcileOutcome
    data object Unchanged : ReconcileOutcome
}

/** Use-case output, including the fail-closed [Skipped] case (RHL-INV-1). */
sealed interface ReconcileResult {
    data class Reconciled(val report: ReconcileReport) : ReconcileResult
    data object Unchanged : ReconcileResult
    data class Skipped(val reason: AppLoadResult.Reason) : ReconcileResult
}
