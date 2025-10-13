package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
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
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        onView(withText("Alphabet")).check(matches(isDisplayed()))
        onView(withText("Zebra")).check(matches(isDisplayed()))
        onView(withText("Apple")).check(matches(isDisplayed()))
    }

    @Test
    fun searchField_filtersRecyclerViewCorrectly() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        onView(withText("Alphabet")).check(matches(isDisplayed()))

        onView(withId(R.id.search_edit_text)).perform(typeText("Zebra"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        onView(allOf(withText("Zebra"), isDescendantOfA(withId(R.id.apps_recycler_view)))).check(matches(isDisplayed()))
        onView(withText("Alphabet")).check(doesNotExist())
    }

    @Test
    fun longClickOnApp_opensContextMenu() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        (appUsageRepository as FakeAppUsageRepository).launchedPackages.clear()

        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        onView(withText("Alphabet")).check(matches(isDisplayed()))

        onView(withText("Alphabet")).perform(longClick())

        onView(withText("Alphabet")).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    @Test
    fun searchField_filtersCaseInsensitive() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        onView(withText("Apple")).check(matches(isDisplayed()))

        onView(withId(R.id.search_edit_text)).perform(replaceText("APPLE"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        onView(allOf(withText("Apple"), isDescendantOfA(withId(R.id.apps_recycler_view)))).check(matches(isDisplayed()))
        onView(withText("Alphabet")).check(doesNotExist())
    }

    @Test
    fun contextMenu_hideAppAction_updatesStateAndUI() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        val appToHide = testApps.first { it.displayName == "Alphabet" }

        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        onView(withText(appToHide.displayName)).check(matches(isDisplayed()))

        onView(withText(appToHide.displayName)).perform(longClick())
        onView(withText(R.string.hide_app_from_drawer)).inRoot(isDialog()).perform(click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val remainingApps = testApps.filter { it.componentName != appToHide.componentName }
        setDrawerAppsState(remainingApps)

        onView(withText(appToHide.displayName)).check(doesNotExist())
    }

    @Test
    fun contextMenu_toggleFavoriteAction_addsToFavorites() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        val appToFavorite = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        val fakeFavoriteAppsUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository
        fakeFavoritesRepo.favoritesState.value = emptySet()

        launchFragmentInHiltContainer<AppDrawerFragment>()
        fakeFavoriteAppsUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(apps = emptyList(), isFallback = false))
        setDrawerAppsState(testApps)
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        onView(withText(appToFavorite.displayName)).check(matches(isDisplayed()))

        onView(withText(appToFavorite.displayName)).perform(longClick())
        onView(withText(R.string.add_to_favorites)).inRoot(isDialog()).perform(click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        assertThat(fakeFavoritesRepo.favorites).contains(appToFavorite.componentName)
    }

    @Test
    fun emptyAppList_displaysEmptyRecyclerView() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(emptyList())
        onView(withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(0))
    }

    @Test
    fun searchWithNoResults_displaysEmptyList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        onView(withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(3))

        onView(withId(R.id.search_edit_text)).perform(typeText("NotExistingApp"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        onView(withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(0))
    }

    @Test
    fun favoriteLimit_preventsAddingMoreFavorites() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        val appToAdd = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        val fakeFavoriteAppsUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository

        // --- KORREKTUR: SCHRITT 1: ERSTELLE EINEN KONSISTENTEN ZUSTAND ---
        // Diese App-Liste dient sowohl als "installierte Apps" als auch als "Favoriten".
        val maxFavoriteApps = (1..AppConstants.MAX_FAVORITES_ON_HOME).map {
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
        fakeFavoriteAppsUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(apps = maxFavoriteApps, isFallback = false))

        // Warte, bis die UI bereit ist
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        onView(withText(appToAdd.displayName)).check(matches(isDisplayed()))

        // --- SCHRITT 3: FÜHRE DIE AKTION AUS ---
        onView(withText(appToAdd.displayName)).perform(longClick())
        onView(withText(R.string.add_to_favorites)).inRoot(isDialog()).perform(click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // --- SCHRITT 4: ÜBERPRÜFE DAS ENDERGEBNIS ---
        // Die Favoritenliste sollte unverändert sein.
        assertThat(fakeFavoritesRepo.favorites).doesNotContain(appToAdd.componentName)
        assertThat(fakeFavoritesRepo.favorites).hasSize(AppConstants.MAX_FAVORITES_ON_HOME)
    }

    @Test
    fun searchField_clearsAndResetsList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        onView(withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(3))

        onView(withId(R.id.search_edit_text)).perform(typeText("Zebra"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())
        onView(withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(1))

        onView(withId(R.id.search_edit_text)).perform(replaceText(""))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.apps_recycler_view)).perform(EspressoTestUtils.waitForUiThread())

        onView(withId(R.id.apps_recycler_view)).check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(3))
        onView(withText("Alphabet")).check(matches(isDisplayed()))
    }
}