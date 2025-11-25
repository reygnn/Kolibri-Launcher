package com.github.reygnn.kolibri_launcher.ui

import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.BaseAndroidTest
import com.github.reygnn.kolibri_launcher.di.launchFragmentInHiltContainer
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.ui.hiddenapps.HiddenAppsActivity
import com.github.reygnn.kolibri_launcher.ui.onboarding.LaunchMode
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingActivity
import com.github.reygnn.kolibri_launcher.ui.settings.SettingsFragment
import com.google.common.truth.Truth
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.CoreMatchers
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

        Espresso.onView(ViewMatchers.withText(R.string.category_apps_title))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText(R.string.hidden_apps_title))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText(R.string.double_tap_to_lock_title))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText(R.string.change_wallpaper_title))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun hiddenAppsPreference_launchesHiddenAppsActivity() = testCoroutineRule.runTestAndLaunchUI {
        launchFragmentInHiltContainer<SettingsFragment>()

        Espresso.onView(ViewMatchers.withText(R.string.hidden_apps_title))
            .perform(ViewActions.click())

        Intents.intended(IntentMatchers.hasComponent(HiddenAppsActivity::class.java.name))
    }

    @Test
    fun editFavoritesPreference_launchesOnboardingActivity() = testCoroutineRule.runTestAndLaunchUI {
        launchFragmentInHiltContainer<SettingsFragment>()

        Espresso.onView(ViewMatchers.withText(R.string.settings_select_favorites_title))
            .perform(ViewActions.click())

        Intents.intended(
            CoreMatchers.allOf(
                IntentMatchers.hasComponent(OnboardingActivity::class.java.name),
                IntentMatchers.hasExtra(
                    OnboardingActivity.Companion.EXTRA_LAUNCH_MODE,
                    LaunchMode.EDIT_FAVORITES.name
                )
            )
        )
    }

    @Test
    fun doubleTapToLockSwitch_updatesRepositoryState() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange
        val fakeSettingsRepo = settingsRepository as FakeSettingsRepository
        fakeSettingsRepo.doubleTap = false // KORRIGIERT: Verwende die Property, nicht den Flow

        // Act
        launchFragmentInHiltContainer<SettingsFragment>()
        Espresso.onView(ViewMatchers.withText(R.string.double_tap_to_lock_title))
            .perform(ViewActions.click())

        // Synchronisation
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert: Überprüfe den Zustand des Fakes
        Truth.assertThat(fakeSettingsRepo.doubleTap).isTrue() // KORRIGIERT: Verwende die Property
    }

    @Test
    fun swipeDownToNotificationsSwitch_updatesRepositoryState() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange
        val fakeSettingsRepo = settingsRepository as FakeSettingsRepository
        fakeSettingsRepo.swipeDown = false

        // Act
        launchFragmentInHiltContainer<SettingsFragment>()
        Espresso.onView(ViewMatchers.withId(androidx.preference.R.id.recycler_view))
            .perform(
                RecyclerViewActions.scrollTo<RecyclerView.ViewHolder>(
                    ViewMatchers.hasDescendant(ViewMatchers.withText(R.string.swipe_down_to_notifications_title))
            ))
        Espresso.onView(ViewMatchers.withText(R.string.swipe_down_to_notifications_title))
            .perform(ViewActions.click())

        // Synchronisation
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        Truth.assertThat(fakeSettingsRepo.swipeDown).isTrue()
    }

    @Test
    fun sortFavoritesWithNoFavorites_doesNotCrash() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange: Setze den Zustand des Fakes explizit
        (favoritesRepository as FakeFavoritesRepository).favoritesState.value = emptySet()

        // Act
        launchFragmentInHiltContainer<SettingsFragment>()
        Espresso.onView(ViewMatchers.withText(R.string.sort_favorites)).perform(ViewActions.click())

        // Assert: Der Test ist erfolgreich, wenn hier keine Exception fliegt.
        // Optional könnte man hier auf eine Toast-Message prüfen.
    }
}