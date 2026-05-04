package com.github.reygnn.kolibri_launcher.support

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Resets the app to a fresh-install state before each test by running
 * `pm clear <pkg>` via the instrumentation shell. This wipes DataStore,
 * SharedPreferences, filesDir, cacheDir, and revokes runtime permissions —
 * exactly what we need for tests that assert on first-run state.
 *
 * Usage:
 *   @get:Rule(order = 1) val clearData = ClearAppDataRule()
 *
 * MUST run at order > 0 (Hilt is order 0). Running before Hilt would clear
 * data while Hilt is still referencing it.
 *
 * NOTE: `pm clear` on the test process can in principle kill our own
 * instrumentation. In practice the test runner's process is separate
 * from the app process (HiltTestApplication runs in :main, instrumentation
 * runs in its own pid), so this is safe.
 */
class ClearAppDataRule : TestWatcher() {

    override fun starting(description: Description) {
        val pkg = InstrumentationRegistry.getInstrumentation()
            .targetContext.packageName
        ShellCommand.run("pm clear $pkg")
    }
}

internal object ShellCommand {
    fun run(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(cmd)
        return pfd.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor)
                .bufferedReader()
                .use { it.readText() }
        }
    }
}
