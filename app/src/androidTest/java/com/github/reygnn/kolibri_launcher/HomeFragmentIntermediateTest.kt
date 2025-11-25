package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.home.HomeFragment
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class HomeFragmentIntermediateTest : BaseAndroidTest() {

    // Helper für konsistentes Setup - KORRIGIERT!
    private fun setupFragmentWithApps(apps: List<AppInfo>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            (settingsRepository as FakeSettingsRepository)
                .setReadabilityModeBlocking("smart_contrast")

            // RICHTIG: Die Repositories füttern, die der echte UseCase verwendet!
            val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
            val fakeFavorites = favoritesRepository as FakeFavoritesRepository

            fakeInstalledState.updateApps(apps)
            val componentNames = apps.map { it.componentName }.toSet()
            fakeFavorites.favoritesState.value = componentNames
        }

        launchAndTrackFragment<HomeFragment>()

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.rootLayout))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))
    }

    // Helper für State-Updates während Fragment läuft
    private fun updateAppsWhileRunning(apps: List<AppInfo>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val fakeInstalledState = installedAppsStateRepository as FakeInstalledAppsStateRepository
            val fakeFavorites = favoritesRepository as FakeFavoritesRepository

            fakeInstalledState.updateApps(apps)
            val componentNames = apps.map { it.componentName }.toSet()
            fakeFavorites.favoritesState.value = componentNames
        }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.appList))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))
    }

    // ========== LEVEL 1: Simple Click Tests ==========

    @Test
    fun test_101_singleButtonClick_noVerification() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("ClickTest", "ClickTest", "com.click", "com.click.Main")
        setupFragmentWithApps(listOf(testApp))

        onView(withText("ClickTest")).perform(click())
    }

    @Test
    fun test_102_multipleButtonsClick_firstButton() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val apps = listOf(
            AppInfo("First", "First", "com.first", "com.first.Main"),
            AppInfo("Second", "Second", "com.second", "com.second.Main")
        )
        setupFragmentWithApps(apps)

        onView(withText("First")).perform(click())
    }

    // ========== LEVEL 2: Click mit Zustands-Verifikation ==========

    @Test
    fun test_103_clickWithVerification() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("ClickTest", "ClickTest", "com.click", "com.click.Main")
        setupFragmentWithApps(listOf(testApp))

        onView(withText("ClickTest")).perform(click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val fakeRepo = appUsageRepository as FakeAppUsageRepository
        assertThat(fakeRepo.launchedPackages).contains("com.click")
    }

    @Test
    fun test_104_clickPerformsAction() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("FastTest", "FastTest", "com.fast", "com.fast.Main")
        setupFragmentWithApps(listOf(testApp))

        onView(withText("FastTest")).perform(click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val fakeRepo = appUsageRepository as FakeAppUsageRepository
        assertThat(fakeRepo.launchedPackages).contains("com.fast")
    }

    // ========== LEVEL 3: State-Updates während Fragment läuft ==========

    @Test
    fun test_105_addButtonWhileRunning() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val initialApp = AppInfo("Initial", "Initial", "com.init", "com.init.Main")
        setupFragmentWithApps(listOf(initialApp))

        onView(withText("Initial")).check(matches(isDisplayed()))

        // State-Update mit Helper
        val newApps = listOf(
            initialApp,
            AppInfo("Added", "Added", "com.added", "com.added.Main")
        )
        updateAppsWhileRunning(newApps)

        onView(withText("Initial")).check(matches(isDisplayed()))
        onView(withText("Added")).check(matches(isDisplayed()))
    }

    @Test
    fun test_106_removeButtonWhileRunning() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val apps = listOf(
            AppInfo("Keep", "Keep", "com.keep", "com.keep.Main"),
            AppInfo("Remove", "Remove", "com.remove", "com.remove.Main")
        )
        setupFragmentWithApps(apps)

        onView(withText("Keep")).check(matches(isDisplayed()))
        onView(withText("Remove")).check(matches(isDisplayed()))

        // State-Update - nur "Keep" bleibt
        val newApps = listOf(AppInfo("Keep", "Keep", "com.keep", "com.keep.Main"))
        updateAppsWhileRunning(newApps)

        onView(withText("Keep")).check(matches(isDisplayed()))
        onView(withText("Remove")).check(doesNotExist())
    }

    // ========== LEVEL 4: Mehrfache Clicks ==========

    @Test
    fun test_107_clickSameButtonTwice() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("DoubleClick", "DoubleClick", "com.double", "com.double.Main")
        setupFragmentWithApps(listOf(testApp))

        onView(withText("DoubleClick")).perform(click())
        onView(withText("DoubleClick")).perform(click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val fakeRepo = appUsageRepository as FakeAppUsageRepository
        assertThat(fakeRepo.launchedPackages).hasSize(2)
        assertThat(fakeRepo.launchedPackages).containsExactly("com.double", "com.double").inOrder()
    }

    @Test
    fun test_108_clickDifferentButtons() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val apps = listOf(
            AppInfo("App1", "App1", "com.app1", "com.app1.Main"),
            AppInfo("App2", "App2", "com.app2", "com.app2.Main")
        )
        setupFragmentWithApps(apps)

        onView(withText("App1")).perform(click())
        onView(withText("App2")).perform(click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val fakeRepo = appUsageRepository as FakeAppUsageRepository
        assertThat(fakeRepo.launchedPackages).containsExactly("com.app1", "com.app2").inOrder()
    }

    // ========== LEVEL 5: State Transitions ==========

    @Test
    fun test_109_transitionFromEmptyToSuccess() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        // Start mit leerem State
        setupFragmentWithApps(emptyList())

        onView(withId(R.id.appList)).check(matches(hasChildCount(0)))

        // Transition zu Apps
        val apps = listOf(AppInfo("Loaded", "Loaded", "com.loaded", "com.loaded.Main"))
        updateAppsWhileRunning(apps)

        onView(withText("Loaded")).check(matches(isDisplayed()))
    }

    @Test
    fun test_110_transitionFromSuccessToEmpty() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val apps = listOf(AppInfo("WillBeRemoved", "WillBeRemoved", "com.remove", "com.remove.Main"))
        setupFragmentWithApps(apps)

        onView(withText("WillBeRemoved")).check(matches(isDisplayed()))

        // Transition zu leer
        updateAppsWhileRunning(emptyList())

        onView(withId(R.id.appList)).check(matches(hasChildCount(0)))
    }

    // ========== LEVEL 6: Custom Display Names ==========

    @Test
    fun test_111_displayNameDifferentFromOriginal() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val testApp = AppInfo("Original Name", "Custom Display", "com.custom", "com.custom.Main")
        setupFragmentWithApps(listOf(testApp))

        onView(withText("Custom Display")).perform(click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val fakeRepo = appUsageRepository as FakeAppUsageRepository
        assertThat(fakeRepo.launchedPackages).contains("com.custom")
    }

    @Test
    fun test_112_updateDisplayNameWhileRunning() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val initialApp = AppInfo("Original", "Original", "com.test", "com.test.Main")
        setupFragmentWithApps(listOf(initialApp))

        onView(withText("Original")).check(matches(isDisplayed()))

        // Update Display Name
        val updatedApp = AppInfo("Original", "New Custom Name", "com.test", "com.test.Main")
        updateAppsWhileRunning(listOf(updatedApp))

        onView(withText("Original")).check(doesNotExist())
        onView(withText("New Custom Name")).check(matches(isDisplayed()))
    }

    @Test
    fun test_fake_repository_is_injected_and_works() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val fakeRepo = appUsageRepository as FakeAppUsageRepository

        assertThat(fakeRepo.launchedPackages).isEmpty()

        fakeRepo.recordPackageLaunch("test.package")

        assertThat(fakeRepo.launchedPackages).contains("test.package")
    }
}