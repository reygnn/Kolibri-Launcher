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
import com.github.reygnn.launcher.testing.awaitUntil
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pins the negative half of the drag-to-dismiss contract: a SHORT downward
 * drag — below `DragToDismissCore.dismissDistanceFraction` (0.28 of the
 * host height) and slow enough not to fling — must NOT dismiss. The core
 * follows the finger, then `onStopNestedScroll` sees the offset is under the
 * threshold and springs the sheet back (`animateSettleBack`). The overlay
 * stays up.
 *
 * Why the gesture changed from the pre-migration version: the old
 * `SwipeDownDismissLayout` dismissed on a *velocity*-gated flick, so the
 * negative case was "a slow drag of any length". `DragToDismissCore` is
 * *distance*-gated on release (plus a fling shortcut), so a slow drag that
 * travels far enough now legitimately dismisses. The meaningful negative is
 * therefore a slow drag kept deliberately short — this is what a user does
 * when they start to peek the sheet down and change their mind. The positive
 * direction (fast fling dismisses) is pinned by [AppDrawerSwipeDismissTest].
 *
 * Drag distance: 15% of the list's height, comfortably under the 28%
 * host-height threshold (the list is shorter than the host, so 0.15·list <
 * 0.28·host with margin). `Swipe.SLOW` keeps velocity well under the
 * fling-dismiss cutoff, so neither the distance nor the fling path fires.
 *
 * Why VISIBLE_CENTER as the origin: TOP_CENTER lands at y=0, the system
 * status-bar window; those events never reach the drawer. The drag starts on
 * the RecyclerView (the nested-scroll child) so the offset is actually
 * routed to DragToDismissCore.
 *
 * Why `Thread.sleep` for the negative assertion: there is no positive
 * condition for `awaitUntil` to converge on — we verify that *no* dismiss
 * fires within a window long enough that the hide animation (≈180 ms) plus
 * the settle-back spring (≈220 ms) would both have completed. 1500 ms covers
 * it comfortably; a real misfire surfaces well within it.
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
    fun shortSlowDragDownOnDrawer_doesNotDismiss() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // PackageManager pre-warm — same idiom as AppDrawerSwipeDismissTest.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            // Open the drawer through the production event path, same as
            // AppDrawerSwipeDismissTest.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = currentResumedActivity<MainActivity>()
                    ?: error("MainActivity not RESUMED — cannot open drawer")
                activity.viewModel.onFlingUp()
            }

            // Pre-condition: drawer is up.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "AppDrawer overlay never became visible after onFlingUp()" },
            ) {
                try {
                    onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
                    onView(withId(R.id.app_drawer_root)).check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ACT: short, slow downward drag on the list. `Swipe.SLOW` stays
            // under the fling-dismiss velocity, and the 15% travel stays under
            // the 28% distance threshold, so DragToDismissCore settles back
            // instead of dismissing.
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

            // ASSERT: drawer is STILL up (settle-back, no dismiss).
            // Negative-window pattern — see the class KDoc for why
            // `Thread.sleep` is the correct idiom here.
            Thread.sleep(1_500)
            onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
            onView(withId(R.id.app_drawer_root)).check(matches(isDisplayed()))
        }
    }

    /**
     * A CoordinatesProvider that ends [fraction] of the matched view's height
     * BELOW the point [origin] resolves to — used to build a deliberately
     * short downward drag whose total travel stays under
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
