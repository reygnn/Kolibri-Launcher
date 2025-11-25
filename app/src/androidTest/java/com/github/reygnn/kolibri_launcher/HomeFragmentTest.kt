package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.ui.home.HomeFragment
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class HomeFragmentTest : BaseAndroidTest() {

    // Helper für konsistentes Setup - KORRIGIERT!
    private fun setupFragmentWithApps(apps: List<AppInfo>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            (settingsRepository as FakeSettingsRepository)
                .setReadabilityModeBlocking("smart_contrast")

            // RICHTIG: Die Repositories füttern, die der echte UseCase verwendet!
            val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
            val fakeFavorites = favoritesRepository as FakeFavoritesRepository

            fakeInstalledState.updateApps(apps)
            val componentNames = apps.map { it.componentName }.toSet()
            fakeFavorites.favoritesState.value = componentNames
        }

        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))
    }

    // Helper für State-Updates während Fragment läuft
    private fun updateAppsWhileRunning(apps: List<AppInfo>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
            val fakeFavorites = favoritesRepository as FakeFavoritesRepository

            fakeInstalledState.updateApps(apps)
            val componentNames = apps.map { it.componentName }.toSet()
            fakeFavorites.favoritesState.value = componentNames
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.appList))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))
    }

    @Test
    fun basicTest_displaysFavoriteApps() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testFavorites = listOf(
            AppInfo(
                "Test Favorite 1",
                "Test Favorite 1",
                "com.test.fav1",
                "com.test.fav1.MainActivity"
            ),
            AppInfo(
                "Test Favorite 2",
                "Test Favorite 2",
                "com.test.fav2",
                "com.test.fav2.MainActivity"
            )
        )
        setupFragmentWithApps(testFavorites)

        onView(withText("Test Favorite 1")).check(matches(isDisplayed()))
        onView(withText("Test Favorite 2")).check(matches(isDisplayed()))
    }

    @Test
    fun clickOnFavorite_recordsAppLaunch() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("Mail", "Mail", "com.mail", "com.mail.MainActivity")
        setupFragmentWithApps(listOf(testApp))

        onView(withText("Mail")).perform(click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val fakeRepo = appUsageRepository as FakeAppUsageRepository
        assertThat(fakeRepo.launchedPackages).contains("com.mail")
    }

    @Test
    fun longClickOnFavorite_opensContextMenu() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("Mail", "Mail", "com.mail", "com.mail.MainActivity")
        setupFragmentWithApps(listOf(testApp))

        onView(withText("Mail")).perform(longClick())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Überprüfe, ob der Dialog mit dem App-Namen erscheint
        onView(withText(testApp.displayName))
            .inRoot(isDialog())
            .check(matches(isDisplayed()))
    }

    @Test
    fun emptyFavorites_showsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        setupFragmentWithApps(emptyList())

        onView(withId(R.id.appList))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            .check(matches(hasChildCount(0)))
    }

    @Test
    fun contextMenu_toggleFavoriteAction() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testApp = AppInfo("Mail", "Mail", "com.mail", "com.mail.MainActivity")
        val fakeFavRepo = favoritesRepository as FakeFavoritesRepository

        instrumentation.runOnMainSync {
            (settingsRepository as FakeSettingsRepository)
                .setReadabilityModeBlocking("smart_contrast")

            // RICHTIG: Beide Repositories korrekt setzen
            val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
            fakeInstalledState.updateApps(listOf(testApp))
            fakeFavRepo.favoritesState.value = setOf(testApp.componentName)
        }

        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withText("Mail")).perform(longClick())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Klicke auf "Remove from favorites" im Dialog
        onView(withText(R.string.remove_from_favorites))
            .inRoot(isDialog())
            .perform(click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Überprüfe, ob die Methode im Fake aufgerufen wurde
        assertThat(fakeFavRepo.favorites).doesNotContain(testApp.componentName)
    }

    @Test
    fun favoriteAppsUpdate_refreshesUI() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val initialFavorites = listOf(
            AppInfo("App One", "App One", "com.one", "com.one.MainActivity")
        )
        setupFragmentWithApps(initialFavorites)

        onView(withText("App One")).check(matches(isDisplayed()))

        // Simuliere ein Update mit korrektem Helper
        val updatedFavorites = listOf(
            AppInfo("App Two", "App Two", "com.two", "com.two.MainActivity"),
            AppInfo("App Three", "App Three", "com.three", "com.three.MainActivity")
        )
        updateAppsWhileRunning(updatedFavorites)

        onView(withText("App One")).check(doesNotExist())
        onView(withText("App Two")).check(matches(isDisplayed()))
        onView(withText("App Three")).check(matches(isDisplayed()))
    }

    @Test
    fun timeDateAndBattery_areDisplayedOnHomeScreen() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            (settingsRepository as FakeSettingsRepository)
                .setReadabilityModeBlocking("smart_contrast")
        }

        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        onView(withId(R.id.timeText))
            .check(matches(isDisplayed()))
            .check(matches(withText(not(""))))

        onView(withId(R.id.dateText))
            .check(matches(isDisplayed()))
            .check(matches(withText(not(""))))

        onView(withId(R.id.batteryText))
            .check(matches(isDisplayed()))
            .check(matches(withText(containsString("%"))))
    }
}