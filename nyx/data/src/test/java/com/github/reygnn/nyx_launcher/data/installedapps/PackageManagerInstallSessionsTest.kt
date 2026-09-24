package com.github.reygnn.nyx_launcher.data.installedapps

import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
import kotlin.test.assertFailsWith

/**
 * Unit test for [PackageManagerInstallSessions] — the install/restore-session gate
 * (AUDIT-1 F7 review, fix 3). [PackageManager], its [PackageInstaller] and the
 * [PackageInstaller.SessionInfo]s are MockK mocks. Single dispatcher via
 * [MainDispatcherRuleBase], passed to both `runTest` and the code under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackageManagerInstallSessionsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRuleBase(StandardTestDispatcher())

    private val installer = mockk<PackageInstaller>()
    private val packageManager = mockk<PackageManager> { every { packageInstaller } returns installer }
    private val sessions = PackageManagerInstallSessions(packageManager, mainDispatcherRule.testDispatcher)

    private fun session(pkg: String?, active: Boolean): PackageInstaller.SessionInfo =
        mockk {
            every { appPackageName } returns pkg
            every { isActive } returns active
        }

    private fun onSessions(vararg infos: PackageInstaller.SessionInfo) {
        every { installer.allSessions } returns infos.toList()
    }

    @Test
    fun `true when an active session targets the package`() =
        runTest(mainDispatcherRule.testDispatcher) {
            onSessions(session("com.example.a", active = true))

            assertThat(sessions.hasActiveSession("com.example.a")).isTrue()
        }

    @Test
    fun `false when the session for the package is not active`() =
        runTest(mainDispatcherRule.testDispatcher) {
            onSessions(session("com.example.a", active = false))

            assertThat(sessions.hasActiveSession("com.example.a")).isFalse()
        }

    @Test
    fun `false when active sessions target other packages`() =
        runTest(mainDispatcherRule.testDispatcher) {
            onSessions(session("com.example.other", active = true), session(null, active = true))

            assertThat(sessions.hasActiveSession("com.example.a")).isFalse()
        }

    @Test
    fun `false when there are no sessions`() =
        runTest(mainDispatcherRule.testDispatcher) {
            onSessions()

            assertThat(sessions.hasActiveSession("com.example.a")).isFalse()
        }

    @Test
    fun `fail-safe to keep when reading sessions throws`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Can't read sessions → keep, so a restore-in-progress is never pruned on a
            // transient query failure.
            every { installer.allSessions } throws RuntimeException("system boom")

            assertThat(sessions.hasActiveSession("com.example.a")).isTrue()
        }

    @Test
    fun `cancellation propagates and is not folded into keep`() =
        runTest(mainDispatcherRule.testDispatcher) {
            every { installer.allSessions } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> { sessions.hasActiveSession("com.example.a") }
        }
}
