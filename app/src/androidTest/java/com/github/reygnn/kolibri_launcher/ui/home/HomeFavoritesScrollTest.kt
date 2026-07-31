package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.actionWithAssertions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.support.awaitUntil
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Pins that a slow vertical drag on an overflowing favorites list
 * scrolls the RecyclerView via the wrapper's pass-through path —
 * the analyzer returns IGNORED for slow drags, `super.dispatchTouchEvent`
 * routes through to the RecyclerView, RecyclerView's
 * `onInterceptTouchEvent` claims past `touchSlop`, and scroll happens.
 *
 * Why this test exists: round-2 of the HomeGesture-Wrapper migration's
 * real-device validation found that the (then-existing)
 * `ScrollStateVerifier` was periodically resetting `allowIntercept` on
 * `NonInterceptingScrollView`, silently undoing the scroll fix on every
 * verification cycle. The bug was caught only by manual real-device
 * testing because no automated test populated favorites enough to
 * overflow the screen. `homescroll.md` Step 5 round-1 notes flagged the
 * gap; this test closes it. Even after the cleanup branch removed the
 * verifier and replaced the ScrollView with a RecyclerView (commit
 * 501c8ab), the underlying behavior — slow drags must scroll, fast
 * swipes must trigger the wrapper — is the load-bearing contract this
 * test pins.
 */
@HiltAndroidTest
class HomeFavoritesScrollTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)

            // Seed favorites with REAL launchable apps from the device,
            // per `INSTRUMENTED_TESTING_NOTES` "ASSUMPTION TRAPS" #1:
            // synthetic component strings persist silently and don't
            // round-trip through the resolveActivity pipeline — the
            // home renderer would not show them.
            val launcherIntent =
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
            val componentNames = resolved.map { ri ->
                "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
            }
            assumeTrue(
                "Need ≥10 launchable apps so the favorites list overflows; " +
                    "got ${componentNames.size}",
                componentNames.size >= 10,
            )
            favoritesRepository.saveFavoriteComponents(componentNames)
        }
    }

    @Test
    fun slowDragOnOverflowingFavorites_scrollsTheList() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // PackageManager pre-warm — same idiom as the other instrumented
        // tests; smooths cold-start RootViewPicker patience.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            // Wait for the favorites RecyclerView to populate. We need
            // enough items to overflow the screen — 10 small Buttons are
            // enough to overflow the AVD's vertical viewport.
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "favoritesRecyclerView never reached ≥10 children" },
            ) {
                try {
                    onView(withId(R.id.favoritesRecyclerView))
                        .check(matches(hasMinimumChildCount(10)))
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            onView(withId(R.id.homeGestureRoot)).check(matches(isDisplayed()))

            // Sanity: scroll position is at the top before we drag.
            onView(withId(R.id.favoritesRecyclerView)).check { view, _ ->
                val rv = view as RecyclerView
                check(rv.computeVerticalScrollOffset() == 0) {
                    "expected scrollOffset=0 at start, got " +
                        "${rv.computeVerticalScrollOffset()}"
                }
            }

            // ACT: slow drag upward on the wrapper. `Swipe.SLOW` stays
            // under the wrapper's `1.2 px/ms` velocity threshold so the
            // analyzer returns IGNORED; events fall through to the
            // RecyclerView whose `onInterceptTouchEvent` claims past
            // `scaledTouchSlop` and starts scrolling.
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

            // ASSERT: the RecyclerView scrolled.
            // `computeVerticalScrollOffset()` is the pixel-accurate
            // distance from the top — 0 before any scroll, > 0 after.
            // Wrap in `awaitUntil` because the scroll animation
            // continues asynchronously after the touch sequence ends
            // (fling deceleration finalising the offset).
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "favoritesRecyclerView scrollOffset never became >0" },
            ) {
                try {
                    onView(withId(R.id.favoritesRecyclerView)).check { view, _ ->
                        val rv = view as RecyclerView
                        check(rv.computeVerticalScrollOffset() > 0) {
                            "computeVerticalScrollOffset()=" +
                                "${rv.computeVerticalScrollOffset()}"
                        }
                    }
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }
}
