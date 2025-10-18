package com.github.reygnn.kolibri_launcher

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Eine JUnit-Regel, die Animationen auf dem Testgerät vor jedem Test
 * über UIAutomation und adb-shell-Befehle zuverlässig deaktiviert
 * und danach wieder aktiviert. Dies ist die robusteste Methode für stabile Espresso-Tests.
 */
class DisableAnimationsRule : TestWatcher() {

    override fun starting(description: Description) {
        super.starting(description)
        setAnimationScales(0.0f)
    }

    override fun finished(description: Description) {
        super.finished(description)
        setAnimationScales(1.0f)
    }

    private fun setAnimationScales(scale: Float) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.executeShellCommand("settings put global window_animation_scale $scale")
        uiAutomation.executeShellCommand("settings put global transition_animation_scale $scale")
        uiAutomation.executeShellCommand("settings put global animator_duration_scale $scale")
    }
}