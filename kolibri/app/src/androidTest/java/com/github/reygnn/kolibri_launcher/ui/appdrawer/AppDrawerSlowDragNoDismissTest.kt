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
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Pins that a slow vertical drag downward on the AppDrawer root does
 * NOT trigger SwipeDownDismissLayout — the analyzer stays under the
 * `1.2 px/ms` velocity threshold, the wrapper falls through to
 * `super.dispatchTouchEvent`, the RecyclerView consumes the touches
 * normally, and no `popBackStack()` fires.
 *
 * Why this test exists: symmetric mirror of
 * [com.github.reygnn.kolibri_launcher.ui.home.HomeGestureLayoutTest.slowDragUpOnHome_doesNotOpenAppDrawer]
 * for the AppDrawer side. `SwipeDownDismissLayout` is the original
 * `dispatchTouchEvent` wrapper that `HomeGestureLayout` was modeled on,
 * and shares the same velocity-threshold contract: fast swipes
 * dismiss, slow drags fall through. [AppDrawerSwipeDismissTest] pins
 * the positive direction (fast swipe-down dismisses); this test pins
 * the negative direction (slow drag-down does NOT). A future change
 * that lowers the velocity threshold, or that re-introduces a "dismiss
 * if total downward distance > X" branch without a velocity gate,
 * would surface here.
 *
 * Why VISIBLE_CENTER, not TOP_CENTER, as the swipe origin:
 * `INSTRUMENTED_TESTING_NOTES.kt` rule 11. TOP_CENTER lands at y=0
 * which is the system status-bar window — events go to the status
 * bar and never reach `SwipeDownDismissLayout.dispatchTouchEvent`.
 * VISIBLE_CENTER is guaranteed inside the matched view's hit-test
 * region.
 *
 * Why `Thread.sleep` for the negative assertion: same reasoning as
 * the home-side slow-drag test. There is no positive condition for
 * `awaitUntil` to converge on — we want to verify that *no* nav
 * transition fires within a window long enough that one would have
 * completed if it were going to. 1500 ms covers
 * `findNavController().popBackStack()` + FragmentManager.commit +
 * HomeFragment view inflation. A real misfire surfaces well within
 * this window.
 */
@HiltAndroidTest
class AppDrawerSlowDragNoDismissTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)
        }
    }

    @Test
    fun slowDragDownOnDrawerRoot_doesNotDismiss() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // PackageManager pre-warm — same idiom as
        // AppDrawerSwipeDismissTest. The drawer's launchable-apps
        // pipeline has cold-start latency; warming the system PM here
        // keeps RootViewPicker patient enough.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            // Navigate Home → AppDrawer programmatically, same pattern
            // as AppDrawerSwipeDismissTest.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = currentResumedActivity<MainActivity>()
                    ?: error("MainActivity not RESUMED — cannot navigate")
                val nav = activity.findNavController(R.id.nav_host_fragment)
                nav.navigate(R.id.action_homeFragment_to_appDrawerFragment)
            }

            // Pre-condition: drawer is up.
            onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
            onView(withId(R.id.app_drawer_root)).check(matches(isDisplayed()))

            // ACT: slow downward drag on the drawer root.
            // `Swipe.SLOW` stays under SwipeDownDismissLayout's
            // `1.2 px/ms` velocity threshold. The wrapper's analyzer
            // returns IGNORED, `super.dispatchTouchEvent` falls
            // through to the RecyclerView, and no
            // `popBackStack()` fires.
            onView(withId(R.id.app_drawer_root)).perform(
                actionWithAssertions(
                    GeneralSwipeAction(
                        Swipe.SLOW,
                        GeneralLocation.VISIBLE_CENTER,
                        GeneralLocation.BOTTOM_CENTER,
                        Press.FINGER,
                    )
                )
            )

            // ASSERT: drawer is STILL up (no dismiss happened).
            //
            // Negative-window pattern — see KDoc above for why
            // `Thread.sleep` is the correct idiom here, not a
            // synchronization anti-pattern.
            Thread.sleep(1_500)
            onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
            onView(withId(R.id.app_drawer_root)).check(matches(isDisplayed()))
        }
    }

    private inline fun <reified T : Activity> currentResumedActivity(): T? {
        val resumed = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
        return resumed.firstOrNull { it is T } as? T
    }
}
