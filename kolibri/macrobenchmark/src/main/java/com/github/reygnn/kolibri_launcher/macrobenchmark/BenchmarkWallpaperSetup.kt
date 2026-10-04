package com.github.reygnn.kolibri_launcher.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * The fixed test wallpaper for [WallpaperPaintBenchmark] and [WallpaperCompositeBenchmark]
 * (SPEC_NYX_REWRITE F4) — one restore flow for both, no second copy: TWO layers with
 * different images, from one backup file that is pushed to the device once
 * (`adb push <file> /sdcard/Download/`). `connectedBenchmarkAndroidTest` reinstalls the target
 * fresh, so the wallpaper is restored on the first setup of each run through onboarding's
 * "Restore backup" button and the system document picker. No seam ships in the app.
 *
 * A no-op once restored (the restore persists across the cold kills), and when the install is
 * already past onboarding (the run's second test shares the install). The latch lives in the
 * test process: each benchmark class runs as its own `am instrument` invocation after a
 * `pm clear`, so a fresh process re-arms it and a fresh onboarding shows the restore button.
 */
fun MacrobenchmarkScope.restoreBenchmarkWallpaperIfNeeded() {
    if (benchmarkWallpaperRestored) return
    startActivityAndWait()
    val restore = device.wait(Until.findObject(By.res(KOLIBRI_PACKAGE, RESTORE_BACKUP_BUTTON_ID)), GATE_MS)
    if (restore != null) {
        restore.click()
        pickDocument(BENCHMARK_BACKUP_FILE)
    }
    // The consent dialog appears over Home AFTER the restore copies the photos, and it hides
    // wallpaper_view from UiAutomator — so "first Home, then the dialog" (12b) could never see
    // Home. Both conditions are polled in one loop instead (3a-8a-d): decline the dialog as soon
    // as it shows WHILE waiting for Home, in short event-wait slices, up to RESTORE_MS.
    val wallpaper = By.res(KOLIBRI_PACKAGE, "wallpaper_view")
    val deadline = SystemClock.uptimeMillis() + RESTORE_MS
    var reached = false
    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(wallpaper)) {
            reached = true
            break
        }
        if (device.hasObject(consentDecline)) declineConsentDialog()
        device.wait(Until.hasObject(wallpaper), POLL_SLICE_MS) // short slice; the loop re-checks the dialog
    }
    check(reached) { "Kolibri did not reach Home after restoring $BENCHMARK_BACKUP_FILE" }
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
    if (!device.wait(Until.hasObject(consentDecline), timeoutMs)) return
    declineConsentDialog()
}

/**
 * Taps "decline" on the showing consent dialog and insists it goes: a stuck dialog is reported as
 * such, separately from a missing Home. Null-safe against the dialog vanishing before the tap.
 */
private fun MacrobenchmarkScope.declineConsentDialog() {
    device.findObject(consentDecline)?.click()
    check(device.wait(Until.gone(consentDecline), CONSENT_GONE_MS)) {
        "The consent dialog is still showing after declining — the benchmark setup cannot go on"
    }
    device.waitForIdle()
}

/** The consent dialog's decline button: the AlertDialog negative button, in Kolibri's package. */
private val consentDecline = By.res(ANDROID_PACKAGE, DIALOG_NEGATIVE_BUTTON_ID).pkg(KOLIBRI_PACKAGE)

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
private const val CONSENT_GONE_MS = 5_000L  // how long the dialog may take to go after declining
private const val POLL_SLICE_MS = 500L      // one slice of the Home/consent poll loop
private const val ANDROID_PACKAGE = "android"
private const val DIALOG_NEGATIVE_BUTTON_ID = "button2" // AlertDialog negative button, locale-independent
// DocumentsUI labels, English and German (the A17 runs German).
private val SHOW_ROOTS: Pattern = Pattern.compile("Show roots|Stammverzeichnisse anzeigen")
private val DOWNLOADS_ROOT: Pattern = Pattern.compile("Downloads?")
