package com.github.reygnn.kolibri_launcher.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures the paths the user hits on EVERY unlock: cold home render, drawer
 * open, and the first RecyclerView scroll (bind/inflate of the app rows). These
 * are exactly the slices that run interpreted on first use without a profile —
 * the biggest cold-start win on a weak CPU (A17 5G).
 *
 * Requires a connected, **unlocked** device with the launcher **past onboarding**
 * and at least one home favorite — same preconditions as [LaunchDispatchBenchmark]
 * in :macrobenchmark.
 *
 * Run:  ./gradlew :app:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE,
        // Also emit a startup profile (dexopt hint) for these critical paths.
        includeInStartupProfile = true,
    ) {
        // Ensure the screen is on before driving UI: on an emulator the display
        // can doze between iterations, which silently swallows the drawer swipe
        // (observed as a flaky "drawer has no rows"). No-op on an awake device.
        device.wakeUp()
        pressHome()
        startActivityAndWait()

        // Self-sufficient from a CLEAN install: the launcher shows two first-run
        // interstitials that would block the drawer. Clear both if present; each
        // is a no-op on an already-set-up device (the widget is simply absent).
        //  1. Onboarding — tap Done (an empty favorite set is legitimate in
        //     INITIAL_SETUP) so home is reached.
        //  2. ACRA consent dialog (privacy-by-default, first run) — decline so it
        //     stops blocking; the profiling run needs no crash reporting.
        completeOnboardingIfPresent()
        dismissConsentDialogIfPresent()

        // Open the drawer with the same gesture the latency benchmark uses.
        device.swipe(
            device.displayWidth / 2,
            (device.displayHeight * 0.85).toInt(),
            device.displayWidth / 2,
            (device.displayHeight * 0.30).toInt(),
            SWIPE_STEPS,
        )
        device.wait(
            Until.findObject(By.res(TARGET_PACKAGE, APP_NAME_ID)),
            FIND_TIMEOUT_MS,
        ) ?: error("App drawer has no rows — is the launcher unlocked and past onboarding?")

        // Exercise a scroll so the row bind/inflate path is captured too.
        device.findObject(By.res(TARGET_PACKAGE, DRAWER_LIST_ID))?.apply {
            setGestureMargin(device.displayWidth / 5)
            fling(Direction.DOWN)
            fling(Direction.UP)
        }
        device.waitForIdle()
    }

    /**
     * Completes first-run onboarding if [OnboardingActivity]'s Done button is on
     * screen, then waits for it to disappear (navigation back to home). No-op
     * when the launcher is already past onboarding (button absent) — the short
     * wait is the only cost on an already-set-up device.
     */
    private fun MacrobenchmarkScope.completeOnboardingIfPresent() {
        val doneButton = device.wait(
            Until.findObject(By.res(TARGET_PACKAGE, DONE_BUTTON_ID)),
            ONBOARDING_TIMEOUT_MS,
        ) ?: return
        doneButton.click()
        device.wait(Until.gone(By.res(TARGET_PACKAGE, DONE_BUTTON_ID)), FIND_TIMEOUT_MS)
        device.waitForIdle()
    }

    /**
     * Declines the first-run ACRA consent dialog if it is up (standard
     * AlertDialog negative button, `android:id/button2` — locale-independent, so
     * this holds on a German device too). No-op once consent has been answered.
     */
    private fun MacrobenchmarkScope.dismissConsentDialogIfPresent() {
        val declineButton = device.wait(
            Until.findObject(By.res(ANDROID_PACKAGE, DIALOG_NEGATIVE_BUTTON_ID)),
            CONSENT_TIMEOUT_MS,
        ) ?: return
        declineButton.click()
        device.wait(Until.gone(By.res(ANDROID_PACKAGE, DIALOG_NEGATIVE_BUTTON_ID)), FIND_TIMEOUT_MS)
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.github.reygnn.kolibri_launcher"
        const val FIND_TIMEOUT_MS = 5_000L
        // Onboarding either shows within a moment of MainActivity display or not
        // at all — a short window keeps the already-onboarded no-op cheap.
        const val ONBOARDING_TIMEOUT_MS = 3_000L
        const val CONSENT_TIMEOUT_MS = 3_000L
        const val DONE_BUTTON_ID = "done_button" // activity_onboarding.xml
        const val ANDROID_PACKAGE = "android"
        const val DIALOG_NEGATIVE_BUTTON_ID = "button2" // AlertDialog negative button
        const val SWIPE_STEPS = 8
        // Duplicated across the :macrobenchmark module boundary on purpose (see
        // the drift note in the setup doc): cross-module sharing of three test
        // constants costs more complexity than it saves. Keep in sync with
        // LaunchDispatchBenchmark if the drawer layout ids ever change.
        const val APP_NAME_ID = "app_name"              // drawer row label (item_app_drawer.xml)
        const val DRAWER_LIST_ID = "apps_recycler_view" // fragment_app_drawer.xml
    }
}
