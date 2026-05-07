package com.github.reygnn.kolibri_launcher.ui.home

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
 * Why instrumented: [HomeGestureLayout] is a custom `dispatchTouchEvent`
 * implementation. It detects swipes via raw-delta velocity (not via
 * `GestureDetector.onFling` and not via VelocityTracker), exists
 * specifically because nested `ScrollView` calls
 * `requestDisallowInterceptTouchEvent(true)` mid-gesture which silently
 * disables `OnTouchListener` and `onInterceptTouchEvent` on the
 * parent. That entire pipeline — touch slop, real elapsed-time
 * deltas, the dispatch chain, and the synthesized ACTION_CANCEL —
 * cannot be honestly covered by Robolectric (same reasoning as for
 * [com.github.reygnn.kolibri_launcher.ui.appdrawer.AppDrawerSwipeDismissTest]).
 *
 * Test budget rationale (per `INSTRUMENTED_TESTING_NOTES.kt` rule 8):
 * Two tests, one positive and one negative, both targeting `swipeUp`.
 * The other three directions (`swipeDown` / `swipeLeft` / `swipeRight`)
 * route to system-level effects (notifications via accessibility,
 * configured swipe-app launch) that are not structurally observable
 * from inside the app's view tree without polluting the production
 * API with a test-only callback hook. Direction-discrimination of the
 * underlying [com.github.reygnn.kolibri_launcher.ui.util.SwipeGestureAnalyzer]
 * is JVM-tested in `SwipeGestureAnalyzerTest`; what only the real touch pipeline can
 * prove is that fast swipes trigger and slow drags don't, which one
 * direction demonstrates structurally. Real-device validation
 * (homescroll.md §6 Step 5) covers per-direction feel.
 *
 * What this test asserts:
 *  1. After MainActivity launches into HomeFragment (onboarding marked
 *     complete + crash-report consent already asked), `homeGestureRoot`
 *     is displayed — proves the wrapper is part of the inflated tree.
 *  2. A FAST swipe-up gesture on `homeGestureRoot` flips the analyzer
 *     to `UP`, fires the wired `onSwipeUp` callback, which routes
 *     through `GestureDelegate.onFlingUp` → `UiEvent.ShowAppDrawer`
 *     → `MainActivity`'s nav collector → `nav.navigate(…)` opens
 *     the AppDrawer; `apps_recycler_view` becomes visible.
 *  3. A SLOW drag on the same axis stays under the wrapper's
 *     `1.2 px/ms` velocity threshold, the analyzer returns IGNORED,
 *     the parent dispatches normally to children — and HomeFragment's
 *     `favoritesRecyclerView` stays visible (no nav transition fired).
 *
 * Why VISIBLE_CENTER, not TOP_CENTER, as the swipe origin:
 * `INSTRUMENTED_TESTING_NOTES.kt` rule 11. TOP_CENTER lands at y=0
 * which is the system status-bar window — events go to the status
 * bar and never reach `HomeGestureLayout.dispatchTouchEvent`.
 * VISIBLE_CENTER is guaranteed inside the matched view's hit-test
 * region.
 */
@HiltAndroidTest
class HomeGestureLayoutTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            // Skip onboarding so MainActivity lands on HomeFragment, not
            // OnboardingActivity. Same boilerplate as
            // AppDrawerSwipeDismissTest / AppDrawerFragmentSearchTest.
            settings.setOnboardingCompleted()
            // Mark crash-report consent as already asked so the dialog
            // doesn't show up on top of HomeFragment and steal the
            // touches we're trying to dispatch. consent=false so ACRA
            // doesn't actually transmit anything during instrumented runs.
            CrashReportConsentStore.saveConsent(ctx, consent = false)
        }
    }

    @Test
    fun fastSwipeUpOnHome_opensAppDrawer() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // PackageManager pre-warm — the AppDrawer's WhileSubscribed
        // launchable-apps StateFlow has cold-start latency on a fresh
        // process; warming the system PM here keeps RootViewPicker
        // patient enough. Same idiom as AppDrawerSwipeDismissTest.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            // Pre-condition: HomeFragment is showing and the wrapper is
            // attached. If this fails, MainActivity didn't reach
            // HomeFragment (onboarding redirect, ACRA dialog, etc.) and
            // every other assertion below would be misleading.
            onView(withId(R.id.homeGestureRoot)).check(matches(isDisplayed()))
            onView(withId(R.id.favoritesRecyclerView)).check(matches(isDisplayed()))

            // ACT: fast upward swipe on the wrapper.
            //
            // Swipe.FAST + half-screen distance (VISIBLE_CENTER → TOP_CENTER)
            // blows past all three thresholds in HomeGestureLayout:
            // distance > scaledTouchSlop * 4, velocity > 1.2 px/ms,
            // dominance |dy| > 1.5 * |dx|. Half-screen is unambiguous on
            // any density, so no per-device tuning is needed.
            onView(withId(R.id.homeGestureRoot)).perform(
                actionWithAssertions(
                    GeneralSwipeAction(
                        Swipe.FAST,
                        GeneralLocation.VISIBLE_CENTER,
                        GeneralLocation.TOP_CENTER,
                        Press.FINGER,
                    )
                )
            )

            // ASSERT: AppDrawer attaches asynchronously after the nav
            // transaction commits. Espresso's idle model doesn't gate
            // on coroutine-driven UiEvent → nav transitions, so a bare
            // `onView(R.id.apps_recycler_view).check(...)` would race.
            // Same `awaitUntil` shape as AppDrawerSwipeDismissTest.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "AppDrawer never opened after fast swipe-up on homeGestureRoot" },
            ) {
                try {
                    onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    @Test
    fun slowDragUpOnHome_doesNotOpenAppDrawer() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            onView(withId(R.id.homeGestureRoot)).check(matches(isDisplayed()))
            onView(withId(R.id.favoritesRecyclerView)).check(matches(isDisplayed()))

            // ACT: slow drag on the same axis. Espresso's `Swipe.SLOW`
            // dispatches the same coordinate path over a longer
            // duration — the resulting velocity stays below
            // HomeGestureLayout's `1.2 px/ms` threshold. The analyzer
            // returns IGNORED, the parent's `dispatchTouchEvent` falls
            // through to `super.dispatchTouchEvent`, the ScrollView
            // receives events normally, and no `onSwipeUp` callback
            // fires.
            onView(withId(R.id.homeGestureRoot)).perform(
                actionWithAssertions(
                    GeneralSwipeAction(
                        Swipe.SLOW,
                        GeneralLocation.VISIBLE_CENTER,
                        GeneralLocation.TOP_CENTER,
                        Press.FINGER,
                    )
                )
            )

            // ASSERT: HomeFragment is still showing, AppDrawer is not.
            //
            // This is a "did NOT happen" assertion. There is no positive
            // condition for `awaitUntil` to converge on — we want to
            // verify that *no* nav transition fires within a window
            // long enough that one would have completed if it were
            // going to. `Thread.sleep` is the right idiom here, not a
            // synchronization anti-pattern (rule 7 in
            // INSTRUMENTED_TESTING_NOTES forbids sleep-as-sync, not
            // sleep-as-window for negative assertions).
            //
            // 1500 ms covers: GestureDelegate.launchSafe coroutine hop,
            // UiEvent SharedFlow emission, MainActivity event collector,
            // FragmentManager.commit, AppDrawer view inflation. A real
            // misfire would surface well within this window.
            Thread.sleep(1_500)
            onView(withId(R.id.favoritesRecyclerView)).check(matches(isDisplayed()))
        }
    }
}
