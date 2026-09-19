package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.app.Activity
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.launcher.testing.awaitUntil
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Why instrumented: both cases turn on the real Activity/Fragment lifecycle
 * that Robolectric cannot honestly reproduce here — the FragmentManager
 * *lazily* materialising a programmatically-added fragment into a
 * [androidx.fragment.app.FragmentContainerView], and the full
 * onSaveInstanceState → recreate → onRestoreInstanceState round-trip that
 * restores that fragment (with its view state) into the same container and
 * re-shows the overlay. The Robolectric companion
 * ([AppDrawerFragmentRobolectricTest]) attaches the fragment to a bare host
 * and never exercises `activity_main.xml`, the container, or a config change.
 *
 * Architecture context (post overlay-drawer + lazy migration): the drawer is
 * a visibility-toggled overlay in `activity_main.xml`, NOT a NavController
 * destination. Its `FragmentContainerView` carries no `android:name`, so the
 * fragment is not inflated at launch; `MainActivity.showDrawer()` adds it on
 * the first open (via `commitNow`) and reuses it thereafter. Opening goes
 * through the production path — `viewModel.onFlingUp()` emits
 * `UiEvent.ShowAppDrawer`, which the Activity turns into `showDrawer()`.
 *
 * What these tests assert:
 *  1. [drawerFragmentNotCreatedUntilFirstOpen]: the container is EMPTY right
 *     after launch (lazy — no fragment, no apps StateFlow, no view tree), and
 *     an AppDrawerFragment appears and renders only after the first onFlingUp.
 *  2. [openDrawerSurvivesRecreate]: with the drawer open, a config change
 *     (simulated by `recreate()`) re-shows the overlay WITHOUT another
 *     onFlingUp (STATE_DRAWER_OPEN → `showDrawer(animate = false)`), and the
 *     restored fragment keeps its typed search query (`resetContent = false`
 *     on the restore path, so the open-time reset is skipped).
 *
 * Why `recreate()` as the config-change proxy: it drives the exact
 * save/restore code path (onSaveInstanceState/onRestoreInstanceState) stably,
 * without depending on OEM rotation behaviour. INSTRUMENTED_TESTING_NOTES
 * only forbids `recreate()` AFTER the Activity has finish()'d itself; here the
 * Activity is alive (the drawer is open), so the call is valid.
 *
 * Why VISIBLE-region ids and the production event path (not the home swipe):
 * the swipe/gesture wiring is its own hazard surface (touch slop, velocity
 * tracker) covered by [AppDrawerSwipeDismissTest]; here we verify lifecycle
 * and restore, so we drive the ViewModel directly.
 */
@HiltAndroidTest
class AppDrawerLazyOverlayTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            // Otherwise MainActivity redirects to OnboardingActivity and never
            // reaches the drawer. Consent seeded to Denied to skip the dialog.
            settings.setOnboardingCompleted()
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)
        }
    }

    @Test
    fun drawerFragmentNotCreatedUntilFirstOpen() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        prewarmPackageManager()

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            // ── ASSERT (lazy): nothing in the container at launch. With the
            // static `android:name` removed, the fragment is only added on the
            // first showDrawer(), so its view tree and apps StateFlow are not
            // built during cold start.
            assertThat(drawerFragmentOrNull()).isNull()

            // ── ACT: first open through the production event path.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = currentResumedActivity<MainActivity>()
                    ?: error("MainActivity not RESUMED — cannot open drawer")
                activity.viewModel.onFlingUp()
            }

            // ── ASSERT: overlay attached and shown. Wrapped in awaitUntil
            // because ShowAppDrawer hops a Channel and showDrawer runs a short
            // slide-in; the first open also pays the lazy commitNow inflation.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "AppDrawer overlay never became visible after first onFlingUp()" },
            ) {
                try {
                    onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
                    onView(withId(R.id.app_drawer_root)).check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ── ASSERT: the fragment now exists and is our AppDrawerFragment
            // (created lazily by the first open, not at launch).
            assertThat(drawerFragmentOrNull()).isInstanceOf(AppDrawerFragment::class.java)
        }
    }

    @Test
    fun openDrawerSurvivesRecreate() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        prewarmPackageManager()

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            // ── Open through the production path, then wait until shown.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = currentResumedActivity<MainActivity>()
                    ?: error("MainActivity not RESUMED — cannot open drawer")
                activity.viewModel.onFlingUp()
            }
            awaitDrawerVisible("AppDrawer overlay never became visible after onFlingUp()")

            // ── Type a query so we can prove the restore path keeps content
            // (resetContent = false) rather than clearing it like a fresh open.
            onView(withId(R.id.search_edit_text)).perform(typeText(SEARCH_QUERY), closeSoftKeyboard())

            // ── ACT: config change. recreate() runs the real
            // onSaveInstanceState → onRestoreInstanceState cycle; the drawer
            // was open, so STATE_DRAWER_OPEN is persisted.
            scenario.recreate()

            // ── ASSERT (primary): the overlay is shown again on its own —
            // onRestoreInstanceState re-ran showDrawer(animate = false). No
            // second onFlingUp was issued, so a still-visible drawer proves
            // the restore path, not a fresh open.
            awaitDrawerVisible("AppDrawer overlay was not restored after recreate()")

            // ── ASSERT (secondary): the typed query survived. The fragment is
            // restored with its view state, and the no-animate restore uses
            // resetContent = false, so onDrawerShown does NOT clear the field.
            onView(withId(R.id.search_edit_text)).check(matches(withText(SEARCH_QUERY)))
        }
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * PackageManager pre-warm — same idiom as [AppDrawerSwipeDismissTest]:
     * cache the launcher list before MainActivity's drawerApps pipeline starts
     * so the cold first-launch doesn't stretch past Espresso's RootViewPicker
     * patience.
     */
    private fun prewarmPackageManager() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)
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

    /**
     * The fragment currently hosted by the overlay container, or null if it has
     * not been added yet. FragmentManager access must happen on the main
     * thread, hence runOnMainSync.
     */
    private fun drawerFragmentOrNull(): Fragment? {
        var fragment: Fragment? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = currentResumedActivity<MainActivity>()
                ?: error("MainActivity not RESUMED — cannot inspect FragmentManager")
            fragment = activity.supportFragmentManager.findFragmentById(R.id.drawer_container)
        }
        return fragment
    }

    private inline fun <reified T : Activity> currentResumedActivity(): T? {
        val resumed = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
        return resumed.firstOrNull { it is T } as? T
    }

    private companion object {
        // Short, deterministic query; content is irrelevant, survival is.
        const val SEARCH_QUERY = "ab"
    }
}
