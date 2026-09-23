package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.ReconcileOutcome
import com.github.reygnn.nyx_launcher.home.model.ReconcileResult
import com.github.reygnn.nyx_launcher.home.model.SkipReason
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutReconciler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Reconciles the persisted layout against installed apps. FAIL-CLOSED (RHL-INV-1):
 * the pure reconciler runs ONLY against a genuine, non-empty load; anything else —
 * an explicit [AppLoad.Failed], or no non-empty list within the prime window —
 * returns [ReconcileResult.Skipped] with zero mutation and zero save, so a
 * transient enumeration failure never empties the home screen.
 *
 * MIGRATION (SHARED_INSTALLED_APPS_SPEC §2 / §9.2): reads the shared reactive
 * `Flow<AppLoad>` in place of Nyx's deleted one-shot `AppLoadResult`. The shared
 * envelope is *value-honest* — `Loaded(emptyList())` is legitimate, not a failure —
 * so the "empty ⇒ suspicious" rule that used to live in `ENUMERATION_EMPTY` now
 * lives HERE as Nyx's Klasse-B reconcile policy: an empty (or never-arriving) list
 * is a [SkipReason.LOAD_EMPTY] skip, exactly as it used to skip on the old
 * `ENUMERATION_EMPTY`. The prime predicate waits for the first `Failed` or the
 * first *non-empty* `Loaded`, because the shared `StateFlow` is seeded with a
 * conflated initial `Loaded(emptyList())` (see [SkipReason.LOAD_EMPTY]).
 */
class ReconcileHomeLayoutUseCase @Inject constructor(
    private val layoutRepository: HomeLayoutRepository,
    private val appsRepository: InstalledAppsRepository,
    private val idFactory: ItemIdFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): ReconcileResult = withContext(dispatcher) {
        // [F3] Reads the shared LOADER directly, not the shared holder — Nyx keeps
        // no in-RAM app list of its own; each reconcile re-primes the loader.
        // First settled signal: an explicit failure, or a genuine non-empty load.
        // A conflated initial `Loaded(empty)`, a persistent `Failed`, or a stuck
        // load all fall through to the timeout (LOAD_EMPTY) — all fail-closed.
        val settled: AppLoad? = withTimeoutOrNull(AppConstants.INSTALLED_APPS_PRIME_TIMEOUT_MS) {
            appsRepository.getInstalledApps()
                .first { it is AppLoad.Failed || (it is AppLoad.Loaded && it.apps.isNotEmpty()) }
        }

        when (settled) {
            is AppLoad.Failed -> ReconcileResult.Skipped(SkipReason.LOAD_FAILED)
            null -> ReconcileResult.Skipped(SkipReason.LOAD_EMPTY)
            is AppLoad.Loaded -> {
                // `settled` is non-empty here (the predicate guarantees it).
                val installed = settled.apps.mapTo(HashSet()) { it.key }
                var result: ReconcileResult = ReconcileResult.Unchanged
                layoutRepository.update { current -> // atomic RMW (A1-03)
                    when (val outcome = HomeLayoutReconciler.reconcile(current, installed, idFactory::next)) {
                        ReconcileOutcome.Unchanged -> null
                        is ReconcileOutcome.Changed -> {
                            result = ReconcileResult.Reconciled(outcome.report)
                            outcome.layout
                        }
                    }
                }
                result
            }
        }
    }
}
