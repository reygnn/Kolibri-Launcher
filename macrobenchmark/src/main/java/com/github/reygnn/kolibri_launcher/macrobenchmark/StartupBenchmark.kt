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
 * [StartupTimingMetric] under [StartupMode.COLD]. It emits two numbers:
 *  - `timeToInitialDisplayMs` (TTID) — process fork to the FIRST home frame.
 *    Always present.
 *  - `timeToFullDisplayMs` (TTFD) — process fork to "ready for user
 *    interaction". Present only because `HomeFragment` calls
 *    `Activity.reportFullyDrawn()` one frame after the favorites first paint
 *    (their enumeration-gated appearance is the true ready point — clock, date,
 *    battery and wallpaper are already on screen by TTID). TTID answers "when is
 *    something on screen", TTFD answers "when can the user act"; the TTFD−TTID
 *    gap is the favorites-enumeration tail. See `HomeFragment`'s
 *    `endFavoritesFirstPaintTraceOnNextDraw`, `LaunchTrace.Names.FAVORITES_FIRST_PAINT`
 *    and PERF-BENCHMARK-SETUP.md.
 *
 * `verifyStartupBenchmark` currently gates only the TTID median (580 ms). No
 * TTFD gate yet — no TTFD baseline has been captured on the A17 reference unit.
 * Once one exists, add a sibling gate mirroring the TTID one (same precedent the
 * favorites-first-paint benchmark documents for its own missing gate).
 *
 * A launcher registered as HOME is *always running*, so macrobenchmark's
 * cold-start guard ("must not be running prior to cold start") makes this
 * UNMEASURABLE while Kolibri is the default home. Run these with a DIFFERENT
 * launcher set as default (so Kolibri can be force-stopped and cold-started),
 * then restore Kolibri — an operational step, not something the test can do
 * itself. Cold start still matters in the real world: the post-boot / post-LMK
 * home render is exactly where the profile pays off.
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
        setupBlock = {
            if (!pastOnboarding) {
                // First iteration only: launch once to clear the first-run gates.
                // The tap writes onboarding-complete + consent to DataStore, which
                // persist across iterations; the COLD measure below kills this
                // process, so every measured cold start still starts from scratch
                // — and now renders Home instead of the onboarding gate.
                startActivityAndWait()
                dismissFirstRunGatesIfPresent(TARGET_PACKAGE)
                pastOnboarding = true
            }
            pressHome()
        },
    ) {
        startActivityAndWait()
    }

    // Onboarding cleared once (first setupBlock iteration); see
    // dismissFirstRunGatesIfPresent() in OnboardingSetup.kt. NOTE: cold start is
    // only measurable with ANOTHER launcher set as default home — a default-home
    // Kolibri is restarted by the system after kill ("must not be running prior
    // to cold start").
    private var pastOnboarding = false

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val ITERATIONS = 20 // cold start is heavier than the dispatch gate; 20 keeps runtime sane
    }
}
