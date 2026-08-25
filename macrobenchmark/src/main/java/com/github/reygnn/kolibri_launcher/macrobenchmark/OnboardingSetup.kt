package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/**
 * Drives the launcher past its two first-run gates via UI, so a benchmark lands
 * on Home instead of OnboardingActivity / the ACRA consent dialog.
 *
 * WHY UI and not a seed broadcast / a benchmark-only build type:
 *  - `connectedBenchmarkAndroidTest` reinstalls the target fresh on every run
 *    (it uninstalls on completion), so a hand-done first-run cannot survive —
 *    onboarding MUST be handled inside the run.
 *  - Driving the real onboarding UI needs NO extra production code and ships
 *    nothing in the release APK (no exported seam), and it keeps every benchmark
 *    measuring the LITERAL `release` build — which alone carries the full
 *    baseline profile, so StartupBenchmark's cold-start TTID stays valid (a
 *    dedicated `benchmark` build type is not profile-equivalent to release).
 *
 * Mirrors `StartupBaselineProfileGenerator` in :baselineprofile, which already
 * clears the same two gates the same way. The resource ids are duplicated across
 * the module boundary on purpose (same drift note as that file): cross-module
 * sharing of three test constants costs more than it saves. Keep in sync with
 * onboarding if `done_button` / the consent dialog ever change.
 *
 * Call once per test method from `setupBlock`, AFTER the launcher is in the
 * foreground (`startActivityAndWait`). Each gate is dismissed only if its widget
 * is present, so this is a no-op once past onboarding — but callers still guard
 * it with a first-iteration flag so later iterations don't pay even the short
 * "is it there?" wait. The tap that completes onboarding / declines consent
 * persists to DataStore, so the flags survive across the run's iterations
 * (macrobenchmark does not clear app data between iterations).
 */
fun MacrobenchmarkScope.dismissFirstRunGatesIfPresent(targetPackage: String) {
    // 1. Onboarding — tap Done. An empty favorite set is legitimate in
    //    INITIAL_SETUP, so Home is reachable straight away.
    device.wait(Until.findObject(By.res(targetPackage, ONBOARDING_DONE_ID)), GATE_TIMEOUT_MS)
        ?.let { done ->
            done.click()
            device.wait(Until.gone(By.res(targetPackage, ONBOARDING_DONE_ID)), GONE_TIMEOUT_MS)
            device.waitForIdle()
        }

    // 2. ACRA consent dialog (privacy-by-default, first run) — decline. Uses the
    //    standard AlertDialog negative button (`android:id/button2`), which is
    //    locale-independent, so this holds on a German device too.
    device.wait(Until.findObject(By.res(ANDROID_PACKAGE, DIALOG_NEGATIVE_BUTTON_ID)), GATE_TIMEOUT_MS)
        ?.let { decline ->
            decline.click()
            device.wait(Until.gone(By.res(ANDROID_PACKAGE, DIALOG_NEGATIVE_BUTTON_ID)), GONE_TIMEOUT_MS)
            device.waitForIdle()
        }
}

// Each gate shows within a moment of MainActivity display or not at all, so a
// short window keeps the already-onboarded no-op cheap.
private const val GATE_TIMEOUT_MS = 3_000L
private const val GONE_TIMEOUT_MS = 5_000L
private const val ANDROID_PACKAGE = "android"
private const val ONBOARDING_DONE_ID = "done_button"       // activity_onboarding.xml
private const val DIALOG_NEGATIVE_BUTTON_ID = "button2"    // AlertDialog negative button
