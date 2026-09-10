package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drawer-scroll jank under BOTH compilation modes, so the baseline-profile gain
 * is falsifiable rather than asserted:
 *  - [CompilationMode.None]    → no profile (cold JIT/interpreter path)
 *  - [CompilationMode.Partial] → ship-equivalent (baseline profile installed)
 * The None−Partial delta on the drawer fling IS the profile's value for an
 * always-resident launcher — its most relevant effect, since the drawer bind/
 * inflate of the app rows is what the user actually scrolls.
 *
 * Split out of [StartupBenchmark] on purpose (2026-08-25): the two surfaces have
 * incompatible run constraints and, run together, the jank fling churns the
 * device and inflates the cold-start tail (see PERF-BENCHMARK-SETUP.md,
 * "Methodology"). Keeping them in separate classes means
 * `...class=StartupBenchmark` measures cold start with NO contention from here,
 * and this class is run on its own when jank is what you want.
 *
 * Unlike cold start, jank needs **no** cold start, so it runs happily with
 * Kolibri as the default home. Same device preconditions otherwise: connected,
 * unlocked, past onboarding. Local device only; not run in the device-free CI.
 */
@RunWith(AndroidJUnit4::class)
class DrawerScrollJankBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

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
            // First iteration: clear onboarding + consent so the drawer opens on
            // Home. The flags persist across iterations (no app-data clear).
            if (!pastOnboarding) { dismissFirstRunGatesIfPresent(TARGET_PACKAGE); pastOnboarding = true }
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

    // Onboarding cleared once per test method (first setupBlock iteration); see
    // dismissFirstRunGatesIfPresent() in OnboardingSetup.kt.
    private var pastOnboarding = false

    /** Fling the drawer list, re-locating it each call to dodge StaleObjectException. */
    private fun MacrobenchmarkScope.flingDrawer(direction: Direction) {
        val list = device.findObject(By.res(TARGET_PACKAGE, DRAWER_LIST_ID)) ?: return
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(direction)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val ITERATIONS = 20 // jank is heavier than the dispatch gate; 20 keeps runtime sane
        const val FIND_TIMEOUT_MS = 5_000L
        const val SWIPE_STEPS = 8
        const val SCROLL_REPEATS = 3
        const val DRAWER_LIST_ID = "apps_recycler_view"
    }
}
