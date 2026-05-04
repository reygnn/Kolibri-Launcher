package com.github.reygnn.kolibri_launcher.support

import android.app.role.RoleManager
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Manipulates the android.app.role.HOME role via shell commands. This is
 * the only stable way to flip default-launcher state in instrumented tests —
 * the UI flow (RoleManager.createRequestRoleIntent + system dialog) varies
 * across OEM skins and is not driveable from Espresso reliably.
 *
 * Requires WRITE_SECURE_SETTINGS at the shell level, which UiAutomation has.
 * Verified on stock AOSP. Pixel and a few major OEMs.
 *
 * Order matters: cmd role takes a userId. We always use 0 (the default user
 * for non-multi-user test devices).
 */
object DefaultHomeRoleHelper {

    private const val ROLE = RoleManager.ROLE_HOME // "android.app.role.HOME"
    private const val USER = "--user 0"

    fun setSelfAsDefault() {
        val pkg = pkg()
        ShellCommand.run("cmd role add-role-holder $USER $ROLE $pkg")
    }

    fun clearSelfAsDefault() {
        val pkg = pkg()
        ShellCommand.run("cmd role remove-role-holder $USER $ROLE $pkg")
    }

    /**
     * Verifies via the same shell that the role is actually held. Use this
     * in @Before to guard against tests running on a configuration where
     * the shell command silently no-ops (rare but documented on some MIUI
     * builds — those should be skipped, not allowed to produce green tests).
     */
    fun isSelfDefault(): Boolean {
        val pkg = pkg()
        val out = ShellCommand.run("cmd role get-role-holders $USER $ROLE")
        return out.lines().any { it.trim() == pkg }
    }

    private fun pkg(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.packageName
}
