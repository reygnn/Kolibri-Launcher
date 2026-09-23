package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppEnumerator
import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.ReconcileOutcome
import com.github.reygnn.nyx_launcher.home.model.ReconcileResult
import com.github.reygnn.nyx_launcher.home.model.SkipReason
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutReconciler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Reconciles the persisted layout against installed apps. FAIL-CLOSED (RHL-INV-1):
 * the pure reconciler runs ONLY against a genuine, non-empty enumeration — a load
 * failure ([SkipReason.LOAD_FAILED]) or an empty result ([SkipReason.LOAD_EMPTY])
 * returns [ReconcileResult.Skipped] with zero mutation and zero save, so a transient
 * enumeration failure never empties the home screen.
 *
 * FRESHNESS (F5): a one-shot reconcile reads the SHARED [AppEnumerator] directly, NOT
 * the cached loader `Flow<AppLoad>`. The loader is a `WhileSubscribed` `StateFlow`
 * that replays a possibly-stale cached list within its sharing window, so priming it
 * (`first { … }`) could reconcile a package-removal against the pre-removal list and
 * defer the prune. `enumerate()` is a direct suspend read of the CURRENT launchable
 * set (via the same shared LauncherApps seam, SIA-INV-4), so the reconcile always
 * prunes against the fresh list — deterministically, with no StateFlow race and no
 * prime timeout. It throws on a real enumeration failure (→ LOAD_FAILED) and rethrows
 * cancellation; an empty result is the value-honest LOAD_EMPTY skip (§9.2), which
 * subsumes Nyx's former `ENUMERATION_EMPTY`.
 *
 * The drawer keeps reading the cached loader (fast quick-reopen replay); only the
 * fail-closed reconcile needs the always-fresh read.
 */
class ReconcileHomeLayoutUseCase @Inject constructor(
    private val layoutRepository: HomeLayoutRepository,
    private val enumerator: AppEnumerator,
    private val idFactory: ItemIdFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): ReconcileResult = withContext(dispatcher) {
        val apps = try {
            enumerator.enumerate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fail-closed: an enumeration failure never mutates the layout (RHL-INV-1).
            return@withContext ReconcileResult.Skipped(SkipReason.LOAD_FAILED)
        }
        if (apps.isEmpty()) {
            // Fail-closed: an empty/partial load is impossible on a real device (>= 1
            // launchable app), so it is treated as suspicious and skipped, never a
            // signal to prune the whole home screen (RHL-INV-1).
            return@withContext ReconcileResult.Skipped(SkipReason.LOAD_EMPTY)
        }
        val installed = apps.mapTo(HashSet()) { it.key }
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
