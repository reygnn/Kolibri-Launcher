package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.reygnn.kolibri_launcher.EspressoTestUtils.waitForView
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.home.HomeFragment
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class BabyStepTests : BaseAndroidTest() {

    // =========================================================================
    // BABY STEP 1: Fake Repository funktioniert überhaupt?
    // =========================================================================
    @Test
    fun step1_fakeRepositoryCanSetState() {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        val testApp = AppInfo("Test", "Test", "com.test", "com.test.Main")

        fakeUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(listOf(testApp), isFallback = false)
        )

        val currentState = fakeUseCase.favoriteAppsState.value
        assertTrue("State sollte Success sein", currentState is UiState.Success)
        assertEquals(1, (currentState as UiState.Success).data.apps.size)
        assertEquals("Test", currentState.data.apps[0].displayName)
    }

    // =========================================================================
    // BABY STEP 2: State bleibt nach Setzen erhalten?
    // =========================================================================
    @Test
    fun step2_stateRemainsAfterAdvanceUntilIdle() {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        val testApp = AppInfo("Test", "Test", "com.test", "com.test.Main")

        fakeUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(listOf(testApp), isFallback = false)
        )

        // Simuliere was in echten Tests passiert
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val currentState = fakeUseCase.favoriteAppsState.value
        assertTrue("State sollte nach advanceUntilIdle noch Success sein", currentState is UiState.Success)
    }

    // =========================================================================
    // BABY STEP 3: InstalledAppsRepository funktioniert?
    // =========================================================================
    @Test
    fun step3_installedAppsRepositoryCanSetApps() {
        val fakeRepo = installedAppsRepository as FakeInstalledAppsRepository
        val testApp = AppInfo("Test", "Test", "com.test", "com.test.Main")

        fakeRepo.appsFlow.value = listOf(testApp)

        assertEquals(1, fakeRepo.appsFlow.value.size)
        assertEquals("Test", fakeRepo.appsFlow.value[0].displayName)
    }

    // =========================================================================
    // BABY STEP 4: Settings Repository funktioniert?
    // =========================================================================
    @Test
    fun step4_settingsRepositoryCanSetReadabilityMode() {
        val fakeSettings = settingsRepository as FakeSettingsRepository

        fakeSettings.setReadabilityModeBlocking("smart_contrast")

        // Kein Crash = Erfolg
        assertTrue(true)
    }

    // =========================================================================
    // BABY STEP 5: Alles zusammen OHNE Fragment
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
    // BABY STEP 7: Fragment mit leerem State - appList ist leer?
    // =========================================================================
    @Test
    fun step7_fragmentWithEmptyStateShowsEmptyList() {
        // State ist nach purge bereits Loading/Empty
        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        // appList sollte 0 Kinder haben
        onView(withId(R.id.appList)).check(matches(hasChildCount(0)))
    }

    // =========================================================================
    // BABY STEP 8: State VOR Fragment setzen - kommt er an?
    // =========================================================================
    @Test
    fun step8_stateSetBeforeFragmentLaunch() {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        val testApp = AppInfo("TestApp", "TestApp", "com.test", "com.test.Main")

        // STATE ZUERST!
        fakeUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(listOf(testApp), isFallback = false)
        )

        // Dann Fragment
        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        // Hat appList jetzt 1 Kind?
        onView(withId(R.id.appList)).check(matches(hasChildCount(1)))
    }

    // =========================================================================
    // BABY STEP 9: Wie Step 8, aber mit waitForView
    // =========================================================================
    @Test
    fun step9_stateSetBeforeFragmentWithWaitForView() {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        val testApp = AppInfo("TestApp", "TestApp", "com.test", "com.test.Main")

        fakeUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(listOf(testApp), isFallback = false)
        )

        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Warte explizit auf den Text
        onView(isRoot()).perform(waitForView(withText("TestApp"), 5000))

        onView(withText("TestApp")).check(matches(isDisplayed()))
    }

    // =========================================================================
    // BABY STEP 10: Debug - Was bekommt das ViewModel wirklich?
    // =========================================================================
    @Test
    fun step10_debugViewModelReceivesCorrectType() {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        val testApp = AppInfo("DebugApp", "DebugApp", "com.debug", "com.debug.Main")

        fakeUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(listOf(testApp), isFallback = false)
        )

        val scenario = launchAndTrackFragment<HomeFragment>()

        // Hole das ViewModel aus dem Fragment
        scenario.onActivity { activity ->
            val navHostFragment = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment)
            val homeFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()

            // Debug output
            println(">>> Fragment type: ${homeFragment?.javaClass?.simpleName}")
            println(">>> Fake state: ${fakeUseCase.favoriteAppsState.value}")
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Dieser Test ist nur zum Debuggen - schaue die Logcat Ausgabe an
        assertTrue(true)
    }

    // =========================================================================
    // BABY STEP 11: Die RICHTIGEN Repositories füttern!
    // =========================================================================
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
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        // Jetzt sollte appList 1 Kind haben!
        onView(withId(R.id.appList)).check(matches(hasChildCount(1)))
    }

    // =========================================================================
    // BABY STEP 12: Mit waitForView
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