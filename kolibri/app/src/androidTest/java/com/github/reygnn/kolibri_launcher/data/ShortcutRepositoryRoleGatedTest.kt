package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import com.github.reygnn.kolibri_launcher.support.DefaultHomeRoleHelper
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Why instrumented: ShortcutRepositoryImpl.getShortcutsForPackage() has two
 * gates that JVM/Robolectric cannot honestly verify:
 *   1. isDefaultLauncher() check at line 48 — Robolectric's PackageManager
 *      shadow always resolves CATEGORY_HOME to the test package, regardless
 *      of actual role state.
 *   2. The catch (SecurityException) branch at line 77 — Robolectric's
 *      ShadowLauncherApps does not throw SecurityException when the caller
 *      is not the default launcher; the real OS does.
 *
 * Both branches are dead code under Robolectric. This test exercises both
 * with the real RoleManager + LauncherApps backing them.
 *
 * Target package: com.android.settings ships on every AOSP/OEM image and
 * has at least one manifest shortcut, so this test is portable across
 * standard test devices.
 */
@HiltAndroidTest
class ShortcutRepositoryRoleGatedTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var shortcuts: ShortcutRepository

    private companion object {
        const val TARGET_PKG = "com.android.settings"
    }

    @Before fun setUp() {
        hiltRule.inject()
    }

    @After fun tearDown() {
        // Always release the role at the end so a leaked role does not
        // pollute subsequent tests on this device.
        try { DefaultHomeRoleHelper.clearSelfAsDefault() } catch (_: Throwable) {}
    }

    @Test
    fun getShortcuts_whenWeAreDefaultLauncher_returnsRealShortcuts() {
        DefaultHomeRoleHelper.setSelfAsDefault()
        // Sanity: skip the test if the device's role-shell is non-functional.
        // This protects against false greens on non-AOSP images rather than
        // hiding a real failure.
        assumeRoleShellIsFunctional()

        val result = shortcuts.getShortcutsForPackage(TARGET_PKG)

        // We do not assert a specific shortcut count — that varies by Android
        // image. We assert the gate opened (i.e. we got past line 48) and
        // that the data shape is sane.
        assertThat(result).isNotNull()
        result.forEach { s ->
            assertThat(s.packageName).isEqualTo(TARGET_PKG)
            assertThat(s.id).isNotEmpty()
        }
        // On AOSP Settings has at least one manifest shortcut. If your CI
        // image is unusually stripped, weaken to assertThat(result).isNotNull().
        assertThat(result).isNotEmpty()
    }

    @Test
    fun getShortcuts_whenWeAreNOTDefaultLauncher_returnsEmptyList() {
        DefaultHomeRoleHelper.clearSelfAsDefault()
        // The contract: returns emptyList() rather than throwing. This is
        // what hides the SecurityException from upstream callers (line 77-79).
        val result = shortcuts.getShortcutsForPackage(TARGET_PKG)
        assertThat(result).isEmpty()
    }

    @Test
    fun getShortcuts_blankPackage_shortcircuitsBeforeRoleCheck() {
        DefaultHomeRoleHelper.setSelfAsDefault()
        // This codepath is identical under JVM, but running it here gives
        // us a fast canary: if even the blank-string guard is broken in
        // production, every other shortcut call is broken too.
        assertThat(shortcuts.getShortcutsForPackage("")).isEmpty()
        assertThat(shortcuts.getShortcutsForPackage("   ")).isEmpty()
    }

    private fun assumeRoleShellIsFunctional() {
        // org.junit.Assume would be cleaner but we avoid the dependency.
        // If the helper says we're not the default after explicitly setting
        // it, the device's role-shell is non-functional and the test result
        // would be meaningless. Skip via AssumptionViolatedException.
        if (!DefaultHomeRoleHelper.isSelfDefault()) {
            throw org.junit.AssumptionViolatedException(
                "Device's `cmd role` shell is non-functional; skipping role-gated test."
            )
        }
    }
}
