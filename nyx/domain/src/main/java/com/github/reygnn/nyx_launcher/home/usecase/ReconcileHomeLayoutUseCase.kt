package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppEnumerator
import com.github.reygnn.launcher.core.AppPresence
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.launcher.core.InstallSessionInspector
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
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
 *
 * PARTIAL-SNAPSHOT GATE (RHL-INV-6, AUDIT-1 F7): the throw/empty gates above are
 * all-or-nothing — a non-empty but INCOMPLETE enumeration (a transient short
 * `getActivityList` mid-restore / early post-unlock, or a per-item drop in the
 * enumerator) passes both and would let the reconciler prune every app missing from
 * the partial set, permanently. So a layout key absent from the snapshot is only a
 * *candidate* for pruning, kept if EITHER of two independent signals says so:
 * [AppPresence] (cross-surface PackageManager, so a LauncherApps transient can't poison
 * it) OR [InstallSessionInspector] (a package mid-install/restore — Launcher3-style
 * promise). Both fail-safe toward keeping; a confirmed-present or being-restored key is
 * folded back into the installed set so the pure reconciler never drops it. Nyx analog of
 * Kolibri's per-target deletion gate (RECONCILE_FIX_SPEC R-INV-2): bulk snapshot proposes,
 * single-target checks dispose. A COMPLETE snapshot yields zero candidates and performs
 * zero gate IPC — the common path is unchanged in cost and behavior. One residual case is
 * accepted (a restore with no discoverable session); see nyx ACCEPTED_LIMITATIONS.md.
 */
class ReconcileHomeLayoutUseCase @Inject constructor(
    private val layoutRepository: HomeLayoutRepository,
    private val enumerator: AppEnumerator,
    private val appPresence: AppPresence,
    private val installSessions: InstallSessionInspector,
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
        // each against an independent presence check (fail-safe → present) and fold the
        // still-installed ones back into `installed`, so the pure reconciler never prunes a
        // merely-transiently-absent app. The candidate read is fail-CLOSED ([snapshot], not
        // the fail-open [layout] flow): a transient read error aborts this pass as a
        // value-honest [SkipReason.STORE_FAILED] skip (see the catch below) — never a throw and
        // never a degrade to an empty layout that drops every protection. It runs outside the atomic
        // RMW below, which is safe because it only ever ADDS protection (it can reduce
        // pruning, never cause a bad write) and the reconcile still runs against the fresh
        // `current` inside the lock (A1-03 preserved). A complete snapshot → no candidates
        // → no gate IPC (neither arm consulted).
        try {
            val candidates = layoutRepository.snapshot()
                .referencedKeys()
                .filterTo(HashSet()) { it !in installed }
            for (key in candidates) {
                // Keep the key if EITHER it still resolves independently (cross-surface
                // PackageManager presence — a LauncherApps enumeration transient can't poison it)
                // OR its package has an install/restore session in flight (Launcher3-style promise:
                // an app on its way back during restore is legitimately absent right now but must
                // not be pruned). Only a key that is BOTH absent AND session-less is pruned.
                // Short-circuit: presence first (one cheap package query), session only if absent.
                if (appPresence.isComponentPresent(key) || installSessions.hasActiveSession(key.packageName)) {
                    installed.add(key)
                }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fail-closed store I/O (RHL-INV-1), reported the SAME way as an enumeration
            // failure: a value, not a throw. Both the fail-closed candidate read ([snapshot])
            // and the atomic RMW ([update]) read the raw DataStore and propagate an IOException
            // on a transient error; either one skips the pass with zero mutation rather than
            // degrading to an empty layout and pruning. Keeping this on the value channel makes
            // invoke() total (only CancellationException escapes), so every caller —
            // PackageEventCoordinator and ImportLayoutUseCase — is correct without its own guard.
            // (The gate arms themselves never land here: AppPresence / InstallSessionInspector are
            // fail-safe-to-keep and swallow their own platform errors, rethrowing only cancellation.)
            TimberWrapper.silentError(e, "Reconcile store read/write failed; skipping pass without pruning")
            ReconcileResult.Skipped(SkipReason.STORE_FAILED)
        }
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
