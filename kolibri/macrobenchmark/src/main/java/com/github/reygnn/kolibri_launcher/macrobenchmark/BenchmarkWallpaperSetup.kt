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
    }
    // The restore copies two photos, then onboarding hands over to Home — wait for that EVENT,
    // not a fixed time; only then can the consent dialog appear.
    check(device.wait(Until.hasObject(By.res(KOLIBRI_PACKAGE, "wallpaper_view")), RESTORE_MS)) {
        "Kolibri did not reach Home after restoring $BENCHMARK_BACKUP_FILE"
    }
    declineConsentDialogIfItAppears(CONSENT_AFTER_RESTORE_MS)
    benchmarkWallpaperRestored = true
}

/**
 * The ACRA consent dialog, in the UNMEASURED setup only (3a-8a-b). Waits for the dialog as an
 * event (no sleep, no assumption that it is already there); always declines — benchmark runs send
 * no crash reports, and before and after must run under the same conditions. If it does not
 * appear within [timeoutMs] (consent already stored), the setup carries on. If it is still there
 * after declining, the setup stops with a clear message.
 */
fun MacrobenchmarkScope.declineConsentDialogIfItAppears(timeoutMs: Long) {
    val decline = By.res(ANDROID_PACKAGE, DIALOG_NEGATIVE_BUTTON_ID).pkg(KOLIBRI_PACKAGE)
    if (!device.wait(Until.hasObject(decline), timeoutMs)) return
    device.findObject(decline)?.click()
    check(device.wait(Until.gone(decline), CONSENT_GONE_MS)) {
        "The consent dialog is still showing after declining — the benchmark setup cannot go on"
    }
    device.waitForIdle()
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
private const val GATE_MS = 10_000L // event wait: returns as soon as onboarding shows its restore button
private const val PICKER_MS = 5_000L
private const val RESTORE_MS = 30_000L // two 12-MP photos are copied before Home shows
private const val CONSENT_AFTER_RESTORE_MS = 10_000L
private const val CONSENT_GONE_MS = 5_000L
private const val ANDROID_PACKAGE = "android"
private const val DIALOG_NEGATIVE_BUTTON_ID = "button2" // AlertDialog negative button, locale-independent
// DocumentsUI labels, English and German (the A17 runs German).
private val SHOW_ROOTS: Pattern = Pattern.compile("Show roots|Stammverzeichnisse anzeigen")
private val DOWNLOADS_ROOT: Pattern = Pattern.compile("Downloads?")
