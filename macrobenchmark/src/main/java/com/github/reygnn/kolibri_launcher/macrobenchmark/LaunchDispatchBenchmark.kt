package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device gate for the app-launch hot path (PERF-BENCHMARK-SETUP.md +
 * PERF-RESULTS.md).
 *
 * Measures an app-drawer launch on the `release` build and pins the launcher's
 * own three synchronous chunks plus the TAP->DISPATCH gap (the ViewModel+Channel
 * hop) via [LaunchDispatchGapMetric]. The gap is the value that could regress
 * invisibly — a `suspend` call sneaking into the dispatch would add a frame with
 * no diff-visible change — so the `verifyLaunchBenchmark` Gradle task fails the
 * build if its worst iteration crosses the sub-frame threshold.
 *
 * The drawer (not a home favorite) is the tap target on purpose: every launcher
 * past onboarding has apps in the drawer, so the benchmark needs no user-
 * specific favorite configured. Requires a connected, **unlocked** device with
 * the launcher **past onboarding**. Local device only; not run in the device-
 * free GitHub CI.
 */
@RunWith(AndroidJUnit4::class)
class LaunchDispatchBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun appDrawerLaunchDispatch() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric(SECTION_TAP, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(SECTION_DISPATCH, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(SECTION_START, TraceSectionMetric.Mode.Sum),
            LaunchDispatchGapMetric(),
        ),
        iterations = ITERATIONS,
        setupBlock = {
            // Re-establish the launcher home in the foreground before every
            // measured iteration (the previous iteration left a foreign app on
            // top, with the drawer closed).
            pressHome()
            startActivityAndWait()
        },
    ) {
        // One launch per iteration: open the drawer, tap the first app row. The
        // tap fires app_launch_tap -> (Channel hop) -> app_launch_dispatch ->
        // app_launch_startMainActivity synchronously; waitForIdle keeps all
        // three slices inside the traced window.
        device.swipe(
            device.displayWidth / 2,
            (device.displayHeight * 0.85).toInt(),
            device.displayWidth / 2,
            (device.displayHeight * 0.30).toInt(),
            SWIPE_STEPS,
        )
        val firstApp = device.wait(
            Until.findObject(By.res(TARGET_PACKAGE, APP_NAME_ID)),
            FIND_TIMEOUT_MS,
        ) ?: error(
            "App drawer has no app rows ($APP_NAME_ID) — is the launcher " +
                "unlocked and past onboarding?",
        )
        // click() taps the row's center; the clickable drawer row handles it.
        firstApp.click()
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val ITERATIONS = 40
        const val FIND_TIMEOUT_MS = 5_000L
        const val SWIPE_STEPS = 8 // ~fast fling to open the drawer
        const val APP_NAME_ID = "app_name" // drawer row label (AppDrawerAdapter)
        const val SECTION_TAP = "app_launch_tap"
        const val SECTION_DISPATCH = "app_launch_dispatch"
        const val SECTION_START = "app_launch_startMainActivity"
    }
}
