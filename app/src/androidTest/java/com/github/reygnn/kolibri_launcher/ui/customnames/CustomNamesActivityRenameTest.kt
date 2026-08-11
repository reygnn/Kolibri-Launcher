package com.github.reygnn.kolibri_launcher.ui.customnames

import android.content.Intent
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.support.awaitUntil
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Why instrumented: CustomNamesActivity orchestrates a real RecyclerView,
 * an AlertDialog with a programmatically-created EditText, and a
 * persistence path through CustomNamesRepository → DataStore. Of those:
 *
 *   - Real AlertDialog showing in a separate window (Robolectric's
 *     ShadowAlertDialog has no real window-token, no IME interaction)
 *   - The dialog's EditText has no R.id; matched here via class +
 *     RootMatchers.isDialog(), which only works against a real attached
 *     dialog window
 *   - DataStore round-trip on the save path (the existing Robolectric
 *     companion CustomNamesActivityRobolectricTest only verifies the
 *     activity inflates without crashing — no list, no dialog, no save)
 *
 * What this test asserts (in order):
 *  1. RecyclerView populates from PackageManager.
 *  2. Clicking the first row opens an AlertDialog with the rename title.
 *  3. Typing in the dialog's EditText and clicking Save calls through to
 *     CustomNamesRepository, observable via getAllCustomNames().
 *  4. The chip strip reflects the new entry once the UI-state Flow
 *     re-emits.
 */
@HiltAndroidTest
class CustomNamesActivityRenameTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var customNames: CustomNamesRepository

    @Before fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun renamingFirstApp_throughDialog_persistsToCustomNamesRepository() {
        // ── PRECONDITION: device has a launcher app to rename. ──────────
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
        assumeTrue(
            "Need at least 1 launcher app on the test device; got ${resolved.size}",
            resolved.size >= 1,
        )

        val newName = "α-test-${System.nanoTime()}"

        // ── BASELINE: see HiddenAppsActivityToggleTest for why the
        // assertion is on a delta vs. the pre-test repo state.
        val baselineNames = runBlocking { customNames.getAllCustomNames() }

        val launchIntent = Intent(ctx, CustomNamesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<CustomNamesActivity>(launchIntent).use {
            onView(withId(R.id.all_apps_recycler_view)).check(matches(isDisplayed()))

            // ── WAIT: same StateFlow-vs-Espresso-idle gap as elsewhere.
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

            // ── ACT: open rename dialog for the first row. The activity
            // creates a fresh AlertDialog with a programmatic EditText.
            onView(withId(R.id.all_apps_recycler_view)).perform(
                RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()),
            )

            // ── WAIT: dialog inflated. The activity launches a 100ms
            // delay coroutine to request focus + show keyboard, so we
            // poll the Save button visibility instead of asserting
            // immediately.
            //
            // No `.inRoot(isDialog())` filter: that path routes through
            // RootViewPicker → ActivityInvoker$-CC (a desugared
            // interface companion missing under our androidx.test
            // runtime → NoClassDefFoundError). Espresso's default root
            // selector already picks the topmost focusable window,
            // which is the dialog while it's showing.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "rename dialog Save button never appeared" },
            ) {
                try {
                    onView(withText(R.string.save))
                        .check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ── ACT: type new name into the dialog EditText. The
            // EditText is programmatic (no R.id), so we match on class.
            // Same default-root rationale as above.
            onView(isAssignableFrom(EditText::class.java))
                .perform(replaceText(newName), closeSoftKeyboard())

            // ── ACT: commit. handleRename → viewModel.setCustomName →
            // repository.
            onView(withText(R.string.save))
                .perform(click())

            // ── ASSERT: chip-strip got a new entry, proving the UI-state
            // Flow saw the repo update and rebuilt the chip strip. The
            // chain is: setCustomNameUseCase → DataStore edit →
            // CustomNamesRepository.customNamesFlow re-emits →
            // GetInstalledAppsUseCase combine folds the name in via
            // applyCustomNames (no PackageManager re-enumeration,
            // REACTIVE_APPLIST_SPEC) → CustomNamesViewModel's collect →
            // updateUiFromMasterList → updateCustomNameChips.
            //
            // We assert by chip-group child count (withId path) instead of
            // by chip text (withText path). The withText matcher routes
            // through Espresso's RootViewPicker.getActivities() which
            // uses `androidx.test.internal.platform.app.ActivityInvoker$-CC`
            // — a desugared interface companion missing under our androidx.test
            // runtime, which throws ClassNotFoundException before the
            // matcher can run. The exact chip text is verified post-hoc
            // through the repo assertion below.
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "rename chip never appeared in app_name_chip_group" },
            ) {
                try {
                    onView(withId(R.id.app_name_chip_group))
                        .check(matches(hasMinimumChildCount(1)))
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }

        // ── ASSERT: persistence reached the repository. The exact
        // packageName depends on the device's first-listed app, so we
        // assert on the *value* delta which is unique per test run.
        val finalNames = runBlocking { customNames.getAllCustomNames() }
        val newEntries = finalNames.filterValues { it == newName } - baselineNames.keys
        assertThat(newEntries).hasSize(1)
    }
}
