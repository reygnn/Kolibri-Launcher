package com.github.reygnn.kolibri_launcher.core

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeGetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.home.HomeFragment
import com.github.reygnn.kolibri_launcher.util.EspressoTestUtils.waitForView
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BabyStepTests - Smoke Test Suite für die Test-Infrastruktur.
 *
 * Diese Tests verifizieren die Grundlagen:
 * - Fake Repositories funktionieren korrekt
 * - Hilt DI injiziert die richtigen Fakes
 * - Fragment-Launch ohne Crash
 * - UseCase-Pattern funktioniert (WICHTIG: Step 11 & 12!)
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class BabyStepTests : BaseAndroidTest() {

    // =========================================================================
    // BABY STEP 1: Fake UseCase Repository akzeptiert State?
    // =========================================================================
    @Test
    fun step1_fakeUseCaseAcceptsState() {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        val testApp = AppInfo("Test", "Test", "com.test", "com.test.Main")

        fakeUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(listOf(testApp), isFallback = false)
        )

        val currentState = fakeUseCase.favoriteAppsState.value
        assertTrue(currentState is UiState.Success)
        assertEquals(1, (currentState as UiState.Success).data.apps.size)
    }

    // =========================================================================
    // BABY STEP 2: Fake InstalledAppsRepository funktioniert?
    // =========================================================================
    @Test
    fun step2_installedAppsRepositoryWorks() {
        val fakeRepo = installedAppsRepository as FakeInstalledAppsRepository
        val testApp = AppInfo("TestApp", "TestApp", "com.test", "com.test.Main")

        fakeRepo.appsFlow.value = listOf(testApp)

        assertEquals(1, fakeRepo.appsFlow.value.size)
        assertEquals("TestApp", fakeRepo.appsFlow.value[0].displayName)
    }

    // =========================================================================
    // BABY STEP 3: Fake SettingsRepository funktioniert?
    // =========================================================================
    @Test
    fun step3_settingsRepositoryWorks() {
        val fakeSettings = settingsRepository as FakeSettingsRepository

        // Kein Crash = Erfolg
        assertTrue(true)
    }

    // =========================================================================
    // BABY STEP 4: SettingsRepository kann ReadabilityMode setzen?
    // =========================================================================
    @Test
    fun step4_settingsRepositoryCanSetReadabilityMode() {
        val fakeSettings = settingsRepository as FakeSettingsRepository

        fakeSettings.setReadabilityModeBlocking("smart_contrast")

        // Kein Crash = Erfolg
        assertTrue(true)
    }

    // =========================================================================
    // BABY STEP 5: Alle Fakes zusammen OHNE Fragment
    // =========================================================================
    @Test
    fun step5_allFakesWorkTogether() {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        val fakeRepo = installedAppsRepository as FakeInstalledAppsRepository
        val fakeSettings = settingsRepository as FakeSettingsRepository

        val testApp = AppInfo("MyApp", "MyApp", "com.myapp", "com.myapp.Main")

        // Setup alles
        fakeSettings.setReadabilityModeBlocking("smart_contrast")
        fakeRepo.appsFlow.value = listOf(testApp)
        fakeUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(listOf(testApp), isFallback = false)
        )

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Verify alles
        assertEquals(1, fakeRepo.appsFlow.value.size)
        val state = fakeUseCase.favoriteAppsState.value
        assertTrue(state is UiState.Success)
        assertEquals("MyApp", (state as UiState.Success).data.apps[0].displayName)
    }

    // =========================================================================
    // BABY STEP 6: Fragment startet ohne Crash?
    // =========================================================================
    @Test
    fun step6_fragmentLaunchesWithoutCrash() {
        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Nur prüfen dass rootLayout existiert
        onView(withId(R.id.rootLayout)).check(matches(isDisplayed()))
    }

    // =========================================================================
    // BABY STEP 7: Fragment mit leerem State zeigt leere appList?
    // =========================================================================
    @Test
    fun step7_fragmentWithEmptyStateShowsEmptyList() {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository

        fakeUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(emptyList(), isFallback = false)
        )

        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withId(R.id.rootLayout)).check(matches(isDisplayed()))
        onView(withId(R.id.appList)).check(matches(hasChildCount(0)))
    }

    // =========================================================================
    // BABY STEP 8 & 9: ÜBERSPRUNGEN
    // Diese Tests waren fehlgeschlagen weil sie das FakeGetFavoriteAppsUseCaseRepository
    // fütterten, aber das ViewModel den echten UseCase verwendet.
    // Siehe Step 11 & 12 für das RICHTIGE Pattern!
    // =========================================================================

    // =========================================================================
    // BABY STEP 10: InstalledAppsStateRepository funktioniert?
    // =========================================================================
    @Test
    fun step10_installedAppsStateRepositoryWorks() {
        val fakeStateRepo = installedAppsStateRepository as FakeInstalledAppsStateRepository
        val testApp = AppInfo("StateApp", "StateApp", "com.state", "com.state.Main")

        fakeStateRepo.updateApps(listOf(testApp))

        // Kein Crash = Erfolg
        assertTrue(true)
    }

    // =========================================================================
    // BABY STEP 11: Die RICHTIGEN Repositories füttern!
    // =========================================================================
    /**
     * WICHTIG: Das ist das korrekte Pattern!
     *
     * Der echte GetFavoriteAppsUseCase kombiniert:
     * - InstalledAppsStateRepository.installedAppsFlow
     * - FavoritesRepository.favoriteComponentsFlow
     *
     * Also müssen wir DIESE Repositories füttern, nicht FakeGetFavoriteAppsUseCaseRepository!
     */
    @Test
    fun step11_feedCorrectRepositories() {
        val testApp = AppInfo("TestApp", "TestApp", "com.test", "com.test.Main")

        // DIESE Repositories verwendet der echte GetFavoriteAppsUseCase!
        val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
        val fakeFavorites = favoritesRepository as FakeFavoritesRepository

        // 1. Apps in den State-Repository laden
        fakeInstalledState.updateApps(listOf(testApp))

        // 2. Als Favorit markieren (componentName!)
        fakeFavorites.favoritesState.value = setOf("com.test/com.test.Main")

        // Dann Fragment starten
        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withId(R.id.rootLayout))
            .perform(com.github.reygnn.kolibri_launcher.util.EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        // Jetzt sollte appList 1 Kind haben!
        onView(withId(R.id.appList)).check(matches(hasChildCount(1)))
    }

    // =========================================================================
    // BABY STEP 12: Mit waitForView für robustere UI-Prüfung
    // =========================================================================
    @Test
    fun step12_feedCorrectRepositoriesWithWaitForView() {
        val testApp = AppInfo("MyTestApp", "MyTestApp", "com.test", "com.test.Main")

        val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
        val fakeFavorites = favoritesRepository as FakeFavoritesRepository

        fakeInstalledState.updateApps(listOf(testApp))
        fakeFavorites.favoritesState.value = setOf("com.test/com.test.Main")

        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Warte auf den Text
        onView(isRoot()).perform(waitForView(withText("MyTestApp"), 5000))

        onView(withText("MyTestApp")).check(matches(isDisplayed()))
    }
}