package com.github.reygnn.kolibri_launcher.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Why instrumented: validates the first-run path on real Android
 * infrastructure. Robolectric covers the fragments and ViewModel logic
 * individually, but cannot honestly exercise:
 *   - real RecyclerView measure + layout (OnboardingAppListAdapter is
 *     populated via DiffUtil and a real LinearLayoutManager — Robolectric
 *     fakes layout passes and can mask "ItemView never reaches measured
 *     state because parent was 0px" bugs)
 *   - real Espresso ChipGroup interactions
 *   - real Intent firing across activity boundaries
 *
 * COVERAGE LIMIT (read this before extending):
 * The original test additionally called scenario.recreate() AFTER the
 * done-click to assert that state survives a config change. That setup
 * cannot work — OnboardingActivity.finish()'es itself when it launches
 * MainActivity, so by the time recreate() is called the underlying
 * Activity is already destroyed and ActivityScenario throws NPE.
 * Persistence-across-recreate belongs in a Robolectric ViewModel /
 * Activity test instead, where the lifecycle can be replayed without
 * the cross-activity launch killing the subject. Don't add it back here.
 */
@HiltAndroidTest
class OnboardingToHomeSmokeTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Before fun setUp() {
        hiltRule.inject()
        Intents.init()
    }

    @After fun tearDown() {
        Intents.release()
    }

    @Test
    fun firstRun_selectsFavorites_firesIntentToMainActivity() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val launchIntent = Intent(ctx, OnboardingActivity::class.java).apply {
            putExtra(OnboardingActivity.EXTRA_LAUNCH_MODE, /*INITIAL_SETUP*/ "INITIAL_SETUP")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<OnboardingActivity>(launchIntent).use {
            // ── ASSERT shell rendered ────────────────────────────────────────
            onView(withId(R.id.all_apps_recycler_view)).check(matches(isDisplayed()))
            onView(withId(R.id.done_button)).check(matches(isDisplayed()))

            // ── ACT: pick the first three items in the list ──────────────────
            // Index-based selection is intentional: package-name-based would
            // depend on the exact device's installed apps and be flaky.
            // Espresso's RecyclerViewActions throws a PerformException with
            // a clear message if the position doesn't exist, which is the
            // failure mode we want if the list isn't populated yet.
            repeat(3) { i ->
                onView(withId(R.id.all_apps_recycler_view))
                    .perform(RecyclerViewActions.actionOnItemAtPosition<androidx.recyclerview.widget.RecyclerView.ViewHolder>(i, click()))
            }

            // ── ACT: tap continue ────────────────────────────────────────────
            onView(withId(R.id.done_button)).perform(click())

            // ── ASSERT: intent was fired to MainActivity ─────────────────────
            Intents.intended(hasComponent(MainActivity::class.java.name))
        }
    }
}
