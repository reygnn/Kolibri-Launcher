package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
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
    private val favoritesRepository: FavoritesRepository
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

                        // Geschäftslogik: Favoriten aufräumen
                        val allValidComponentNames = realApps.map { it.componentName }
                        try {
                            favoritesRepository.cleanupFavoriteComponents(allValidComponentNames)
                        } catch (cleanupError: Throwable) {
                            TimberWrapper.silentError(cleanupError, "Error cleaning up favorites")
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
}