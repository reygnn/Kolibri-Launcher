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
 * Quantifies the baseline-profile gain under BOTH compilation modes so it is
 * falsifiable, not asserted:
 *  - [CompilationMode.None]    → no profile (cold JIT/interpreter path)
 *  - [CompilationMode.Partial] → ship-equivalent (baseline profile installed)
 * The None−Partial delta IS the value of the profile.
 *
 * Two surfaces, because a launcher is unusual:
 *
 *  1. **Drawer-scroll jank** ([drawerScrollJankNoCompilation] /
 *     [drawerScrollJankBaselineProfile]) — [FrameTimingMetric] over the drawer
 *     fling (bind/inflate of the app rows). This needs no cold start, so it runs
 *     with Kolibri as the default home. It is the profile's most relevant effect
 *     for an always-resident launcher.
 *
 *  2. **Cold-start TTID** ([startupNoCompilation] / [startupBaselineProfile]) —
 *     [StartupTimingMetric] under [StartupMode.COLD]. A launcher registered as
 *     HOME is *always running*, so macrobenchmark's cold-start guard ("must not
 *     be running prior to cold start") makes this UNMEASURABLE while Kolibri is
 *     the default home. Run these with a DIFFERENT launcher set as default (so
 *     Kolibri can be force-stopped and cold-started), then restore Kolibri —
 *     `macrobenchmark/README`-style operational step, not something the test can
 *     do itself. Cold start still matters in the real world: the post-boot /
 *     post-LMK home render is exactly where the profile pays off.
 *
 * Same device preconditions as [LaunchDispatchBenchmark]: connected, unlocked,
 * past onboarding. Local device only.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    // --- Drawer-scroll jank: works with Kolibri as default home ---------------

    @Test
    fun drawerScrollJankNoCompilation() = measureDrawerJank(CompilationMode.None())

    @Test
    fun drawerScrollJankBaselineProfile() = measureDrawerJank(CompilationMode.Partial())

    private fun measureDrawerJank(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        compilationMode = mode,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        // Open the drawer with the same gesture the latency benchmark uses.
        device.swipe(
            device.displayWidth / 2,
            (device.displayHeight * 0.85).toInt(),
            device.displayWidth / 2,
            (device.displayHeight * 0.30).toInt(),
            SWIPE_STEPS,
        )
        device.wait(
            Until.hasObject(By.res(TARGET_PACKAGE, DRAWER_LIST_ID)),
            FIND_TIMEOUT_MS,
        ) || error("Drawer list ($DRAWER_LIST_ID) not found — unlocked & past onboarding?")

        // Re-fetch the RecyclerView UiObject2 before EACH fling: a fling rebinds
        // the list, which invalidates a previously captured handle and throws
        // StaleObjectException on the next call. Finding it fresh each time is
        // the fix for that flake.
        repeat(SCROLL_REPEATS) {
            flingDrawer(Direction.DOWN)
            flingDrawer(Direction.UP)
        }
        device.waitForIdle()
    }

    /** Fling the drawer list, re-locating it each call to dodge StaleObjectException. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.flingDrawer(direction: Direction) {
        val list = device.findObject(By.res(TARGET_PACKAGE, DRAWER_LIST_ID)) ?: return
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(direction)
    }

    // --- Cold-start TTID: REQUIRES Kolibri NOT be the default home ------------

    /** Cold start WITHOUT a profile. See class KDoc: run with another default home. */
    @Test
    fun startupNoCompilation() = measureStartup(CompilationMode.None())

    /** Cold start WITH the baseline profile. See class KDoc: run with another default home. */
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

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val ITERATIONS = 20 // startup/jank are heavier than the dispatch gate; 20 keeps runtime sane
        const val FIND_TIMEOUT_MS = 5_000L
        const val SWIPE_STEPS = 8
        const val SCROLL_REPEATS = 3
        const val DRAWER_LIST_ID = "apps_recycler_view"
    }
}
