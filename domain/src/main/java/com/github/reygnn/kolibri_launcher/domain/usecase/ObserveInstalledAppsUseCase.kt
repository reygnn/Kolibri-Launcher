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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
    private val customNamesRepository: CustomNamesRepository
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
                        // These are four separate cleanup calls by design, one
                        // per repository. They are NOT batched into a single
                        // DataStore transaction on purpose: each store owns its
                        // keys behind its own repository interface, and merging
                        // the writes would break that boundary. Cost is bounded —
                        // DataStore.edit skips the disk write when nothing
                        // changed, so the steady state (no orphans) is four cheap
                        // in-memory reads. Don't "optimize" this into one edit.
                        val allValidComponentNames = realApps.map { it.componentName }
                        val allValidPackageNames = realApps.map { it.packageName }
                        runCleanup("favorites") {
                            favoritesRepository.cleanupFavoriteComponents(allValidComponentNames)
                        }
                        runCleanup("swipe actions") {
                            swipeActionsRepository.cleanupSwipeActions(allValidComponentNames)
                        }
                        runCleanup("hidden components") {
                            hiddenAppsRepository.cleanupHiddenComponents(allValidComponentNames)
                        }
                        runCleanup("custom names") {
                            customNamesRepository.cleanupCustomNames(allValidPackageNames)
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