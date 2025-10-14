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

            // Arrange: "Clock" ist initial versteckt
            (installedAppsRepository as FakeInstalledAppsRepository).appsFlow.value = testApps
            fakeVisibilityRepo.hiddenAppsState.value =
                setOf("com.google.clock/com.google.clock.Main")

            // Act
            val scenario = ActivityScenario.launch(HiddenAppsActivity::class.java)

            // Klicke "Photos" (wird neu versteckt)
            onView(withId(R.id.all_apps_recycler_view))
                .perform(
                    actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                        hasDescendant(withText("Photos")), click()
                    )
                )
            // Klicke "Clock" (wird wieder sichtbar)
            onView(withId(R.id.all_apps_recycler_view))
                .perform(
                    actionOnItem<OnboardingAppListAdapter.ViewHolder>(
                        hasDescendant(withText("Clock")), click()
                    )
                )

            onView(withId(R.id.done_button)).perform(click())


            // 1. Führe alle Coroutinen aus, die JETZT in der Warteschlange sind.
            testCoroutineRule.testDispatcher.scheduler.runCurrent()

            // 2. Führe alle verbleibenden (durch Schritt 1 evtl. neu erzeugten) Coroutinen aus, bis alles stillsteht.
            testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

            // 3. Warte auf den UI-Thread, um die Konsequenzen (den finish()-Aufruf) zu verarbeiten.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()


            // Assert: Überprüfe den finalen Zustand des Fakes, anstatt `verify` zu verwenden.
            // Dies ist robuster und klarer.
            val finalHiddenApps = fakeVisibilityRepo.hiddenApps

            assertThat(finalHiddenApps).contains("com.google.photos/com.google.photos.Main") // Wurde hinzugefügt
            assertThat(finalHiddenApps).doesNotContain("com.google.clock/com.google.clock.Main") // Wurde entfernt
            assertThat(finalHiddenApps).doesNotContain("com.google.maps/com.google.maps.Main") // Wurde nie angefasst

            // Überprüfe, ob die Activity beendet wurde
            assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
}
