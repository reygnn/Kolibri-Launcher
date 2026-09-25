package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.ReconcileOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for the reconcile policy (RECONCILE_HOME_LAYOUT_SPEC §2).
 *
 * STRUCTURAL ONLY — the reconciler no longer prunes (Windows-shortcut model, root
 * TODO.md): references to uninstalled apps are KEPT. The folder-repair inputs a test
 * builds (a 0- or 1-member folder) are the structurally-invalid states that reach the
 * reconciler via IMPORT of a malformed layout, not via a (now-removed) prune. The
 * folder invariant (>= 2 members) is enforced by the transitions, not by the type, so
 * such states are directly constructable here.
 */
class HomeLayoutReconcilerTest {

    private val grid = GridSpec(columns = 4, rows = 6)
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun app(id: String, p: String) = HomeItem.App(ItemId(id), ck(p))
    private fun folder(id: String, vararg m: ComponentKey) = HomeItem.Folder(ItemId(id), "", m.toList())
    private fun placed(item: HomeItem, page: Int, x: Int, y: Int) = PlacedItem(item, CellPos(page, x, y))
    private fun layout(items: List<PlacedItem> = emptyList(), dock: List<HomeItem> = emptyList(), pages: Int = 1) =
        HomeLayout(grid, pages, items, dock)
    private fun ids(vararg xs: String): ItemIdFactory { val i = xs.iterator(); return ItemIdFactory { ItemId(i.next()) } }

    @Test fun single_app_is_unchanged() {
        val start = layout(items = listOf(placed(app("a", "pa"), 0, 0, 0)))
        val out = HomeLayoutReconciler.reconcile(start, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun references_are_never_pruned_whatever_is_installed() {
        // No-prune contract: the reconciler consults no installed-set, so a layout of
        // structurally-valid tiles/dock is left completely intact even if some apps are
        // no longer installed (the dead tiles are surfaced/removed lazily in the UI).
        val start = layout(
            items = listOf(placed(app("a", "pa"), 0, 0, 0), placed(app("b", "pb"), 0, 1, 0)),
            dock = listOf(app("d", "pd")),
        )
        val out = HomeLayoutReconciler.reconcile(start, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun one_member_folder_dissolves_into_a_tile() {
        val start = layout(items = listOf(placed(folder("f", ck("pa")), 0, 2, 3)))
        val out = HomeLayoutReconciler.reconcile(start, ids("survivor")::next) as ReconcileOutcome.Changed
        val survivor = out.layout.items.single()
        assertThat(survivor.pos).isEqualTo(CellPos(0, 2, 3)) // folder's old cell
        assertThat((survivor.item as HomeItem.App).key).isEqualTo(ck("pa"))
        assertThat(survivor.item.id).isEqualTo(ItemId("survivor"))
        assertThat(out.report.dissolvedFolders).isEqualTo(1)
    }

    @Test fun empty_folder_is_removed() {
        val start = layout(items = listOf(placed(folder("f"), 0, 0, 0)))
        val out = HomeLayoutReconciler.reconcile(start, ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.items).isEmpty()
        assertThat(out.report.removedEmptyFolders).isEqualTo(1)
    }

    @Test fun folder_with_two_members_stays_a_folder() {
        val start = layout(items = listOf(placed(folder("f", ck("pa"), ck("pb")), 0, 0, 0)))
        val out = HomeLayoutReconciler.reconcile(start, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun trailing_empty_pages_are_trimmed_but_interior_kept() {
        // items on page 0 and page 2; page 1 interior-empty; pages = 5 → keep 3.
        val start = layout(
            items = listOf(placed(app("a", "pa"), 0, 0, 0), placed(app("c", "pc"), 2, 0, 0)),
            pages = 5,
        )
        val out = HomeLayoutReconciler.reconcile(start, ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.pages).isEqualTo(3) // pages 0,1,2 (interior page 1 preserved)
        assertThat(out.report.trimmedPages).isEqualTo(2)
    }

    @Test fun over_capacity_dock_is_left_untouched_by_reconcile() {
        // Reconcile no longer enforces dock capacity (the regridder owns it against the
        // real device grid) — an over-capacity dock is kept intact, not trimmed/dropped.
        val dock = (0..4).map { app("d$it", "pd$it") } // 5 > columns(4)
        val start = layout(dock = dock)
        val out = HomeLayoutReconciler.reconcile(start, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun dock_one_member_folder_dissolves_into_an_app_in_the_dock() {
        // repair() is type-agnostic and runs on the dock too: a dock folder with a single
        // member dissolves into a plain app that stays in the dock.
        val start = layout(dock = listOf(folder("f", ck("pa"))))
        val out = HomeLayoutReconciler.reconcile(start, ids("survivor")::next) as ReconcileOutcome.Changed
        assertThat(out.layout.dock).hasSize(1)
        val survivor = out.layout.dock.single() as HomeItem.App
        assertThat(survivor.key).isEqualTo(ck("pa"))
        assertThat(survivor.id).isEqualTo(ItemId("survivor"))
        assertThat(out.layout.items).isEmpty()
        assertThat(out.report.dissolvedFolders).isEqualTo(1)
    }

    @Test fun dock_empty_folder_is_removed_from_the_dock() {
        val start = layout(dock = listOf(folder("f"), app("keep", "pk")))
        val out = HomeLayoutReconciler.reconcile(start, ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.dock.map { it.id }).containsExactly(ItemId("keep"))
        assertThat(out.report.removedEmptyFolders).isEqualTo(1)
    }

    @Test fun removing_every_item_via_empty_folders_clamps_the_page_count_to_one() {
        // All grid items are empty folders → they are removed → usedPages becomes 0, and
        // the maxOf(1, ...) guard keeps the layout at one page (a launcher must always
        // have at least one home page), never zero.
        val start = layout(
            items = listOf(placed(folder("f0"), 0, 0, 0), placed(folder("f1"), 1, 0, 0)),
            pages = 3,
        )
        val out = HomeLayoutReconciler.reconcile(start, ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.items).isEmpty()
        assertThat(out.layout.pages).isEqualTo(1)
        assertThat(out.report.removedEmptyFolders).isEqualTo(2)
        assertThat(out.report.trimmedPages).isEqualTo(2) // 3 → 1
    }

    @Test fun reconcile_is_idempotent() {
        // A 1-member folder dissolves on the first pass; the second pass is a fixed point.
        val start = layout(
            items = listOf(placed(folder("f", ck("pa")), 0, 0, 0), placed(app("x", "px"), 0, 1, 0)),
            pages = 3,
        )
        val first = HomeLayoutReconciler.reconcile(start, ids("s")::next) as ReconcileOutcome.Changed
        val second = HomeLayoutReconciler.reconcile(first.layout, ids()::next)
        assertThat(second).isEqualTo(ReconcileOutcome.Unchanged) // fixed point
    }
}
