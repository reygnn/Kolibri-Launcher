package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.R
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
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

class ObserveInstalledAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val favoritesRepository: FavoritesRepository
) {
    private var appLoadRetryCount = 0
    private val maxAppLoadRetries = 3

    /**
     * Aktiviert den Flow, der die installierten Apps lädt, verarbeitet und den State aktualisiert.
     * Gibt ein 'AppLoadResult' aus, das dem ViewModel signalisiert, ob ein Fehler
     * aufgetreten ist, der dem Benutzer angezeigt werden muss.
     */
    operator fun invoke(): Flow<AppLoadResult> = flow {
        try {
            // Die gesamte Logik aus 'observeInstalledApps' ist jetzt HIER
            installedAppsRepository.getInstalledApps()
                .retry(maxAppLoadRetries.toLong()) { cause ->
                    try {
                        if (cause is IOException) {
                            appLoadRetryCount++
                            Timber.w("App loading failed, retry ${appLoadRetryCount}/${maxAppLoadRetries}")
                            delay(1000L * appLoadRetryCount)
                            true
                        } else {
                            false
                        }
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
                        Timber.w("Using cached apps as fallback (${cachedApps.size} apps)")
                        installedAppsStateRepository.updateApps(cachedApps)
                        // Sende KEIN Fehler-Event, da wir einen Cache haben
                    } else {
                        installedAppsStateRepository.updateApps(emptyList())
                        // Nur einen Fehler senden, wenn der Cache auch leer ist
                        emit(AppLoadResult.Error(R.string.error_app_list_not_loaded))
                    }
                }
                .collect { realApps ->
                    // Die gesamte Verarbeitungslogik
                    try {
                        if (realApps.isEmpty()) {
                            Timber.w("Collected an empty app list. Skipping cleanup to prevent data loss.")
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
                        appLoadRetryCount = 0

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