package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class HomeFragmentBasicTest : BaseAndroidTest() {

    @Before
    fun setup() {
    }

    // ========== LEVEL 1: Fragment startet überhaupt ==========

    @Test
    fun test_001_fragmentCanBeLaunched() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()
        }
    }

    @Test
    fun test_002_fragmentShowsRootLayout() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.root_layout)).check(matches(isDisplayed()))
        }
    }

    // ========== LEVEL 2: Statische UI-Elemente existieren ==========

    @Test
    fun test_003_timeTextViewExists() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.time_text)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun test_004_dateTextViewExists() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.date_text)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun test_005_batteryTextViewExists() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.battery_text)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun test_006_favoriteAppsContainerExists() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.favorite_apps_container))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        }
    }

    @Test
    fun test_007_timeTextHasContent() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.time_text)).check(matches(withText(not(""))))
        }
    }

    @Test
    fun test_008_dateTextHasContent() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.date_text)).check(matches(withText(not(""))))
        }
    }

    @Test
    fun test_009_batteryTextHasPercentSign() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.battery_text)).check(matches(withText(containsString("%"))))
        }
    }

    // ========== LEVEL 4: StateFlow Basis-Funktionalität ==========

    @Test
    fun test_010_emptyFavoritesStateShowsEmptyContainer() {
        testCoroutineRule.runTestAndLaunchUI {
            val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
            fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(emptyList(), false))

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
        }
    }

    @Test
    fun test_011_loadingStateShowsEmptyContainer() {
        testCoroutineRule.runTestAndLaunchUI {
            val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
            fakeUseCase.favoriteApps.value = UiState.Loading

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
        }
    }

    @Test
    fun test_012_errorStateShowsEmptyContainer() {
        testCoroutineRule.runTestAndLaunchUI {
            val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
            fakeUseCase.favoriteApps.value = UiState.Error("Test Error")

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
        }
    }

    // ========== LEVEL 5: Ein einzelner Favorit ==========

    @Test
    fun test_013_singleFavoriteCreatesOneChild() {
        testCoroutineRule.runTestAndLaunchUI {
            val testApp = AppInfo("TestApp", "TestApp", "com.test", "com.test.Main")
            val state = UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(1)))
        }
    }

    @Test
    fun test_014_singleFavoriteShowsCorrectText() {
        testCoroutineRule.runTestAndLaunchUI {
            val testApp = AppInfo("MyTestApp", "MyTestApp", "com.test", "com.test.Main")
            val state = UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("MyTestApp")).check(matches(isDisplayed()))
        }
    }

    // ========== LEVEL 6: Mehrere Favoriten ==========

    @Test
    fun test_015_threeFavoritesCreateThreeChildren() {
        testCoroutineRule.runTestAndLaunchUI {
            val apps = listOf(
                AppInfo("App1", "App1", "com.app1", "com.app1.Main"),
                AppInfo("App2", "App2", "com.app2", "com.app2.Main"),
                AppInfo("App3", "App3", "com.app3", "com.app3.Main")
            )
            val state = UiState.Success(FavoriteAppsResult(apps, false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(3)))
        }
    }

    @Test
    fun test_016_multipleFavoritesAllVisible() {
        testCoroutineRule.runTestAndLaunchUI {
            val apps = listOf(
                AppInfo("Alpha", "Alpha", "com.alpha", "com.alpha.Main"),
                AppInfo("Beta", "Beta", "com.beta", "com.beta.Main")
            )
            val state = UiState.Success(FavoriteAppsResult(apps, false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("Alpha")).check(matches(isDisplayed()))
            onView(withText("Beta")).check(matches(isDisplayed()))
        }
    }

    // ========== LEVEL 7: State Updates nach Fragment-Start ==========

    @Test
    fun test_017_stateUpdateAfterLaunchAddsButton() {
        testCoroutineRule.runTestAndLaunchUI {
            val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
            fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(emptyList(), false))
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))

            // Update State direkt auf dem Fake
            val newApp = AppInfo("NewApp", "NewApp", "com.new", "com.new.Main")
            val newState = UiState.Success(FavoriteAppsResult(listOf(newApp), false))
            fakeUseCase.favoriteApps.value = newState

            onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(1)))
            onView(withText("NewApp")).check(matches(isDisplayed()))
        }
    }

    // ========== LEVEL 8: Basis Button-Eigenschaften ==========

    @Test
    fun test_018_favoriteButtonIsClickable() {
        testCoroutineRule.runTestAndLaunchUI {
            val testApp = AppInfo("ClickMe", "ClickMe", "com.click", "com.click.Main")
            val state = UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("ClickMe")).check(matches(isClickable()))
        }
    }

    @Test
    fun test_019_favoriteButtonIsEnabled() {
        testCoroutineRule.runTestAndLaunchUI {
            val testApp = AppInfo("EnabledApp", "EnabledApp", "com.enabled", "com.enabled.Main")
            val state = UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("EnabledApp")).check(matches(isEnabled()))
        }
    }

    // ========== LEVEL 9: DisplayName vs OriginalName ==========

    @Test
    fun test_020_usesDisplayNameNotOriginalName() {
        testCoroutineRule.runTestAndLaunchUI {
            val testApp = AppInfo("Original", "Custom Display", "com.test", "com.test.Main")
            val state = UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("Custom Display")).check(matches(isDisplayed()))
            onView(withText("Original")).check(doesNotExist())
        }
    }
}