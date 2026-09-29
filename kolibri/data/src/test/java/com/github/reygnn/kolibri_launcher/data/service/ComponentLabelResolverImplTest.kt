package com.github.reygnn.kolibri_launcher.data.service

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because [ComponentLabelResolverImpl] builds real `Intent`s and reads
 * `ResolveInfo`/`ActivityInfo` — Android types with no usable pure-JVM stub. The
 * `PackageManager` is mocked so each resolution outcome is controlled; labels are set
 * via `nonLocalizedLabel` so `loadLabel` returns them without a resource lookup.
 *
 * The load-bearing assertion is the fail-safe direction for label resolution: a
 * PackageManager failure (or an unresolvable component) resolves to `null` — the
 * favorite is OMITTED from the provisional paint — so a transient error or a
 * disappeared app can never paint a ghost favorite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComponentLabelResolverImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val packageManager: PackageManager = mockk()
    private val resolver = ComponentLabelResolverImpl(packageManager, mainDispatcherRule.testDispatcher)

    private fun launcherActivity(pkg: String, cls: String, label: String?): ResolveInfo =
        ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = cls
            }
            nonLocalizedLabel = label
        }

    @Test
    fun `resolveLabel - matching launcher activity - returns its label`() = runTest(mainDispatcherRule.testDispatcher) {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(launcherActivity("com.app", "com.app.Main", "App One"))

        assertThat(resolver.resolveLabel("com.app/com.app.Main")).isEqualTo("App One")
    }

    @Test
    fun `resolveLabel - blank label - falls back to package name`() = runTest(mainDispatcherRule.testDispatcher) {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(launcherActivity("com.app", "com.app.Main", ""))

        assertThat(resolver.resolveLabel("com.app/com.app.Main")).isEqualTo("com.app")
    }

    @Test
    fun `resolveLabel - package has other launcher activity but not this one - null`() = runTest(mainDispatcherRule.testDispatcher) {
        // The alias case: the package still has a (different) launcher entry, but the
        // exact component the favorite points at is gone → omit, no ghost.
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns listOf(launcherActivity("com.app", "com.app.OtherAlias", "Other"))

        assertThat(resolver.resolveLabel("com.app/com.app.Main")).isNull()
    }

    @Test
    fun `resolveLabel - no launcher activities - null`() = runTest(mainDispatcherRule.testDispatcher) {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns emptyList()

        assertThat(resolver.resolveLabel("com.app/com.app.Main")).isNull()
    }

    @Test
    fun `resolveLabel - PackageManager throws - null (fail-closed, no ghost)`() = runTest(mainDispatcherRule.testDispatcher) {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } throws RuntimeException("PM dead")

        assertThat(resolver.resolveLabel("com.app/com.app.Main")).isNull()
    }
}
