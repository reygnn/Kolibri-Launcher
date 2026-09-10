package com.github.reygnn.kolibri_launcher.support

import android.app.role.RoleManager
import android.content.Intent
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
        setRoleHolderTo(pkg())
    }

    fun clearSelfAsDefault() {
        val pkg = pkg()
        ShellCommand.run("cmd role remove-role-holder $USER $ROLE $pkg")
    }

    /**
     * Sets [packageName] as HOME role-holder. Because HOME is an exclusive
     * role, this implicitly displaces whoever held it before — no need to
     * clear first. Use this to simulate the realistic Production transition
     * "user picks a different launcher" (set self → set other → both
     * indicators must report the other), as opposed to the unrealistic
     * "set self → clear" which leaves the system in a no-holder limbo
     * that Production never enters (resolveActivity then falls back to
     * any CATEGORY_HOME activity, including ours).
     */
    fun setRoleHolderTo(packageName: String) {
        ShellCommand.run("cmd role add-role-holder $USER $ROLE $packageName")
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

    /**
     * Returns the first installed package that exposes a CATEGORY_HOME
     * activity and is NOT us. On Pixel emulators this is typically
     * `com.google.android.apps.nexuslauncher`; on AOSP it's the bundled
     * Launcher3. Returns null if no other HOME-capable app is on the
     * device — tests that need this should `assumeTrue` on a non-null
     * result, not fail.
     */
    fun findAnotherInstalledLauncher(): String? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ours = ctx.packageName
        return ctx.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != ours }
    }

    private fun pkg(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.packageName
}
