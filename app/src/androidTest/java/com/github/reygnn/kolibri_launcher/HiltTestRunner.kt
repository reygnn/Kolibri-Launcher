package com.github.reygnn.kolibri_launcher

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom runner that swaps in HiltTestApplication so @HiltAndroidTest classes
 * get their own isolated SingletonComponent per test process. Required by the
 * testInstrumentationRunner declaration in app/build.gradle.kts.
 *
 * Also disables system animations before the suite runs — Espresso's
 * RecyclerViewActions throws PerformException with the message "Animations
 * or transitions are enabled on the target device" if any of window /
 * transition / animator scale is non-zero. Doing it here keeps every
 * @HiltAndroidTest immune; we don't restore on shutdown because dev
 * devices typically already keep animations disabled and CI emulators
 * are throwaway. (See AndroidJUnitRunner docs > "Disabling animations".)
 *
 * No production-side equivalent — this only ever runs under androidTest.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }

    override fun onStart() {
        disableSystemAnimations()
        collapseNotificationShade()
        super.onStart()
    }

    private fun disableSystemAnimations() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "settings put global window_animation_scale 0.0",
            "settings put global transition_animation_scale 0.0",
            "settings put global animator_duration_scale 0.0",
        ).forEach { cmd ->
            automation.executeShellCommand(cmd).close()
        }
    }

    /**
     * Headless emulators left running for a while end up with the
     * NotificationShade as the focused window — between tests there's no
     * real user activity to push it back, and the system chrome creeps to
     * the front. With NotificationShade focused, Espresso's RootViewPicker
     * sees `has-window-focus=false` on every test Activity's window and
     * times out with `RootViewWithoutFocusException` after 10 s.
     *
     * `cmd statusbar collapse` is the canonical shell-side dismiss; harmless
     * when no shade is open. Run before every test process via the runner
     * because per-test @Before would be 12+ duplicated copies.
     */
    private fun collapseNotificationShade() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("cmd statusbar collapse").close()
    }
}
