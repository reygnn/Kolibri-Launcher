package com.github.reygnn.launcher.common.data

import com.github.reygnn.launcher.core.TimberWrapper
import org.junit.rules.ExternalResource

/**
 * Suppresses [TimberWrapper]'s DEBUG-throw for the duration of a test, so
 * error-path branches that call `silentError` don't crash the test. Local copy
 * of the apps' TimberRule (this module can't reach into an app's testFixtures).
 */
class TimberRule : ExternalResource() {
    override fun before() {
        TimberWrapper.preventCrashForTesting.set(true)
    }

    override fun after() {
        TimberWrapper.preventCrashForTesting.set(false)
    }
}
