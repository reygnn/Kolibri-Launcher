package com.github.reygnn.kolibri_launcher.ui

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.BaseAndroidTest
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.ui.customnames.CustomNamesActivity
import com.github.reygnn.kolibri_launcher.util.TestCoroutineRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import org.hamcrest.CoreMatchers
import org.hamcrest.Matchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class CustomNamesActivityTest : BaseAndroidTest() {

    @Before
    fun setup() {
        runBlocking { (customNamesRepository as FakeCustomNamesRepository).purgeRepository() }
    }

    @Test
    fun initialScreen_displaysAllAppsAndNoChips() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.SAFE) {
        // Arrange: Starte die Activity. Hilt sorgt für die korrekten initialen Daten.
        ActivityScenario.launch(CustomNamesActivity::class.java)

        // Synchronisiere, um sicherzustellen, dass die initiale Lade-Coroutine des ViewModels abgeschlossen ist.
        (testCoroutineRule.testDispatcher as TestDispatcher).scheduler.advanceUntilIdle()

        // Assert
        Espresso.onView(ViewMatchers.withId(R.id.all_apps_recycler_view))
            .check(ViewAssertions.matches(ViewMatchers.hasChildCount(3)))
        Espresso.onView(ViewMatchers.withText("Alpha Browser"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Beta Calculator"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Zeta Clock"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.chips_scroll_view))
            .check(ViewAssertions.matches(Matchers.not(ViewMatchers.isDisplayed())))
    }

    @Test
    fun renameApp_updatesListAndShowsChip() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.SAFE) {
        // Arrange: Starte die Activity.
        ActivityScenario.launch(CustomNamesActivity::class.java)

        // Synchronisiere, um sicherzustellen, dass die initiale UI vollständig geladen ist.
        (testCoroutineRule.testDispatcher as TestDispatcher).scheduler.advanceUntilIdle()

        // Act: Führe die UI-Aktion aus. Der Rest passiert automatisch und reaktiv.
        Espresso.onView(ViewMatchers.withId(R.id.all_apps_recycler_view))
            .perform(
                RecyclerViewActions.actionOnItem<RecyclerView.ViewHolder>(
                    ViewMatchers.hasDescendant(
                        ViewMatchers.withText("Beta Calculator")
                    ), ViewActions.click()
                )
            )
        Espresso.onView(ViewMatchers.withClassName(CoreMatchers.endsWith("EditText")))
            .perform(ViewActions.replaceText("My Calc"))
        Espresso.onView(ViewMatchers.withText(R.string.save)).perform(ViewActions.click())

        // Synchronisation: Führe alle anstehenden Coroutinen aus (ViewModel-Logik, Flow-Emission, UI-Update).
        (testCoroutineRule.testDispatcher as TestDispatcher).scheduler.advanceUntilIdle()

        // Assert: Die UI muss jetzt den korrekten Zustand haben.
        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withId(R.id.display_name_text),
                ViewMatchers.withText("My Calc")
            )
        )
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withId(R.id.original_name_text),
                ViewMatchers.withText("Beta Calculator")
            )
        )
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withId(R.id.chips_scroll_view))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("My Calc"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.app_name_chip_group))
            )
        )
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }
}