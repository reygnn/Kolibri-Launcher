package com.github.reygnn.nyx_launcher.data.installedapps

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFailsWith

/**
 * Unit test for [LauncherAppsPresence] — the per-target deletion gate (AUDIT-1 F7,
 * RHL-INV-6). Robolectric only so `Process.myUserHandle()` resolves; [LauncherApps]
 * and its `LauncherActivityInfo` are MockK mocks so the component-exact match and the
 * fail-safe policy are exercised without a device.
 *
 * Single dispatcher via [MainDispatcherRuleBase] (convention: one dispatcher source,
 * passed to both `runTest` and the code under test; no ad-hoc TestScope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LauncherAppsPresenceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRuleBase(StandardTestDispatcher())

    private val launcherApps = mockk<LauncherApps>()
    private val presence = LauncherAppsPresence(launcherApps, mainDispatcherRule.testDispatcher)

    private fun activity(pkg: String, cls: String): LauncherActivityInfo =
        mockk<LauncherActivityInfo>().also {
            every { it.componentName } returns ComponentName(pkg, cls)
        }

    @Test
    fun `present when the exact component resolves for the primary user`() =
        runTest(mainDispatcherRule.testDispatcher) {
            every { launcherApps.getActivityList("com.example.a", Process.myUserHandle()) } returns
                listOf(activity("com.example.a", "com.example.a.Main"))

            val result = presence.isPresent(ComponentKey("com.example.a", "com.example.a.Main"))

            assertThat(result).isTrue()
        }

    @Test
    fun `absent when only a different alias of the same package resolves (component-exact)`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Package is installed, but NOT this specific launcher class (an alias was
            // disabled). Component-exact ⇒ this key is gone (SPEC-DECISION R-1).
            every { launcherApps.getActivityList("com.example.a", Process.myUserHandle()) } returns
                listOf(activity("com.example.a", "com.example.a.OtherAlias"))

            val result = presence.isPresent(ComponentKey("com.example.a", "com.example.a.Main"))

            assertThat(result).isFalse()
        }

    @Test
    fun `absent when the package has no launcher activities`() =
        runTest(mainDispatcherRule.testDispatcher) {
            every { launcherApps.getActivityList("com.example.gone", Process.myUserHandle()) } returns
                emptyList()

            val result = presence.isPresent(ComponentKey("com.example.gone", "com.example.gone.Main"))

            assertThat(result).isFalse()
        }

    @Test
    fun `fail-safe to present when the platform query throws`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // A transient system-API error must NEVER become a prune (RHL-INV-6): resolve
            // to present so the reconcile keeps the placement.
            every { launcherApps.getActivityList(any(), any()) } throws RuntimeException("system boom")

            val result = presence.isPresent(ComponentKey("com.example.a", "com.example.a.Main"))

            assertThat(result).isTrue()
        }

    @Test
    fun `cancellation propagates and is not folded into present`() =
        runTest(mainDispatcherRule.testDispatcher) {
            every { launcherApps.getActivityList(any(), any()) } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> {
                presence.isPresent(ComponentKey("com.example.a", "com.example.a.Main"))
            }
        }
}
