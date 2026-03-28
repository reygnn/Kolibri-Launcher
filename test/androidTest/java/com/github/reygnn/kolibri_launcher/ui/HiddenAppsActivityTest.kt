package com.github.reygnn.kolibri_launcher.ui

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
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
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.ui.hiddenapps.HiddenAppsActivity
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
        (appVisibilityRepository as FakeHiddenAppsRepository).hiddenAppsState.value = emptySet()

        // Act: Starte die Activity
        ActivityScenario.launch(HiddenAppsActivity::class.java)

        // Assert: Überprüfe die UI
        Espresso.onView(ViewMatchers.withText("Photos"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.chips_scroll_view))
            .check(ViewAssertions.matches(Matchers.not(ViewMatchers.isDisplayed())))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedTitle = context.getString(R.string.hidden_apps_title_screen)
        Espresso.onView(ViewMatchers.withId(R.id.title_text))
            .check(ViewAssertions.matches(ViewMatchers.withText(expectedTitle)))
    }

    @Test
    fun selectAndDeselectApp_updatesChipsCorrectly() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange
        (installedAppsRepository as FakeInstalledAppsRepository).appsFlow.value = testApps
        (appVisibilityRepository as FakeHiddenAppsRepository).hiddenAppsState.value = emptySet()

        // Act & Assert
        ActivityScenario.launch(HiddenAppsActivity::class.java)

        // Klicke auf "Maps" in der Liste
        Espresso.onView(ViewMatchers.withId(R.id.all_apps_recycler_view))
            .perform(
                RecyclerViewActions.actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                    ViewMatchers.hasDescendant(ViewMatchers.withText("Maps")), ViewActions.click()
                )
            )

        // Chip sollte jetzt sichtbar sein
        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("Maps"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.selection_chip_group))
            )
        )
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // Klicke auf das "Schließen"-Icon des Chips
        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("Maps"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.selection_chip_group))
            )
        )
            .perform(EspressoTestUtils.clickOnChipCloseIcon())

        // Chip sollte wieder verschwunden sein
        Espresso.onView(ViewMatchers.withId(R.id.chips_scroll_view))
            .check(ViewAssertions.matches(Matchers.not(ViewMatchers.isDisplayed())))
        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("Maps"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.selection_chip_group))
            )
        )
            .check(ViewAssertions.doesNotExist())
    }

    @Test
    fun doneButton_updatesRepositoryStateAndFinishesActivity() =
        testCoroutineRule.runTestAndLaunchUI {
            val fakeVisibilityRepo = appVisibilityRepository as FakeHiddenAppsRepository
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
            Espresso.onView(ViewMatchers.withId(R.id.all_apps_recycler_view))
                .perform(
                    RecyclerViewActions.actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                        ViewMatchers.hasDescendant(ViewMatchers.withText("Photos")),
                        ViewActions.click()
                    )
                )
            Espresso.onView(ViewMatchers.withId(R.id.all_apps_recycler_view))
                .perform(
                    RecyclerViewActions.actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                        ViewMatchers.hasDescendant(ViewMatchers.withText("Clock")),
                        ViewActions.click()
                    )
                )

            Espresso.onView(ViewMatchers.withId(R.id.done_button)).perform(ViewActions.click())

            // Wait
            testCoroutineRule.awaitAll()

            // Assert
            val finalHiddenApps = fakeVisibilityRepo.hiddenApps
            Truth.assertThat(finalHiddenApps).contains("com.google.photos/com.google.photos.Main")
            Truth.assertThat(finalHiddenApps).doesNotContain("com.google.clock/com.google.clock.Main")
            Truth.assertThat(finalHiddenApps).doesNotContain("com.google.maps/com.google.maps.Main")

            Truth.assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
}