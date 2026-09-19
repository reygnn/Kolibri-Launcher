package com.github.reygnn.nyx_launcher

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps in HiltTestApplication so @HiltAndroidTest classes get their own
 * isolated SingletonComponent per test process. Required by the
 * testInstrumentationRunner declaration in nyx/app/build.gradle.kts.
 *
 * Mirrors Kolibri's runner (the shared convention lives there; this is Nyx's
 * own instance because the runner class must sit in the app's own package /
 * test classpath). Disables system animations so Espresso's RecyclerView /
 * gesture actions don't trip "Animations are enabled on the target device".
 *
 * No production-side equivalent — this only ever runs under androidTest.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
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
        ).forEach { cmd -> automation.executeShellCommand(cmd).close() }
    }

    private fun collapseNotificationShade() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd statusbar collapse").close()
    }
}
