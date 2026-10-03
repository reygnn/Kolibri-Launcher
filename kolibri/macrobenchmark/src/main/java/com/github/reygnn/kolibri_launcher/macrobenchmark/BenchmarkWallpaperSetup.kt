package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * The fixed test wallpaper for [WallpaperPaintBenchmark] (SPEC_NYX_REWRITE F4): TWO layers with
 * different images, from one backup file that is pushed to the device once
 * (`adb push <file> /sdcard/Download/`). `connectedBenchmarkAndroidTest` reinstalls the target
 * fresh, so the wallpaper is restored on the first setup of each run through onboarding's
 * "Restore backup" button and the system document picker. No seam ships in the app.
 *
 * A no-op once restored (the restore persists across the cold kills), and when the install is
 * already past onboarding (the run's second test shares the install).
 */
fun MacrobenchmarkScope.restoreBenchmarkWallpaperIfNeeded() {
    if (benchmarkWallpaperRestored) return
    startActivityAndWait()
    val restore = device.wait(Until.findObject(By.res(KOLIBRI_PACKAGE, RESTORE_BACKUP_BUTTON_ID)), GATE_MS)
    if (restore != null) {
        restore.click()
        pickDocument(BENCHMARK_BACKUP_FILE)
        // After the restore onboarding hands over to Home; the ACRA consent may still show.
        dismissFirstRunGatesIfPresent(KOLIBRI_PACKAGE)
    }
    check(device.wait(Until.hasObject(By.res(KOLIBRI_PACKAGE, "wallpaper_view")), RESTORE_MS)) {
        "Kolibri did not reach Home after restoring $BENCHMARK_BACKUP_FILE"
    }
    benchmarkWallpaperRestored = true
}

/** Picks [fileName] in the system document picker, opening its Downloads root if needed. */
private fun MacrobenchmarkScope.pickDocument(fileName: String) {
    val byName = By.text(fileName)
    var file = device.wait(Until.findObject(byName), PICKER_MS)
    if (file == null) {
        device.wait(Until.findObject(By.desc(SHOW_ROOTS)), PICKER_MS)?.click()
        device.wait(Until.findObject(By.text(DOWNLOADS_ROOT)), PICKER_MS)?.click()
        file = device.wait(Until.findObject(byName), PICKER_MS)
    }
    (file ?: error("$fileName not found in the document picker — push it to /sdcard/Download/ first (see the spec)")).click()
}

/** The backup that sets the fixed two-layer test wallpaper; described in SPEC_NYX_REWRITE (3a-8). */
const val BENCHMARK_BACKUP_FILE = "kolibri-benchmark-wallpaper.zip"

private var benchmarkWallpaperRestored = false
private const val KOLIBRI_PACKAGE = "com.github.reygnn.kolibri_launcher"
private const val RESTORE_BACKUP_BUTTON_ID = "restore_backup_button" // activity_onboarding.xml
private const val GATE_MS = 3_000L
private const val PICKER_MS = 5_000L
private const val RESTORE_MS = 15_000L
// DocumentsUI labels, English and German (the A17 runs German).
private val SHOW_ROOTS: Pattern = Pattern.compile("Show roots|Stammverzeichnisse anzeigen")
private val DOWNLOADS_ROOT: Pattern = Pattern.compile("Downloads?")
