package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup + scroll-jank surface, deliberately measured under BOTH compilation
 * modes so the baseline-profile gain is falsifiable, not asserted:
 *  - [CompilationMode.None]    → cold JIT/interpreter path (no profile)
 *  - [CompilationMode.Partial] → ship-equivalent (baseline profile installed)
 * The delta between the two IS the value of the profile.
 *
 * ORDER MATTERS — [startupBaselineProfile] and [drawerScrollJank] use
 * `CompilationMode.Partial()`, whose default `BaselineProfileMode.Require`
 * THROWS if no baseline profile has been generated yet. Generate it first
 * (`./gradlew :app:generateBaselineProfile`, i.e. Part A of the setup) before
 * running those two. [startupNoCompilation] has no such precondition — run it
 * first to capture the profile-less baseline.
 *
 * Same device preconditions as [LaunchDispatchBenchmark]: connected, unlocked,
 * past onboarding, at least one home favorite. Local device only.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Cold start WITHOUT a profile — the profile-less baseline (today's state). */
    @Test
    fun startupNoCompilation() = measureStartup(CompilationMode.None())

    /** Cold start WITH the baseline profile — ship-equivalent. Requires Part A. */
    @Test
    fun startupBaselineProfile() = measureStartup(CompilationMode.Partial())

    private fun measureStartup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = mode,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    /**
     * Jank of the drawer fling, measured on the ship-equivalent build. Uses
     * `CompilationMode.Partial()` — requires a generated profile (Part A).
     */
    @Test
    fun drawerScrollJank() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        device.swipe(
            device.displayWidth / 2,
            (device.displayHeight * 0.85).toInt(),
            device.displayWidth / 2,
            (device.displayHeight * 0.30).toInt(),
            SWIPE_STEPS,
        )
        val list = device.wait(
            Until.findObject(By.res(TARGET_PACKAGE, DRAWER_LIST_ID)),
            FIND_TIMEOUT_MS,
        ) ?: error("Drawer list ($DRAWER_LIST_ID) not found — unlocked & past onboarding?")

        // Reserve edge margin so the fling is not swallowed by system gestures.
        list.setGestureMargin(device.displayWidth / 5)
        repeat(SCROLL_REPEATS) {
            list.fling(Direction.DOWN)
            list.fling(Direction.UP)
        }
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val ITERATIONS = 20 // startup is heavier than the dispatch gate; 20 keeps runtime sane
        const val FIND_TIMEOUT_MS = 5_000L
        const val SWIPE_STEPS = 8
        const val SCROLL_REPEATS = 3
        const val DRAWER_LIST_ID = "apps_recycler_view"
    }
}
