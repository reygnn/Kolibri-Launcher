package com.github.reygnn.kolibri_launcher.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import com.github.reygnn.kolibri_launcher.support.DefaultHomeRoleHelper
import com.github.reygnn.kolibri_launcher.support.awaitUntil
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
 * The clearing-direction test deliberately measures the convergence
 * window — if it grows past the 500ms budget, the RoleManager UID-side
 * cache is a real production concern for SettingsFragment showing stale
 * "you are default" or "you are not default" status to the user.
 * See TODO.md §17.
 */
@HiltAndroidTest
class DefaultLauncherRoleConsistencyTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var roleManager: RoleManager
    private lateinit var ourPkg: String

    private companion object {
        // Hard test budget — exceeding this means the two paths never
        // converge at all, which is a structural failure.
        const val TEST_BUDGET_MS = 15_000L

        // Soft production budget — convergence beyond this is a real UX
        // concern for SettingsFragment showing a stale Default-Launcher
        // status. We *log* the measured value but don't fail the test on
        // it, because the lag is system-side and can vary across emulator
        // images. The number is fed back into TODO.md §17 manually.
        const val PRODUCTION_BUDGET_MS = 500L

        const val LOG_TAG = "DefaultLauncherRoleTest"
    }

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
    fun afterSettingRole_bothPathsConvergeOnDefault() {
        DefaultHomeRoleHelper.setSelfAsDefault()
        assumeRoleShellWorks()

        val convergedMs = awaitUntil(
            timeoutMs = TEST_BUDGET_MS,
            describe = {
                "viaRoleManager=${roleManager.isRoleHeld(RoleManager.ROLE_HOME)}, " +
                    "viaPackageManager=${isDefaultViaResolveActivity()}"
            },
        ) {
            roleManager.isRoleHeld(RoleManager.ROLE_HOME) && isDefaultViaResolveActivity()
        }
        Log.i(LOG_TAG, "set-direction convergence: ${convergedMs}ms (production budget = ${PRODUCTION_BUDGET_MS}ms)")
    }

    @Test
    fun afterClearingRole_bothPathsConvergeOnNotDefault() {
        // Set first to exercise the *transition* path (set-then-clear is
        // the case where caches are most likely to lag, because they are
        // now warm with the previous "true" value).
        DefaultHomeRoleHelper.setSelfAsDefault()
        assumeRoleShellWorks()

        DefaultHomeRoleHelper.clearSelfAsDefault()

        val convergedMs = awaitUntil(
            timeoutMs = TEST_BUDGET_MS,
            describe = {
                "viaRoleManager=${roleManager.isRoleHeld(RoleManager.ROLE_HOME)}, " +
                    "viaPackageManager=${isDefaultViaResolveActivity()}"
            },
        ) {
            !roleManager.isRoleHeld(RoleManager.ROLE_HOME) && !isDefaultViaResolveActivity()
        }
        Log.i(LOG_TAG, "clear-direction convergence: ${convergedMs}ms (production budget = ${PRODUCTION_BUDGET_MS}ms)")

        // Soft assertion: we don't fail when convergence > production
        // budget, but we make it visible in the test output so the
        // measurement makes its way back into TODO.md §17 via the human
        // who reads the test log.
        assertThat(convergedMs).isAtMost(TEST_BUDGET_MS)
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
