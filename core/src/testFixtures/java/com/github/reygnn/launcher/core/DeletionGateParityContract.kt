package com.github.reygnn.launcher.core

import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-launcher parity contract for the shared deletion gate (root `TODO.md` "Drift-Prävention"
 * step b). Both nyx's home reconcile and kolibri's store reconcile MUST produce identical keep/prune
 * outcomes for a component-keyed candidate, because both route it through the same core
 * [DeletionGatePass] (step a). This pins that each launcher actually APPLIES the gate at its prune
 * sites — one scenario table, two implementations. A launcher that stops routing a store through the
 * gate, or diverges on the fail-safe direction, turns one of these red, which the two independent
 * per-launcher suites alone would not catch (that is the drift this contract exists to prevent).
 *
 * A subclass implements [componentCandidateSurvives]: set up its launcher's component-keyed store
 * holding a prune candidate, run the REAL reconcile against a non-empty enumeration that omits the
 * candidate, with the given gate state, and report whether the candidate survived. Component grain
 * only — the package grain (kolibri custom names) has no nyx analog and is covered by
 * [DeletionGatePassTest] plus kolibri's own suite.
 *
 * Standard project contract-test shape: abstract `@Test` methods here, run once per concrete
 * subclass. `MainDispatcherRuleBase` + `runTest(testDispatcher)` per the non-negotiable dispatcher
 * rule; the subclass wires the same dispatcher into its use case.
 */
abstract class DeletionGateParityContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRuleBase(StandardTestDispatcher())

    /** The prune candidate: a component the store references but the enumeration will omit. */
    protected val candidate = ComponentKey("com.parity.candidate", "com.parity.candidate.Main")

    /**
     * Set up this launcher's component-keyed store holding [candidate] (plus, freely, other
     * still-installed assignments), run its real reconcile against an enumeration that OMITS
     * [candidate] but is non-empty, with [presentComponents] as the cross-surface presence answer
     * and [activeSessions] as the install-session set (`null` = undetermined query), and return
     * whether [candidate] SURVIVED (was kept).
     */
    protected abstract suspend fun componentCandidateSurvives(
        presentComponents: Set<ComponentKey>,
        activeSessions: Set<String>?,
    ): Boolean

    @Test
    fun `present candidate is kept`() = runTest(mainDispatcherRule.testDispatcher) {
        assertTrue(componentCandidateSurvives(presentComponents = setOf(candidate), activeSessions = emptySet()))
    }

    @Test
    fun `absent candidate with an active install-restore session is kept`() =
        runTest(mainDispatcherRule.testDispatcher) {
            assertTrue(
                componentCandidateSurvives(presentComponents = emptySet(), activeSessions = setOf(candidate.packageName)),
            )
        }

    @Test
    fun `absent and session-less candidate is pruned`() = runTest(mainDispatcherRule.testDispatcher) {
        assertFalse(componentCandidateSurvives(presentComponents = emptySet(), activeSessions = emptySet()))
    }

    @Test
    fun `absent candidate with an undetermined session read is kept`() =
        runTest(mainDispatcherRule.testDispatcher) {
            assertTrue(componentCandidateSurvives(presentComponents = emptySet(), activeSessions = null))
        }
}
