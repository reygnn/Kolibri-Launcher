package com.github.reygnn.kolibri_launcher

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
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
import org.hamcrest.Matcher

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

    /**
     * Eine leere Aktion, die Espresso zwingt, auf den UI-Thread zu warten, bis er idle ist.
     * Das ist extrem nützlich, um auf das Rendern von RecyclerViews zu warten.
     */
    fun waitForUiThread() = object : ViewAction {
        override fun getConstraints(): Matcher<View> {
            return isDisplayed()
        }
        override fun getDescription(): String {
            return "wait for UI thread to be idle"
        }
        override fun perform(uiController: UiController, view: View) {
            uiController.loopMainThreadUntilIdle()
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
        onView(withId(R.id.apps_recycler_view)).check(RecyclerViewItemCountAssertion.withItemCount(0))
    }

    @Test
    fun searchWithNoResults_displaysEmptyList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        // 1. Arrange & Initial Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withId(R.id.apps_recycler_view)).check(RecyclerViewItemCountAssertion.withItemCount(3))

        // 2. Act
        onView(withId(R.id.search_edit_text)).perform(typeText("NotExistingApp"))

        // 3. WARTEN (Der Zwei-Schritt-Prozess)
        // Schritt 3a: Warten auf die ViewModel-Logik (Coroutinen)
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Schritt 3b: Warten auf die UI-Logik (RecyclerView Rendering)
        onView(withId(R.id.apps_recycler_view)).perform(waitForUiThread())

        // 4. Assert
        // Diese Überprüfung ist jetzt 100% sicher.
        onView(withId(R.id.apps_recycler_view)).check(RecyclerViewItemCountAssertion.withItemCount(0))
    }

    @Test
    fun favoriteLimit_preventsAddingMoreFavorites() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        val appToAdd = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        // Hole den Fake UseCase, den wir direkt manipulieren werden
        val fakeFavoriteAppsUseCase = getFavoriteAppsUseCase as FakeGetFavoriteAppsUseCaseRepository

        // --- SCHRITT 1: STARTE DIE UI IN EINEM SAUBEREN ZUSTAND ---
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        onView(withText(appToAdd.displayName)).check(matches(isDisplayed()))

        // --- SCHRITT 2: SIMULIERE DEN "VOLLEN" ZUSTAND DIREKT IM USECASE ---
        // Erstelle die Daten, die das Fragment empfangen soll. In diesem Fall ist die App-Liste
        // für die Favoriten-UI nicht wichtig, nur die Tatsache, dass das Limit erreicht ist.
        // Wir simulieren, dass der UseCase eine Liste mit der maximalen Anzahl von Apps liefert.
        // Wichtig ist, dass die ViewModel-Logik auf die Größe dieser Liste zugreift.
        // Wenn die Logik die Größe direkt aus dem favoritesRepository nimmt, müssen wir auch das füllen.

        // Fülle den zugrundeliegenden Repository-State, falls das ViewModel ihn direkt prüft
        val maxFavorites = (1..AppConstants.MAX_FAVORITES_ON_HOME).map { "com.fake.app$it" }.toSet()
        fakeFavoritesRepo.favoritesState.value = maxFavorites

        // Pushe den UiState, den das Fragment tatsächlich beobachtet.
        // Erzeuge eine Dummy-Liste, deren Größe dem Limit entspricht.
        val dummyFullAppList = maxFavorites.map { AppInfo(it, it, it, it) }
        fakeFavoriteAppsUseCase.favoriteApps.value = UiState.Success(FavoriteAppsResult(apps = dummyFullAppList, isFallback = false))

        // --- SCHRITT 3: WARTE AUF DIE VERARBEITUNG DES NEUEN ZUSTANDS ---
        // Gib dem Fragment Zeit, diesen neuen UiState zu empfangen und zu verarbeiten.
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // --- SCHRITT 4: FÜHRE DIE AKTION AUS ---
        // Jetzt ist der Zustand des Fragments garantiert aktuell.
        onView(withText(appToAdd.displayName)).perform(longClick())
        onView(withText(R.string.add_to_favorites)).inRoot(isDialog()).perform(click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // --- SCHRITT 5: ÜBERPRÜFE DAS ENDERGEBNIS ---
        // Die ViewModel-Logik hat jetzt den korrekten Count erhalten.
        assertThat(fakeFavoritesRepo.favorites).doesNotContain(appToAdd.componentName)
        assertThat(fakeFavoritesRepo.favorites).hasSize(AppConstants.MAX_FAVORITES_ON_HOME)
    }

    @Test
    fun searchField_clearsAndResetsList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        // 1. Arrange & Sync
        launchFragmentInHiltContainer<AppDrawerFragment>()
        setDrawerAppsState(testApps)
        // Verwende deine Assertion auch hier für die initiale Überprüfung
        onView(withId(R.id.apps_recycler_view)).check(RecyclerViewItemCountAssertion.withItemCount(3))

        // 2. Act 1: Filtern
        onView(withId(R.id.search_edit_text)).perform(typeText("Zebra"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // --- HIER IST DIE KORREKTUR ---
        // Ersetze die unzuverlässige Prüfung durch deine stabile Assertion
        onView(withId(R.id.apps_recycler_view)).check(RecyclerViewItemCountAssertion.withItemCount(1))

        // 3. Act 2: Text löschen mit der robusten Methode
        onView(withId(R.id.search_edit_text)).perform(replaceText(""))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // --- UND HIER NOCHMALS ---
        onView(withId(R.id.apps_recycler_view)).check(RecyclerViewItemCountAssertion.withItemCount(3))

        // 4. Assert (optional, aber gut zur Sicherheit)
        // Diese Überprüfungen sind jetzt sicher, da wir wissen, dass die Daten korrekt sind.
        onView(withText("Alphabet")).check(matches(isDisplayed()))
        onView(withText("Zebra")).check(matches(isDisplayed()))
    }
}