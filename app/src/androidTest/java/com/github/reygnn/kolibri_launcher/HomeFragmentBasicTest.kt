package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.EspressoTestUtils.nestedScrollTo
import com.github.reygnn.kolibri_launcher.EspressoTestUtils.waitForView
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.home.HomeFragment
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
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

        onView(withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))
    }


    @Test
    fun fragmentCanBeLaunched() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        // ID Update: root_layout -> rootLayout
        onView(withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withId(R.id.rootLayout)).check(matches(isDisplayed()))
    }

    @Test
    fun staticUiElementsAreDisplayed() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchFragmentWithFavorites(emptyList())

        // ID Updates: time_text -> timeText, etc.
        onView(withId(R.id.timeText)).check(matches(isDisplayed()))
        onView(withId(R.id.dateText)).check(matches(isDisplayed()))
        onView(withId(R.id.batteryText)).check(matches(isDisplayed()))

        // ID Update: favorite_apps_list -> appList
        // appList ist der Container innerhalb der ScrollView
        onView(withId(R.id.appList))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
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

        onView(withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        // ID Updates
        onView(withId(R.id.timeText)).check(matches(withText(not(""))))
        onView(withId(R.id.dateText)).check(matches(withText(not(""))))
        onView(withId(R.id.batteryText)).check(matches(withText(containsString("%"))))
    }

    @Test
    fun emptyFavoritesStateShowsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchFragmentWithFavorites(emptyList())
        // ID Update: favorite_apps_list -> appList
        onView(withId(R.id.appList)).check(matches(hasChildCount(0)))
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

        onView(withId(R.id.appList))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withId(R.id.appList)).check(matches(hasChildCount(0)))
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

        onView(withId(R.id.appList))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withId(R.id.appList)).check(matches(hasChildCount(0)))
    }

    @Test
    fun singleFavoriteShowsOneButtonWithCorrectText() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("MyTestApp", "MyTestApp", "com.test", "com.test.Main")
        launchFragmentWithFavorites(listOf(testApp))

        // 1. WARTEN bis der Text "MyTestApp" in der Hierarchie auftaucht
        onView(isRoot()).perform(waitForView(withText("MyTestApp")))

        // 2. Scrollen (falls nötig) und Prüfen
        onView(withText("MyTestApp"))
            .perform(nestedScrollTo())
            .check(matches(isDisplayed()))

        onView(withId(R.id.appList)).check(matches(hasChildCount(1)))
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
        onView(isRoot()).perform(waitForView(withText("App3")))

        // Check App 1
        onView(withText("App1"))
            .perform(nestedScrollTo())
            .check(matches(isDisplayed()))

        // Check App 2
        onView(withText("App2"))
            .perform(nestedScrollTo())
            .check(matches(isDisplayed()))

        // Check App 3
        onView(withText("App3"))
            .perform(nestedScrollTo())
            .check(matches(isDisplayed()))

        onView(withId(R.id.appList)).check(matches(hasChildCount(3)))
    }

    @Test
    fun stateUpdateAfterLaunchAddsButton() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        // Erstmal leer starten
        launchFragmentWithFavorites(emptyList())

        onView(withText("NewApp")).check(doesNotExist())
        onView(withId(R.id.appList)).check(matches(hasChildCount(0)))

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
        onView(isRoot()).perform(waitForView(withText("NewApp")))

        onView(withText("NewApp"))
            .perform(nestedScrollTo())
            .check(matches(isDisplayed()))

        onView(withId(R.id.appList)).check(matches(hasChildCount(1)))
    }

    @Test
    fun favoriteButtonIsClickableAndEnabled() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("ClickMe", "ClickMe", "com.click", "com.click.Main")
        launchFragmentWithFavorites(listOf(testApp))

        onView(isRoot()).perform(waitForView(withText("ClickMe")))

        onView(withText("ClickMe"))
            .perform(nestedScrollTo())
            .check(matches(isClickable()))
            .check(matches(isEnabled()))
    }

    @Test
    fun usesDisplayNameNotOriginalName() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("Original", "Custom Display", "com.test", "com.test.Main")
        launchFragmentWithFavorites(listOf(testApp))

        onView(isRoot()).perform(waitForView(withText("Custom Display")))

        onView(withText("Custom Display"))
            .perform(nestedScrollTo())
            .check(matches(isDisplayed()))

        onView(withText("Original")).check(doesNotExist())
    }
}