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
     * `enumerator.enumerate()` threw (a non-cancellation `Throwable`): the shared
     * enumeration failed. `ReconcileHomeLayoutUseCase` maps that throw 1:1 to this
     * reason and skips with zero mutation (fail-closed, RHL-INV-1).
     */
    LOAD_FAILED,

    /**
     * `enumerator.enumerate()` returned an empty list — no usable app list (a
     * genuinely-empty device, or a transient empty enumeration). Skipped with zero
     * mutation as suspicious (fail-closed, RHL-INV-1).
     *
     * `ReconcileHomeLayoutUseCase` reads the shared `AppEnumerator` DIRECTLY — a
     * one-shot suspend read of the CURRENT launchable set, NOT the cached
     * `StateFlow<AppLoad>` loader — so an empty result here is a *settled* empty:
     * there is no prime window and no `Loaded(emptyList())` initial value to
     * confuse it with (this replaces the historical `ENUMERATION_EMPTY` and the
     * old prime-window reasoning). Both reasons are observability-only (no consumer
     * branches on them); the invariant that matters — skip with zero mutation on
     * anything that is not a genuine non-empty load — holds regardless (RHL-INV-1).
     */
    LOAD_EMPTY,

    /**
     * The layout STORE could not be read or written during the pass: the fail-closed
     * candidate read (`HomeLayoutRepository.snapshot()`) or the atomic read-modify-write
     * (`update`) threw a non-cancellation `Throwable` (a transient DataStore IOException).
     * `ReconcileHomeLayoutUseCase` catches it and skips with zero mutation — the same
     * value-honest fail-closed posture as [LOAD_FAILED], just for the store side rather
     * than the enumeration side (RHL-INV-1). Kept distinct from [LOAD_FAILED] for
     * observability: a store fault and an enumeration fault have different root causes.
     * Observability-only, like the others (no consumer branches on it).
     */
    STORE_FAILED,
}

/** Use-case output, including the fail-closed [Skipped] case (RHL-INV-1). */
sealed interface ReconcileResult {
    data class Reconciled(val report: ReconcileReport) : ReconcileResult
    data object Unchanged : ReconcileResult
    data class Skipped(val reason: SkipReason) : ReconcileResult
}
