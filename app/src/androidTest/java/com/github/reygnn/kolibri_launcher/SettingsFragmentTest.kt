package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.CoreMatchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SettingsFragmentTest : BaseAndroidTest() {

    @Before
    fun setup() {
        // Intents manuell initialisieren, das ist der moderne Weg
        Intents.init()
    }

    @After
    fun tearDown() {
        // Intents nach jedem Test wieder freigeben
        Intents.release()
    }

    @Test
    fun preferences_areVisibleOnScreen() = testCoroutineRule.runTestAndLaunchUI {
        launchFragmentInHiltContainer<SettingsFragment>()
        // Kein Thread.sleep mehr nötig, Espresso wartet auf die Views

        onView(withText(R.string.category_apps_title)).check(matches(isDisplayed()))
        onView(withText(R.string.hidden_apps_title)).check(matches(isDisplayed()))
        onView(withText(R.string.double_tap_to_lock_title)).check(matches(isDisplayed()))
        onView(withText(R.string.change_wallpaper_title)).check(matches(isDisplayed()))
    }

    @Test
    fun hiddenAppsPreference_launchesHiddenAppsActivity() = testCoroutineRule.runTestAndLaunchUI {
        launchFragmentInHiltContainer<SettingsFragment>()

        onView(withText(R.string.hidden_apps_title)).perform(click())

        Intents.intended(hasComponent(HiddenAppsActivity::class.java.name))
    }

    @Test
    fun editFavoritesPreference_launchesOnboardingActivity() = testCoroutineRule.runTestAndLaunchUI {
        launchFragmentInHiltContainer<SettingsFragment>()

        onView(withText(R.string.settings_select_favorites_title)).perform(click())

        Intents.intended(allOf(
            hasComponent(OnboardingActivity::class.java.name),
            hasExtra(OnboardingActivity.EXTRA_LAUNCH_MODE, LaunchMode.EDIT_FAVORITES.name)
        ))
    }

    @Test
    fun doubleTapToLockSwitch_updatesRepositoryState() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange
        val fakeSettingsRepo = settingsRepository as FakeSettingsRepository
        fakeSettingsRepo.doubleTapToLockEnabledFlow.value = false // Setze den Anfangszustand

        // Act
        launchFragmentInHiltContainer<SettingsFragment>()
        onView(withText(R.string.double_tap_to_lock_title)).perform(click())

        // Synchronisation
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert: Überprüfe den Zustand des Fakes
        assertThat(fakeSettingsRepo.doubleTapToLockEnabledFlow.value).isTrue()
    }

    @Test
    fun sortFavoritesWithNoFavorites_doesNotCrash() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange: Setze den Zustand des Fakes explizit
        (favoritesRepository as FakeFavoritesRepository).favoritesState.value = emptySet()

        // Act
        launchFragmentInHiltContainer<SettingsFragment>()
        onView(withText(R.string.sort_favorites)).perform(click())

        // Assert: Der Test ist erfolgreich, wenn hier keine Exception fliegt.
        // Optional könnte man hier auf eine Toast-Message prüfen.
    }
}