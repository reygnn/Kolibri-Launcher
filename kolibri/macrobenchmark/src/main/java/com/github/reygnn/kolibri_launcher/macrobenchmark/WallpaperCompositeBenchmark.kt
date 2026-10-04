package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A/B for the [WallpaperCompositeCache]: the flattened multi-layer composite that
 * makes a drawer→home view re-creation a ~0 ms one-texture re-attach instead of an
 * O(N) software decode+compose.
 *
 * The two arms differ ONLY in whether the cache can serve the composite:
 *
 *  - **Cache MISS ("ohne Cache")** — [compositeFlattenOnRotate]. A rotation versions
 *    the resolution-keyed composite key, so `onDisplayConfigChanged` → `refillCache`
 *    finds a miss and re-flattens for the new resolution. The `wallpaper_warm` /
 *    `wallpaper_flatten` async trace sections (WallpaperDelegate.warmComposite) capture
 *    that cost. This is the work the cache elides — and on a 4-layer wallpaper it is
 *    exactly where a weak CPU sweats.
 *
 *  - **Cache HIT ("mit Cache")** — [compositeReattachWarm]. The process stays warm and
 *    the resolution is unchanged, so drawer→home re-attaches the cached composite with
 *    NO re-flatten (`wallpaper_flatten` never fires). [FrameTimingMetric] measures that
 *    transition — the cache's payoff.
 *
 * Each run restores the fixed TWO-layer test wallpaper itself in the UNMEASURED setup
 * ([restoreBenchmarkWallpaperIfNeeded]) — a single-layer state flattens no composite, so
 * [compositeFlattenOnRotate]'s trace section would never appear. Driven as its own
 * `am instrument` invocation after a `pm clear` (fresh process → the restore latch resets,
 * fresh onboarding → the restore flow appears). No cold start, so Kolibri may stay the
 * default home. Local device only.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperCompositeBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * Cache MISS: force a re-flatten by rotating (new resolution key → miss) and measure
     * the composite warm/flatten cost. Two rotations per iteration = two cold re-flattens.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun compositeFlattenOnRotate() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric(SECTION_WARM, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(SECTION_FLATTEN, TraceSectionMetric.Mode.Sum),
        ),
        iterations = ITERATIONS,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            // Establish the fixed two-layer wallpaper (no-op after the first iteration's
            // restore; a fresh process + pm clear per run re-arms it). Handles onboarding
            // restore AND the consent decline internally.
            restoreBenchmarkWallpaperIfNeeded()
            pressHome()
            startActivityAndWait()
        },
    ) {
        // Landscape: new resolution key → cache miss → re-flatten for landscape.
        device.setOrientationLandscape()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, WALLPAPER_VIEW_ID)), FIND_TIMEOUT_MS)
        // The refill warms on a background dispatcher; hold the traced window open until the
        // async flatten has finished (Sum measures the section, not this wait).
        Thread.sleep(WARM_SETTLE_MS)

        // Back to portrait: another new-key miss → another re-flatten.
        device.setOrientationNatural()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, WALLPAPER_VIEW_ID)), FIND_TIMEOUT_MS)
        Thread.sleep(WARM_SETTLE_MS)
        device.waitForIdle()
    }

    /**
     * Cache HIT: warm process, unchanged resolution — drawer→home re-attaches the cached
     * composite with no re-flatten. Frame timing of that transition is the cache's benefit.
     */
    @Test
    fun compositeReattachWarm() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            // Same two-layer restore as the miss arm, so a cache hit has a real composite
            // to re-attach (no-op after the first iteration).
            restoreBenchmarkWallpaperIfNeeded()
            pressHome()
            startActivityAndWait()
        },
    ) {
        // Open the drawer …
        device.swipe(
            device.displayWidth / 2,
            (device.displayHeight * 0.85).toInt(),
            device.displayWidth / 2,
            (device.displayHeight * 0.30).toInt(),
            SWIPE_STEPS,
        )
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, DRAWER_LIST_ID)), FIND_TIMEOUT_MS)
        // … then back to home: HomeFragment is re-created and re-attaches the cached
        // composite (compositeCache.get hit). This is the exact path the cache optimizes.
        device.pressBack()
        device.wait(Until.hasObject(By.res(TARGET_PACKAGE, WALLPAPER_VIEW_ID)), FIND_TIMEOUT_MS)
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val ITERATIONS = 10 // F4 fixes 10, aligned with WallpaperPaintBenchmark
        const val FIND_TIMEOUT_MS = 5_000L
        // A 4-layer software flatten + HARDWARE copy is tens–hundreds of ms; 1.5 s is generous
        // headroom so the async warm always lands inside the traced window.
        const val WARM_SETTLE_MS = 1_500L
        const val SWIPE_STEPS = 8
        const val SECTION_WARM = "wallpaper_warm"     // LaunchTrace.Names.WALLPAPER_WARM
        const val SECTION_FLATTEN = "wallpaper_flatten" // LaunchTrace.Names.WALLPAPER_FLATTEN
        const val DRAWER_LIST_ID = "apps_recycler_view"
        // The wallpaper view lives in activity_main.xml since §25 (the old fragment id
        // "wallpaperView" no longer exists, so the waits silently timed out; fixed in 3a-8a).
        const val WALLPAPER_VIEW_ID = "wallpaper_view"
    }
}
