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
 * Regression backstop for AUDIT.md §9.11 (Real-Device-Stress).
 *
 * The §9.11 manual run on `Virtual_Pixel_9a` exercised ~1750 random
 * input events across cold-launch, kill+relaunch, rotation, touch-spam,
 * and three monkey passes (200 / 1000 / default-HOME 500). All passed
 * with zero `FATAL`, zero `SILENT_ERROR`, and zero `silentDeath`. This
 * test pins a deterministic subset of that result so a future refactor
 * that tears out a safety net (multi-layer crash handlers,
 * fragmentExceptionHandler System.err fallback, four-category-frame
 * catches in MainActivity, etc.) trips the build instead of escaping
 * silently.
 *
 * **Why instrumented (Rule 8):**
 *  - Monkey routes events through the **real** Android input dispatcher
 *    (`InputManagerService` → window dispatcher → `View.onTouchEvent`).
 *    Robolectric models input differently and would not exercise the
 *    actual dispatch path that the bombensicher review (§9) audits.
 *  - With Kolibri set as default HOME, every monkey HOME-key press is
 *    routed by `ActivityTaskManager` back to `MainActivity` — exercising
 *    the singleTask launchMode resolution that `MainActivityHomeIntentTest`
 *    pins for the two-press case, but at higher event volume and with
 *    interleaved touches.
 *  - The fixed seed (`-s 12345`) makes the event sequence reproducible
 *    across runs / Android versions, so the test is a regression check
 *    rather than a flaky monkey ride.
 *
 * **Launch path — `am start`, not `ActivityScenario.launch`:**
 * MainActivity is `@HiltAndroidEntryPoint` and queries the application's
 * Hilt component during `onCreate` to resolve `@HiltViewModel
 * LauncherViewModel`. ActivityScenario's direct activity construction
 * race-conditions with HiltTestApplication's component bring-up and
 * fails with "The component was not created". The `am start` route goes
 * through ActivityTaskManager exactly like a real HOME press would, and
 * that path is what HiltTestApplication is wired for. Same approach as
 * `MainActivityHomeIntentTest`.
 *
 * **Coverage limit (read before extending):**
 *  - Monkey is a smoke-stress test, not a functional one. It cannot
 *    drive Wallpaper-Edit-Mode (multi-touch pinch+zoom), trigger
 *    a synthetic 8s main-thread block (would need a debug menu hook),
 *    or simulate OEM-specific quirks (Samsung One UI, MIUI, etc.).
 *    Those scenarios stay in §9.10 as manual-or-telemetry items.
 *  - Event budget is intentionally modest (200 events, ~6s with the
 *    30ms throttle). The §9.11 manual run did 1750 events for a
 *    higher-confidence one-time signal; instrumented tests pay
 *    ~40s/run in suite cost regardless of test duration, so 200
 *    events is the cost-balanced regression check.
 *
 * **Failure-mode signals checked:**
 *  1. Monkey's own `// CRASH:` / `// NOT RESPONDING:` markers in stdout.
 *  2. `Events injected: 200` confirms monkey reached the planned count
 *     (a process death mid-run truncates).
 *  3. Logcat scan for `AndroidRuntime:E` (FATAL EXCEPTION header) and
 *     `SILENT_ERROR:*` (TimberWrapper's tag — non-throwing silent-error
 *     paths that monkey's own crash detector wouldn't catch).
 */
@HiltAndroidTest
class MainActivityMonkeyStressTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            // Same gates as MainActivityHomeIntentTest: skip onboarding +
            // crash-consent dialog so MainActivity reaches the home/drawer
            // surface that monkey is meant to exercise.
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
        // Same teardown pattern as MainActivityHomeIntentTest: never leak
        // a default-launcher role into the next test on this device.
        try { DefaultHomeRoleHelper.clearSelfAsDefault() } catch (_: Throwable) {}
    }

    @Test
    fun launcher_survives_200_random_monkey_events_without_crash() {
        // Bring MainActivity to foreground via the production HOME path
        // (ActivityTaskManager). See class KDoc for why we don't use
        // ActivityScenario here.
        ShellCommand.run(
            "am start " +
                "-a ${Intent.ACTION_MAIN} " +
                "-c ${Intent.CATEGORY_HOME} " +
                "-f ${Intent.FLAG_ACTIVITY_NEW_TASK}"
        )
        awaitUntil(
            timeoutMs = 10_000,
            describe = { "MainActivity never reached RESUMED before monkey run" },
        ) { resumedMainActivities().isNotEmpty() }

        // Clear logcat right before monkey so the post-run scan only
        // sees events from this test. `logcat -c` is per-buffer
        // best-effort; if it fails, the AndroidRuntime/SILENT_ERROR
        // filter narrows the result anyway.
        ShellCommand.run("logcat -c")

        // monkey -p limits app launches; --throttle slows down
        // event delivery (lets the app actually process between
        // events instead of firehose); -s makes the sequence
        // deterministic; -v emits the per-event log to stdout
        // including any crash markers.
        val monkeyOutput = ShellCommand.run(
            "monkey -p $PACKAGE --throttle 30 -s 12345 -v 200"
        )

        // Primary signal: monkey's own crash markers.
        assertThat(monkeyOutput).doesNotContain("// CRASH:")
        assertThat(monkeyOutput).doesNotContain("// NOT RESPONDING:")
        assertThat(monkeyOutput).contains("Events injected: 200")

        // Belt-and-suspenders: silentError in DEBUG throws and would
        // surface via AndroidRuntime FATAL, but a non-throwing
        // silent-error path (e.g. preventCrashForTesting flag set,
        // or a future RELEASE-only path) wouldn't. Scan logcat for
        // both signals.
        val crashLog = ShellCommand.run(
            "logcat -d -s AndroidRuntime:E $SILENT_LOG_TAG:*"
        )
        assertThat(crashLog).doesNotContain("FATAL EXCEPTION")
        assertThat(crashLog).doesNotContain("RuntimeException")
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

    private companion object {
        private const val PACKAGE = "com.github.reygnn.kolibri_launcher"

        // Mirrors TimberWrapper.SILENT_LOG_TAG (private to that file by
        // intent — duplicated here so the test source set doesn't widen
        // the production visibility). Keep these two strings in sync.
        private const val SILENT_LOG_TAG = "SILENT_ERROR"
    }
}
