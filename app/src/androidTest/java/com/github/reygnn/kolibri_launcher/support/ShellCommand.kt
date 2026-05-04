package com.github.reygnn.kolibri_launcher.support

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Wrapper around UiAutomation.executeShellCommand for instrumented tests.
 * Returns stdout as a String. Used by DefaultHomeRoleHelper to manipulate
 * the android.app.role.HOME role via `cmd role`.
 */
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
