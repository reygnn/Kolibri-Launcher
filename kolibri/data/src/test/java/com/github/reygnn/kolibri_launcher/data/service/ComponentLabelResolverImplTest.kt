package com.github.reygnn.kolibri_launcher.data.service

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because [ComponentLabelResolverImpl] builds real `Intent`s and reads
 * `ResolveInfo`/`ActivityInfo` — Android types with no usable pure-JVM stub. The
 * `PackageManager` is mocked so each resolution outcome is controlled; labels are set
 * via `nonLocalizedLabel` so `loadLabel` returns them without a resource lookup.
 *
 * The load-bearing assertion is the inverse of PackagePresence's fail-safe: a
 * PackageManager failure (or an unresolvable component) resolves to `null` — the
 * favorite is OMITTED from the provisional paint — so a transient error or a
 * disappeared app can never paint a ghost favorite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComponentLabelResolverImplTest {

    private val packageManager: PackageManager = mockk()
    private val resolver = ComponentLabelResolverImpl(packageManager, UnconfinedTestDispatcher())

    private fun launcherActivity(pkg: String, cls: String, label: String?): ResolveInfo =
        ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = cls
            }
            nonLocalizedLabel = label
        }

    @Test
    fun `resolveLabel - matching launcher activity - returns its label`() = runTest {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(launcherActivity("com.app", "com.app.Main", "App One"))

        assertEquals("App One", resolver.resolveLabel("com.app/com.app.Main"))
    }

    @Test
    fun `resolveLabel - blank label - falls back to package name`() = runTest {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(launcherActivity("com.app", "com.app.Main", ""))

        assertEquals("com.app", resolver.resolveLabel("com.app/com.app.Main"))
    }

    @Test
    fun `resolveLabel - package has other launcher activity but not this one - null`() = runTest {
        // The alias case: the package still has a (different) launcher entry, but the
        // exact component the favorite points at is gone → omit, no ghost.
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(launcherActivity("com.app", "com.app.OtherAlias", "Other"))

        assertNull(resolver.resolveLabel("com.app/com.app.Main"))
    }

    @Test
    fun `resolveLabel - no launcher activities - null`() = runTest {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns emptyList()

        assertNull(resolver.resolveLabel("com.app/com.app.Main"))
    }

    @Test
    fun `resolveLabel - PackageManager throws - null (fail-closed, no ghost)`() = runTest {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } throws RuntimeException("PM dead")

        assertNull(resolver.resolveLabel("com.app/com.app.Main"))
    }
}
