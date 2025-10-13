package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Test
    fun fragmentCanBeLaunched() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        launchFragmentInHiltContainer<HomeFragment>()
        onView(withId(R.id.root_layout)).check(matches(isDisplayed()))
    }

    @Test
    fun staticUiElementsAreDisplayed() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        launchFragmentInHiltContainer<HomeFragment>()
        EspressoTestUtils.waitForUiIdle()

        onView(withId(R.id.time_text)).check(matches(isDisplayed()))
        onView(withId(R.id.date_text)).check(matches(isDisplayed()))
        onView(withId(R.id.battery_text)).check(matches(isDisplayed()))
        onView(withId(R.id.favorite_apps_container)).check(matches(isDisplayed()))
    }

    @Test
    fun dynamicTextViewsHaveContent() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        launchFragmentInHiltContainer<HomeFragment>()
        EspressoTestUtils.waitForUiIdleShort()

        onView(withId(R.id.time_text)).check(matches(withText(not(""))))
        onView(withId(R.id.date_text)).check(matches(withText(not(""))))
        onView(withId(R.id.battery_text)).check(matches(withText(containsString("%"))))
    }

    @Test
    fun emptyFavoritesStateShowsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(emptyList(), false))

        launchFragmentInHiltContainer<HomeFragment>()
        EspressoTestUtils.waitForUiIdleShort()

        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
    }

    @Test
    fun loadingStateShowsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        fakeUseCase.favoriteApps.value = UiState.Loading

        launchFragmentInHiltContainer<HomeFragment>()
        EspressoTestUtils.waitForUiIdleShort()

        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
    }

    @Test
    fun errorStateShowsEmptyContainer() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        fakeUseCase.favoriteApps.value = UiState.Error("Test Error")

        launchFragmentInHiltContainer<HomeFragment>()
        EspressoTestUtils.waitForUiIdleShort()

        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
    }

    @Test
    fun singleFavoriteShowsOneButtonWithCorrectText() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        launchFragmentInHiltContainer<HomeFragment>()

        val testApp = AppInfo("MyTestApp", "MyTestApp", "com.test", "com.test.Main")
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(listOf(testApp), false))

        EspressoTestUtils.waitForUiIdle()

        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(1)))
        onView(withText("MyTestApp")).check(matches(isDisplayed()))
    }

    @Test
    fun threeFavoritesShowThreeButtons() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        launchFragmentInHiltContainer<HomeFragment>()

        val apps = listOf(
            AppInfo("App1", "App1", "com.app1", "com.app1.Main"),
            AppInfo("App2", "App2", "com.app2", "com.app2.Main"),
            AppInfo("App3", "App3", "com.app3", "com.app3.Main")
        )
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(apps, false))

        EspressoTestUtils.waitForUiIdle()

        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(3)))
        onView(withText("App1")).check(matches(isDisplayed()))
        onView(withText("App2")).check(matches(isDisplayed()))
        onView(withText("App3")).check(matches(isDisplayed()))
    }

    @Test
    fun stateUpdateAfterLaunchAddsButton() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository

        // Start mit leerem Zustand
        fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(emptyList(), false))
        launchFragmentInHiltContainer<HomeFragment>()
        EspressoTestUtils.waitForUiIdle()

        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))

        // Zustand aktualisieren
        val newApp = AppInfo("NewApp", "NewApp", "com.new", "com.new.Main")
        fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(listOf(newApp), false))

        EspressoTestUtils.waitForUiIdle()

        onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(1)))
        onView(withText("NewApp")).check(matches(isDisplayed()))
    }

    @Test
    fun favoriteButtonIsClickableAndEnabled() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        launchFragmentInHiltContainer<HomeFragment>()

        val testApp = AppInfo("ClickMe", "ClickMe", "com.click", "com.click.Main")
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(listOf(testApp), false))

        EspressoTestUtils.waitForUiIdle()

        onView(withText("ClickMe")).check(matches(isClickable()))
        onView(withText("ClickMe")).check(matches(isEnabled()))
    }

    @Test
    fun usesDisplayNameNotOriginalName() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.DIRTY) {
        launchFragmentInHiltContainer<HomeFragment>()

        val testApp = AppInfo("Original", "Custom Display", "com.test", "com.test.Main")
        val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(listOf(testApp), false))

        EspressoTestUtils.waitForUiIdle()

        onView(withText("Custom Display")).check(matches(isDisplayed()))
        onView(withText("Original")).check(doesNotExist())
    }
}