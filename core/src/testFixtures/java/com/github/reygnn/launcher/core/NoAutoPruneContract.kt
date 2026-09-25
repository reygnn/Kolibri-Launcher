package com.github.reygnn.launcher.core

import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Cross-launcher NO-AUTO-PRUNE contract (root `TODO.md`, the no-prune / Windows-shortcut
 * rebuild). This is the successor to the deleted `DeletionGateParityContract`: that pinned
 * that both launchers applied the SAME delete gate; there is no gate any more, and the
 * property both launchers must now share is the OPPOSITE — a curated reference to an app
 * that is absent from a NON-EMPTY installed view is NEVER auto-pruned. It survives and stays
 * observable to the UI (rendered greyed / "missing", removable only by an explicit user
 * action), on both nyx and kolibri.
 *
 * One assertion, two implementations. A subclass sets up its launcher's curated store
 * holding [candidate], runs the REAL load/reconcile path with the app ABSENT from a
 * non-empty installed view (at least one other, genuinely-installed reference present), and
 * reports whether [candidate] SURVIVED. A launcher that reintroduces an auto-prune at its
 * load/reconcile site turns its subclass red — the drift this contract exists to prevent,
 * which the two independent per-launcher suites alone would not necessarily catch.
 *
 * The EMPTY-view case (cold start) is deliberately NOT here: that is the separate
 * [LazySlotMembership] rule (empty = "not loaded", flags nothing), unit-pinned in
 * `LazySlotMembershipTest`. In this contract the installed view is always NON-EMPTY — a
 * genuine "the app is gone" signal — so survival is a pure no-prune guarantee, not the
 * not-loaded guard.
 *
 * Standard project contract shape: abstract behaviour here, run once per concrete subclass;
 * `MainDispatcherRuleBase` + `runTest(testDispatcher)` per the one-dispatcher rule, and the
 * subclass wires the SAME dispatcher into its use case.
 */
abstract class NoAutoPruneContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRuleBase(StandardTestDispatcher())

    /** The curated reference whose target is absent from the (non-empty) installed view. */
    protected val candidate = ComponentKey("com.ghost.gone", "com.ghost.gone.Main")

    /**
     * Seed this launcher's curated store with [candidate], run its REAL load/reconcile path
     * against a NON-EMPTY installed view that OMITS [candidate] (with at least one other,
     * genuinely-installed reference present), and return whether [candidate] SURVIVED — is
     * still held by the store / still rendered to the UI, not dropped.
     */
    protected abstract suspend fun candidateSurvivesUninstall(): Boolean

    @Test
    fun `a curated reference to a no-longer-installed app is never auto-pruned`() =
        runTest(mainDispatcherRule.testDispatcher) {
            assertTrue(
                "a curated reference must survive a non-empty load/reconcile that omits it " +
                    "(no auto-prune, Windows-shortcut model)",
                candidateSurvivesUninstall(),
            )
        }
}
