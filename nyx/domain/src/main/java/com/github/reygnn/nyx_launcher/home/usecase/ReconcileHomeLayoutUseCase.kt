package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppEnumerator
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.ReconcileOutcome
import com.github.reygnn.nyx_launcher.home.model.ReconcileResult
import com.github.reygnn.nyx_launcher.home.model.SkipReason
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.service.AppPresence
import com.github.reygnn.nyx_launcher.home.transition.HomeLayoutReconciler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
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
 *
 * PARTIAL-SNAPSHOT GATE (RHL-INV-6, AUDIT-1 F7): the throw/empty gates above are
 * all-or-nothing — a non-empty but INCOMPLETE enumeration (a transient short
 * `getActivityList` mid-restore / early post-unlock, or a per-item drop in the
 * enumerator) passes both and would let the reconciler prune every app missing from
 * the partial set, permanently. So a layout key absent from the snapshot is only a
 * *candidate* for pruning: each candidate is re-confirmed through [AppPresence]
 * (which fail-safes to "present") and, if still installed, folded back into the
 * installed set so the pure reconciler never drops it. This is the Nyx analog of
 * Kolibri's per-target deletion gate (RECONCILE_FIX_SPEC R-INV-2): bulk snapshot
 * proposes, single-target check disposes. A COMPLETE snapshot yields zero candidates
 * and performs zero presence IPC — the common path is unchanged in cost and behavior.
 */
class ReconcileHomeLayoutUseCase @Inject constructor(
    private val layoutRepository: HomeLayoutRepository,
    private val enumerator: AppEnumerator,
    private val appPresence: AppPresence,
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
            // Fail-closed: an EMPTY load (zero launchable apps — impossible on a real
            // device) is treated as suspicious and skipped, never a signal to prune the
            // whole home screen (RHL-INV-1). NOTE: this guard catches ONLY the fully-empty
            // case. A non-empty-but-PARTIAL load is NOT stopped here — it is handled by
            // the per-candidate presence gate below (RHL-INV-6). (Former comment claimed
            // "empty/partial … skipped" here; the partial half was never implemented — F7.)
            return@withContext ReconcileResult.Skipped(SkipReason.LOAD_EMPTY)
        }
        val installed = apps.mapTo(HashSet()) { it.key }

        // PARTIAL-SNAPSHOT GATE (RHL-INV-6, Nyx analog of R-INV-2). Every key the layout
        // references but this snapshot missed is only a *candidate* for pruning; confirm
        // each against the live LauncherApps seam (fail-safe → present) and fold the
        // still-installed ones back into `installed`, so the pure reconciler never prunes a
        // merely-transiently-absent app. Reading the layout once here is outside the atomic
        // RMW below; that is safe because this only ever ADDS protection (it can reduce
        // pruning, never cause a bad write), and the reconcile still runs against the fresh
        // `current` inside the lock (A1-03 preserved). A complete snapshot → no candidates
        // → no presence IPC.
        val candidates = layoutRepository.layout().first()
            .referencedKeys()
            .filterTo(HashSet()) { it !in installed }
        for (key in candidates) {
            if (appPresence.isPresent(key)) installed.add(key)
        }

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

/**
 * Every [ComponentKey] the layout references, across BOTH scopes the reconciler prunes
 * (RHL-INV-4): top-level app tiles + dock apps, plus all folder members (grid and dock
 * folders). These are exactly the keys the reconciler can drop, so they are exactly the
 * set the presence gate must be able to protect (RHL-INV-6). Folder members are included
 * because `HomeLayoutReconciler.pruneMembers` prunes them the same way as top-level apps.
 */
private fun HomeLayout.referencedKeys(): Set<ComponentKey> {
    val keys = HashSet<ComponentKey>()
    fun collect(item: HomeItem) {
        when (item) {
            is HomeItem.App -> keys.add(item.key)
            is HomeItem.Folder -> keys.addAll(item.members)
        }
    }
    items.forEach { collect(it.item) }
    dock.forEach { collect(it) }
    return keys
}
