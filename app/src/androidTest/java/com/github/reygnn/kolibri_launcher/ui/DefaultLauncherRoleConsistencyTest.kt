package com.github.reygnn.kolibri_launcher.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.support.ClearAppDataRule
import com.github.reygnn.kolibri_launcher.support.DefaultHomeRoleHelper
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Why instrumented: the codebase has TWO distinct ways to decide "are we
 * the default launcher":
 *
 *   A) SettingsFragment.updateDefaultLauncherStatus()  uses RoleManager.
 *   B) ShortcutRepositoryImpl.isDefaultLauncher()      uses PackageManager
 *                                                       .resolveActivity(CATEGORY_HOME).
 *
 * In production these MUST agree. Under Robolectric they trivially "agree"
 * because both shadows return whatever you tell them to. In reality:
 *   - Right after `cmd role add-role-holder HOME` runs, the PackageManager
 *     resolver is updated synchronously, but the RoleManager's role-state
 *     cache for the calling UID can lag by one IPC.
 *   - After role-loss (e.g. user picks a different launcher), the
 *     RoleManager updates first, the PackageManager next. There's a brief
 *     window where they disagree.
 *
 * If A and B disagree even once in our codebase, users see "you are the
 * default" on the settings screen but get empty shortcut menus, or vice
 * versa. This test asserts A and B are in lockstep across set/clear cycles.
 */
@HiltAndroidTest
class DefaultLauncherRoleConsistencyTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val clearData = ClearAppDataRule()

    private lateinit var context: Context
    private lateinit var roleManager: RoleManager
    private lateinit var ourPkg: String

    @Before
    fun setUp() {
        hiltRule.inject()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        roleManager = context.getSystemService(RoleManager::class.java)!!
        ourPkg = context.packageName
    }

    @After
    fun tearDown() {
        try { DefaultHomeRoleHelper.clearSelfAsDefault() } catch (_: Throwable) {}
    }

    @Test
    fun afterSettingRole_bothPathsReportSelfAsDefault() {
        DefaultHomeRoleHelper.setSelfAsDefault()
        assumeRoleShellWorks()

        val viaRoleManager = roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        val viaPackageManager = isDefaultViaResolveActivity()

        assertThat(viaRoleManager).isTrue()
        assertThat(viaPackageManager).isTrue()
        assertThat(viaRoleManager).isEqualTo(viaPackageManager) // belt + braces
    }

    @Test
    fun afterClearingRole_bothPathsReportNotDefault() {
        // Set then clear, to also exercise the *transition* path.
        DefaultHomeRoleHelper.setSelfAsDefault()
        DefaultHomeRoleHelper.clearSelfAsDefault()

        val viaRoleManager = roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        val viaPackageManager = isDefaultViaResolveActivity()

        assertThat(viaRoleManager).isFalse()
        // viaPackageManager may be non-null but pointing to whatever the
        // device's stock launcher is — i.e. NOT us. That's the contract.
        assertThat(viaPackageManager).isFalse()
    }

    /** Mirrors ShortcutRepositoryImpl.isDefaultLauncher() exactly. */
    private fun isDefaultViaResolveActivity(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolved = context.packageManager.resolveActivity(intent, 0)
        return resolved?.activityInfo?.packageName == ourPkg
    }

    private fun assumeRoleShellWorks() {
        if (!DefaultHomeRoleHelper.isSelfDefault()) {
            throw org.junit.AssumptionViolatedException(
                "cmd role is non-functional on this device; skipping."
            )
        }
    }
}
