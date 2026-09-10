package com.github.reygnn.kolibri_launcher.data.service

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because [PackagePresenceImpl] builds real `Intent`s and reads
 * `ResolveInfo`/`ActivityInfo` — Android types with no usable pure-JVM stub.
 * The `PackageManager` itself is mocked so each presence outcome is controlled.
 *
 * The load-bearing assertions are the two fail-safe cases: a PackageManager
 * failure must resolve to "present" so the reconcile never deletes an
 * assignment on a transient system-API error (RECONCILE_SPEC R-INV / R-1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PackagePresenceImplTest {

    private val packageManager: PackageManager = mockk()
    private val presence = PackagePresenceImpl(packageManager, UnconfinedTestDispatcher())

    private fun launcherActivity(pkg: String, cls: String): ResolveInfo =
        ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = cls
            }
        }

    // ===== isPackagePresent (package-level, for custom-names) =====

    @Test
    fun `isPackagePresent - launch intent exists - present`() = runTest {
        every { packageManager.getLaunchIntentForPackage("com.app") } returns mockk<Intent>()
        assertTrue(presence.isPackagePresent("com.app"))
    }

    @Test
    fun `isPackagePresent - no launch intent - absent`() = runTest {
        every { packageManager.getLaunchIntentForPackage("com.app") } returns null
        assertFalse(presence.isPackagePresent("com.app"))
    }

    @Test
    fun `isPackagePresent - PackageManager throws - present (fail-safe)`() = runTest {
        every { packageManager.getLaunchIntentForPackage(any()) } throws RuntimeException("PM dead")
        assertTrue(presence.isPackagePresent("com.app"))
    }

    // ===== isComponentPresent (component-level, for favorites/swipe/hidden) =====

    @Test
    fun `isComponentPresent - matching launcher activity - present`() = runTest {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(launcherActivity("com.app", "com.app.Main"))

        assertTrue(presence.isComponentPresent("com.app/com.app.Main"))
    }

    @Test
    fun `isComponentPresent - package has other launcher activities but not this one - absent`() = runTest {
        // The alias-disable case: the class the assignment points at is gone,
        // even though the package still has a (different) launcher entry.
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(launcherActivity("com.app", "com.app.OtherAlias"))

        assertFalse(presence.isComponentPresent("com.app/com.app.Main"))
    }

    @Test
    fun `isComponentPresent - no launcher activities - absent`() = runTest {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns emptyList()

        assertFalse(presence.isComponentPresent("com.app/com.app.Main"))
    }

    @Test
    fun `isComponentPresent - PackageManager throws - present (fail-safe)`() = runTest {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } throws RuntimeException("PM dead")

        assertTrue(presence.isComponentPresent("com.app/com.app.Main"))
    }
}
