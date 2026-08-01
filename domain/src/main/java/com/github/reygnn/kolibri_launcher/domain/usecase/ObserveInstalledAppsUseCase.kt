package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.service.PackagePresence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import com.github.reygnn.kolibri_launcher.core.KolibriLog
import java.io.IOException
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
     * Aktiviert den Flow, der die installierten Apps lädt, verarbeitet und den State aktualisiert.
     * Gibt ein 'AppLoadResult' aus, das dem ViewModel signalisiert, ob ein Fehler
     * aufgetreten ist, der dem Benutzer angezeigt werden muss.
     *
     * The retry counter is a local var per flow invocation. Earlier the
     * counter was a class field that only reset on success — after a fully
     * failed invocation it stayed at MAX_APP_LOAD_RETRIES, so the next
     * invocation's first retry computed `delay = base * (count+1)` instead
     * of `base * 1`. Local var also removes a latent race if two consumers
     * ever collected this UseCase concurrently.
     */
    operator fun invoke(): Flow<AppLoadResult> = flow {
        var retryCount = 0

        try {
            // Die gesamte Logik aus 'observeInstalledApps' ist jetzt HIER
            installedAppsRepository.getInstalledApps()
                .retry(AppConstants.MAX_APP_LOAD_RETRIES.toLong()) { cause ->
                    try {
                        if (cause is IOException) {
                            retryCount++
                            KolibriLog.w("App loading failed, retry $retryCount/${AppConstants.MAX_APP_LOAD_RETRIES}")
                            delay(AppConstants.APP_LOAD_RETRY_BASE_DELAY_MS * retryCount)
                            true
                        } else {
                            false
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in retry logic")
                        false
                    }
                }
                .catch { e ->
                    // Dies fängt Fehler im Upstream-Flow (getInstalledApps, retry)
                    TimberWrapper.silentError(e, "Failed to collect installed apps")

                    // Fallback-Logik
                    val cachedApps = installedAppsStateRepository.getCurrentApps()
                    if (cachedApps.isNotEmpty()) {
                        KolibriLog.w("Using cached apps as fallback (${cachedApps.size} apps)")
                        installedAppsStateRepository.updateApps(cachedApps)
                        // Sende KEIN Fehler-Event, da wir einen Cache haben
                    } else {
                        installedAppsStateRepository.updateApps(emptyList())
                        // Nur einen Fehler senden, wenn der Cache auch leer ist
                        emit(AppLoadResult.Error(AppLoadResult.Failure.NotLoaded))
                    }
                }
                .collect { realApps ->
                    // Die gesamte Verarbeitungslogik
                    try {
                        if (realApps.isEmpty()) {
                            KolibriLog.w("Collected an empty app list. Skipping cleanup to prevent data loss.")
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
                        // ground truth (RECONCILE_SPEC §3, R-INV): an assignment
                        // absent from it is verified through PackagePresence before
                        // being dropped, and a still-present target is added back
                        // into the effective valid set (a "veto"). This is what
                        // makes a partial or transient load unable to prune a
                        // still-installed app. Verification runs only on candidates
                        // (usually none), so the steady state costs nothing beyond
                        // the existing intersect.
                        //
                        // Still four separate cleanup calls by design, one per
                        // repository: each store owns its keys behind its own
                        // interface; merging the writes would break that boundary.
                        val validComponents = realApps.map { it.componentName }
                        val validPackages = realApps.map { it.packageName }
                        val effectiveValidComponents =
                            validComponents + vetoedComponents(validComponents.toHashSet())
                        val effectiveValidPackages =
                            validPackages + vetoedPackages(validPackages.toHashSet())
                        runCleanup("favorites") {
                            favoritesRepository.cleanupFavoriteComponents(effectiveValidComponents)
                        }
                        runCleanup("swipe actions") {
                            swipeActionsRepository.cleanupSwipeActions(effectiveValidComponents)
                        }
                        runCleanup("hidden components") {
                            hiddenAppsRepository.cleanupHiddenComponents(effectiveValidComponents)
                        }
                        runCleanup("custom names") {
                            customNamesRepository.cleanupCustomNames(effectiveValidPackages)
                        }

                        // Wichtig: Den zentralen State aktualisieren
                        installedAppsStateRepository.updateApps(realApps)
                        // Reset the linear-backoff counter so a later mid-stream
                        // failure restarts the delay sequence at base * 1.
                        retryCount = 0

                        // Signalisiere Erfolg (das UI muss nichts tun)
                        emit(AppLoadResult.Success)

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
    /**
     * Component-keyed assignments (favorites + swipe + hidden) that are absent
     * from [validComponents] — i.e. removal candidates — but that
     * [PackagePresence] still reports present. These are VETOED: added back to
     * the effective valid set so cleanup keeps them. A partial load thus cannot
     * prune a still-installed app; a genuinely-gone one still fails the check
     * and is removed. CancellationException propagates from the reads/checks.
     */
    private suspend fun vetoedComponents(validComponents: Set<String>): Set<String> {
        val assigned = HashSet<String>()
        assigned += favoritesRepository.favoriteComponentsFlow.first()
        assigned += hiddenAppsRepository.hiddenAppsFlow.first()
        swipeActionsRepository.swipeLeftAppFlow.first()?.let { assigned += it }
        swipeActionsRepository.swipeRightAppFlow.first()?.let { assigned += it }

        val candidates = assigned - validComponents
        return candidates.filterTo(HashSet()) { packagePresence.isComponentPresent(it) }
    }

    /**
     * Package-keyed custom-name assignments absent from [validPackages] that
     * [PackagePresence] still reports present — vetoed, see [vetoedComponents].
     */
    private suspend fun vetoedPackages(validPackages: Set<String>): Set<String> {
        val candidates = customNamesRepository.getAllCustomNames().keys - validPackages
        return candidates.filterTo(HashSet()) { packagePresence.isPackagePresent(it) }
    }

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