package com.github.reygnn.kolibri_launcher.ui

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.BaseAndroidTest
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.ui.onboarding.LaunchMode
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingActivity
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingAppListAdapter
import com.github.reygnn.kolibri_launcher.util.EspressoTestUtils
import com.github.reygnn.kolibri_launcher.util.EspressoTestUtils.awaitAll
import com.google.common.truth.Truth
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.Matchers
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
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            OnboardingActivity::class.java
        ).apply {
            putExtra(OnboardingActivity.Companion.EXTRA_LAUNCH_MODE, mode.name)
        }
        return ActivityScenario.launch(intent)
    }

    @Test
    fun initialSetupMode_displaysCorrectTitleAndApps() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange: Setze den Zustand auf dem RICHTIGEN Repository
        val fakeInstalledApps = installedAppsRepository as FakeInstalledAppsRepository

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            fakeInstalledApps.appsFlow.value = testApps
        }
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Act & Assert
        launchActivityWithMode(LaunchMode.INITIAL_SETUP)

        Espresso.onView(ViewMatchers.withId(R.id.title_text))
            .check(ViewAssertions.matches(ViewMatchers.withText(R.string.onboarding_title_welcome)))
        Espresso.onView(ViewMatchers.withText("Photos"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun selectAndDeselectApp_updatesChipsCorrectly() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange
        val fakeInstalledApps = installedAppsRepository as FakeInstalledAppsRepository

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            fakeInstalledApps.appsFlow.value = testApps
        }
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Act & Assert
        launchActivityWithMode(LaunchMode.INITIAL_SETUP)

        Espresso.onView(ViewMatchers.withId(R.id.all_apps_recycler_view)).perform(
            RecyclerViewActions.actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                ViewMatchers.hasDescendant(ViewMatchers.withText("Maps")), ViewActions.click()
            )
        )

        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("Maps"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.selection_chip_group))
            )
        ).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("Maps"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.selection_chip_group))
            )
        ).perform(EspressoTestUtils.clickOnChipCloseIcon())

        Espresso.onView(ViewMatchers.withId(R.id.chips_scroll_view))
            .check(ViewAssertions.matches(Matchers.not(ViewMatchers.isDisplayed())))
    }

    @Test
    fun doneButton_savesFavoritesAndFinishesActivity() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        val fakeInstalledApps = installedAppsRepository as FakeInstalledAppsRepository

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            fakeInstalledApps.appsFlow.value = testApps
        }
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Act
        val scenario = launchActivityWithMode(LaunchMode.EDIT_FAVORITES)

        // Execute
        Espresso.onView(ViewMatchers.withId(R.id.all_apps_recycler_view)).perform(
            RecyclerViewActions.actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                ViewMatchers.hasDescendant(ViewMatchers.withText("Photos")), ViewActions.click()
            )
        )
        Espresso.onView(ViewMatchers.withId(R.id.all_apps_recycler_view)).perform(
            RecyclerViewActions.actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                ViewMatchers.hasDescendant(ViewMatchers.withText("Clock")), ViewActions.click()
            )
        )

        Espresso.onView(ViewMatchers.withId(R.id.done_button)).perform(ViewActions.click())
        testCoroutineRule.awaitAll()

        // Assert
        val expectedFavorites = setOf(
            "com.google.photos/com.google.photos.Main",
            "com.google.clock/com.google.clock.Main"
        )

        Truth.assertThat(fakeFavoritesRepo.favorites).containsExactlyElementsIn(expectedFavorites)
        Truth.assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
    }
}