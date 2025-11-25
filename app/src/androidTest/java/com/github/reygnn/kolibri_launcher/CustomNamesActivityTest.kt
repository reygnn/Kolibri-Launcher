package com.github.reygnn.kolibri_launcher

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItem
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.ui.customnames.CustomNamesActivity
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import org.hamcrest.CoreMatchers.endsWith
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
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
        onView(withId(R.id.all_apps_recycler_view)).check(matches(hasChildCount(3)))
        onView(withText("Alpha Browser")).check(matches(isDisplayed()))
        onView(withText("Beta Calculator")).check(matches(isDisplayed()))
        onView(withText("Zeta Clock")).check(matches(isDisplayed()))
        onView(withId(R.id.chips_scroll_view)).check(matches(not(isDisplayed())))
    }

    @Test
    fun renameApp_updatesListAndShowsChip() = testCoroutineRule.runTestAndLaunchUI(mode = TestCoroutineRule.Mode.SAFE) {
        // Arrange: Starte die Activity.
        ActivityScenario.launch(CustomNamesActivity::class.java)

        // Synchronisiere, um sicherzustellen, dass die initiale UI vollständig geladen ist.
        (testCoroutineRule.testDispatcher as TestDispatcher).scheduler.advanceUntilIdle()

        // Act: Führe die UI-Aktion aus. Der Rest passiert automatisch und reaktiv.
        onView(withId(R.id.all_apps_recycler_view))
            .perform(actionOnItem<RecyclerView.ViewHolder>(hasDescendant(withText("Beta Calculator")), click()))
        onView(withClassName(endsWith("EditText"))).perform(replaceText("My Calc"))
        onView(withText(R.string.save)).perform(click())

        // Synchronisation: Führe alle anstehenden Coroutinen aus (ViewModel-Logik, Flow-Emission, UI-Update).
        (testCoroutineRule.testDispatcher as TestDispatcher).scheduler.advanceUntilIdle()

        // Assert: Die UI muss jetzt den korrekten Zustand haben.
        onView(allOf(withId(R.id.display_name_text), withText("My Calc")))
            .check(matches(isDisplayed()))
        onView(allOf(withId(R.id.original_name_text), withText("Beta Calculator")))
            .check(matches(isDisplayed()))

        onView(withId(R.id.chips_scroll_view)).check(matches(isDisplayed()))
        onView(allOf(withText("My Calc"), isDescendantOfA(withId(R.id.app_name_chip_group))))
            .check(matches(isDisplayed()))
    }
}