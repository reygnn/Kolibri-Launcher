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
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AppDrawerFragmentTest : BaseAndroidTest() {

    // Wir definieren unsere Test-Daten zentral
    private val testApps = listOf(
        AppInfo("Alphabet", "Alphabet", "com.alphabet", "com.alphabet.MainActivity"),
        AppInfo("Zebra", "Zebra", "com.zebra", "com.zebra.MainActivity"),
        AppInfo("Apple", "Apple", "com.apple", "com.apple.MainActivity")
    )

    @Before
    fun setup() {
    }

    /** Helper-Funktion, um den Zustand des UseCases zu setzen. */
    private fun setDrawerAppsState(apps: List<AppInfo>) {
        // Hole die Instrumentation-Instanz, um auf dem UI-Thread arbeiten zu können
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Führe den Code synchron auf dem echten UI-Thread aus
        instrumentation.runOnMainSync {
            (getDrawerAppsUseCase as FakeGetDrawerAppsUseCaseRepository).drawerApps.setValue(apps)
        }
    }

    @Test
    fun drawerOpensAndDisplaysData() = testCoroutineRule.runTestAndLaunchUI {
        setDrawerAppsState(testApps)

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withText("Alphabet")).check(matches(isDisplayed()))
        onView(withText("Zebra")).check(matches(isDisplayed()))
        onView(withText("Apple")).check(matches(isDisplayed()))
    }

    @Test
    fun searchField_filtersRecyclerViewCorrectly() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        // 1. Arrange: Daten synchron auf dem UI-Thread setzen
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        // 2. Act: Eine Aktion im Test-Thread ausführen
        onView(withId(R.id.search_edit_text)).perform(typeText("Zebra"))

        // 3. Warten: Die durch die Aktion ausgelöste Coroutine im Test-Scheduler abarbeiten lassen
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // 4. Assert: Das Ergebnis in der UI überprüfen
        onView(withText("Zebra")).check(matches(isDisplayed()))
        onView(withText("Alphabet")).check(doesNotExist())
    }

    @Test
    fun longClickOnApp_opensContextMenu() = testCoroutineRule.runTestAndLaunchUI {
        setDrawerAppsState(testApps)
        // Stelle sicher, dass für den Test keine Nutzungsdaten existieren
        (appUsageRepository as FakeAppUsageRepository).launchedPackages.clear()
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withText("Alphabet")).perform(longClick())

        // Überprüft, ob der Dialog mit dem korrekten App-Namen geöffnet wird.
        onView(withText("Alphabet"))
            .inRoot(isDialog())
            .check(matches(isDisplayed()))
    }

    @Test
    fun searchField_filtersCaseInsensitive() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) { // SAFE-Modus
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withId(R.id.search_edit_text)).perform(replaceText("APPLE"))

        // WARTEN auf die Filter-Aktion
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withText("Apple")).check(matches(isDisplayed()))
        onView(withText("Alphabet")).check(doesNotExist())
    }

    @Test
    fun contextMenu_hideAppAction_updatesStateAndUI() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        val appToHide = testApps.first { it.displayName == "Alphabet" }

        // 1. Arrange
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        // 2. Act
        onView(withText(appToHide.displayName)).perform(longClick())
        onView(withText(R.string.hide_app_from_drawer)).inRoot(isDialog()).perform(click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Warten auf ViewModel-Aktion

        // 3. Simulate & Wait
        val remainingApps = testApps.filter { it.componentName != appToHide.componentName }
        setDrawerAppsState(remainingApps) // Synchrones Update auf dem UI-Thread
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Warten auf die UI-Reaktion

        // 4. Assert
        onView(withText(appToHide.displayName)).check(doesNotExist())
    }

    @Test
    fun contextMenu_toggleFavoriteAction_addsToFavorites() = testCoroutineRule.runTestAndLaunchUI {
        val appToFavorite = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository

        // Sicherstellen, dass die App initial kein Favorit ist
        fakeFavoritesRepo.favoritesState.value = emptySet()

        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withText(appToFavorite.displayName)).perform(longClick())
        onView(withText(R.string.add_to_favorites)).inRoot(isDialog()).perform(click())

        // Überprüfen, ob der Fake-State korrekt aktualisiert wurde
        assertThat(fakeFavoritesRepo.favorites).contains(appToFavorite.componentName)
    }

    @Test
    fun emptyAppList_displaysEmptyRecyclerView() = testCoroutineRule.runTestAndLaunchUI {
        setDrawerAppsState(emptyList())
        launchFragmentInHiltContainer<AppDrawerFragment>()

        // Eine benutzerdefinierte Assertion ist gut, aber hasChildCount(0) ist oft einfacher.
        onView(withId(R.id.apps_recycler_view)).check(matches(hasChildCount(0)))
    }

    @Test
    fun searchWithNoResults_displaysEmptyList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) { // SAFE-Modus
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withId(R.id.search_edit_text)).perform(typeText("NotExistingApp"))

        // WARTEN auf die Filter-Aktion
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        onView(withId(R.id.apps_recycler_view)).check(matches(hasChildCount(0)))
    }

    @Test
    fun favoriteLimit_preventsAddingMoreFavorites() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) { // SAFE-Modus
        val appToAdd = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository

        val maxFavorites = (1..AppConstants.MAX_FAVORITES_ON_HOME)
            .map { "com.fake.app$it" }
            .toSet()
        fakeFavoritesRepo.favoritesState.value = maxFavorites

        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withText(appToAdd.displayName)).perform(longClick())
        onView(withText(R.string.add_to_favorites)).inRoot(isDialog()).perform(click())

        // WARTEN auf die Klick-Aktion im ViewModel
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Jetzt ist die Überprüfung sicher
        assertThat(fakeFavoritesRepo.favorites).doesNotContain(appToAdd.componentName)
        assertThat(fakeFavoritesRepo.favorites).hasSize(AppConstants.MAX_FAVORITES_ON_HOME)
    }

    @Test
    fun searchField_clearsAndResetsList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) { // SAFE-Modus
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        // Erste Aktion: Filtern
        onView(withId(R.id.search_edit_text)).perform(typeText("Zebra"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // Warten
        onView(withText("Alphabet")).check(doesNotExist())

        // Zweite Aktion: Text löschen
        onView(withId(R.id.search_edit_text)).perform(clearText())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle() // ERNEUT WARTEN

        // Überprüfen, ob alle ursprünglichen Apps wieder da sind
        onView(withText("Alphabet")).check(matches(isDisplayed()))
        onView(withText("Zebra")).check(matches(isDisplayed()))
        onView(withText("Apple")).check(matches(isDisplayed()))
    }
}