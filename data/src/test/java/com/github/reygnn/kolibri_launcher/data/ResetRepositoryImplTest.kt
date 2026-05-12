package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class ResetRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    // relaxUnitFun = true: purgeRepository() ist suspend + returns Unit → kein coEvery/just Runs nötig
    @MockK(relaxUnitFun = true) private lateinit var favoritesRepository: FavoritesRepository
    @MockK(relaxUnitFun = true) private lateinit var hiddenAppsRepository: HiddenAppsRepository
    @MockK(relaxUnitFun = true) private lateinit var customNamesRepository: CustomNamesRepository
    @MockK(relaxUnitFun = true) private lateinit var appUsageRepository: AppUsageRepository
    @MockK(relaxUnitFun = true) private lateinit var favoritesOrderRepository: FavoritesOrderRepository
    @MockK(relaxUnitFun = true) private lateinit var swipeActionsRepository: SwipeActionsRepository
    @MockK(relaxUnitFun = true) private lateinit var wallpaperRepository: WallpaperRepository
    @MockK(relaxUnitFun = true) private lateinit var fabPositionRepository: FabPositionRepository
    @MockK(relaxUnitFun = true) private lateinit var settingsRepository: SettingsRepository
    @MockK(relaxUnitFun = true) private lateinit var screenLockRepository: ScreenLockRepository
    @MockK(relaxUnitFun = true) private lateinit var installedAppsStateRepository: InstalledAppsStateRepository
    @MockK(relaxUnitFun = true) private lateinit var timeBasedEventsRepository: TimeBasedEventsRepository

    private lateinit var resetManager: ResetRepositoryImpl

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        resetManager = ResetRepositoryImpl(
            favoritesRepository,
            hiddenAppsRepository,
            customNamesRepository,
            appUsageRepository,
            favoritesOrderRepository,
            swipeActionsRepository,
            wallpaperRepository,
            fabPositionRepository,
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

        coVerify { favoritesRepository.purgeRepository() }
        coVerify { favoritesOrderRepository.purgeRepository() }
        coVerify { hiddenAppsRepository.purgeRepository() }
        coVerify { customNamesRepository.purgeRepository() }
        coVerify { swipeActionsRepository.purgeRepository() }
        coVerify { wallpaperRepository.purgeRepository() }
        coVerify { fabPositionRepository.purgeRepository() }
        coVerify { installedAppsStateRepository.purgeRepository() }
        coVerify { screenLockRepository.purgeRepository() }
        coVerify { timeBasedEventsRepository.purgeRepository() }

        // AppUsage und Settings dürfen hier NICHT angefasst werden
        coVerify(exactly = 0) { appUsageRepository.purgeRepository() }
        coVerify(exactly = 0) { settingsRepository.purgeRepository() }
    }

    @Test
    fun `resetUserData - when one purge fails - continues others and returns false`() = runTest {
        coEvery { favoritesOrderRepository.purgeRepository() } throws RuntimeException("DB Error")

        val result = resetManager.resetUserData()

        Assert.assertFalse("Result should be false if one component fails", result)

        coVerify { favoritesRepository.purgeRepository() }
        coVerify { favoritesOrderRepository.purgeRepository() }
        coVerify { swipeActionsRepository.purgeRepository() }
    }

    // ========== RESET SETTINGS TESTS ==========

    @Test
    fun `resetSettings - calls purge on settings repository`() = runTest {
        val result = resetManager.resetSettings()

        Assert.assertTrue(result)
        coVerify { settingsRepository.purgeRepository() }
    }

    @Test
    fun `resetSettings - when purge fails - returns false`() = runTest {
        coEvery { settingsRepository.purgeRepository() } throws RuntimeException("Fail")

        val result = resetManager.resetSettings()

        Assert.assertFalse(result)
    }

    // ========== RESET APP USAGE TESTS ==========

    @Test
    fun `resetAppUsageData - calls purge on app usage repository`() = runTest {
        val result = resetManager.resetAppUsageData()

        Assert.assertTrue(result)
        coVerify { appUsageRepository.purgeRepository() }
    }

    @Test
    fun `resetAppUsageData - when purge fails - returns false`() = runTest {
        coEvery { appUsageRepository.purgeRepository() } throws RuntimeException("Fail")

        val result = resetManager.resetAppUsageData()

        Assert.assertFalse(result)
    }

    // ========== RESET ALL DATA TESTS ==========

    @Test
    fun `resetAllData - calls all reset methods and returns true on success`() = runTest {
        val result = resetManager.resetAllData()

        Assert.assertTrue(result)

        coVerify { favoritesRepository.purgeRepository() }
        coVerify { settingsRepository.purgeRepository() }
        coVerify { appUsageRepository.purgeRepository() }
    }

    @Test
    fun `resetAllData - when one part fails - executes others but returns false`() = runTest {
        coEvery { favoritesRepository.purgeRepository() } throws RuntimeException("Fail")

        val result = resetManager.resetAllData()

        Assert.assertFalse(result)

        coVerify { favoritesRepository.purgeRepository() }
        coVerify { settingsRepository.purgeRepository() }
        coVerify { appUsageRepository.purgeRepository() }
    }
}
