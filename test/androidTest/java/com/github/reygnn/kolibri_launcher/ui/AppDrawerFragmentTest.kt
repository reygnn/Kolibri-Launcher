package com.github.reygnn.kolibri_launcher.ui

import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.BaseAndroidTest
import com.github.reygnn.kolibri_launcher.di.launchFragmentInHiltContainer
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.ui.appdrawer.AppDrawerFragment
import com.github.reygnn.kolibri_launcher.util.EspressoTestUtils
import com.github.reygnn.kolibri_launcher.util.TestCoroutineRule
import com.google.common.truth.Truth
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.Matchers
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AppDrawerFragmentTest : BaseAndroidTest() {

    private val testApps = listOf(
        AppInfo("Alphabet", "Alphabet", "com.alphabet", "com.alphabet.MainActivity"),
        AppInfo("Zebra", "Zebra", "com.zebra", "com.zebra.MainActivity"),
        AppInfo("Apple", "Apple", "com.apple", "com.apple.MainActivity")
    )

    // KORRIGIERT: Die richtigen Repositories füttern!
    private fun setDrawerAppsState(apps: List<AppInfo>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            // GetDrawerAppsUseCase verwendet diese Repositories:
            val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
            val fakeHiddenApps = appVisibilityRepository as FakeHiddenAppsRepository

            // Apps als "installiert" markieren
            fakeInstalledState.updateApps(apps)

            // Keine Apps verstecken (außer der Test will das explizit)
            fakeHiddenApps.hiddenAppsState.value = emptySet()
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
    }

    // Helper für Updates während Fragment läuft
    private fun updateDrawerAppsWhileRunning(apps: List<AppInfo>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
            fakeInstalledState.updateApps(apps)
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())
    }

    @Test
    fun drawerOpensAndDisplaysData() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Zebra"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Apple"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun searchField_filtersRecyclerViewCorrectly() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE
    ) {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.typeText("Zebra"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(1))

        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("Zebra"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.apps_recycler_view))
            )
        ).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Alphabet")).check(ViewAssertions.doesNotExist())
    }

    @Test
    fun longClickOnApp_opensContextMenu() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        (appUsageRepository as FakeAppUsageRepository).launchedPackages.clear()

        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withText("Alphabet")).perform(ViewActions.longClick())

        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .inRoot(RootMatchers.isDialog())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun searchField_filtersCaseInsensitive() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE
    ) {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText("Apple"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.replaceText("APPLE"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(
            Matchers.allOf(
                ViewMatchers.withText("Apple"),
                ViewMatchers.isDescendantOfA(ViewMatchers.withId(R.id.apps_recycler_view))
            )
        ).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText("Alphabet")).check(ViewAssertions.doesNotExist())
    }

    @Test
    fun contextMenu_hideAppAction_updatesStateAndUI() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE
    ) {
        val appToHide = testApps.first { it.displayName == "Alphabet" }

        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText(appToHide.displayName))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withText(appToHide.displayName))
            .perform(ViewActions.longClick())
        Espresso.onView(ViewMatchers.withText(R.string.hide_app_from_drawer))
            .inRoot(RootMatchers.isDialog()).perform(ViewActions.click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Simuliere dass App jetzt versteckt ist
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val fakeHiddenApps = appVisibilityRepository as FakeHiddenAppsRepository
            fakeHiddenApps.hiddenAppsState.value = setOf(appToHide.componentName)
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(ViewMatchers.withText(appToHide.displayName))
            .check(ViewAssertions.doesNotExist())
    }

    @Test
    fun contextMenu_toggleFavoriteAction_addsToFavorites() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE
    ) {
        val appToFavorite = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        fakeFavoritesRepo.favoritesState.value = emptySet()

        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withText(appToFavorite.displayName))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withText(appToFavorite.displayName))
            .perform(ViewActions.longClick())
        Espresso.onView(ViewMatchers.withText(R.string.add_to_favorites))
            .inRoot(RootMatchers.isDialog()).perform(ViewActions.click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        Truth.assertThat(fakeFavoritesRepo.favorites).contains(appToFavorite.componentName)
    }

    @Test
    fun emptyAppList_displaysEmptyRecyclerView() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE
    ) {
        setDrawerAppsState(emptyList())
        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(0))
    }

    @Test
    fun searchWithNoResults_displaysEmptyList() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE
    ) {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(3))

        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.typeText("NotExistingApp"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(0))
    }

    @Test
    fun favoriteLimit_preventsAddingMoreFavorites() = testCoroutineRule.runTestAndLaunchUI(
        TestCoroutineRule.Mode.SAFE
    ) {
        val appToAdd = testApps.first { it.displayName == "Apple" }
        val fakeFavoritesRepo = favoritesRepository as FakeFavoritesRepository
        val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
        val fakeHiddenApps = appVisibilityRepository as FakeHiddenAppsRepository

        // 256 Fake-ComponentNames für die Limit-Prüfung (keine echten AppInfo nötig!)
        val fakeComponentNames = (1..AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME).map {
            "com.fake.app$it/com.fake.app$it.MainActivity"
        }.toSet()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            // Favoriten-State hat 256 Einträge (für UseCase Limit-Check)
            fakeFavoritesRepo.favoritesState.value = fakeComponentNames

            // ABER: Nur testApps werden gerendert (schnell + "Apple" sichtbar)
            fakeInstalledState.updateApps(testApps)
            fakeHiddenApps.hiddenAppsState.value = emptySet()
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())

        // Nur 3 Apps sichtbar, aber Favoriten-Count = 256
        Espresso.onView(ViewMatchers.withText(appToAdd.displayName))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        Espresso.onView(ViewMatchers.withText(appToAdd.displayName))
            .perform(ViewActions.longClick())
        Espresso.onView(ViewMatchers.withText(R.string.add_to_favorites))
            .inRoot(RootMatchers.isDialog()).perform(ViewActions.click())
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Verify: Limit erreicht, App nicht hinzugefügt
        Truth.assertThat(fakeFavoritesRepo.favorites).doesNotContain(appToAdd.componentName)
        Truth.assertThat(fakeFavoritesRepo.favorites).hasSize(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
    }

    @Test
    fun searchField_clearsAndResetsList() = testCoroutineRule.runTestAndLaunchUI(TestCoroutineRule.Mode.SAFE) {
        setDrawerAppsState(testApps)
        launchFragmentInHiltContainer<AppDrawerFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(3))

        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.typeText("Zebra"))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(1))

        Espresso.onView(ViewMatchers.withId(R.id.search_edit_text))
            .perform(ViewActions.replaceText(""))
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .perform(EspressoTestUtils.waitForUiThread())

        Espresso.onView(ViewMatchers.withId(R.id.apps_recycler_view))
            .check(EspressoTestUtils.RecyclerViewItemCountAssertion.withItemCount(3))
        Espresso.onView(ViewMatchers.withText("Alphabet"))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }
}