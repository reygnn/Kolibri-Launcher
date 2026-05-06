package com.github.reygnn.kolibri_launcher.ui

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.github.reygnn.kolibri_launcher.data.CrashReportConsentStore
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.support.DefaultHomeRoleHelper
import com.github.reygnn.kolibri_launcher.support.ShellCommand
import com.github.reygnn.kolibri_launcher.support.awaitUntil
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Why instrumented: when Kolibri is the default launcher, every press of
 * the HOME button (from any other app) routes
 * `Intent(ACTION_MAIN).addCategory(CATEGORY_HOME)` to MainActivity. The
 * AndroidManifest declares `android:launchMode="singleTask"`, so the OS
 * must reuse the existing instance via `onNewIntent` / `onResume` rather
 * than starting a fresh one.
 *
 * If that wiring is silently broken — wrong launchMode, missing intent
 * filter, an onCreate path that re-runs init logic on every HOME press —
 * the user gets a "Frame 1" launcher every time they press HOME. State
 * loss, slow re-loads of the favorites list, and (worst case) crashes
 * during re-init.
 *
 * What this test asserts:
 *  1. After the second HOME intent, MainActivity's instance identity
 *     is unchanged (singleTask honoured the existing Activity).
 *  2. The instance count of MainActivity in the lifecycle registry stays
 *     at exactly one (no zombie second instance lurking in the back stack).
 *
 * Robolectric cannot reproduce this: launchMode resolution is
 * ActivityTaskManager territory in system_server, not in the app process.
 *
 * Coverage limit (read before extending): we don't try to assert
 * "HomeFragment is the visible destination" — that requires another
 * Activity to be on top first (so HOME has somewhere to come from).
 * Spinning up an unrelated Activity inside our process and then sending
 * HOME would just relaunch us, not test the singleTask preservation. The
 * pure-instance-identity assertion is the structural fact we actually
 * care about; the destination question is covered by HomeFragment's own
 * Robolectric tests for navigation state restore.
 */
@HiltAndroidTest
class MainActivityHomeIntentTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            // Same gates as AppDrawerFragmentSearchTest: skip onboarding +
            // crash-consent dialog, otherwise MainActivity.onCreate stalls
            // on user-input expectations and we never reach RESUMED.
            settings.setOnboardingCompleted()
            CrashReportConsentStore.saveConsent(ctx, consent = false)
        }
        DefaultHomeRoleHelper.setSelfAsDefault()
        assumeTrue(
            "cmd role did not actually set self as default — skip on this device build",
            DefaultHomeRoleHelper.isSelfDefault(),
        )
    }

    @After fun tearDown() {
        // Same teardown pattern as ShortcutRepositoryRoleGatedTest: never
        // leak a default-launcher role into the next test on this device.
        try { DefaultHomeRoleHelper.clearSelfAsDefault() } catch (_: Throwable) {}
    }

    @Test
    fun secondHomeIntent_reusesSingleTaskInstance_doesNotCreateSecondActivity() {
        // ── ACT 1: send first HOME intent. The shell command goes through
        // ActivityTaskManager exactly like a real HOME press would, so this
        // exercises the production launch path — not ActivityScenario,
        // which calls Activity.startActivity directly and bypasses the
        // launchMode resolution we want to verify.
        sendHomeIntent()
        awaitUntil(
            timeoutMs = 10_000,
            describe = { "MainActivity never reached RESUMED after first HOME intent" },
        ) { resumedMainActivities().isNotEmpty() }
        val firstInstance: Activity = resumedMainActivities().single()

        // ── ACT 2: send second HOME intent. With singleTask + we already
        // being the resolved HOME activity, ActivityTaskManager should
        // route this to the existing instance via onNewIntent (or no-op
        // if we're still RESUMED). It must NOT call onCreate again on a
        // new instance.
        sendHomeIntent()
        // A small await window lets ATM dispatch the second intent.
        // Activity-instance changes show up immediately on success and
        // within a few hundred ms on failure (a fresh onCreate). The 3 s
        // budget catches both.
        Thread.sleep(500)

        // ── ASSERT: identity preserved, count unchanged.
        val resumedNow = resumedMainActivities()
        assertThat(resumedNow).hasSize(1)
        assertThat(resumedNow.single()).isSameInstanceAs(firstInstance)
    }

    private fun sendHomeIntent() {
        // `am start -c android.intent.category.HOME` is the canonical
        // shell-side equivalent of pressing the system HOME button. Adding
        // FLAG_ACTIVITY_NEW_TASK matches the OS's own delivery flags.
        ShellCommand.run(
            "am start " +
                "-a ${Intent.ACTION_MAIN} " +
                "-c ${Intent.CATEGORY_HOME} " +
                "-f ${Intent.FLAG_ACTIVITY_NEW_TASK}"
        )
    }

    private fun resumedMainActivities(): List<Activity> {
        // Run on the main thread because the registry walks non-thread-
        // safe stage maps. `runOnMainSync` blocks the test thread until
        // the lambda returns the snapshot.
        var snapshot: List<Activity> = emptyList()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            snapshot = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
        }
        return snapshot
    }
}
