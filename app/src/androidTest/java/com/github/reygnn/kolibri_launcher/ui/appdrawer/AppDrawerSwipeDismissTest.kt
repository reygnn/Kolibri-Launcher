package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.app.Activity
import android.content.Intent
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.actionWithAssertions
import androidx.test.espresso.assertion.ViewAssertions.matches
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Why instrumented: AppDrawerFragment dismisses-on-swipe-down via
 * `SwipeDownDismissLayout` — a custom `dispatchTouchEvent` implementation
 * that exists because RecyclerView's `requestDisallowInterceptTouchEvent`
 * defeats normal `OnTouchListener` / `onInterceptTouchEvent` once scrolling
 * starts. The class KDoc explicitly warns "don't try to move the detection
 * logic… it has to live in the View hierarchy where dispatchTouchEvent is
 * reachable."
 *
 * That code is exactly the sort of touch-pipeline logic Robolectric cannot
 * honestly cover: touch slop, velocity tracker, the dispatchTouchEvent
 * callback chain. The Robolectric AppDrawerFragmentRobolectricTest just
 * attaches the fragment and asserts no crash; it never sends a gesture.
 *
 * What this test asserts:
 *  1. After programmatically navigating Home → AppDrawer, the drawer's
 *     RecyclerView and search field render.
 *  2. A swipe-down gesture on `app_drawer_root` triggers the
 *     SwipeDownDismissLayout callback, which calls
 *     `findNavController().popBackStack()` and brings HomeFragment back.
 *  3. HomeFragment's `appList` is visible, proving we're back at the
 *     start destination of the nav graph.
 *
 * Why a custom GeneralSwipeAction instead of `swipeDown()`: Espresso's
 * built-in `swipeDown()` uses fixed coordinate ratios that, on the AppDrawer
 * root view, can fall on the (empty-state) RecyclerView area instead of the
 * search container. The custom action goes from TOP_CENTER to BOTTOM_CENTER
 * which traverses the full screen height and is unambiguously detectable
 * by SwipeDownDismissLayout's threshold logic.
 */
@HiltAndroidTest
class AppDrawerSwipeDismissTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            CrashReportConsentStore.saveConsent(ctx, consent = false)
        }
    }

    @Test
    fun swipeDownOnDrawerRoot_popsBackToHomeFragment() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // Same PackageManager pre-warm as AppDrawerFragmentSearchTest:
        // calling queryIntentActivities here ensures the system PM has
        // its launcher list cached before MainActivity's drawerApps
        // pipeline starts. Without this warm-up the cold first-launch
        // can stretch past Espresso's default RootViewPicker patience.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            // ── Navigate Home → AppDrawer (same pattern as
            // AppDrawerFragmentSearchTest: programmatic nav via the
            // lifecycle registry, avoids the
            // ActivityScenario.onActivity { } path).
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = currentResumedActivity<MainActivity>()
                    ?: error("MainActivity not RESUMED — cannot navigate")
                val nav = activity.findNavController(R.id.nav_host_fragment)
                nav.navigate(R.id.action_homeFragment_to_appDrawerFragment)
            }

            // ── ASSERT: drawer attached. Direct asserts (not wrapped in
            // awaitUntil) — Espresso's RootViewPicker has its own ~10s
            // patience built in. Wrapping in awaitUntil's 50ms polling
            // races with Espresso's internal wait and gives confusing
            // timeouts. AppDrawerFragmentSearchTest uses the same shape.
            onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
            onView(withId(R.id.app_drawer_root)).check(matches(isDisplayed()))

            // ── ACT: swipe down on the drawer root.
            //
            // Use VISIBLE_CENTER → BOTTOM_CENTER instead of
            // TOP_CENTER → BOTTOM_CENTER. TOP_CENTER lands at y=0 which
            // on a real device is the system status bar area — those
            // events go to the status-bar window and never reach
            // SwipeDownDismissLayout. VISIBLE_CENTER is guaranteed to
            // be inside the matched view's hit-test region.
            //
            // SwipeDownDismissLayout requires three thresholds (distance
            // > 4×touchSlop, vertical-dominant, velocity > 1.2px/ms);
            // a half-screen FAST swipe blows past all three on any
            // density.
            onView(withId(R.id.app_drawer_root)).perform(
                actionWithAssertions(
                    GeneralSwipeAction(
                        Swipe.FAST,
                        GeneralLocation.VISIBLE_CENTER,
                        GeneralLocation.BOTTOM_CENTER,
                        Press.FINGER,
                    )
                )
            )

            // ── ASSERT: HomeFragment is back. `appList` is HomeFragment's
            // favorites RecyclerView; AppDrawerFragment doesn't have a view
            // with that id, so its presence is a unique structural signal
            // that the navigation popped back.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "HomeFragment never returned after swipe-down" },
            ) {
                try {
                    onView(withId(R.id.appList)).check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    private inline fun <reified T : Activity> currentResumedActivity(): T? {
        val resumed = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
        return resumed.firstOrNull { it is T } as? T
    }
}
