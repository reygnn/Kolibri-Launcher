package com.github.reygnn.launcher.common.data.installedapps

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
 * (AUDIT-1 F7 review, fix 3; batched in point 5). [activeSessionPackages] returns the SET of
 * package names with an active session, or `null` when the query fails (undetermined → the
 * caller keeps every candidate). [PackageManager], its [PackageInstaller] and the
 * [PackageInstaller.SessionInfo]s are MockK mocks. Single dispatcher via [MainDispatcherRuleBase],
 * passed to both `runTest` and the code under test.
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
    fun `returns the packages of active sessions`() =
        runTest(mainDispatcherRule.testDispatcher) {
            onSessions(session("com.example.a", active = true), session("com.example.b", active = true))

            assertThat(sessions.activeSessionPackages()).containsExactly("com.example.a", "com.example.b")
        }

    @Test
    fun `committed or queued (inactive) restore sessions are still kept`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // AUDIT-1 F7 branch review: SessionInfo.isActive reflects only momentary forward
            // progress, so a committed-but-installing / queued restore session reports
            // isActive=false yet still represents a pending restore whose package presence cannot
            // rescue. It MUST be kept — filtering it out would re-open the F7 prune. So an inactive
            // session with a readable package name is kept exactly like an active one.
            // Mutation guard: re-add `.filter { it.isActive }` to the impl and this goes red.
            onSessions(session("com.example.a", active = true), session("com.example.b", active = false))

            assertThat(sessions.activeSessionPackages()).containsExactly("com.example.a", "com.example.b")
        }

    @Test
    fun `sessions with no readable package name are dropped`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // A foreign session whose appPackageName is not granted (e.g. nyx not the current
            // launcher) contributes nothing rather than a null entry.
            onSessions(session(null, active = true), session("com.example.a", active = true))

            assertThat(sessions.activeSessionPackages()).containsExactly("com.example.a")
        }

    @Test
    fun `empty set when there are no sessions`() =
        runTest(mainDispatcherRule.testDispatcher) {
            onSessions()

            // Determined "nothing active" — an EMPTY set, distinct from null (undetermined).
            assertThat(sessions.activeSessionPackages()).isEmpty()
        }

    @Test
    fun `null (undetermined) when reading sessions throws`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Can't read sessions → null, so the caller fail-safe keeps every candidate and a
            // restore-in-progress is never pruned on a transient query failure.
            every { installer.allSessions } throws RuntimeException("system boom")

            assertThat(sessions.activeSessionPackages()).isNull()
        }

    @Test
    fun `cancellation propagates and is not folded into null`() =
        runTest(mainDispatcherRule.testDispatcher) {
            every { installer.allSessions } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> { sessions.activeSessionPackages() }
        }
}
