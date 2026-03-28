package com.github.reygnn.kolibri_launcher.ui

import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.BaseAndroidTest
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.ui.home.HomeFragment
import com.github.reygnn.kolibri_launcher.util.EspressoTestUtils
import com.github.reygnn.kolibri_launcher.util.TestCoroutineRule
import com.google.common.truth.Truth
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.CoreMatchers
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
        Espresso.onView(ViewMatchers.withId(R.id.rootLayout))
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
        Espresso.onView(ViewMatchers.withId(R.id.appList))
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

        Espresso.onView(ViewMatchers.withText("Test Favorite 1"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Test Favorite 2"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun clickOnFavorite_recordsAppLaunch() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("Mail", "Mail", "com.mail", "com.mail.MainActivity")
        setupFragmentWithApps(listOf(testApp))

        Espresso.onView(ViewMatchers.withText("Mail")).perform(ViewActions.click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val fakeRepo = appUsageRepository as FakeAppUsageRepository
        Truth.assertThat(fakeRepo.launchedPackages).contains("com.mail")
    }

    @Test
    fun longClickOnFavorite_opensContextMenu() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("Mail", "Mail", "com.mail", "com.mail.MainActivity")
        setupFragmentWithApps(listOf(testApp))

        Espresso.onView(ViewMatchers.withText("Mail")).perform(ViewActions.longClick())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Überprüfe, ob der Dialog mit dem App-Namen erscheint
        Espresso.onView(ViewMatchers.withText(testApp.displayName))
            .inRoot(RootMatchers.isDialog())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun emptyFavorites_showsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        setupFragmentWithApps(emptyList())

        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))
            .check(ViewAssertions.matches(ViewMatchers.hasChildCount(0)))
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
        Espresso.onView(ViewMatchers.withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        Espresso.onView(ViewMatchers.withText("Mail")).perform(ViewActions.longClick())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Klicke auf "Remove from favorites" im Dialog
        Espresso.onView(ViewMatchers.withText(R.string.remove_from_favorites))
            .inRoot(RootMatchers.isDialog())
            .perform(ViewActions.click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Überprüfe, ob die Methode im Fake aufgerufen wurde
        Truth.assertThat(fakeFavRepo.favorites).doesNotContain(testApp.componentName)
    }

    @Test
    fun favoriteAppsUpdate_refreshesUI() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val initialFavorites = listOf(
            AppInfo("App One", "App One", "com.one", "com.one.MainActivity")
        )
        setupFragmentWithApps(initialFavorites)

        Espresso.onView(ViewMatchers.withText("App One"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // Simuliere ein Update mit korrektem Helper
        val updatedFavorites = listOf(
            AppInfo("App Two", "App Two", "com.two", "com.two.MainActivity"),
            AppInfo("App Three", "App Three", "com.three", "com.three.MainActivity")
        )
        updateAppsWhileRunning(updatedFavorites)

        Espresso.onView(ViewMatchers.withText("App One")).check(ViewAssertions.doesNotExist())
        Espresso.onView(ViewMatchers.withText("App Two"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("App Three"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
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
        Espresso.onView(ViewMatchers.withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        Espresso.onView(ViewMatchers.withId(R.id.timeText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
            .check(ViewAssertions.matches(ViewMatchers.withText(CoreMatchers.not(""))))

        Espresso.onView(ViewMatchers.withId(R.id.dateText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
            .check(ViewAssertions.matches(ViewMatchers.withText(CoreMatchers.not(""))))

        Espresso.onView(ViewMatchers.withId(R.id.batteryText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
            .check(ViewAssertions.matches(ViewMatchers.withText(CoreMatchers.containsString("%"))))
    }
}