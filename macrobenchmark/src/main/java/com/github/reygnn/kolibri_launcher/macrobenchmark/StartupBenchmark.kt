package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start TTID under BOTH compilation modes, so the baseline-profile gain is
 * falsifiable rather than asserted:
 *  - [CompilationMode.None]    → no profile (cold JIT/interpreter path)
 *  - [CompilationMode.Partial] → ship-equivalent (baseline profile installed)
 * The None−Partial delta IS the value of the profile. Feeds the
 * `verifyStartupBenchmark` gate (reads the `startupBaselineProfile` median).
 *
 * [StartupTimingMetric] under [StartupMode.COLD]. A launcher registered as HOME
 * is *always running*, so macrobenchmark's cold-start guard ("must not be running
 * prior to cold start") makes this UNMEASURABLE while Kolibri is the default
 * home. Run these with a DIFFERENT launcher set as default (so Kolibri can be
 * force-stopped and cold-started), then restore Kolibri — an operational step,
 * not something the test can do itself. Cold start still matters in the real
 * world: the post-boot / post-LMK home render is exactly where the profile pays
 * off.
 *
 * Drawer-scroll jank (the profile's other surface) lives in the separate
 * [DrawerScrollJankBenchmark] — split out 2026-08-25 so its fling can't churn the
 * device and inflate the cold-start tail (see PERF-BENCHMARK-SETUP.md,
 * "Methodology"). Run this class on its own for a contention-free cold-start
 * number.
 *
 * Same device preconditions as [LaunchDispatchBenchmark]: connected, unlocked,
 * past onboarding. Local device only.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

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
        const val ITERATIONS = 20 // cold start is heavier than the dispatch gate; 20 keeps runtime sane
    }
}
