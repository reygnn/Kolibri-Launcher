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
        // Wir casten zum Fake, um die LiveData zu befüllen.
        // postValue wird verwendet, um Thread-sicher zu sein.
        (getDrawerAppsUseCase as FakeGetDrawerAppsUseCaseRepository).drawerApps.postValue(apps)
    }

    @Test
    fun drawerOpensAndDisplaysData() = testCoroutineRule.runTestAndLaunchUI {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withText("Alphabet")).check(matches(isDisplayed()))
        onView(withText("Zebra")).check(matches(isDisplayed()))
        onView(withText("Apple")).check(matches(isDisplayed()))
    }

    @Test
    fun searchField_filtersRecyclerViewCorrectly() = testCoroutineRule.runTestAndLaunchUI {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withId(R.id.search_edit_text)).perform(typeText("Zebra"))

        // Überprüfe, dass nur noch das gesuchte Element da ist
        onView(withText("Zebra")).check(matches(isDisplayed()))
        onView(withText("Alphabet")).check(doesNotExist())
        onView(withText("Apple")).check(doesNotExist())
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
    fun searchField_filtersCaseInsensitive() = testCoroutineRule.runTestAndLaunchUI {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withId(R.id.search_edit_text)).perform(replaceText("APPLE"))

        onView(withText("Apple")).check(matches(isDisplayed()))
        onView(withText("Alphabet")).check(doesNotExist())
    }

    @Test
    fun contextMenu_hideAppAction_updatesStateAndUI() = testCoroutineRule.runTestAndLaunchUI {
        val appToHide = testApps.first { it.displayName == "Alphabet" }
        val fakeVisibilityRepo = appVisibilityRepository as FakeAppVisibilityRepository

        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        // 1. Aktion ausführen
        onView(withText(appToHide.displayName)).perform(longClick())
        onView(withText(R.string.hide_app_from_drawer)).inRoot(isDialog()).perform(click())

        // 2. Zustand des Fakes überprüfen: Wurde die App als "versteckt" markiert?
        assertThat(fakeVisibilityRepo.hiddenApps).contains(appToHide.componentName)

        // 3. UI-Update simulieren (das würde normalerweise das ViewModel tun)
        val remainingApps = testApps.filter { it.componentName != appToHide.componentName }
        setDrawerAppsState(remainingApps)

        // 4. UI überprüfen: Ist die App aus der Liste verschwunden?
        onView(withText(appToHide.displayName)).check(doesNotExist())
        onView(withText("Zebra")).check(matches(isDisplayed()))
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
    fun searchWithNoResults_displaysEmptyList() = testCoroutineRule.runTestAndLaunchUI {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withId(R.id.search_edit_text)).perform(typeText("NotExistingApp"))

        onView(withId(R.id.apps_recycler_view)).check(matches(hasChildCount(0)))
    }

    @Test
    fun favoriteLimit_preventsAddingMoreFavorites() = testCoroutineRule.runTestAndLaunchUI {
        val appToAdd = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository

        // Simuliere, dass das Favoriten-Limit erreicht ist
        val maxFavorites = (1..AppConstants.MAX_FAVORITES_ON_HOME)
            .map { "com.fake.app$it" }
            .toSet()
        fakeFavoritesRepo.favoritesState.value = maxFavorites

        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        onView(withText(appToAdd.displayName)).perform(longClick())
        onView(withText(R.string.add_to_favorites)).inRoot(isDialog()).perform(click())

        // Der wichtigste Check: Wurde die App NICHT zu den Favoriten hinzugefügt?
        assertThat(fakeFavoritesRepo.favorites).doesNotContain(appToAdd.componentName)
        // Die Anzahl der Favoriten hat sich nicht geändert.
        assertThat(fakeFavoritesRepo.favorites).hasSize(AppConstants.MAX_FAVORITES_ON_HOME)
    }

    @Test
    fun searchField_clearsAndResetsList() = testCoroutineRule.runTestAndLaunchUI {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        // Filtern, sodass die Liste kurz ist
        onView(withId(R.id.search_edit_text)).perform(typeText("Zebra"))
        onView(withText("Alphabet")).check(doesNotExist())

        // Text löschen
        onView(withId(R.id.search_edit_text)).perform(clearText())

        // Überprüfen, ob alle ursprünglichen Apps wieder da sind
        onView(withText("Alphabet")).check(matches(isDisplayed()))
        onView(withText("Zebra")).check(matches(isDisplayed()))
        onView(withText("Apple")).check(matches(isDisplayed()))
    }
}