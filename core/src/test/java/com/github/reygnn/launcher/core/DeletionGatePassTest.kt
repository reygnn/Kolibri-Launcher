package com.github.reygnn.launcher.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit test for [DeletionGatePass] — the shared per-pass deletion gate both launchers' reconciles
 * apply (AUDIT-1 F7; root TODO.md step a). Pins the fail-safe-to-keep policy in ONE place, so a
 * regression here is caught for nyx AND kolibri at once. Plain inline fakes (`:core` has no MockK):
 * [FakePresence] answers from two sets, [FakeSessions] counts reads and can return `null`.
 */
class DeletionGatePassTest {

    private val alpha = ComponentKey("com.alpha", "com.alpha.Main")
    private val beta = ComponentKey("com.beta", "com.beta.Main")

    // ---- component grain ----

    @Test
    fun `present component is kept and the session set is never read`() = runTest {
        val sessions = FakeSessions(active = emptySet())
        val gate = DeletionGatePass(FakePresence(presentComponents = setOf(alpha)), sessions)

        assertTrue(gate.keepComponent(alpha))
        // Presence short-circuits the `||`, so the session arm is never consulted.
        assertEquals(0, sessions.reads)
    }

    @Test
    fun `absent component with an active session is kept (restore promise)`() = runTest {
        val sessions = FakeSessions(active = setOf("com.alpha"))
        val gate = DeletionGatePass(FakePresence(), sessions)

        assertTrue(gate.keepComponent(alpha))
        assertEquals(1, sessions.reads)
    }

    @Test
    fun `absent and session-less component is pruned`() = runTest {
        val sessions = FakeSessions(active = emptySet())
        val gate = DeletionGatePass(FakePresence(), sessions)

        assertFalse(gate.keepComponent(alpha))
        assertEquals(1, sessions.reads)
    }

    @Test
    fun `undetermined session read keeps a presence-absent component (fail-safe)`() = runTest {
        val sessions = FakeSessions(active = null) // query failed → undetermined
        val gate = DeletionGatePass(FakePresence(), sessions)

        assertTrue(gate.keepComponent(alpha))
        assertEquals(1, sessions.reads)
    }

    // ---- package grain ----

    @Test
    fun `present package is kept and the session set is never read`() = runTest {
        val sessions = FakeSessions(active = emptySet())
        val gate = DeletionGatePass(FakePresence(presentPackages = setOf("com.alpha")), sessions)

        assertTrue(gate.keepPackage("com.alpha"))
        assertEquals(0, sessions.reads)
    }

    @Test
    fun `absent package with an active session is kept`() = runTest {
        val sessions = FakeSessions(active = setOf("com.alpha"))
        val gate = DeletionGatePass(FakePresence(), sessions)

        assertTrue(gate.keepPackage("com.alpha"))
    }

    @Test
    fun `absent and session-less package is pruned`() = runTest {
        val gate = DeletionGatePass(FakePresence(), FakeSessions(active = emptySet()))

        assertFalse(gate.keepPackage("com.alpha"))
    }

    // ---- batching across a pass ----

    @Test
    fun `session set is read at most once across many presence-absent candidates`() = runTest {
        val sessions = FakeSessions(active = setOf("com.alpha"))
        val gate = DeletionGatePass(FakePresence(), sessions)

        // Three presence-absent candidates across both grains; the session set is read ONCE and
        // reused — the batching guarantee kolibri relies on across its four stores.
        assertTrue(gate.keepComponent(alpha))   // restoring → keep
        assertFalse(gate.keepComponent(beta))   // absent + session-less → prune
        assertFalse(gate.keepPackage("com.beta"))
        assertEquals(1, sessions.reads)
    }

    private class FakePresence(
        val presentComponents: Set<ComponentKey> = emptySet(),
        val presentPackages: Set<String> = emptySet(),
    ) : AppPresence {
        override suspend fun isComponentPresent(key: ComponentKey) = key in presentComponents
        override suspend fun isPackagePresent(packageName: String) = packageName in presentPackages
    }

    private class FakeSessions(private val active: Set<String>?) : InstallSessionInspector {
        var reads = 0
            private set

        override suspend fun activeSessionPackages(): Set<String>? {
            reads++
            return active
        }
    }
}
