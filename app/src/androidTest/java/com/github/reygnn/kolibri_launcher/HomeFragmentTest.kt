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
import com.google.common.truth.Truth.assertThat
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
class HomeFragmentTest : BaseAndroidTest() {

    @Before
    fun setup() {
    }

    private fun setFavoriteAppsState(apps: List<AppInfo>, isFallback: Boolean = false) {
        val successState = UiState.Success(FavoriteAppsResult(apps, isFallback))
        (getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository).favoriteApps.value = successState
    }

    @Test
    fun basicTest_displaysFavoriteApps() {
        testCoroutineRule.runTestAndLaunchUI {
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
            setFavoriteAppsState(testFavorites)

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("Test Favorite 1")).check(matches(isDisplayed()))
            onView(withText("Test Favorite 2")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun clickOnFavorite_recordsAppLaunch() {
        testCoroutineRule.runTestAndLaunchUI {
            val testApp = AppInfo("Mail", "Mail", "com.mail", "com.mail.MainActivity")
            setFavoriteAppsState(listOf(testApp))

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("Mail")).perform(click())

            val fakeRepo = appUsageRepository as FakeAppUsageRepository
            assertThat(fakeRepo.launchedPackages).contains("com.mail")
        }
    }

    @Test
    fun longClickOnFavorite_opensContextMenu() {
        testCoroutineRule.runTestAndLaunchUI {
            val testApp = AppInfo("Mail", "Mail", "com.mail", "com.mail.MainActivity")
            setFavoriteAppsState(listOf(testApp))

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("Mail")).perform(longClick())

            // Überprüfe, ob der Dialog mit dem App-Namen erscheint
            onView(withText(testApp.displayName))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun emptyFavorites_showsEmptyContainer() {
        testCoroutineRule.runTestAndLaunchUI {
            setFavoriteAppsState(emptyList())

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withId(R.id.favorite_apps_container))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
                .check(matches(hasChildCount(0)))
        }
    }

    @Test
    fun contextMenu_toggleFavoriteAction() {
        testCoroutineRule.runTestAndLaunchUI {
            val testApp = AppInfo("Mail", "Mail", "com.mail", "com.mail.MainActivity")
            val fakeFavRepo = favoritesRepository as FakeFavoritesRepository
            fakeFavRepo.favoritesState.value = setOf(testApp.componentName) // Setze den initialen Zustand
            setFavoriteAppsState(listOf(testApp))

            launchFragmentInHiltContainer<HomeFragment>()

            onView(withText("Mail")).perform(longClick())

            // Klicke auf "Remove from favorites" im Dialog
            onView(withText(R.string.remove_from_favorites)).inRoot(isDialog()).perform(click())

            // Überprüfe, ob die Methode im Fake aufgerufen wurde (indirekt über den Zustand)
            assertThat(fakeFavRepo.favorites).doesNotContain(testApp.componentName)
        }
    }

    @Test
    fun favoriteAppsUpdate_refreshesUI() {
        testCoroutineRule.runTestAndLaunchUI {
            val initialFavorites =
                listOf(AppInfo("App One", "App One", "com.one", "com.one.MainActivity"))
            val updatedFavorites = listOf(
                AppInfo("App Two", "App Two", "com.two", "com.two.MainActivity"),
                AppInfo("App Three", "App Three", "com.three", "com.three.MainActivity")
            )
            val fakeUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository

            setFavoriteAppsState(initialFavorites)
            launchFragmentInHiltContainer<HomeFragment>()
            onView(withText("App One")).check(matches(isDisplayed()))

            // Simuliere ein Update
            fakeUseCase.favoriteApps.value =
                UiState.Success(FavoriteAppsResult(updatedFavorites, false))

            onView(withText("App One")).check(doesNotExist())
            onView(withText("App Two")).check(matches(isDisplayed()))
            onView(withText("App Three")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun timeDateAndBattery_areDisplayedOnHomeScreen() {
        testCoroutineRule.runTestAndLaunchUI {
            launchFragmentInHiltContainer<HomeFragment>()

            onView(withId(R.id.time_text)).check(matches(isDisplayed()))
                .check(matches(withText(not(""))))
            onView(withId(R.id.date_text)).check(matches(isDisplayed()))
                .check(matches(withText(not(""))))
            onView(withId(R.id.battery_text)).check(matches(isDisplayed()))
                .check(matches(withText(containsString("%"))))
        }
    }

}