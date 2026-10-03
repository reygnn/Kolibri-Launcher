package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The SPEC_NYX_REWRITE F4 measurement for 3a-8 (the composite path): time to the first drawn
 * wallpaper on a cold start, and after a change. Measured BEFORE 3a-8 on the commit with 3a-7
 * (after this measurement patch, 3a-8a) and AFTER 3a-8 with this class unchanged, on the A17.
 *
 * Method (fixed before measuring, Senior 03.10.):
 *  - Benchmark build only (release-derived, not debuggable, R8), like every class here.
 *  - The sections come from `LaunchTrace` (`wallpaper_first_paint`, `wallpaper_change_paint`),
 *    after the pattern of `favorites_first_paint`; `TraceSectionMetric` measures the section,
 *    not the waits around it.
 *  - The change needs no system picker: in the editor swap two layers, then save.
 *  - Every iteration measures the same thing: the starting state is restored after each
 *    iteration in the UNMEASURED setup block. With the fixed TWO-layer test wallpaper a swap
 *    toggles the order, so the setup swaps back and saves, then the measured block swaps again.
 *  - The test wallpaper is fixed and comes from one backup ([BENCHMARK_BACKUP_FILE], restored
 *    through onboarding on the fresh install; see [restoreBenchmarkWallpaperIfNeeded]).
 *  - 10 iterations each; compared are the medians — one more than 10 % worse afterwards counts
 *    as a regression. Min and max go into the spec too.
 *
 * Preconditions on the device: the backup pushed to `/sdcard/Download/` (see the spec), unlocked,
 * USB. The cold-start test additionally needs ANOTHER launcher as default home (the default-home
 * caveat in PERF-BENCHMARK-SETUP.md); the change test runs with Kolibri as default home.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperPaintBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun wallpaperFirstPaintColdStart() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(TraceSectionMetric(SECTION_FIRST_PAINT, TraceSectionMetric.Mode.First)),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(), // ship-equivalent (baseline profile installed)
        setupBlock = {
            restoreBenchmarkWallpaperIfNeeded()
            pressHome()
        },
    ) {
        startActivityAndWait()
        // Hold the traced window open until the async first-paint span has ended.
        awaitWallpaperView()
        Thread.sleep(PAINT_SETTLE_MS)
        device.waitForIdle()
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun wallpaperChangePaint() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(TraceSectionMetric(SECTION_CHANGE_PAINT, TraceSectionMetric.Mode.First)),
        iterations = ITERATIONS,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            restoreBenchmarkWallpaperIfNeeded()
            pressHome()
            startActivityAndWait()
            awaitWallpaperView()
            // Restore the starting order (unmeasured): swap back and save, then let the warm and
            // the composite attach settle so the measured block starts from a quiet state.
            if (swapped) {
                swapLayersAndSave()
                swapped = false
                Thread.sleep(PAINT_SETTLE_MS)
            }
            device.waitForIdle()
        },
    ) {
        swapLayersAndSave()
        swapped = true
        Thread.sleep(PAINT_SETTLE_MS)
        device.waitForIdle()
    }

    // ---- UI steps ----

    private fun MacrobenchmarkScope.awaitWallpaperView() {
        check(device.wait(Until.hasObject(By.res(TARGET_PACKAGE, WALLPAPER_VIEW_ID)), FIND_TIMEOUT_MS)) {
            "Wallpaper view not found — is Kolibri on screen?"
        }
    }

    /** Opens the editor (long press → "Edit wallpaper"), swaps two layers, saves. */
    private fun MacrobenchmarkScope.swapLayersAndSave() {
        device.swipe(device.displayWidth / 2, (device.displayHeight * LONG_PRESS_Y).toInt(),
            device.displayWidth / 2, (device.displayHeight * LONG_PRESS_Y).toInt(), LONG_PRESS_STEPS)
        val edit = device.wait(Until.findObject(By.text(EDIT_WALLPAPER_LABEL)), FIND_TIMEOUT_MS)
            ?: error("Customization menu without \"Edit wallpaper\" — long press missed or the label changed")
        edit.click()
        tap(FAB_OPEN_COMMANDS_ID)
        // The active layer moves up if it can, else down — with two layers one of them always works.
        val up = device.wait(Until.findObject(By.res(TARGET_PACKAGE, BTN_LAYER_UP_ID)), FIND_TIMEOUT_MS)
            ?: error("Commands panel did not open")
        if (up.isEnabled) up.click() else tap(BTN_LAYER_DOWN_ID)
        tap(BTN_CLOSE_COMMANDS_ID)
        tap(FAB_SAVE_ID)
        device.wait(Until.gone(By.res(TARGET_PACKAGE, FAB_SAVE_ID)), FIND_TIMEOUT_MS)
    }

    private fun MacrobenchmarkScope.tap(id: String) {
        (device.wait(Until.findObject(By.res(TARGET_PACKAGE, id)), FIND_TIMEOUT_MS) ?: error("$id not found")).click()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val ITERATIONS = 10 // fixed before measuring (F4)
        const val FIND_TIMEOUT_MS = 5_000L
        // A 2-layer flatten + HARDWARE copy is well under a second on the A17; 2 s keeps the async
        // span (and, after a change, the composite warm and attach) inside the traced window.
        const val PAINT_SETTLE_MS = 2_000L
        const val SECTION_FIRST_PAINT = "wallpaper_first_paint"   // LaunchTrace.Names.WALLPAPER_FIRST_PAINT
        const val SECTION_CHANGE_PAINT = "wallpaper_change_paint" // LaunchTrace.Names.WALLPAPER_CHANGE_PAINT
        const val WALLPAPER_VIEW_ID = "wallpaper_view"            // activity_main.xml
        const val FAB_OPEN_COMMANDS_ID = "fabOpenCommands"         // view_speed_dial_fab_cluster.xml
        const val FAB_SAVE_ID = "fabSave"
        const val BTN_LAYER_UP_ID = "btnLayerUp"                   // view_commands_panel.xml
        const val BTN_LAYER_DOWN_ID = "btnLayerDown"
        const val BTN_CLOSE_COMMANDS_ID = "btnCloseCommands"
        // R.string.wallpaper_edit_mode, English and German (the A17 runs German).
        val EDIT_WALLPAPER_LABEL: Pattern = Pattern.compile("Edit wallpaper|Hintergrund bearbeiten")
        // An empty strip of the home screen below the favorites; a long press there opens the menu.
        const val LONG_PRESS_Y = 0.92
        const val LONG_PRESS_STEPS = 120 // a zero-length swipe this slow is a long press

        /** Set after a measured swap, so the next setup knows to swap back. */
        var swapped = false
    }
}
