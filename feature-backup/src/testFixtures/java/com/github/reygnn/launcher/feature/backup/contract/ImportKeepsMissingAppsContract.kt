package com.github.reygnn.launcher.feature.backup.contract

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Cross-launcher import of references to apps that are not installed (SPEC_NYX_REWRITE A2,
 * 2b-2c). E1: the import never filters by what is installed — such a reference survives in
 * every curated store the backup carries it in. B14: a stored short form (`.Main`) comes
 * back normalized (`pkg.Main`), exactly like a reference written in full.
 *
 * The subclass builds a backup in its own format whose curated stores (home layout,
 * favorites, hidden apps, folders, swipe slots — whatever the launcher has) reference
 * [missing], with nothing of it installed, imports it through the REAL import path and
 * reports what each store holds afterwards.
 *
 * Standard project contract shape (`NoAutoPruneContract`): `MainDispatcherRule` +
 * `runTest(testDispatcher)`; the subclass wires the SAME dispatcher into its stores.
 */
abstract class ImportKeepsMissingAppsContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** The reference whose app is not installed. */
    protected val missing: ComponentKey = ComponentKey.of("com.ghost.gone", "com.ghost.gone.Main")

    /**
     * Imports a backup referencing [missing] with its class name stored as [storedClassName]
     * (full or short form) in every curated store; returns store name → keys it holds after
     * the import. Every store listed must have carried the reference in the backup.
     */
    protected abstract suspend fun importReferencing(storedClassName: String): Map<String, Set<ComponentKey>>

    @Test
    fun `a reference to an app that is not installed survives the import`() =
        runTest(mainDispatcherRule.testDispatcher) {
            assertKeptEverywhere(importReferencing(missing.className), "E1")
        }

    @Test
    fun `a stored short form comes back normalized`() =
        runTest(mainDispatcherRule.testDispatcher) {
            assertKeptEverywhere(importReferencing(".Main"), "B14")
        }

    private fun assertKeptEverywhere(stores: Map<String, Set<ComponentKey>>, rule: String) {
        assertWithMessage("the subclass must report at least one curated store").that(stores).isNotEmpty()
        for ((store, keys) in stores) {
            assertWithMessage("$rule: store '$store' must hold ${missing.flat} after the import").that(keys).contains(missing)
        }
    }
}
