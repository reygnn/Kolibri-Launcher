package com.github.reygnn.kolibri_launcher

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItem
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.EspressoTestUtils.awaitAll
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class OnboardingActivityTest : BaseAndroidTest() {

    private val testApps = listOf(
        AppInfo("Photos", "Photos", "com.google.photos", "com.google.photos.Main"),
        AppInfo("Maps", "Maps", "com.google.maps", "com.google.maps.Main"),
        AppInfo("Clock", "Clock", "com.google.clock", "com.google.clock.Main")
    )

    @Before
    fun setup() {
    }

    private fun launchActivityWithMode(mode: LaunchMode): ActivityScenario<OnboardingActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), OnboardingActivity::class.java).apply {
            putExtra(OnboardingActivity.EXTRA_LAUNCH_MODE, mode.name)
        }
        return ActivityScenario.launch(intent)
    }

    @Test
    fun initialSetupMode_displaysCorrectTitleAndApps() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange: Setze den Zustand des steuerbaren Flows im Fake
        (getOnboardingAppsUseCase as FakeGetOnboardingAppsUseCaseRepository).mutableOnboardingAppsFlow.value = testApps

        // Act & Assert
        launchActivityWithMode(LaunchMode.INITIAL_SETUP)
        onView(withId(R.id.title_text)).check(matches(withText(R.string.onboarding_title_welcome)))
        onView(withText("Photos")).check(matches(isDisplayed()))
    }

    @Test
    fun selectAndDeselectApp_updatesChipsCorrectly() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange
        (getOnboardingAppsUseCase as FakeGetOnboardingAppsUseCaseRepository).mutableOnboardingAppsFlow.value = testApps

        // Act & Assert
        launchActivityWithMode(LaunchMode.INITIAL_SETUP)

        onView(withId(R.id.all_apps_recycler_view)).perform(actionOnItem<OnboardingAppListAdapter.ViewHolder>(
            hasDescendant(withText("Maps")), click()
        ))

        onView(allOf(withText("Maps"), isDescendantOfA(withId(R.id.selection_chip_group))))
            .check(matches(isDisplayed()))

        onView(allOf(withText("Maps"), isDescendantOfA(withId(R.id.selection_chip_group))))
            .perform(EspressoTestUtils.clickOnChipCloseIcon())

        onView(withId(R.id.chips_scroll_view)).check(matches(not(isDisplayed())))
    }

    @Test
    fun doneButton_savesFavoritesAndFinishesActivity() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        (getOnboardingAppsUseCase as FakeGetOnboardingAppsUseCaseRepository).mutableOnboardingAppsFlow.value = testApps

        // Act
        val scenario = launchActivityWithMode(LaunchMode.EDIT_FAVORITES)
        scenario.onActivity { activity ->
            (activity as OnboardingActivity).viewModel.initialize(LaunchMode.EDIT_FAVORITES)
        }
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Execute
        onView(withId(R.id.all_apps_recycler_view)).perform(actionOnItem<OnboardingAppListAdapter.ViewHolder>(
            hasDescendant(withText("Photos")), click()
        ))
        onView(withId(R.id.all_apps_recycler_view)).perform(actionOnItem<OnboardingAppListAdapter.ViewHolder>(
            hasDescendant(withText("Clock")), click()
        ))

        onView(withId(R.id.done_button)).perform(click())


        testCoroutineRule.awaitAll()


        // Assert
        val expectedFavorites = setOf(
            "com.google.photos/com.google.photos.Main",
            "com.google.clock/com.google.clock.Main"
        )

        assertThat(fakeFavoritesRepo.favorites).containsExactlyElementsIn(expectedFavorites)
        assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
    }
}

