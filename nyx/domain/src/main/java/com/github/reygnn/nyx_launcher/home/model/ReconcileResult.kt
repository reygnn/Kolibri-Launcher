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

/**
 * Why a reconcile pass was skipped without touching the layout (fail-closed,
 * RHL-INV-1). Nyx-local, replacing the deleted `AppLoadResult.Reason`: the shared
 * envelope (`com.github.reygnn.launcher.core.AppLoad`) is value-honest and carries
 * no reason enum (§9.2), so Nyx derives its own coarse skip reason at the reconcile
 * edge — this is exactly the per-app reconcile policy the shared spec keeps Klasse B.
 */
enum class SkipReason {
    /**
     * An explicit `AppLoad.Failed` was observed within the prime window: the
     * shared loader caught a load error. Crisp — mapped 1:1 from the envelope.
     */
    LOAD_FAILED,

    /**
     * The prime window elapsed without ever observing a non-empty `Loaded` and
     * without an explicit `Failed` — no usable app list materialised (a
     * genuinely-empty device, or a load that stayed empty/stuck).
     *
     * NOTE — semantic shift from the deleted `AppLoadResult.Reason`: Nyx's old
     * one-shot repository could return a crisp terminal `Loaded(empty)`. The
     * shared contract is a hot `StateFlow<AppLoad>` whose *initial* value is
     * itself `Loaded(emptyList())`, so a one-shot reconcile call cannot separate
     * "settled empty" from "initial/priming empty". This reason therefore means
     * "no non-empty list within the window", which subsumes the historical
     * `ENUMERATION_EMPTY`. Both reasons are observability-only (no consumer
     * branches on them); the invariant that matters — skip with zero mutation on
     * anything that is not a genuine non-empty load — holds either way (RHL-INV-1).
     */
    LOAD_EMPTY,
}

/** Use-case output, including the fail-closed [Skipped] case (RHL-INV-1). */
sealed interface ReconcileResult {
    data class Reconciled(val report: ReconcileReport) : ReconcileResult
    data object Unchanged : ReconcileResult
    data class Skipped(val reason: SkipReason) : ReconcileResult
}
