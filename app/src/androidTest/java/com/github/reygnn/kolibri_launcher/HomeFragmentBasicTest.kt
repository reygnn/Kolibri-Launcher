package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        }

        launchAndTrackFragment<HomeFragment>()

        instrumentation.runOnMainSync {
            val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
            val fakeInstalledAppsRepo = installedAppsRepository as FakeInstalledAppsRepository

            fakeInstalledAppsRepo.appsFlow.value = apps
            fakeUseCase.favoriteApps.value = UiState.Success(
                FavoriteAppsResult(apps, isFallback = false)
            )
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withId(R.id.root_layout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))
    }

    @Test
    fun fragmentCanBeLaunched() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.root_layout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withId(R.id.root_layout)).check(matches(isDisplayed()))
    }

    @Test
    fun staticUiElementsAreDisplayed() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchFragmentWithFavorites(emptyList())

        onView(withId(R.id.time_text)).check(matches(isDisplayed()))
        onView(withId(R.id.date_text)).check(matches(isDisplayed()))
        onView(withId(R.id.battery_text)).check(matches(isDisplayed()))

        // ✅ KRITISCH: Für leere Container mit height=0 verwende withEffectiveVisibility
        onView(withId(R.id.favorite_apps_container))
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

        onView(withId(R.id.root_layout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        onView(withId(R.id.time_text)).check(matches(withText(not(""))))
        onView(withId(R.id.date_text)).check(matches(withText(not(""))))
        onView(withId(R.id.battery_text)).check(matches(withText(containsString("%"))))
    }

    @Test
    fun emptyFavoritesStateShowsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchFragmentWithFavorites(emptyList())
        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
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
                .favoriteApps.value = UiState.Loading
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withId(R.id.favorite_apps_container))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
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
                .favoriteApps.value = UiState.Error("Test Error")
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withId(R.id.favorite_apps_container))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
    }

    @Test
    fun singleFavoriteShowsOneButtonWithCorrectText() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("MyTestApp", "MyTestApp", "com.test", "com.test.Main")
        launchFragmentWithFavorites(listOf(testApp))

        onView(withText("MyTestApp")).check(matches(isDisplayed()))
        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(1)))
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

        onView(withText("App1")).check(matches(isDisplayed()))
        onView(withText("App2")).check(matches(isDisplayed()))
        onView(withText("App3")).check(matches(isDisplayed()))
        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(3)))
    }

    @Test
    fun stateUpdateAfterLaunchAddsButton() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchFragmentWithFavorites(emptyList())

        onView(withText("NewApp")).check(doesNotExist())
        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val newApp = AppInfo("NewApp", "NewApp", "com.new", "com.new.Main")

        instrumentation.runOnMainSync {
            (installedAppsRepository as FakeInstalledAppsRepository).appsFlow.value = listOf(newApp)
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                UiState.Success(FavoriteAppsResult(listOf(newApp), isFallback = false))
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withId(R.id.favorite_apps_container))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        onView(withText("NewApp")).check(matches(isDisplayed()))
        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(1)))
    }

    @Test
    fun favoriteButtonIsClickableAndEnabled() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("ClickMe", "ClickMe", "com.click", "com.click.Main")
        launchFragmentWithFavorites(listOf(testApp))

        onView(withText("ClickMe")).check(matches(isClickable()))
        onView(withText("ClickMe")).check(matches(isEnabled()))
    }

    @Test
    fun usesDisplayNameNotOriginalName() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("Original", "Custom Display", "com.test", "com.test.Main")
        launchFragmentWithFavorites(listOf(testApp))

        onView(withText("Custom Display")).check(matches(isDisplayed()))
        onView(withText("Original")).check(doesNotExist())
    }
}