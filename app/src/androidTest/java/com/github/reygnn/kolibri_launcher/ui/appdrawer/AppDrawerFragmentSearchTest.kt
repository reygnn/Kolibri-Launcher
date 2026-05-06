package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.data.CrashReportConsentStore
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.support.awaitUntil
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Why instrumented: AppDrawerFragment is the launcher's primary
 * interaction surface — a real RecyclerView populated through a
 * `WhileSubscribed` StateFlow on PackageManager + a debounced search
 * that filters the list via [com.github.reygnn.kolibri_launcher.ui.appdrawer.AppSearchFilter]
 * and updates the adapter through DiffUtil. None of this is honestly
 * exercised by the existing Robolectric companion
 * (AppDrawerFragmentRobolectricTest only attaches the fragment to a
 * HiltTestActivity and asserts no crash).
 *
 * What this test asserts (in order):
 *  1. MainActivity launches into HomeFragment after marking onboarding
 *     as complete (otherwise MainActivity redirects to OnboardingActivity
 *     and the test would never reach the drawer).
 *  2. Programmatic NavController.navigate from HomeFragment → AppDrawer
 *     succeeds. The swipe-up gesture would be more realistic but the
 *     RecyclerView+search pipeline is what we want to verify, not the
 *     gesture wiring.
 *  3. `apps_recycler_view` populates from real PackageManager via the
 *     WhileSubscribed StateFlow — this is the cold-path-priming pattern
 *     that the BACKUP_COLD_PATH_FIX commit exposed in a different code
 *     site. Here the production code path drives the priming naturally
 *     because the fragment's `viewModel.drawerApps.observe` is the
 *     subscriber.
 *  4. Typing a nonsense query into `search_edit_text` empties the list
 *     after the SEARCH_DEBOUNCE_DELAY_MS debounce window. Clearing the
 *     query repopulates it. Both transitions go through the real
 *     search-flow pipeline (EditText → ViewModel → AppSearchFilter →
 *     adapter).
 *
 * NOT covered here:
 *  - The actual app launch on click — that's `LauncherApps.startMainActivity`
 *    which is its own portability hazard (launch may fail on a device
 *    where the resolved app is in a profile we don't have access to)
 *    and the `onAppClicked` plumbing is well-covered by JVM tests of
 *    `AppManagementDelegate`.
 *  - Auto-launch-on-single-result: that path requires the
 *    autoLaunchApp setting to be enabled, which it isn't by default.
 *    The `AppSearchFilter`-side decision is unit-tested separately.
 */
@HiltAndroidTest
class AppDrawerFragmentSearchTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            // Skip onboarding: MainActivity gates on this and would
            // otherwise launch OnboardingActivity, which doesn't host
            // the drawer at all.
            settings.setOnboardingCompleted()
            // Skip the ACRA crash-report consent dialog. On a fresh test
            // app install (HiltAndroidTest gives every class an isolated
            // DataStore) `HAS_ASKED_KEY` is false → MainActivity shows the
            // dialog on top of HomeFragment → our programmatic navigate()
            // races with the dialog and AppDrawer never reliably attaches.
            // Marking consent as already-asked closes that gap. Set
            // `consent=false` because we don't want ACRA to actually
            // forward anything during instrumented tests.
            CrashReportConsentStore.saveConsent(ctx, consent = false)
        }
    }

    @Test
    fun typingNonsense_filtersListEmpty_clearingRestoresIt() {
        // ── PRECONDITION: the drawer pulls launchable apps via
        // PackageManager. Same portability check as elsewhere.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
        assumeTrue(
            "Need at least 3 launcher apps so 'list shrinks under filter' is a meaningful assertion; got ${resolved.size}",
            resolved.size >= 3,
        )

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            // ── Navigate Home → AppDrawer programmatically. The swipe-
            // gesture path lives in HomeFragment.gestureZone and is its
            // own hazard surface (touch slop, gesture detector); not what
            // we're verifying here.
            //
            // Why not `scenario.onActivity { }`: that path resolves
            // `androidx.test.internal.platform.app.ActivityInvoker$-CC`
            // (a desugared interface companion) which is missing under
            // our androidx.test runtime — fails with NoClassDefFoundError
            // before the lambda ever runs. Walking through the
            // ActivityLifecycleMonitorRegistry instead avoids that path.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = currentResumedActivity<MainActivity>()
                    ?: error("MainActivity not RESUMED — cannot navigate")
                val nav = activity.findNavController(R.id.nav_host_fragment)
                nav.navigate(R.id.action_homeFragment_to_appDrawerFragment)
            }

            // ── WAIT: nav transaction commits asynchronously. Espresso's
            // idle model doesn't gate on FragmentManager transactions
            // outside its own dispatch, so a bare assertion here races
            // with the AppDrawer view-tree inflation.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "AppDrawer view tree never attached after navigate()" },
            ) {
                try {
                    onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            onView(withId(R.id.search_edit_text)).check(matches(isDisplayed()))

            // ── WAIT: app list populated. This is the same StateFlow-
            // not-observed-by-Espresso-idle gap as in
            // OnboardingToHomeSmokeTest. 3-child threshold is the same
            // assumeTrue threshold above.
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "AppDrawer RecyclerView never reached >=3 children" },
            ) {
                try {
                    onView(withId(R.id.apps_recycler_view))
                        .check(matches(hasMinimumChildCount(3)))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ── ACT: type a nonsense string into the search field. The
            // string is intentionally not a substring of any real package
            // or display name, so AppSearchFilter must return ShowList
            // with an empty list (not AutoLaunch — that requires both
            // single match AND the autoLaunchApp setting on, which we
            // didn't enable).
            onView(withId(R.id.search_edit_text)).perform(
                replaceText("zxqv-no-app-matches-this-${System.nanoTime()}"),
                closeSoftKeyboard(),
            )

            // ── ASSERT: list collapses to 0 children after the
            // SEARCH_DEBOUNCE_DELAY_MS (150ms) debounce + filter pass.
            // 5s budget is generous for an emulator's DiffUtil cycle.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "AppDrawer list did not collapse to 0 children after nonsense query" },
            ) {
                try {
                    onView(withId(R.id.apps_recycler_view))
                        .check(matches(hasChildCount(0)))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ── ACT: clear the query. Empty string ⇒ AppSearchFilter
            // returns the full master list.
            onView(withId(R.id.search_edit_text)).perform(
                replaceText(""),
                closeSoftKeyboard(),
            )

            // ── ASSERT: list repopulates. We assert >=3 again rather
            // than the exact original count because device app
            // installations can race with the test (PackageManager
            // observers fire in the background); the lower bound is
            // what's structurally guaranteed.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "AppDrawer list did not repopulate after clearing query" },
            ) {
                try {
                    onView(withId(R.id.apps_recycler_view))
                        .check(matches(hasMinimumChildCount(3)))
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    /**
     * Espresso ships `hasMinimumChildCount` and `hasChildCount` for
     * ViewGroup, but `RecyclerView` reports children = visible viewholders
     * which is what we actually want to assert on. Both built-in matchers
     * work against [androidx.recyclerview.widget.RecyclerView] because
     * RecyclerView extends ViewGroup; this local matcher exists only to
     * make the "exactly N" assertion symmetric with the project's existing
     * `hasMinimumChildCount` usage.
     */
    private fun hasChildCount(count: Int): Matcher<View> =
        object : BoundedMatcher<View, RecyclerView>(RecyclerView::class.java) {
            override fun describeTo(description: Description) {
                description.appendText("RecyclerView with exactly $count children")
            }

            override fun matchesSafely(item: RecyclerView): Boolean =
                item.childCount == count
        }

    /**
     * Returns the currently-RESUMED Activity of type [T], or null if none
     * is resumed. Must be called from the main thread (the registry
     * iterates non-thread-safe stage maps).
     *
     * Used as a substitute for `ActivityScenario.onActivity { }` when
     * that path hits the `ActivityInvoker$-CC` desugaring gap.
     */
    private inline fun <reified T : Activity> currentResumedActivity(): T? {
        val resumed = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
        return resumed.firstOrNull { it is T } as? T
    }
}
