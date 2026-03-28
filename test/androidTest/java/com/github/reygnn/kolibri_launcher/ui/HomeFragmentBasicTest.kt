package com.github.reygnn.kolibri_launcher.ui

import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.BaseAndroidTest
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeGetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.home.HomeFragment
import com.github.reygnn.kolibri_launcher.util.EspressoTestUtils
import com.github.reygnn.kolibri_launcher.util.TestCoroutineRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.CoreMatchers
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class HomeFragmentBasicTest : BaseAndroidTest() {

    private fun launchFragmentWithFavorites(apps: List<AppInfo>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            (settingsRepository as FakeSettingsRepository)
                .setReadabilityModeBlocking("smart_contrast")

            // Die Repositories füttern, die der echte UseCase verwendet
            val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
            val fakeFavorites = favoritesRepository as FakeFavoritesRepository

            // 1. Apps in InstalledAppsStateRepository laden
            fakeInstalledState.updateApps(apps)

            // 2. Alle Apps als Favoriten markieren (componentName!)
            val componentNames = apps.map { it.componentName }.toSet()
            fakeFavorites.favoritesState.value = componentNames
        }

        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        Espresso.onView(ViewMatchers.withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))
    }


    @Test
    fun fragmentCanBeLaunched() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        // ID Update: root_layout -> rootLayout
        Espresso.onView(ViewMatchers.withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        Espresso.onView(ViewMatchers.withId(R.id.rootLayout))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun staticUiElementsAreDisplayed() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchFragmentWithFavorites(emptyList())

        // ID Updates: time_text -> timeText, etc.
        Espresso.onView(ViewMatchers.withId(R.id.timeText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.dateText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.batteryText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // ID Update: favorite_apps_list -> appList
        // appList ist der Container innerhalb der ScrollView
        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))
    }

    @Test
    fun dynamicTextViewsHaveContent() = testCoroutineRule.runTestAndLaunchUI(
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

        // ID Updates
        Espresso.onView(ViewMatchers.withId(R.id.timeText))
            .check(ViewAssertions.matches(ViewMatchers.withText(CoreMatchers.not(""))))
        Espresso.onView(ViewMatchers.withId(R.id.dateText))
            .check(ViewAssertions.matches(ViewMatchers.withText(CoreMatchers.not(""))))
        Espresso.onView(ViewMatchers.withId(R.id.batteryText))
            .check(ViewAssertions.matches(ViewMatchers.withText(CoreMatchers.containsString("%"))))
    }

    @Test
    fun emptyFavoritesStateShowsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchFragmentWithFavorites(emptyList())
        // ID Update: favorite_apps_list -> appList
        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .check(ViewAssertions.matches(ViewMatchers.hasChildCount(0)))
    }

    @Test
    fun loadingStateShowsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            (settingsRepository as FakeSettingsRepository)
                .setReadabilityModeBlocking("smart_contrast")
        }

        launchAndTrackFragment<HomeFragment>()

        instrumentation.runOnMainSync {
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository)
                .favoriteAppsState.value = UiState.Loading
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .check(ViewAssertions.matches(ViewMatchers.hasChildCount(0)))
    }

    @Test
    fun errorStateShowsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            (settingsRepository as FakeSettingsRepository)
                .setReadabilityModeBlocking("smart_contrast")
        }

        launchAndTrackFragment<HomeFragment>()

        instrumentation.runOnMainSync {
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository)
                .favoriteAppsState.value = UiState.Error("Test Error")
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .check(ViewAssertions.matches(ViewMatchers.hasChildCount(0)))
    }

    @Test
    fun singleFavoriteShowsOneButtonWithCorrectText() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("MyTestApp", "MyTestApp", "com.test", "com.test.Main")
        launchFragmentWithFavorites(listOf(testApp))

        // 1. WARTEN bis der Text "MyTestApp" in der Hierarchie auftaucht
        Espresso.onView(ViewMatchers.isRoot())
            .perform(EspressoTestUtils.waitForView(ViewMatchers.withText("MyTestApp")))

        // 2. Scrollen (falls nötig) und Prüfen
        Espresso.onView(ViewMatchers.withText("MyTestApp"))
            .perform(EspressoTestUtils.nestedScrollTo())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .check(ViewAssertions.matches(ViewMatchers.hasChildCount(1)))
    }

    @Test
    fun threeFavoritesShowThreeButtons() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val apps = listOf(
            AppInfo("App1", "App1", "com.app1", "com.app1.Main"),
            AppInfo("App2", "App2", "com.app2", "com.app2.Main"),
            AppInfo("App3", "App3", "com.app3", "com.app3.Main")
        )
        launchFragmentWithFavorites(apps)

        // Auf den letzten warten reicht meistens als Indikator
        Espresso.onView(ViewMatchers.isRoot())
            .perform(EspressoTestUtils.waitForView(ViewMatchers.withText("App3")))

        // Check App 1
        Espresso.onView(ViewMatchers.withText("App1"))
            .perform(EspressoTestUtils.nestedScrollTo())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // Check App 2
        Espresso.onView(ViewMatchers.withText("App2"))
            .perform(EspressoTestUtils.nestedScrollTo())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // Check App 3
        Espresso.onView(ViewMatchers.withText("App3"))
            .perform(EspressoTestUtils.nestedScrollTo())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .check(ViewAssertions.matches(ViewMatchers.hasChildCount(3)))
    }

    @Test
    fun stateUpdateAfterLaunchAddsButton() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        // Erstmal leer starten
        launchFragmentWithFavorites(emptyList())

        Espresso.onView(ViewMatchers.withText("NewApp")).check(ViewAssertions.doesNotExist())
        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .check(ViewAssertions.matches(ViewMatchers.hasChildCount(0)))

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val newApp = AppInfo("NewApp", "NewApp", "com.new", "com.new.Main")

        // Update triggern - RICHTIGE Repositories!
        instrumentation.runOnMainSync {
            val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
            val fakeFavorites = favoritesRepository as FakeFavoritesRepository

            fakeInstalledState.updateApps(listOf(newApp))
            fakeFavorites.favoritesState.value = setOf(newApp.componentName)
        }

        // Coroutines laufen lassen
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // WARTEN auf das UI Update
        Espresso.onView(ViewMatchers.isRoot())
            .perform(EspressoTestUtils.waitForView(ViewMatchers.withText("NewApp")))

        Espresso.onView(ViewMatchers.withText("NewApp"))
            .perform(EspressoTestUtils.nestedScrollTo())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withId(R.id.appList))
            .check(ViewAssertions.matches(ViewMatchers.hasChildCount(1)))
    }

    @Test
    fun favoriteButtonIsClickableAndEnabled() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("ClickMe", "ClickMe", "com.click", "com.click.Main")
        launchFragmentWithFavorites(listOf(testApp))

        Espresso.onView(ViewMatchers.isRoot())
            .perform(EspressoTestUtils.waitForView(ViewMatchers.withText("ClickMe")))

        Espresso.onView(ViewMatchers.withText("ClickMe"))
            .perform(EspressoTestUtils.nestedScrollTo())
            .check(ViewAssertions.matches(ViewMatchers.isClickable()))
            .check(ViewAssertions.matches(ViewMatchers.isEnabled()))
    }

    @Test
    fun usesDisplayNameNotOriginalName() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("Original", "Custom Display", "com.test", "com.test.Main")
        launchFragmentWithFavorites(listOf(testApp))

        Espresso.onView(ViewMatchers.isRoot())
            .perform(EspressoTestUtils.waitForView(ViewMatchers.withText("Custom Display")))

        Espresso.onView(ViewMatchers.withText("Custom Display"))
            .perform(EspressoTestUtils.nestedScrollTo())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withText("Original")).check(ViewAssertions.doesNotExist())
    }
}