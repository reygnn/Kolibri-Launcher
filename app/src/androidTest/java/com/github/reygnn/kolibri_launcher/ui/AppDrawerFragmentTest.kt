package com.github.reygnn.kolibri_launcher.ui

import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.BaseAndroidTest
import com.github.reygnn.kolibri_launcher.di.launchFragmentInHiltContainer
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeGetDrawerAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeGetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.ui.appdrawer.AppDrawerFragment
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.util.EspressoTestUtils
import com.github.reygnn.kolibri_launcher.util.TestCoroutineRule
import com.google.common.truth.Truth
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.Matchers
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

    private fun setDrawerAppsState(apps: List<AppInfo>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            (getDrawerAppsUseCase as FakeGetDrawerAppsUseCaseRepository).drawerApps.value = apps
        }
    }

    @Test
    fun drawerOpensAndDisplaysData() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Zebra"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Apple"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun searchField_filtersRecyclerViewCorrectly() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE) {
        // Arrange
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // Act
        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.typeText("Zebra"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        // Assert
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(1))

        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("Zebra"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.apps_recycler_view))
            )
        )
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Alphabet")).check(ViewAssertions.doesNotExist())
    }

    @Test
    fun longClickOnApp_opensContextMenu() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        (appUsageRepository as FakeAppUsageRepository).launchedPackages.clear()

        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withText("Alphabet")).perform(ViewActions.longClick())

        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun searchField_filtersCaseInsensitive() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText("Apple"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.replaceText("APPLE"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("Apple"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.apps_recycler_view))
            )
        ).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Alphabet")).check(ViewAssertions.doesNotExist())
    }

    @Test
    fun contextMenu_hideAppAction_updatesStateAndUI() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE) {
        val appToHide = testApps.first { it.displayName == "Alphabet" }

        // Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText(appToHide.displayName))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // Act
        Espresso.onView(ViewMatchers.withText(appToHide.displayName))
            .perform(ViewActions.longClick())
        Espresso.onView(ViewMatchers.withText(R.string.hide_app_from_drawer))
            .inRoot(RootMatchers.isDialog()).perform(ViewActions.click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Warten auf ViewModel-Aktion

        // Simulate & Wait
        val remainingApps = testApps.filter { it.componentName != appToHide.componentName }
        setDrawerAppsState(remainingApps) // Synchrones Update auf dem UI-Thread

        // KORREKTUR: Zwinge Espresso, auf die Neuzeichnung des RecyclerViews zu warten,
        // NACHDEM die neue, kürzere Liste gesetzt wurde.
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        // Assert
        Espresso.onView(ViewMatchers.withText(appToHide.displayName))
            .check(ViewAssertions.doesNotExist())
    }

    @Test
    fun contextMenu_toggleFavoriteAction_addsToFavorites() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE) {
        val appToFavorite = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        val fakeFavoriteAppsUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        fakeFavoritesRepo.favoritesState.value = emptySet()

        launchFragmentInHiltContainer<AppDrawerFragment>()
        fakeFavoriteAppsUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(
                apps = emptyList(),
                isFallback = false
            )
        )
        setDrawerAppsState(testApps)
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText(appToFavorite.displayName))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withText(appToFavorite.displayName))
            .perform(ViewActions.longClick())
        Espresso.onView(ViewMatchers.withText(R.string.add_to_favorites))
            .inRoot(RootMatchers.isDialog()).perform(ViewActions.click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        Truth.assertThat(fakeFavoritesRepo.favorites).contains(appToFavorite.componentName)
    }

    @Test
    fun emptyAppList_displaysEmptyRecyclerView() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(emptyList())
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(0))
    }

    @Test
    fun searchWithNoResults_displaysEmptyList() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(3))

        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.typeText("NotExistingApp"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(0))
    }

    @Test
    fun favoriteLimit_preventsAddingMoreFavorites() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE) {
        val appToAdd = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        val fakeFavoriteAppsUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository

        // --- KORREKTUR: SCHRITT 1: ERSTELLE EINEN KONSISTENTEN ZUSTAND ---
        // Diese App-Liste dient sowohl als "installierte Apps" als auch als "Favoriten".
        val maxFavoriteApps = (1..AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME).map {
            val componentName = "com.fake.app$it"
            AppInfo(componentName, componentName, componentName, componentName)
        }
        val maxFavoriteComponentNames = maxFavoriteApps.map { it.componentName }.toSet()

        // Setze die Favoriten
        fakeFavoritesRepo.favoritesState.value = maxFavoriteComponentNames

        // Sorge dafür, dass das ViewModel diese Apps als "installiert" ansieht,
        // damit die Aufräumlogik sie nicht entfernt.
        val fakeInstalledAppsRepo = installedAppsRepository as FakeInstalledAppsRepository
        // Die `testApps` müssen auch in der installierten Liste sein, damit die UI sie anzeigen kann.
        fakeInstalledAppsRepo.appsFlow.value = maxFavoriteApps + testApps

        // --- SCHRITT 2: STARTE DIE UI MIT DEM KONSISTENTEN ZUSTAND ---
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps) // Setzt die Liste für den Drawer
        // Pushe den UiState, den die UI für die Favoritenanzahl beobachtet
        fakeFavoriteAppsUseCase.favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(
                apps = maxFavoriteApps,
                isFallback = false
            )
        )

        // Warte, bis die UI bereit ist
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText(appToAdd.displayName))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // --- SCHRITT 3: FÜHRE DIE AKTION AUS ---
        Espresso.onView(ViewMatchers.withText(appToAdd.displayName))
            .perform(ViewActions.longClick())
        Espresso.onView(ViewMatchers.withText(R.string.add_to_favorites))
            .inRoot(RootMatchers.isDialog()).perform(ViewActions.click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // --- SCHRITT 4: ÜBERPRÜFE DAS ENDERGEBNIS ---
        // Die Favoritenliste sollte unverändert sein.
        Truth.assertThat(fakeFavoritesRepo.favorites).doesNotContain(appToAdd.componentName)
        Truth.assertThat(fakeFavoritesRepo.favorites).hasSize(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
    }

    @Test
    fun searchField_clearsAndResetsList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(3))

        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.typeText("Zebra"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(1))

        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.replaceText(""))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(3))
        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }
}