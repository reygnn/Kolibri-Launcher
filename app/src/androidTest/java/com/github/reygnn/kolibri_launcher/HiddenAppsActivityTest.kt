package com.github.reygnn.kolibri_launcher

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItem
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
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
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class HiddenAppsActivityTest : BaseAndroidTest() {

    private val testApps = listOf(
        AppInfo("Photos", "Photos", "com.google.photos", "com.google.photos.Main"),
        AppInfo("Maps", "Maps", "com.google.maps", "com.google.maps.Main"),
        AppInfo("Clock", "Clock", "com.google.clock", "com.google.clock.Main")
    )

    @Before
    fun setup() {
    }

    @Test
    fun screen_displaysCorrectTitleAndApps() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange: Bereite den Zustand der Fakes vor
        (installedAppsRepository as FakeInstalledAppsRepository).appsFlow.value = testApps
        (appVisibilityRepository as FakeAppVisibilityRepository).hiddenAppsState.value = emptySet()

        // Act: Starte die Activity
        ActivityScenario.launch(HiddenAppsActivity::class.java)

        // Assert: Überprüfe die UI
        onView(withText("Photos")).check(matches(isDisplayed()))
        onView(withId(R.id.chips_scroll_view)).check(matches(not(isDisplayed())))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedTitle = context.getString(R.string.hidden_apps_title_screen)
        onView(withId(R.id.title_text)).check(matches(withText(expectedTitle)))
    }

    @Test
    fun selectAndDeselectApp_updatesChipsCorrectly() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange
        (installedAppsRepository as FakeInstalledAppsRepository).appsFlow.value = testApps
        (appVisibilityRepository as FakeAppVisibilityRepository).hiddenAppsState.value = emptySet()

        // Act & Assert
        ActivityScenario.launch(HiddenAppsActivity::class.java)

        // Klicke auf "Maps" in der Liste
        onView(withId(R.id.all_apps_recycler_view))
            .perform(
                actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                    hasDescendant(withText("Maps")), click()
                )
            )

        // Chip sollte jetzt sichtbar sein
        onView(allOf(withText("Maps"), isDescendantOfA(withId(R.id.selection_chip_group))))
            .check(matches(isDisplayed()))

        // Klicke auf das "Schließen"-Icon des Chips
        onView(allOf(withText("Maps"), isDescendantOfA(withId(R.id.selection_chip_group))))
            .perform(EspressoTestUtils.clickOnChipCloseIcon())

        // Chip sollte wieder verschwunden sein
        onView(withId(R.id.chips_scroll_view)).check(matches(not(isDisplayed())))
        onView(allOf(withText("Maps"), isDescendantOfA(withId(R.id.selection_chip_group))))
            .check(doesNotExist())
    }

    @Test
    fun doneButton_updatesRepositoryStateAndFinishesActivity() =
        testCoroutineRule.runTestAndLaunchUI {
            val fakeVisibilityRepo = appVisibilityRepository as FakeAppVisibilityRepository
            val fakeInstalledAppsRepo = installedAppsRepository as FakeInstalledAppsRepository

            // Arrange
            fakeInstalledAppsRepo.appsFlow.value = testApps
            fakeVisibilityRepo.hiddenAppsState.value =
                setOf("com.google.clock/com.google.clock.Main")

            // Act
            val scenario = ActivityScenario.launch(HiddenAppsActivity::class.java)
            scenario.onActivity { activity ->
                (activity as HiddenAppsActivity).viewModel.initialize()
            }
            testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

            // Execute
            onView(withId(R.id.all_apps_recycler_view))
                .perform(
                    actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                        hasDescendant(withText("Photos")), click()
                    )
                )
            onView(withId(R.id.all_apps_recycler_view))
                .perform(
                    actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                        hasDescendant(withText("Clock")), click()
                    )
                )

            onView(withId(R.id.done_button)).perform(click())

            // Wait
            testCoroutineRule.testDispatcher.scheduler.runCurrent()
            testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            // Assert
            val finalHiddenApps = fakeVisibilityRepo.hiddenApps
            assertThat(finalHiddenApps).contains("com.google.photos/com.google.photos.Main")
            assertThat(finalHiddenApps).doesNotContain("com.google.clock/com.google.clock.Main")
            assertThat(finalHiddenApps).doesNotContain("com.google.maps/com.google.maps.Main")

            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
}
