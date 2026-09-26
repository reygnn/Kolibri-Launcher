package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
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
 * Structurally repairs the persisted home layout. STRUCTURAL ONLY — it does NOT
 * prune (Windows-shortcut model, root TODO.md): a tile whose app is no longer
 * installed is KEPT and surfaced/removed lazily in the UI, never auto-dropped. So
 * this use case reads the layout and runs the pure [HomeLayoutReconciler]
 * (dedup / folder-repair / trailing-page-trim) inside an atomic read-modify-write;
 * it needs no app enumeration and no presence gate any more. The whole deletion-gate
 * apparatus (AppEnumerator diff, AppPresence, InstallSessionInspector,
 * DeletionGatePass, the fail-closed snapshot read, LOAD_FAILED / LOAD_EMPTY) is gone
 * with the prune — there is nothing to silently lose, so nothing to fail-safe against.
 *
 * STORE-SIDE FAIL-CLOSED (RHL-INV-1): the atomic RMW ([HomeLayoutRepository.update])
 * propagates a transient DataStore IOException rather than degrading to an empty
 * layout. [invoke] catches it and returns [ReconcileResult.Skipped]
 * ([SkipReason.STORE_FAILED]) with zero mutation, so [invoke] stays TOTAL — only
 * [CancellationException] escapes. Callers (PackageEventCoordinator; NyxBackupManager
 * after a restore) therefore need no fault handling of their own.
 *
 * The structural passes are idempotent (RHL-INV-2), so running this on a package
 * add/remove event is a harmless no-op unless the stored layout actually carries a
 * structural inconsistency (only ever introduced by an edit/import) — the trigger is
 * kept for that import/edit-cleanup path.
 */
class ReconcileHomeLayoutUseCase @Inject constructor(
    private val layoutRepository: HomeLayoutRepository,
    private val idFactory: ItemIdFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): ReconcileResult = withContext(dispatcher) {
        try {
            var result: ReconcileResult = ReconcileResult.Unchanged
            layoutRepository.update { current -> // atomic RMW (A1-03)
                when (val outcome = HomeLayoutReconciler.reconcile(current, idFactory::next)) {
                    ReconcileOutcome.Unchanged -> null
                    is ReconcileOutcome.Changed -> {
                        result = ReconcileResult.Reconciled(outcome.report)
                        outcome.layout
                    }
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fail-closed store I/O (RHL-INV-1), reported as a VALUE not a throw: the
            // atomic RMW reads the raw DataStore and propagates an IOException on a
            // transient error; this skips the pass with zero mutation rather than
            // degrading to an empty layout. Keeping it on the value channel makes
            // invoke() total (only CancellationException escapes), so every caller is
            // correct without its own guard. reportToAcra (not silentError): a transient
            // DataStore I/O error is environmental (self-heals next pass), and
            // silentError's DEBUG throw would break the "only CancellationException
            // escapes" totality in a DEBUG on-device build (DSR-INV-3).
            TimberWrapper.reportToAcra(e, "Reconcile store read/write failed; skipping structural pass")
            ReconcileResult.Skipped(SkipReason.STORE_FAILED)
        }
    }
}
