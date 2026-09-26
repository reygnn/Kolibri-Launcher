package com.github.reygnn.nyx_launcher.home.model

/**
 * What a reconcile pass changed — for observability (no silent prune, RHL-INV-5).
 * The reconcile is structural-only, so there is no prune/dock-trim count (those were
 * always zero and are dropped).
 */
data class ReconcileReport(
    val dedupedApps: Int,
    val dissolvedFolders: Int,
    val removedEmptyFolders: Int,
    val trimmedPages: Int,
)

/** Pure-policy output of the reconciler. */
sealed interface ReconcileOutcome {
    data class Changed(val layout: HomeLayout, val report: ReconcileReport) : ReconcileOutcome
    data object Unchanged : ReconcileOutcome
}

/**
 * Why a reconcile pass was skipped without touching the layout (fail-closed,
 * RHL-INV-1). Since the no-prune rebuild (root TODO.md) the reconcile is structural
 * only and no longer enumerates apps, so the former enumeration reasons
 * (`LOAD_FAILED` / `LOAD_EMPTY`) are gone; the only skip left is a store fault.
 */
enum class SkipReason {
    /**
     * The layout STORE could not be read or written during the atomic read-modify-write
     * (`update` threw a non-cancellation `Throwable` — a transient DataStore IOException).
     * `ReconcileHomeLayoutUseCase` catches it and skips with zero mutation, keeping the
     * value-honest fail-closed posture (RHL-INV-1). Observability-only (no consumer
     * branches on it).
     */
    STORE_FAILED,
}

/** Use-case output, including the fail-closed [Skipped] case (RHL-INV-1). */
sealed interface ReconcileResult {
    data class Reconciled(val report: ReconcileReport) : ReconcileResult
    data object Unchanged : ReconcileResult
    data class Skipped(val reason: SkipReason) : ReconcileResult
}
