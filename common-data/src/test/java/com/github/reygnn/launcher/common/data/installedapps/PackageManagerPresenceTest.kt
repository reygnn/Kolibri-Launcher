package com.github.reygnn.launcher.common.data.installedapps

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
 * Unit test for [PackageManagerPresence] — the cross-surface deletion gate (AUDIT-1 F7
 * review, fix 2). Robolectric so the impl can build the `ACTION_MAIN` [android.content.Intent];
 * [PackageManager] is a MockK mock and the resolve results are real [ResolveInfo] /
 * [ActivityInfo]. Single dispatcher via [MainDispatcherRuleBase], passed to both `runTest`
 * and the code under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PackageManagerPresenceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRuleBase(StandardTestDispatcher())

    private val packageManager = mockk<PackageManager>()
    private val presence = PackageManagerPresence(packageManager, mainDispatcherRule.testDispatcher)

    private fun resolve(pkg: String, cls: String): ResolveInfo =
        ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = cls } }

    private fun onQuery(vararg results: ResolveInfo) {
        every {
            packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
        } returns results.toList()
    }

    @Test
    fun `present when the exact launcher component resolves`() =
        runTest(mainDispatcherRule.testDispatcher) {
            onQuery(resolve("com.example.a", "com.example.a.Main"))

            assertThat(presence.isPresent(ComponentKey("com.example.a", "com.example.a.Main"))).isTrue()
        }

    @Test
    fun `absent when only a different alias of the package resolves (component-exact)`() =
        runTest(mainDispatcherRule.testDispatcher) {
            onQuery(resolve("com.example.a", "com.example.a.OtherAlias"))

            assertThat(presence.isPresent(ComponentKey("com.example.a", "com.example.a.Main"))).isFalse()
        }

    @Test
    fun `absent when nothing resolves for the package`() =
        runTest(mainDispatcherRule.testDispatcher) {
            onQuery()

            assertThat(presence.isPresent(ComponentKey("com.example.gone", "com.example.gone.Main"))).isFalse()
        }

    @Test
    fun `fail-safe to present when the query throws`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // A transient system-API error must never become a prune (RHL-INV-6).
            every {
                packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
            } throws RuntimeException("system boom")

            assertThat(presence.isPresent(ComponentKey("com.example.a", "com.example.a.Main"))).isTrue()
        }

    @Test
    fun `cancellation propagates and is not folded into present`() =
        runTest(mainDispatcherRule.testDispatcher) {
            every {
                packageManager.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>())
            } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> {
                presence.isPresent(ComponentKey("com.example.a", "com.example.a.Main"))
            }
        }
}
