package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.launcher.core.AppPresence
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.InstallSessionInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.github.reygnn.launcher.core.KolibriLog
import javax.inject.Inject

class ObserveInstalledAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val favoritesRepository: FavoritesRepository,
    private val swipeActionsRepository: SwipeActionsRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val customNamesRepository: CustomNamesRepository,
    private val appPresence: AppPresence,
    private val installSessions: InstallSessionInspector,
) {

    /**
     * Activates the flow that loads the installed apps, reconciles the
     * component-bound stores, updates the central state, and emits an
     * [AppLoadResult] telling the ViewModel whether a user-visible error occurred.
     *
     * The loader now yields a typed [AppLoad] (INSTALLED_APPS_LOAD_SPEC Belang A):
     * a load failure arrives as [AppLoad.Failed], not as a collapsed empty list.
     * This makes the keep-last-good / error recovery LIVE (it used to sit behind a
     * `.catch`/`.retry` on a `stateIn` StateFlow that never delivers upstream
     * exceptions, so it was dead code). The old `.retry(IOException)` is gone: it
     * never fired in production, and the motivating PackageManager failures are not
     * `IOException` anyway. The `isEmpty()` guard STAYS (IAL-INV-3): reconcile runs
     * only on a non-empty [AppLoad.Loaded], never on a genuinely empty load or the
     * `stateIn` cold-start init.
     */
    operator fun invoke(): Flow<AppLoadResult> = flow {
        try {
            installedAppsRepository.getInstalledApps()
                .collect { load ->
                    try {
                        when (load) {
                            is AppLoad.Failed -> {
                                // Load failed (a distinguishable value, not an empty
                                // list). Keep-last-good now has exactly ONE home
                                // (INSTALLED_APPS_LOAD_SPEC Belang B / IAL-INV-4): the
                                // state holder. Its rawAppsFlow (a StateFlow) already
                                // retains the last emitted list, and its own last-good
                                // cache backs the point-read consumers (swipe/recent),
                                // so a transient failure writes NOTHING here — it just
                                // leaves the last known state in place. Not re-writing
                                // also restores the pre-AppLoad behavior for the
                                // Loaded(empty) → Failed corner, where the Commit-1
                                // updateApps(cachedApps) would wrongly revive a stale
                                // list over a genuinely-empty device.
                                //
                                // Report ONLY when the holder has genuinely never held
                                // apps (a cold start that failed): a glitch recovered
                                // from cache stays silent, so a package settling during
                                // a system update does not flood ACRA (Rule-9). The
                                // loader logs a debug breadcrumb only; this no-cache
                                // branch is the single report site — a genuine
                                // developer-must-act fault (launcher shows no apps), so
                                // it goes through reportToAcra (ACRA_REPORT intent tag,
                                // report-by-intent §23) rather than a bare WARN, which
                                // an untagged log no longer delivers.
                                if (installedAppsStateRepository.getCurrentApps().isEmpty()) {
                                    TimberWrapper.reportToAcra(load.cause, "App load failed and no cache available")
                                    emit(AppLoadResult.Error(AppLoadResult.Failure.NotLoaded))
                                } else {
                                    KolibriLog.d("App load failed; keeping last good list")
                                }
                            }

                            is AppLoad.Loaded -> {
                                val realApps = load.apps
                                if (realApps.isEmpty()) {
                                    KolibriLog.w("Loaded an empty app list. Skipping cleanup to prevent data loss.")
                                    installedAppsStateRepository.updateApps(emptyList())
                                    return@collect
                                }

                                // Reconcile the component-bound stores against the
                                // freshly loaded list. This also covers apps uninstalled
                                // while the process was dead (whose PACKAGE_REMOVED
                                // broadcast the receiver missed) — the sweep runs on the
                                // next load. Each store is guarded independently
                                // (runCleanup) so one failure can't skip the others. The
                                // empty-input guard lives above (realApps.isEmpty()).
                                //
                                // The loaded list is only a removal-CANDIDATE finder, not
                                // ground truth (RECONCILE_FIX_SPEC R-INV-2): each store
                                // reconciles its own assignments against the list and gates
                                // every deletion through AppPresence — a candidate the
                                // check reports present is kept. Candidate-read and delete
                                // are the SAME fail-closed store read inside the repo, so a
                                // partial or transient load cannot prune a still-installed
                                // assignment. Verification runs only on candidates (usually
                                // none), so the steady state costs nothing extra.
                                //
                                // Four separate reconcile calls by design, one per
                                // repository (each owns its keys); runCleanup isolates a
                                // per-store failure so one bad store can't skip the others.
                                val validComponents = realApps.map { it.componentName }
                                val validPackages = realApps.map { it.packageName }
                                // Each store's deletion gate keeps a candidate if EITHER it still
                                // resolves (presence) OR its package has an install/restore session
                                // in flight (Launcher3-style promise). The session set is read at
                                // most ONCE per pass and lazily — only when a presence-absent
                                // candidate actually needs it — and shared across all four stores,
                                // so a complete/all-present load performs zero session IPC (AUDIT-1
                                // F7 review point 5; aligns Kolibri's store reconcile with Nyx's
                                // home reconcile). Component-keyed stores (favorites/swipe/hidden)
                                // bridge their flattened "pkg/class" key via
                                // [isFlatComponentPresentOrRestoring] (parse + gate, malformed →
                                // absent); custom names are package-keyed.
                                val sessionGate = PassSessionGate(installSessions)
                                runCleanup("favorites") {
                                    favoritesRepository.reconcileFavoriteComponents(validComponents) {
                                        appPresence.isFlatComponentPresentOrRestoring(it, sessionGate)
                                    }
                                }
                                runCleanup("swipe actions") {
                                    swipeActionsRepository.reconcileSwipeActions(validComponents) {
                                        appPresence.isFlatComponentPresentOrRestoring(it, sessionGate)
                                    }
                                }
                                runCleanup("hidden components") {
                                    hiddenAppsRepository.reconcileHiddenComponents(validComponents) {
                                        appPresence.isFlatComponentPresentOrRestoring(it, sessionGate)
                                    }
                                }
                                runCleanup("custom names") {
                                    customNamesRepository.reconcileCustomNames(validPackages) {
                                        appPresence.isPackagePresentOrRestoring(it, sessionGate)
                                    }
                                }

                                // Update the central state holder.
                                installedAppsStateRepository.updateApps(realApps)

                                // Signal success (the UI has nothing to do).
                                emit(AppLoadResult.Success)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error processing collected apps")
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error in ObserveInstalledAppsUseCase")
        }
    }

    /**
     * Runs one post-load store reconciliation, isolating its failure so the
     * other stores still run. CancellationException is rethrown (never
     * swallowed) so a cancelled load propagates promptly instead of falling
     * through to the state update and Success emit.
     */
    private suspend fun runCleanup(label: String, cleanup: suspend () -> Unit) {
        try {
            cleanup()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error cleaning up $label")
        }
    }
}

/**
 * The install/restore-session set for one reconcile pass: read at most ONCE, lazily, and only
 * when a presence-absent candidate needs it, then membership-tested per key. Shared across all
 * four store reconciles so a pass performs at most a SINGLE PackageInstaller enumeration no matter
 * how many keys it gates — and a complete/all-present load performs zero (AUDIT-1 F7 review point 5;
 * Kolibri analog of Nyx's ReconcileHomeLayoutUseCase session arm). A `null` from
 * [InstallSessionInspector.activeSessionPackages] means "undetermined" (the query failed) → keep
 * every candidate, mirroring that interface's fail-safe contract.
 */
private class PassSessionGate(private val inspector: InstallSessionInspector) {
    private var packages: Set<String>? = null
    private var read = false

    /** True if [packageName] has an active install/restore session, OR if that is undetermined. */
    suspend fun isRestoring(packageName: String): Boolean {
        if (!read) {
            packages = inspector.activeSessionPackages()
            read = true
        }
        // null (undetermined) → fail-safe keep; otherwise keep iff a session targets the package.
        return packages?.contains(packageName) ?: true
    }
}

/**
 * Component-grain deletion gate for a flattened `"pkg/class"` store key (favorites/swipe/hidden):
 * keep it if the component still resolves ([AppPresence.isComponentPresent]) OR its package has an
 * install/restore session in flight ([PassSessionGate] — the Launcher3-style promise arm, aligned
 * with Nyx's home reconcile). Presence is checked first; the session set is consulted only when
 * presence is absent (`||` short-circuit), so the common path stays at zero session IPC.
 *
 * A malformed stored key (`ComponentKey.parse == null` — e.g. a slash-less bare package) is not a
 * real component → absent, and its (non-existent) package is not consulted for a session either, so
 * the garbage is pruned exactly as before (matches the pre-migration string check). The
 * fail-safe-to-present contract lives in [AppPresence]; the session fail-safe in [PassSessionGate].
 * Extracted once (AUDIT-1 F7 review, point 2: DRY) from the three component-keyed call sites.
 */
private suspend fun AppPresence.isFlatComponentPresentOrRestoring(flat: String, sessions: PassSessionGate): Boolean {
    val key = ComponentKey.parse(flat) ?: return false
    return isComponentPresent(key) || sessions.isRestoring(key.packageName)
}

/**
 * Package-grain deletion gate for a package-keyed store (custom names): keep it if the package
 * still exposes a launcher entry ([AppPresence.isPackagePresent]) OR it has an install/restore
 * session in flight. Presence first; the session set is consulted only when presence is absent.
 */
private suspend fun AppPresence.isPackagePresentOrRestoring(pkg: String, sessions: PassSessionGate): Boolean =
    isPackagePresent(pkg) || sessions.isRestoring(pkg)
