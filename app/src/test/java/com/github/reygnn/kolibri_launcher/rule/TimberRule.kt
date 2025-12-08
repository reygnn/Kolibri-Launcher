package com.github.reygnn.kolibri_launcher.rules

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import org.junit.rules.ExternalResource

class TimberRule : ExternalResource() {
    override fun before() {
        // Wird VOR jedem Test ausgeführt
        TimberWrapper.preventCrashForTesting.set(true)
    }

    override fun after() {
        // Wird NACH jedem Test ausgeführt
        TimberWrapper.preventCrashForTesting.set(false)
    }
}