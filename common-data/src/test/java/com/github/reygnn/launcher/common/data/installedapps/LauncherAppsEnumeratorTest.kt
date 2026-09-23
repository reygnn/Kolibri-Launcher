package com.github.reygnn.launcher.common.data.installedapps

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit test for [LauncherAppsEnumerator] — the LauncherApps enumeration seam
 * (§9.1). Runs under Robolectric so `Process.myUserHandle()` / `android.os.Trace`
 * resolve; [LauncherApps] and its `LauncherActivityInfo` are MockK mocks so the
 * fail-closed policy is exercised without a device.
 *
 * Single dispatcher via [MainDispatcherRuleBase] (convention: one dispatcher
 * source, passed to both `runTest` and the code under test; no ad-hoc TestScope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LauncherAppsEnumeratorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRuleBase(StandardTestDispatcher())

    private val launcherApps = mockk<LauncherApps>()
    private val enumerator = LauncherAppsEnumerator(launcherApps, mainDispatcherRule.testDispatcher)

    private fun activity(pkg: String, cls: String, label: String): LauncherActivityInfo =
        mockk<LauncherActivityInfo>().also {
            every { it.componentName } returns ComponentName(pkg, cls)
            every { it.label } returns label
        }

    @Test
    fun `maps activities to raw AppInfo, unsorted, no customName folding`() = runTest(mainDispatcherRule.testDispatcher) {
        // Deliberately reverse-alphabetical to prove the enumerator does NOT sort
        // (SIA-INV-3: holder holds raw; sorting is the consumer's job).
        every { launcherApps.getActivityList(null, Process.myUserHandle()) } returns listOf(
            activity("com.example.z", "com.example.z.Main", "Zeta"),
            activity("com.example.a", "com.example.a.Main", "Alpha"),
        )

        val apps = enumerator.enumerate()

        assertThat(apps.map { it.displayName }).containsExactly("Zeta", "Alpha").inOrder()
        assertThat(apps[0].packageName).isEqualTo("com.example.z")
        assertThat(apps[0].displayName).isEqualTo(apps[0].originalName) // raw: no overlay
    }

    @Test
    fun `blank label falls back to package name`() = runTest(mainDispatcherRule.testDispatcher) {
        every { launcherApps.getActivityList(null, Process.myUserHandle()) } returns listOf(
            activity("com.example.a", "com.example.a.Main", "   "),
        )

        val apps = enumerator.enumerate()

        assertThat(apps.single().originalName).isEqualTo("com.example.a")
    }

    @Test
    fun `empty enumeration is a legitimate empty list, not an error`() = runTest(mainDispatcherRule.testDispatcher) {
        // §9.2: value-honest. "empty ⇒ suspicious" is the consumer's reconcile policy.
        every { launcherApps.getActivityList(null, Process.myUserHandle()) } returns emptyList()

        assertThat(enumerator.enumerate()).isEmpty()
    }

    @Test
    fun `getActivityList failure throws (mapped to Failed upstream)`() = runTest(mainDispatcherRule.testDispatcher) {
        // SIA-INV-2: a real enumeration failure must NOT collapse to an empty list;
        // it propagates so the motor turns it into AppLoad.Failed.
        every { launcherApps.getActivityList(null, Process.myUserHandle()) } throws IllegalStateException("boom")

        assertFailsWith<IllegalStateException> { enumerator.enumerate() }
    }

    @Test
    fun `cancellation propagates, never folded into a value`() = runTest(mainDispatcherRule.testDispatcher) {
        every { launcherApps.getActivityList(null, Process.myUserHandle()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { enumerator.enumerate() }
    }
}
