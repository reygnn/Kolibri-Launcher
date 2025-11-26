package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class ResetManagerTest {

    // Alle Repositories mocken
    @Mock private lateinit var favoritesRepository: FavoritesRepository
    @Mock private lateinit var hiddenAppsRepository: HiddenAppsRepository
    @Mock private lateinit var customNamesRepository: CustomNamesRepository
    @Mock private lateinit var appUsageRepository: AppUsageRepository
    @Mock private lateinit var favoritesOrderRepository: FavoritesOrderRepository
    @Mock private lateinit var swipeActionsRepository: SwipeActionsRepository
    @Mock private lateinit var settingsRepository: SettingsRepository
    @Mock private lateinit var screenLockRepository: ScreenLockRepository
    @Mock private lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @Mock private lateinit var timeBasedEventsRepository: TimeBasedEventsRepository

    private lateinit var resetManager: ResetManager

    @Before
    fun setup() {
        resetManager = ResetManager(
            favoritesRepository,
            hiddenAppsRepository,
            customNamesRepository,
            appUsageRepository,
            favoritesOrderRepository,
            swipeActionsRepository,
            settingsRepository,
            screenLockRepository,
            installedAppsStateRepository,
            timeBasedEventsRepository
        )
    }

    // ========== RESET USER DATA TESTS ==========

    @Test
    fun `resetUserData - calls purge on all relevant repositories`() = runTest {
        val result = resetManager.resetUserData()

        Assert.assertTrue(result)

        // Prüfen, ob alle User-Data Repositories gepurged wurden
        verify(favoritesRepository).purgeRepository()
        verify(favoritesOrderRepository).purgeRepository()
        verify(hiddenAppsRepository).purgeRepository()
        verify(customNamesRepository).purgeRepository()
        verify(swipeActionsRepository).purgeRepository()
        verify(installedAppsStateRepository).purgeRepository()
        verify(screenLockRepository).purgeRepository()
        verify(timeBasedEventsRepository).purgeRepository()

        // WICHTIG: AppUsage und Settings dürfen hier NICHT angefasst werden
        Mockito.verify(appUsageRepository, Mockito.never()).purgeRepository()
        Mockito.verify(settingsRepository, Mockito.never()).purgeRepository()
    }

    @Test
    fun `resetUserData - when one purge fails - continues others and returns false`() = runTest {
        // Arrange: Favorites Order fails
        whenever(favoritesOrderRepository.purgeRepository()).doThrow(RuntimeException("DB Error"))

        // Act
        val result = resetManager.resetUserData()

        // Assert
        Assert.assertFalse("Result should be false if one component fails", result)

        // Verify Robustness: Others MUST still be called
        verify(favoritesRepository).purgeRepository() // Davor
        verify(favoritesOrderRepository).purgeRepository() // Fehlerquelle
        verify(swipeActionsRepository).purgeRepository() // Danach (Muss aufgerufen werden!)
    }

    // ========== RESET SETTINGS TESTS ==========

    @Test
    fun `resetSettings - calls purge on settings repository`() = runTest {
        val result = resetManager.resetSettings()

        Assert.assertTrue(result)
        verify(settingsRepository).purgeRepository()
    }

    @Test
    fun `resetSettings - when purge fails - returns false`() = runTest {
        whenever(settingsRepository.purgeRepository()).doThrow(RuntimeException("Fail"))

        val result = resetManager.resetSettings()

        Assert.assertFalse(result)
    }

    // ========== RESET APP USAGE TESTS ==========

    @Test
    fun `resetAppUsageData - calls purge on app usage repository`() = runTest {
        val result = resetManager.resetAppUsageData()

        Assert.assertTrue(result)
        verify(appUsageRepository).purgeRepository()
    }

    @Test
    fun `resetAppUsageData - when purge fails - returns false`() = runTest {
        whenever(appUsageRepository.purgeRepository()).doThrow(RuntimeException("Fail"))

        val result = resetManager.resetAppUsageData()

        Assert.assertFalse(result)
    }

    // ========== RESET ALL DATA TESTS ==========

    @Test
    fun `resetAllData - calls all reset methods and returns true on success`() = runTest {
        val result = resetManager.resetAllData()

        Assert.assertTrue(result)

        // Transitiv prüfen: Alle Sub-Methoden aufgerufen?
        // User Data
        verify(favoritesRepository).purgeRepository()
        // Settings
        verify(settingsRepository).purgeRepository()
        // App Usage
        verify(appUsageRepository).purgeRepository()
    }

    @Test
    fun `resetAllData - when one part fails - executes others but returns false`() = runTest {
        // Arrange: User Data fails, but Settings and Usage should still reset
        whenever(favoritesRepository.purgeRepository()).doThrow(RuntimeException("Fail"))

        // Act
        val result = resetManager.resetAllData()

        // Assert
        Assert.assertFalse(result)

        // Verify execution flow continues despite error in first step
        verify(favoritesRepository).purgeRepository() // Failed
        verify(settingsRepository).purgeRepository()  // Should still run
        verify(appUsageRepository).purgeRepository()  // Should still run
    }
}