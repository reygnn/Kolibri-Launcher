package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
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
 * Two gates read this class: `verifyStartupBenchmark` (TTID median, 580 ms) and
 * `verifyStartupFullyDrawnBenchmark` (TTFD median, 940 ms) — the latter added once
 * a TTFD baseline was captured on the A17 (PERF-RESULTS.md §3b/§4). Both are fed by
 * one `connectedBenchmarkAndroidTest` run.
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
                // First iteration only: onboard AND seed favorites. TTFD is emitted
                // only when reportFullyDrawn() fires, and that fires solely on the
                // NON-EMPTY favorites paint (HomeFragment.renderFavorites) — an
                // empty-set Home would report TTID but never TTFD. Seeding here (vs
                // the empty dismissFirstRunGatesIfPresent) makes every measured cold
                // start render the real favorites path. The selection persists to
                // DataStore and survives the COLD kills below, so seeding once is
                // enough for all iterations.
                //
                // ...IfPresent (tolerant): this class has TWO @Test methods (None +
                // Partial) sharing ONE install. The first to run meets onboarding and
                // seeds; the second finds the favorites already persisted and skips
                // (the strict completeOnboardingWithFavorites would error on the
                // absent onboarding list). pastOnboarding is per-instance, so both
                // methods enter this block once.
                startActivityAndWait()
                completeOnboardingWithFavoritesIfPresent(TARGET_PACKAGE, SEED_FAVORITES_COUNT)
                pastOnboarding = true
            }
            pressHome()
        },
    ) {
        startActivityAndWait() // returns at the FIRST frame — the TTID point.
        // reportFullyDrawn() fires later, one frame after the enumeration-gated
        // favorites paint. startActivityAndWait does not wait for it, so hold the
        // traced iteration open until the favorites RecyclerView has a painted row —
        // otherwise StartupTimingMetric can stop the trace before the TTFD marker and
        // timeToFullDisplayMs comes back intermittently absent. Mirrors the poll in
        // FavoritesFirstPaintBenchmark (TTID is unaffected — it is the first frame,
        // already captured above).
        var waited = 0L
        while (waited < PAINT_TIMEOUT_MS) {
            val favorites = device.findObject(By.res(TARGET_PACKAGE, FAVORITES_RV_ID))
            if (favorites != null && favorites.childCount > 0) break
            Thread.sleep(POLL_MS)
            waited += POLL_MS
        }
        device.waitForIdle()
    }

    // Onboarding + favorites seeded once (first setupBlock iteration); see
    // completeOnboardingWithFavorites() in FavoriteSeeding.kt. NOTE: cold start is
    // only measurable with ANOTHER launcher set as default home — a default-home
    // Kolibri is restarted by the system after kill ("must not be running prior
    // to cold start").
    private var pastOnboarding = false

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val ITERATIONS = 20 // cold start is heavier than the dispatch gate; 20 keeps runtime sane
        // A small non-empty favorite set so the TTFD ready point (favorites paint)
        // is reached; matches FavoritesFirstPaintBenchmark's seed count.
        const val SEED_FAVORITES_COUNT = 3
        const val PAINT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 16L // ~one frame between "have favorites painted yet?" checks
        const val FAVORITES_RV_ID = "favoritesRecyclerView" // fragment_home.xml
    }
}
