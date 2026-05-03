package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ULTRA-SAFE ResetRepositoryImpl
 * Verwaltet das Zurücksetzen aller App-Daten durch Koordination der Purgeable Repositories
 */
@Singleton
class ResetRepositoryImpl @Inject constructor(
    // User-Data Repositories
    private val favoritesRepository: FavoritesRepository, // mit purgeRepository
    private val hiddenAppsRepository: HiddenAppsRepository, // mit purgeRepository
    private val customNamesRepository: CustomNamesRepository, // mit purgeRepository
    private val appUsageRepository: AppUsageRepository, // mit purgeRepository
    private val favoritesOrderRepository: FavoritesOrderRepository, // mit purgeRepository
    private val swipeActionsRepository: SwipeActionsRepository, // mit purgeRepository
    private val wallpaperRepository: WallpaperRepository, // mit purgeRepository

    // Settings Repository
    private val settingsRepository: SettingsRepository, // mit purgeRepository

    // Weitere Repositories falls vorhanden
    private val screenLockRepository: ScreenLockRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val timeBasedEventsRepository: TimeBasedEventsRepository // kein purgeRepository nötig
) : ResetRepository {

    override suspend fun resetAllData(): Boolean {
        // Outer try/catch removed per Rule 11 — the three reset
        // methods each return Boolean (purgeAll handles their failures
        // internally and never throws); Boolean composition + Timber
        // logs are pure code paths.
        Timber.d("Starting complete data reset")

        val userDataSuccess = resetUserData()
        val settingsSuccess = resetSettings()
        val appUsageSuccess = resetAppUsageData()

        val success = userDataSuccess && settingsSuccess && appUsageSuccess

        if (success) {
            Timber.d("Complete data reset successful")
        } else {
            Timber.w("Complete data reset completed with errors")
        }
        return success
    }

    override suspend fun resetUserData(): Boolean {
        Timber.d("Starting user data reset")

        // appUsageRepository is intentionally absent — it survives a
        // user-data reset and is only purged via [resetAppUsageData].
        val allSuccessful = purgeAll(
            listOf(
                "favorites" to favoritesRepository,
                "favorites order" to favoritesOrderRepository,
                "hidden apps" to hiddenAppsRepository,
                "custom names" to customNamesRepository,
                "swipe actions" to swipeActionsRepository,
                "wallpaper" to wallpaperRepository,
                "installed apps state" to installedAppsStateRepository,
                "screen lock" to screenLockRepository,
                "time-based events" to timeBasedEventsRepository,
            ),
        )

        if (allSuccessful) {
            Timber.d("User data reset successful")
        } else {
            Timber.w("User data reset completed with some errors")
        }
        return allSuccessful
    }

    override suspend fun resetSettings(): Boolean {
        Timber.d("Starting settings reset")
        return purgeAll(listOf("settings" to settingsRepository))
    }

    override suspend fun resetAppUsageData(): Boolean {
        Timber.d("Starting App Usage data reset")
        return purgeAll(listOf("app usage" to appUsageRepository))
    }

    /**
     * Purges every [Purgeable] in [repos], in order, with per-item
     * error isolation: a single failing purge logs via
     * [TimberWrapper.silentError] but does not stop the remaining
     * purges from running. Returns `true` only if every purge
     * succeeded.
     *
     * Replaces eleven structurally identical try/catch blocks across
     * `resetUserData` / `resetSettings` / `resetAppUsageData`. Adding
     * a twelfth Purgeable to a reset path is now a one-line list
     * entry rather than a copy-pasted nine-line catch block —
     * nothing to forget.
     *
     * `CancellationException` propagates unchanged so coroutine
     * cancellation still works correctly.
     */
    private suspend fun purgeAll(repos: List<Pair<String, Purgeable>>): Boolean {
        var allSuccessful = true
        for ((name, repo) in repos) {
            try {
                repo.purgeRepository()
                Timber.d("$name purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging $name")
                allSuccessful = false
            }
        }
        return allSuccessful
    }
}