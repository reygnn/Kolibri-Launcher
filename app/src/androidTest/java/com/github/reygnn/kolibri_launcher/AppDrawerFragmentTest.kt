package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.Matchers.allOf
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AppDrawerFragmentTest : BaseAndroidTest() {

    private val testApps = listOf(
        AppInfo("Alphabet", "Alphabet", "com.alphabet", "com.alphabet.MainActivity"),
        AppInfo("Zebra", "Zebra", "com.zebra", "com.zebra.MainActivity"),
        AppInfo("Apple", "Apple", "com.apple", "com.apple.MainActivity")
    )

    /**
     * Helper-Funktion, um den Zustand des UseCases zu setzen.
     * Verwendet runOnMainSync, um LiveData.setValue sicher auf dem UI-Thread auszuführen.
     */
    private fun setDrawerAppsState(apps: List<AppInfo>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            (getDrawerAppsUseCase as FakeGetDrawerAppsUseCaseRepository).drawerApps.setValue(apps)
        }
    }

    @Test
    fun drawerOpensAndDisplaysData() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)

        // Espresso wartet automatisch, bis die UI stabil ist, daher ist ein expliziter Sync hier nicht nötig.
        onView(withText("Alphabet")).check(matches(isDisplayed()))
        onView(withText("Zebra")).check(matches(isDisplayed()))
        onView(withText("Apple")).check(matches(isDisplayed()))
    }

    @Test
    fun searchField_filtersRecyclerViewCorrectly() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        // Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withText("Alphabet")).check(matches(isDisplayed())) // Synchronisiert mit der UI

        // Act
        onView(withId(R.id.search_edit_text)).perform(typeText("Zebra"))

        // Wait
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        onView(allOf(withText("Zebra"), isDescendantOfA(withId(R.id.apps_recycler_view)))).check(matches(isDisplayed()))
        onView(withText("Alphabet")).check(doesNotExist())
        onView(withText("Apple")).check(doesNotExist())
    }

    @Test
    fun longClickOnApp_opensContextMenu() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        (appUsageRepository as FakeAppUsageRepository).launchedPackages.clear()

        // Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withText("Alphabet")).check(matches(isDisplayed()))

        // Act
        onView(withText("Alphabet")).perform(longClick())

        // Assert
        onView(withText("Alphabet")).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    @Test
    fun searchField_filtersCaseInsensitive() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        // Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withText("Apple")).check(matches(isDisplayed()))

        // Act
        onView(withId(R.id.search_edit_text)).perform(replaceText("APPLE"))

        // Wait
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        onView(allOf(withText("Apple"), isDescendantOfA(withId(R.id.apps_recycler_view)))).check(matches(isDisplayed()))
        onView(withText("Alphabet")).check(doesNotExist())
    }

    @Test
    fun contextMenu_hideAppAction_updatesStateAndUI() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        val appToHide = testApps.first { it.displayName == "Alphabet" }

        // Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withText(appToHide.displayName)).check(matches(isDisplayed()))

        // Act
        onView(withText(appToHide.displayName)).perform(longClick())
        onView(withText(R.string.hide_app_from_drawer)).inRoot(isDialog()).perform(click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Warten auf ViewModel-Aktion

        // Simulate & Wait
        val remainingApps = testApps.filter { it.componentName != appToHide.componentName }
        setDrawerAppsState(remainingApps) // Synchrones Update auf dem UI-Thread

        // Assert
        onView(withText(appToHide.displayName)).check(doesNotExist())
    }

    @Test
    fun contextMenu_toggleFavoriteAction_addsToFavorites() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        val appToFavorite = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        fakeFavoritesRepo.favoritesState.value = emptySet()

        // Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withText(appToFavorite.displayName)).check(matches(isDisplayed()))

        // Act
        onView(withText(appToFavorite.displayName)).perform(longClick())
        onView(withText(R.string.add_to_favorites)).inRoot(isDialog()).perform(click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        assertThat(fakeFavoritesRepo.favorites).contains(appToFavorite.componentName)
    }

    @Test
    fun emptyAppList_displaysEmptyRecyclerView() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(emptyList())
        onView(withId(R.id.apps_recycler_view)).check(matches(hasChildCount(0)))
    }

    @Test
    fun searchWithNoResults_displaysEmptyList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        // Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withId(R.id.apps_recycler_view)).check(matches(hasChildCount(3)))

        // Act
        onView(withId(R.id.search_edit_text)).perform(typeText("NotExistingApp"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        onView(withId(R.id.apps_recycler_view)).check(matches(hasChildCount(0)))
    }

    @Test
    fun favoriteLimit_preventsAddingMoreFavorites() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        val appToAdd = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        val maxFavorites = (1..AppConstants.MAX_FAVORITES_ON_HOME).map { "com.fake.app$it" }.toSet()
        fakeFavoritesRepo.favoritesState.value = maxFavorites

        // Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withText(appToAdd.displayName)).check(matches(isDisplayed()))

        // Act
        onView(withText(appToAdd.displayName)).perform(longClick())
        onView(withText(R.string.add_to_favorites)).inRoot(isDialog()).perform(click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        assertThat(fakeFavoritesRepo.favorites).doesNotContain(appToAdd.componentName)
        assertThat(fakeFavoritesRepo.favorites).hasSize(AppConstants.MAX_FAVORITES_ON_HOME)
    }

    @Test
    fun searchField_clearsAndResetsList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        // Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withId(R.id.apps_recycler_view)).check(matches(hasChildCount(3)))

        // Act 1: Filter
        onView(withId(R.id.search_edit_text)).perform(typeText("Zebra"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.apps_recycler_view)).check(matches(hasChildCount(1)))

        // Act 2: Clear
        onView(withId(R.id.search_edit_text)).perform(clearText())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        onView(withId(R.id.apps_recycler_view)).check(matches(hasChildCount(3)))
        onView(withText("Alphabet")).check(matches(isDisplayed()))
    }
}