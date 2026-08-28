package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device measurement of the home-favorites first paint — the "favorites pop in
 * late" lag documented in `FavoriteNameSnapshot`'s KDoc.
 *
 * Favorites are pure text buttons, but the list can only be built once the full
 * PackageManager enumeration has produced every app's label, so on a cold start
 * the favorites frame is enumeration-gated (~150 ms on an A17 in the reference
 * Perfetto trace) while the clock/date/battery content is already on screen. This
 * benchmark turns that one-off Perfetto observation into a repeatable number:
 * [LaunchTrace.Names.FAVORITES_FIRST_PAINT][com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace.Names.FAVORITES_FIRST_PAINT]
 * (`favorites_first_paint`) is an async span opened at the cold-start Home view
 * creation and closed one frame after the first non-empty favorites paint;
 * [TraceSectionMetric] reads its duration.
 *
 * It is the A/B instrument for the `favorites-first-paint-snapshot` feature: run it
 * on this baseline branch for the enumeration-gated number, then again with the
 * provisional-snapshot feature applied to see the span collapse to ~0 (the
 * provisional list paints before enumeration finishes).
 *
 * ## Preconditions
 *  - **At least one home favorite configured** on the device — with none the span
 *    never closes (no favorite ever paints) and the run errors out. Same "needs a
 *    favorite" precondition the module header already states.
 *  - **Kolibri must NOT be the default home during the run.** The span fires once
 *    per process, so it needs a genuine COLD start each iteration; a launcher
 *    registered as HOME is always running and the system restarts it after a kill,
 *    tripping macrobenchmark's "must not be running prior to cold start" guard.
 *    Set another launcher as default for the run, then restore Kolibri — the exact
 *    operational step [StartupBenchmark] documents.
 *  - Connected, unlocked device, past onboarding. Local device only (not in the
 *    device-free GitHub CI).
 *
 * No threshold `verify…` gate yet: the sub-frame launch-dispatch gate exists
 * because PERF-RESULTS measured its baseline, and no favorites-first-paint baseline
 * has been captured. Once one exists, add a gate mirroring `verifyLaunchBenchmark`.
 */
@RunWith(AndroidJUnit4::class)
class FavoritesFirstPaintBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun favoritesFirstPaint() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            // One span per cold start; Mode.First reads that single first-paint slice.
            TraceSectionMetric(SECTION_FAVORITES_FIRST_PAINT, TraceSectionMetric.Mode.First),
        ),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(), // ship-equivalent (baseline profile installed)
        setupBlock = {
            if (!pastOnboarding) {
                // First iteration only: complete onboarding by SELECTING favorites in
                // the onboarding app list (and declining the ACRA consent that follows).
                // The selection persists to DataStore and survives the COLD kills below,
                // so every measured cold start renders Home with real favorites instead
                // of the empty-set fallback. Required because connectedBenchmarkAndroidTest
                // reinstalls fresh and wipes the favorites store — see
                // completeOnboardingWithFavorites.
                startActivityAndWait()
                completeOnboardingWithFavorites(TARGET_PACKAGE, SEED_FAVORITES_COUNT)
                pastOnboarding = true
            }
            pressHome()
        },
    ) {
        startActivityAndWait()
        // startActivityAndWait only waits for the first window frame; the favorites
        // pop in later (enumeration-gated), so poll the favorites RecyclerView until
        // it has a painted row. This holds the traced window open until the async
        // favorites_first_paint span ends a frame after the list commits (Mode.First
        // measures the span, not this wait).
        var painted = false
        var waited = 0L
        while (waited < PAINT_TIMEOUT_MS) {
            val favorites = device.findObject(By.res(TARGET_PACKAGE, FAVORITES_RV_ID))
            if (favorites != null && favorites.childCount > 0) {
                painted = true
                break
            }
            Thread.sleep(POLL_MS)
            waited += POLL_MS
        }
        if (!painted) {
            error(
                "Favorites never painted within ${PAINT_TIMEOUT_MS}ms — this benchmark " +
                    "requires at least one home favorite configured on the device.",
            )
        }
        device.waitForIdle()
    }

    // Onboarding cleared once (first setupBlock iteration); see
    // dismissFirstRunGatesIfPresent() in OnboardingSetup.kt. NOTE: the COLD measure
    // is only reachable with ANOTHER launcher set as default home — see class KDoc.
    private var pastOnboarding = false

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val ITERATIONS = 20 // cold start is heavy; 20 keeps the run's wall-clock sane
        // Enough favorites to exercise the favorites path (not the empty-set
        // fallback); a small count keeps the one-off UI seeding robust. The provisional
        // first-paint effect is visible with any non-empty favorite set.
        const val SEED_FAVORITES_COUNT = 3
        const val PAINT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 16L // ~one frame between "have favorites painted yet?" checks
        const val SECTION_FAVORITES_FIRST_PAINT = "favorites_first_paint" // LaunchTrace.Names.FAVORITES_FIRST_PAINT
        const val FAVORITES_RV_ID = "favoritesRecyclerView" // fragment_home.xml
    }
}
