package com.github.reygnn.kolibri_launcher.ui.hiddenapps

import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.support.awaitUntil
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Why instrumented: HiddenAppsActivity hosts a real RecyclerView populated
 * via a debounced StateFlow on the device's PackageManager + a click-to-
 * toggle interaction whose persistence path goes through the
 * HiddenAppsRepository → DataStore. The Robolectric companion
 * (HiddenAppsActivityRobolectricTest) attaches the activity but does not
 * exercise the populated list, the toggle, or persistence.
 *
 * What this test asserts (in order):
 *  1. Activity launches and inflates a RecyclerView populated with at
 *     least N items from the real PackageManager (real measure pass —
 *     Robolectric fakes layout, see INSTRUMENTED_TESTING_NOTES.kt §5).
 *  2. Clicking the first row triggers `viewModel.onAppToggled` and adds
 *     a chip to selectionChipGroup (round-trip through the StateFlow
 *     observe → updateUi path).
 *  3. Clicking "Done" persists the selection through
 *     UpdateHiddenAppsUseCase → HiddenAppsRepository, observable via
 *     `hiddenAppsFlow`. The persistence is the structural gap that
 *     Robolectric's stub DataStore + relaxed coroutine scheduling cannot
 *     honestly cover.
 *
 * Index-based row selection (not package-name) is intentional, mirroring
 * OnboardingToHomeSmokeTest §70: the device's actual app list is the
 * fixture, and asserting on "the first launchable app got hidden" is a
 * structural assertion the test can make portably.
 */
@HiltAndroidTest
class HiddenAppsActivityToggleTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var hiddenApps: HiddenAppsRepository

    @Before fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun togglingFirstApp_andClickingDone_persistsToHiddenAppsRepository() {
        // ── PRECONDITION: device has at least one launchable app. ────────
        // The HiddenAppsViewModel filters InstalledAppsRepository through the
        // same PackageManager query the rest of the launcher uses, so this
        // is the same portability check as in BackupRoundTripSafTest.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
        assumeTrue(
            "Need at least 1 launcher app on the test device; got ${resolved.size}",
            resolved.size >= 1,
        )

        // ── BASELINE: capture the existing hidden set so the assertion ──
        // measures the *delta*, not absolute membership. Earlier test runs
        // on the same image may have left state behind even with
        // orchestrator clearPackageData=true (race window between teardown
        // and next setup).
        val baseline = runBlocking { hiddenApps.hiddenAppsFlow.first() }

        val launchIntent = Intent(ctx, HiddenAppsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<HiddenAppsActivity>(launchIntent).use {
            // ── ASSERT: shell rendered, list not yet populated. ──────────
            onView(withId(R.id.all_apps_recycler_view)).check(matches(isDisplayed()))

            // ── WAIT: real PackageManager query + StateFlow → adapter
            // path. Same idle-resource gap as in OnboardingToHomeSmokeTest.
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "RecyclerView did not reach >=1 children before timeout" },
            ) {
                try {
                    onView(withId(R.id.all_apps_recycler_view))
                        .check(matches(hasMinimumChildCount(1)))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ── ACT: toggle the first row. Adds chip and stages the
            // change in the ViewModel — repo is NOT yet written.
            onView(withId(R.id.all_apps_recycler_view)).perform(
                RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()),
            )

            // ── ASSERT: chip appeared (proves the toggle reached the
            // UI-state observer, not just the click listener).
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "selection chip did not appear after toggle" },
            ) {
                try {
                    onView(withId(R.id.selection_chip_group))
                        .check(matches(hasMinimumChildCount(1)))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ── ACT: commit. Triggers UpdateHiddenAppsUseCase → repo
            // and finishes the activity (UiEvent.NavigateUp).
            onView(withId(R.id.done_button)).perform(click())
        }

        // ── ASSERT: the repository now contains exactly one *new* entry
        // beyond the baseline. The exact component depends on the device's
        // first launchable app, so we assert on size delta + non-empty —
        // either is enough to prove the persist path executed.
        val finalSet = runBlocking { hiddenApps.hiddenAppsFlow.first() }
        val added = finalSet - baseline
        assertThat(added).hasSize(1)
    }
}
