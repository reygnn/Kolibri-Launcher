package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.ReconcileResult
import com.github.reygnn.nyx_launcher.home.model.SkipReason
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * STRUCTURAL-ONLY reconcile (no prune, Windows-shortcut model — root TODO.md). The use
 * case no longer enumerates apps or gates deletions: a tile whose app is uninstalled is
 * KEPT (surfaced/removed lazily in the UI). It only runs the pure [HomeLayoutReconciler]
 * (dedup / folder-repair / trailing-page-trim) inside the atomic RMW and saves on change.
 *
 * STORE-SIDE FAIL-CLOSED (RHL-INV-1): a transient DataStore error inside the RMW surfaces
 * as a value-honest [SkipReason.STORE_FAILED] skip with zero mutation, so `invoke()` stays
 * total (only cancellation escapes) and callers need no fault handling of their own.
 */
class ReconcileHomeLayoutUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private val ids = ItemIdFactory { ItemId("new") }
    private fun ck(p: String) = ComponentKey(p, "$p.Main")

    private fun layoutWith(vararg pkgs: String): HomeLayout = HomeLayout(
        grid,
        pages = 1,
        items = pkgs.mapIndexed { i, p -> PlacedItem(HomeItem.App(ItemId(p), ck(p)), CellPos(0, i, 0)) },
        dock = emptyList(),
    )

    /** A single grid folder whose members are [pkgs] (a 1-member folder is the structural-repair input). */
    private fun layoutWithFolder(vararg pkgs: String): HomeLayout = HomeLayout(
        grid,
        pages = 1,
        items = listOf(
            PlacedItem(HomeItem.Folder(ItemId("folder"), title = "", members = pkgs.map { ck(it) }), CellPos(0, 0, 0)),
        ),
        dock = emptyList(),
    )

    private fun useCase(layoutRepo: FakeHomeLayoutRepository) =
        ReconcileHomeLayoutUseCase(layoutRepo, ids, mainDispatcherRule.dispatcher)

    @Test
    fun structurally_clean_layout_is_unchanged_and_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))

        val result = useCase(layoutRepo)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(layoutRepo.saveCount).isEqualTo(0)
    }

    @Test
    fun references_to_uninstalled_apps_are_never_pruned() = runTest(mainDispatcherRule.dispatcher) {
        // No-prune contract: the use case consults no app enumeration, so a structurally-valid
        // layout is left completely intact regardless of whether its apps are still installed —
        // dead tiles are surfaced/removed lazily in the UI, never auto-dropped here.
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))

        val result = useCase(layoutRepo)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(layoutRepo.saveCount).isEqualTo(0)
        assertThat(layoutRepo.current.items.map { it.item.id })
            .containsExactly(ItemId("pa"), ItemId("pb"))
    }

    @Test
    fun structural_repair_is_applied_and_saved_once() = runTest(mainDispatcherRule.dispatcher) {
        // A structurally-invalid layout (a 1-member folder, reachable via a malformed import)
        // is repaired: the folder dissolves into a plain tile. This is the one thing the pass
        // still does — pure structure, no enumeration.
        val layoutRepo = FakeHomeLayoutRepository(layoutWithFolder("pa"))

        val result = useCase(layoutRepo)()

        assertThat(result).isInstanceOf(ReconcileResult.Reconciled::class.java)
        assertThat(layoutRepo.saveCount).isEqualTo(1)
        val survivor = layoutRepo.current.items.single().item
        assertThat(survivor).isInstanceOf(HomeItem.App::class.java)
        assertThat((survivor as HomeItem.App).key).isEqualTo(ck("pa"))
    }

    @Test
    fun store_write_failure_skips_reconcile_without_mutation() = runTest(mainDispatcherRule.dispatcher) {
        // A transient DataStore error inside the atomic RMW aborts the pass as a value-honest
        // STORE_FAILED skip — never a throw, never a degrade to an empty layout.
        val layoutRepo = FakeHomeLayoutRepository(layoutWithFolder("pa")).apply {
            failUpdateWith = IOException("transient store write")
        }

        val result = useCase(layoutRepo)()

        assertThat(result).isEqualTo(ReconcileResult.Skipped(SkipReason.STORE_FAILED))
        assertThat(layoutRepo.saveCount).isEqualTo(0) // nothing written on a bad RMW
    }
}
