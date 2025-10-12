package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class HomeFragmentIntermediateTest : BaseAndroidTest() {

    @Before
    fun setup() {
    }

    // ========== LEVEL 1: Simple Click Tests ==========

    @Test
    fun test_101_singleButtonClick_noVerification() {
        testCoroutineRule.runTestAndLaunchUI {
            // KORREKTE AppInfo-Erstellung
            val testApp = AppInfo("ClickTest", "ClickTest", "com.click", "com.click.Main")
            val state = UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("ClickTest")).perform(click())
        }
    }

    @Test
    fun test_102_multipleButtonsClick_firstButton() {
        testCoroutineRule.runTestAndLaunchUI {
            // KORREKTE AppInfo-Erstellung
            val apps = listOf(
                AppInfo("First", "First", "com.first", "com.first.Main"),
                AppInfo("Second", "Second", "com.second", "com.second.Main")
            )
            val state = UiState.Success(FavoriteAppsResult(apps, false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("First")).perform(click())
        }
    }

    // ========== LEVEL 2: Click mit Zustands-Verifikation ==========

    @Test
    fun test_103_clickWithVerification() {
        testCoroutineRule.runTestAndLaunchUI {
            // KORREKTE AppInfo-Erstellung
            val testApp = AppInfo("ClickTest", "ClickTest", "com.click", "com.click.Main")
            val state = UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state

            launchFragmentInHiltContainer<HomeFragment>()
            onView(withText("ClickTest")).perform(click())

            val fakeRepo = appUsageRepository as FakeAppUsageRepository
            assertThat(fakeRepo.launchedPackages).contains("com.click")
        }
    }

    @Test
    fun test_104_clickPerformsAction() {
        testCoroutineRule.runTestAndLaunchUI {
            // KORREKTE AppInfo-Erstellung
            val testApp = AppInfo("FastTest", "FastTest", "com.fast", "com.fast.Main")
            val state = UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                state
            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("FastTest")).perform(click())

            val fakeRepo = appUsageRepository as FakeAppUsageRepository
            assertThat(fakeRepo.launchedPackages).contains("com.fast")
        }
    }

    // ========== LEVEL 3: State-Updates während Fragment läuft ==========

    @Test
    fun test_105_addButtonWhileRunning() {
        testCoroutineRule.runTestAndLaunchUI {
            // KORREKTE AppInfo-Erstellung
            val initialApp = AppInfo("Initial", "Initial", "com.init", "com.init.Main")
            val initialState = UiState.Success(FavoriteAppsResult(listOf(initialApp), false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                initialState
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withText("Initial")).check(matches(isDisplayed()))

            val newApps = listOf(
                initialApp,
                AppInfo("Added", "Added", "com.added", "com.added.Main")
            )
            val newState = UiState.Success(FavoriteAppsResult(newApps, false))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                newState

            onView(withText("Initial")).check(matches(isDisplayed()))
            onView(withText("Added")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun test_106_removeButtonWhileRunning() {
        testCoroutineRule.runTestAndLaunchUI {
            // KORREKTE AppInfo-Erstellung
            val apps = listOf(
                AppInfo("Keep", "Keep", "com.keep", "com.keep.Main"),
                AppInfo("Remove", "Remove", "com.remove", "com.remove.Main")
            )
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                UiState.Success(FavoriteAppsResult(apps, false))
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withText("Keep")).check(matches(isDisplayed()))
            onView(withText("Remove")).check(matches(isDisplayed()))

            val newApps = listOf(AppInfo("Keep", "Keep", "com.keep", "com.keep.Main"))
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                UiState.Success(FavoriteAppsResult(newApps, false))

            onView(withText("Keep")).check(matches(isDisplayed()))
            onView(withText("Remove")).check(doesNotExist())
        }
    }

    // ========== LEVEL 4: Mehrfache Clicks ==========

    @Test
    fun test_107_clickSameButtonTwice() {
        testCoroutineRule.runTestAndLaunchUI {
            // KORREKTE AppInfo-Erstellung
            val testApp = AppInfo("DoubleClick", "DoubleClick", "com.double", "com.double.Main")
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("DoubleClick")).perform(click())
            onView(withText("DoubleClick")).perform(click())

            val fakeRepo = appUsageRepository as FakeAppUsageRepository
            assertThat(fakeRepo.launchedPackages).hasSize(2)
            assertThat(fakeRepo.launchedPackages).containsExactly("com.double", "com.double")
                .inOrder()
        }
    }

    @Test
    fun test_108_clickDifferentButtons() {
        testCoroutineRule.runTestAndLaunchUI {
            // Der Rest deines Tests bleibt EXAKT GLEICH
            val apps = listOf(
                AppInfo("App1", "App1", "com.app1", "com.app1.Main"),
                AppInfo("App2", "App2", "com.app2", "com.app2.Main")
            )
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                UiState.Success(FavoriteAppsResult(apps, false))
            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("App1")).perform(click())
            onView(withText("App2")).perform(click())

            val fakeRepo = appUsageRepository as FakeAppUsageRepository
            assertThat(fakeRepo.launchedPackages).containsExactly("com.app1", "com.app2").inOrder()
        }
    }

    // ========== LEVEL 5: State Transitions ==========

    @Test
    fun test_109_transitionFromLoadingToSuccess() {
        testCoroutineRule.runTestAndLaunchUI {
            val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
            fakeUseCase.favoriteApps.value = UiState.Loading
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))

            // KORREKTE AppInfo-Erstellung
            val apps = listOf(AppInfo("Loaded", "Loaded", "com.loaded", "com.loaded.Main"))
            fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(apps, false))

            onView(withText("Loaded")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun test_110_transitionFromSuccessToError() {
        testCoroutineRule.runTestAndLaunchUI {
            val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
            // KORREKTE AppInfo-Erstellung
            val apps = listOf(AppInfo("WillFail", "WillFail", "com.fail", "com.fail.Main"))
            fakeUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(apps, false))
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withText("WillFail")).check(matches(isDisplayed()))

            fakeUseCase.favoriteApps.value = UiState.Error("Test Error")

            onView(withId(R.id.favorite_apps_container)).check(matches(hasChildCount(0)))
        }
    }

    // ========== LEVEL 6: Custom Display Names ==========

    @Test
    fun test_111_displayNameDifferentFromOriginal() {
        testCoroutineRule.runTestAndLaunchUI {
            // KORREKTE AppInfo-Erstellung
            val testApp =
                AppInfo("Original Name", "Custom Display", "com.custom", "com.custom.Main")
            (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value =
                UiState.Success(FavoriteAppsResult(listOf(testApp), false))
            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("Custom Display")).perform(click())

            val fakeRepo = appUsageRepository as FakeAppUsageRepository
            assertThat(fakeRepo.launchedPackages).contains("com.custom")
        }
    }

    @Test
    fun test_112_updateDisplayNameWhileRunning() {
        testCoroutineRule.runTestAndLaunchUI {
            val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
            // KORREKTE AppInfo-Erstellung
            val initialApp = AppInfo("Original", "Original", "com.test", "com.test.Main")
            fakeUseCase.favoriteApps.value =
                UiState.Success(FavoriteAppsResult(listOf(initialApp), false))
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withText("Original")).check(matches(isDisplayed()))

            // KORREKTE AppInfo-Erstellung
            val updatedApp = AppInfo("Original", "New Custom Name", "com.test", "com.test.Main")
            fakeUseCase.favoriteApps.value =
                UiState.Success(FavoriteAppsResult(listOf(updatedApp), false))

            onView(withText("Original")).check(doesNotExist())
            onView(withText("New Custom Name")).check(matches(isDisplayed()))
        }
    }

    // ========== Dieser Test ist jetzt der "Simple Works" Test ==========
    @Test
    fun test_fake_repository_is_injected_and_works() {
        testCoroutineRule.runTestAndLaunchUI {
            val fakeRepo = appUsageRepository as FakeAppUsageRepository

            assertThat(fakeRepo.launchedPackages).isEmpty()

            fakeRepo.recordPackageLaunch("test.package")

            assertThat(fakeRepo.launchedPackages).contains("test.package")
        }
    }

}