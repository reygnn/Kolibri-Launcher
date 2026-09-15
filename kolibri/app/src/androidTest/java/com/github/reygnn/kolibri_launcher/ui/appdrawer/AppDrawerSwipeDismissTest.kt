package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.app.Activity
import android.content.Intent
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
 * Why instrumented: the AppDrawer dismisses via `SwipeDownDismissLayout`,
 * which now drives a **drag-to-dismiss** (`DragToDismissCore`) built on
 * nested scrolling — the drawer follows the finger once the list is pinned
 * at the top, and a decisive downward fling (or a release past the distance
 * threshold) hands off to the host's hide animation. Touch slop, the
 * velocity tracker and the nested-scroll callback chain are exactly what
 * Robolectric cannot honestly cover; the Robolectric companion only attaches
 * the fragment and asserts no crash, it never sends a gesture.
 *
 * Architecture note (post overlay-drawer migration): the drawer is no
 * longer a NavController destination. It is a visibility-toggled overlay in
 * `activity_main.xml` (MainActivity.showDrawer/hideDrawer). It is opened
 * through the production path — `viewModel.onFlingUp()` emits
 * `UiEvent.ShowAppDrawer`, which the Activity turns into `showDrawer()` —
 * rather than by navigating to a fragment. Dismissal therefore no longer
 * pops a back stack; it hides the overlay container. The correct "is the
 * drawer gone" signal is that `app_drawer_root` is no longer displayed, NOT
 * that HomeFragment's `favoritesRecyclerView` is displayed: the live home
 * sits behind the overlay the whole time, so it is displayed even while the
 * drawer is open.
 *
 * What this test asserts:
 *  1. After `onFlingUp()`, the drawer's RecyclerView and root render.
 *  2. A fast swipe-down on the (top-pinned) list drives DragToDismissCore
 *     past its fling threshold and commits the dismiss.
 *  3. The overlay hides: `app_drawer_root` is no longer displayed.
 *
 * Why a custom GeneralSwipeAction instead of `swipeDown()`: Espresso's
 * built-in `swipeDown()` uses fixed coordinate ratios that can land on the
 * search container rather than the scrollable list. The custom action goes
 * VISIBLE_CENTER → BOTTOM_CENTER across the list, so the nested-scroll child
 * (the RecyclerView) reports the unconsumed downward delta that
 * DragToDismissCore turns into a dismiss. VISIBLE_CENTER (not TOP_CENTER)
 * avoids y=0, which is the system status-bar window.
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
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)
        }
    }

    @Test
    fun fastSwipeDownOnDrawer_dismissesOverlay() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // PackageManager pre-warm: ensure the system PM has its launcher
        // list cached before MainActivity's drawerApps pipeline starts, so
        // the cold first-launch doesn't stretch past RootViewPicker patience.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            // ── Open the drawer through the production event path (fling-up
            // → UiEvent.ShowAppDrawer → showDrawer). We drive the ViewModel
            // directly rather than performing the home swipe, whose gesture
            // wiring is its own hazard surface and not what we verify here.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = currentResumedActivity<MainActivity>()
                    ?: error("MainActivity not RESUMED — cannot open drawer")
                activity.viewModel.onFlingUp()
            }

            // ── ASSERT: drawer attached and shown. Wrapped in awaitUntil
            // because showDrawer runs a short slide-in animation and the
            // ShowAppDrawer event hops a Channel before it fires.
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

            // ── ACT: fast swipe down on the list. The list is pinned at the
            // top (onDrawerShown scrolls to position 0), so the RecyclerView
            // cannot consume the downward delta; the unconsumed remainder
            // reaches SwipeDownDismissLayout's DragToDismissCore, and a FAST
            // swipe blows past its fling-dismiss velocity.
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

            // ── ASSERT: the overlay is gone. In the overlay model the drawer
            // is dismissed by hiding its container (visibility → gone after
            // the hide animation), so `app_drawer_root` is no longer
            // displayed. HomeFragment behind it is irrelevant — it was
            // displayed the whole time.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "AppDrawer overlay never hid after fast swipe-down" },
            ) {
                try {
                    onView(withId(R.id.app_drawer_root)).check(matches(not(isDisplayed())))
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
