package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
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
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.support.awaitUntil
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression guard for the drag-to-dismiss WEDGE (patch 7).
 *
 * `DragToDismissCore.dragTarget` is the overlay container that
 * `MainActivity.hideDrawer()` also animates. A below-threshold release starts
 * the core's settle-back animation on that container; if a hide fires within
 * the ~220 ms spring, `hideDrawer()`'s `container.animate().cancel()` tears
 * down the settle-back animator. ViewPropertyAnimator's `withEndAction` is not
 * guaranteed to run on cancel, which could leave the core's `settling` flag
 * stuck `true`. Because the core instance outlives the reused fragment, EVERY
 * subsequent drag would then be silently ignored (`onStartNestedScroll` /
 * `onNestedPreFling` are gated on `!settling`).
 *
 * The fix makes a new gesture clear a stale `settling` on start. This test
 * reproduces the trigger sequence and asserts the drag still works afterwards:
 *
 *   1. open → short slow drag (settle-back path) → immediate BACK (hide,
 *      cancelling the settle-back mid-flight) → wait until hidden;
 *   2. reopen → FAST swipe down → the overlay must dismiss.
 *
 * Step 2 dismissing is the proof the core did not wedge. On a platform where
 * `withEndAction` does happen to run on cancel there is no wedge to begin with,
 * so the test simply stays green — it never yields a false failure, only guards
 * the behaviour. The DETERMINISTIC regression guard lives in
 * `DragToDismissCoreTest` (common-ui), which strands `settling` directly and
 * does not depend on animator-cancel timing; this instrumented case is the
 * end-to-end smoke companion. Opened through the production path (`onFlingUp` →
 * `ShowAppDrawer` → `showDrawer`); dismissal hides the overlay container, so
 * "gone" == `app_drawer_root` no longer displayed.
 */
@HiltAndroidTest
class AppDrawerDragReopenWedgeTest {

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
    fun settleBackThenHide_doesNotWedgeNextOpenDismiss() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            open()
            awaitDrawerVisible("AppDrawer never became visible after first onFlingUp()")

            // ── ACT 1: short, slow downward drag → below the distance
            // threshold and under the fling velocity, so the core starts a
            // settle-back spring (drawer stays up).
            onView(withId(R.id.apps_recycler_view)).perform(
                actionWithAssertions(
                    GeneralSwipeAction(
                        Swipe.SLOW,
                        GeneralLocation.VISIBLE_CENTER,
                        shortDownFrom(GeneralLocation.VISIBLE_CENTER, fraction = 0.15f),
                        Press.FINGER,
                    )
                )
            )

            // ── ACT 2: hide immediately via BACK, cancelling the in-flight
            // settle-back animator on the shared container — the wedge trigger.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = currentResumedActivity<MainActivity>()
                    ?: error("MainActivity not RESUMED — cannot press back")
                activity.onBackPressedDispatcher.onBackPressed()
            }
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "AppDrawer overlay never hid after BACK" },
            ) { isGone() }

            // ── ACT 3: reopen and FAST swipe down. If the core wedged, the
            // fling path stays gated on `settling` and nothing dismisses.
            open()
            awaitDrawerVisible("AppDrawer never became visible after reopen")
            onView(withId(R.id.apps_recycler_view)).perform(
                actionWithAssertions(
                    GeneralSwipeAction(
                        Swipe.FAST,
                        GeneralLocation.VISIBLE_CENTER,
                        GeneralLocation.BOTTOM_CENTER,
                        Press.FINGER,
                    )
                )
            )

            // ── ASSERT: dismiss still works — the drag did not wedge.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "AppDrawer did not dismiss on reopen — drag wedged after settle+hide" },
            ) { isGone() }
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun open() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = currentResumedActivity<MainActivity>()
                ?: error("MainActivity not RESUMED — cannot open drawer")
            activity.viewModel.onFlingUp()
        }
    }

    private fun awaitDrawerVisible(describe: String) {
        awaitUntil(timeoutMs = 5_000, describe = { describe }) {
            try {
                onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
                onView(withId(R.id.app_drawer_root)).check(matches(isDisplayed()))
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun isGone(): Boolean = try {
        onView(withId(R.id.app_drawer_root)).check(matches(not(isDisplayed())))
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * A CoordinatesProvider ending [fraction] of the matched view's height
     * below [origin] — a deliberately short downward drag that stays under
     * DragToDismissCore's dismiss-distance threshold.
     */
    private fun shortDownFrom(origin: GeneralLocation, fraction: Float) =
        CoordinatesProvider { view ->
            val start = origin.calculateCoordinates(view)
            floatArrayOf(start[0], start[1] + view.height * fraction)
        }

    private inline fun <reified T : Activity> currentResumedActivity(): T? {
        val resumed = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
        return resumed.firstOrNull { it is T } as? T
    }
}
