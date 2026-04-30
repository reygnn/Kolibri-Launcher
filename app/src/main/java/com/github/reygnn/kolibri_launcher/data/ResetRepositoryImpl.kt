package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
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
        return try {
            Timber.d("Starting complete data reset")

            // User-Daten zurücksetzen
            val userDataSuccess = resetUserData()

            // Settings zurücksetzen
            val settingsSuccess = resetSettings()

            // AppUsage-Daten zurücksetzen
            val appUsageSuccess = resetAppUsageData()

            val success = userDataSuccess && settingsSuccess && appUsageSuccess

            if (success) {
                Timber.d("Complete data reset successful")
            } else {
                Timber.w("Complete data reset completed with errors")
            }

            success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during complete data reset")
            false
        }
    }

    override suspend fun resetUserData(): Boolean {
        return try {
            Timber.d("Starting user data reset")

            var allSuccessful = true

            // Favoriten
            try {
                favoritesRepository.purgeRepository()
                Timber.d("Favorites purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging favorites")
                allSuccessful = false
            }

            // Favorites Order
            try {
                favoritesOrderRepository.purgeRepository()
                Timber.d("Favorites order purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging favorites order")
                allSuccessful = false
            }

            // Hidden Apps
            try {
                hiddenAppsRepository.purgeRepository()
                Timber.d("Hidden apps purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging hidden apps")
                allSuccessful = false
            }

            // Custom Names
            try {
                customNamesRepository.purgeRepository()
                Timber.d("Custom names purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging custom names")
                allSuccessful = false
            }

            // Das appUsageRepository.purgeRepository() hier NICHT purgen !!!

            // Swipe Actions
            try {
                swipeActionsRepository.purgeRepository()
                Timber.d("Swipe actions purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging swipe actions")
                allSuccessful = false
            }

            // Wallpaper
            try {
                wallpaperRepository.purgeRepository()
                Timber.d("Wallpaper purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging wallpaper")
                allSuccessful = false
            }

            // Installed Apps State
            try {
                installedAppsStateRepository.purgeRepository()
                Timber.d("Installed apps state purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging installed apps state")
                allSuccessful = false
            }

            // Screen Lock (falls gewünscht)
            try {
                screenLockRepository.purgeRepository()
                Timber.d("Screen lock purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging screen lock")
                allSuccessful = false
            }

            // Time-Based Events
            try {
                timeBasedEventsRepository.purgeRepository()
                Timber.d("Time-based events purged successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error purging time-based events")
                allSuccessful = false
            }

            if (allSuccessful) {
                Timber.d("User data reset successful")
            } else {
                Timber.w("User data reset completed with some errors")
            }

            allSuccessful
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during user data reset")
            false
        }
    }

    override suspend fun resetSettings(): Boolean {
        return try {
            Timber.d("Starting settings reset")

            settingsRepository.purgeRepository()

            Timber.d("Settings reset successful")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during settings reset")
            false
        }
    }

    override suspend fun resetAppUsageData(): Boolean {
        return try {
            Timber.d("Starting App Usage data reset")
            appUsageRepository.purgeRepository()
            Timber.d("App usage purged successfully")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error purging app usage")
            false
        }
    }
}