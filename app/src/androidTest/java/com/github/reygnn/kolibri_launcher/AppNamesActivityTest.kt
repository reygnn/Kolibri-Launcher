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
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.CoreMatchers.endsWith
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AppNamesActivityTest : BaseAndroidTest() {

    // Definiere die Rohdaten für die Tests
    private val rawApps = listOf(
        AppInfo("Alpha Browser", "Alpha Browser", "com.alpha.browser", "com.alpha.browser.Main"),
        AppInfo("Beta Calculator", "Beta Calculator", "com.beta.calculator", "com.beta.calculator.Main"),
        AppInfo("Zeta Clock", "Zeta Clock", "com.zeta.clock", "com.zeta.clock.Main")
    )

    @Before
    fun setup() {
    }

    /**
     * Helper-Funktion, die den realen Datenfluss simuliert:
     * Sie holt die Rohdaten und wendet die benutzerdefinierten Namen aus dem
     * `FakeAppNamesRepository` an, bevor sie die Liste in den
     * `FakeInstalledAppsRepository` pusht.
     */
    private suspend fun updateAppListState() {
        val fakeNamesRepo = appNamesRepository as FakeAppNamesRepository
        val processedList = rawApps.map { app ->
            app.copy(displayName = fakeNamesRepo.getDisplayNameForPackage(app.packageName, app.originalName))
        }.sortedBy { it.displayName.lowercase() }

        (installedAppsRepository as FakeInstalledAppsRepository).appsFlow.value = processedList
    }

    @Test
    fun initialScreen_displaysAllAppsAndNoChips() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange: Setze den initialen Zustand
        updateAppListState()

        // Act: Starte die Activity
        ActivityScenario.launch(AppNamesActivity::class.java)

        // Assert: Überprüfe die UI
        onView(withId(R.id.all_apps_recycler_view)).check(matches(hasChildCount(3)))
        onView(withText("Alpha Browser")).check(matches(isDisplayed()))
        onView(withText("Beta Calculator")).check(matches(isDisplayed()))
        onView(withText("Zeta Clock")).check(matches(isDisplayed()))
        onView(withId(R.id.chips_scroll_view)).check(matches(not(isDisplayed())))
    }

    @Test
    fun renameApp_updatesListAndShowsChip() = testCoroutineRule.runTestAndLaunchUI {
        // Arrange: Setze den initialen Zustand und starte die Activity
        updateAppListState() // Initial setup is fine
        ActivityScenario.launch(AppNamesActivity::class.java)

        // Act: Führe die UI-Aktionen aus
        onView(withId(R.id.all_apps_recycler_view))
            .perform(actionOnItem<RecyclerView.ViewHolder>(hasDescendant(withText("Beta Calculator")), click()))
        onView(withClassName(endsWith("EditText"))).perform(replaceText("My Calc"))
        onView(withText(R.string.save)).perform(click())

        // Assert: Überprüfe den neuen Zustand der UI
        // The assertions remain the same.
        onView(allOf(withId(R.id.display_name_text), withText("My Calc")))
            .check(matches(isDisplayed()))
        onView(allOf(withId(R.id.original_name_text), withText("Beta Calculator")))
            .check(matches(isDisplayed()))

        onView(withId(R.id.chips_scroll_view)).check(matches(isDisplayed()))
        onView(allOf(withText("My Calc"), isDescendantOfA(withId(R.id.app_name_chip_group))))
            .check(matches(isDisplayed()))
    }
}