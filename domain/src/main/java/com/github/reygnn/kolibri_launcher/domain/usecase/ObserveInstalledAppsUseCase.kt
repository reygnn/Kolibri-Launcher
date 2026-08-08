package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.service.PackagePresence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.github.reygnn.kolibri_launcher.core.KolibriLog
import javax.inject.Inject

class ObserveInstalledAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val favoritesRepository: FavoritesRepository,
    private val swipeActionsRepository: SwipeActionsRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val customNamesRepository: CustomNamesRepository,
    private val packagePresence: PackagePresence
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
                                // Load failed (distinguishable value, not an empty
                                // list). Keep the last good list. This recovery was
                                // dead before AppLoad (unreachable behind a StateFlow
                                // .catch) and is now reachable via the typed Failed.
                                //
                                // Report ONLY when recovery is impossible (no cache):
                                // a glitch fully recovered from cache stays silent, so
                                // a package settling during a system update does not
                                // flood ACRA (INSTALLED_APPS_LOAD_SPEC Rule-9). The
                                // loader logs a debug breadcrumb only; this no-cache
                                // branch is the single report site.
                                val cachedApps = installedAppsStateRepository.getCurrentApps()
                                if (cachedApps.isNotEmpty()) {
                                    KolibriLog.d("App load failed; using ${cachedApps.size} cached apps")
                                    installedAppsStateRepository.updateApps(cachedApps)
                                } else {
                                    KolibriLog.w(load.cause, "App load failed and no cache available")
                                    installedAppsStateRepository.updateApps(emptyList())
                                    emit(AppLoadResult.Error(AppLoadResult.Failure.NotLoaded))
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
                                // every deletion through PackagePresence — a candidate the
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
                                runCleanup("favorites") {
                                    favoritesRepository.reconcileFavoriteComponents(validComponents) {
                                        packagePresence.isComponentPresent(it)
                                    }
                                }
                                runCleanup("swipe actions") {
                                    swipeActionsRepository.reconcileSwipeActions(validComponents) {
                                        packagePresence.isComponentPresent(it)
                                    }
                                }
                                runCleanup("hidden components") {
                                    hiddenAppsRepository.reconcileHiddenComponents(validComponents) {
                                        packagePresence.isComponentPresent(it)
                                    }
                                }
                                runCleanup("custom names") {
                                    customNamesRepository.reconcileCustomNames(validPackages) {
                                        packagePresence.isPackagePresent(it)
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
