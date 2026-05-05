package com.github.reygnn.kolibri_launcher.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.support.DefaultHomeRoleHelper
import com.github.reygnn.kolibri_launcher.support.awaitUntil
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assume.assumeNotNull
import org.junit.AssumptionViolatedException
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
 * In production these MUST agree on the realistic transition path: user
 * sets us as default, then the user picks another launcher. Under
 * Robolectric they trivially "agree" because both shadows return whatever
 * you tell them to. Only a real device can show the convergence-window
 * behaviour or any structural divergence.
 *
 * What we do NOT test: the "set → clear, no replacement" transition. That
 * leaves Android in a no-HOME-holder limbo, which never occurs in
 * production (the user always picks *some* launcher). In that limbo,
 * `PackageManager.resolveActivity(CATEGORY_HOME)` falls back to
 * best-match resolution and returns *us* (since our MainActivity declares
 * CATEGORY_HOME and is the most recently active candidate), while
 * RoleManager honestly reports !isRoleHeld. This is a genuine semantic
 * gap between the two paths but it surfaces only in a state Production
 * cannot enter — see TODO.md §17 for the production code path that would
 * need adjusting if anyone ever did force the system into that state.
 *
 * @see DefaultHomeRoleHelper.setRoleHolderTo
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
    fun afterAnotherLauncherTakesRole_bothPathsConvergeOnNotUs() {
        // Realistic Production transition: we are default, then the user
        // picks a different launcher. HOME is exclusive, so the second
        // set-call implicitly displaces us — no clear in between.
        DefaultHomeRoleHelper.setSelfAsDefault()
        assumeRoleShellWorks()

        val otherLauncher = DefaultHomeRoleHelper.findAnotherInstalledLauncher()
        assumeNotNull(
            "Need at least one other CATEGORY_HOME app installed; got none on this device",
            otherLauncher,
        )

        DefaultHomeRoleHelper.setRoleHolderTo(otherLauncher!!)

        val convergedMs = awaitUntil(
            timeoutMs = TEST_BUDGET_MS,
            describe = {
                "viaRoleManager=${roleManager.isRoleHeld(RoleManager.ROLE_HOME)}, " +
                    "viaPackageManager=${isDefaultViaResolveActivity()}, " +
                    "currentResolved=${currentResolvedHomePkg()}"
            },
        ) {
            !roleManager.isRoleHeld(RoleManager.ROLE_HOME) && !isDefaultViaResolveActivity()
        }
        Log.i(LOG_TAG, "transition-to-other convergence: ${convergedMs}ms (production budget = ${PRODUCTION_BUDGET_MS}ms)")

        // Soft assertion: we don't fail when convergence > production
        // budget, but we make it visible in the test output so the
        // measurement makes its way back into TODO.md §17 via the human
        // who reads the test log.
        assertThat(convergedMs).isAtMost(TEST_BUDGET_MS)
    }

    /** Mirrors ShortcutRepositoryImpl.isDefaultLauncher() exactly. */
    private fun isDefaultViaResolveActivity(): Boolean =
        currentResolvedHomePkg() == ourPkg

    private fun currentResolvedHomePkg(): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        return context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }

    private fun assumeRoleShellWorks() {
        if (!DefaultHomeRoleHelper.isSelfDefault()) {
            throw AssumptionViolatedException(
                "cmd role is non-functional on this device; skipping."
            )
        }
    }
}
