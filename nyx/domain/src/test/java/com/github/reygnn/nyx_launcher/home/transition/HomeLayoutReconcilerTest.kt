package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.ReconcileOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM tests for the reconcile policy (RECONCILE_HOME_LAYOUT_SPEC §2). */
class HomeLayoutReconcilerTest {

    private val grid = GridSpec(columns = 4, rows = 6)
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun app(id: String, p: String) = HomeItem.App(ItemId(id), ck(p))
    private fun folder(id: String, vararg m: ComponentKey) = HomeItem.Folder(ItemId(id), "", m.toList())
    private fun placed(item: HomeItem, page: Int, x: Int, y: Int) = PlacedItem(item, CellPos(page, x, y))
    private fun layout(items: List<PlacedItem> = emptyList(), dock: List<HomeItem> = emptyList(), pages: Int = 1) =
        HomeLayout(grid, pages, items, dock)
    private fun ids(vararg xs: String): ItemIdFactory { val i = xs.iterator(); return ItemIdFactory { ItemId(i.next()) } }

    @Test fun all_installed_is_unchanged() {
        val start = layout(items = listOf(placed(app("a", "pa"), 0, 0, 0)))
        val out = HomeLayoutReconciler.reconcile(start, setOf(ck("pa")), ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun prunes_dead_apps_from_grid_and_dock() {
        val start = layout(
            items = listOf(placed(app("a", "pa"), 0, 0, 0), placed(app("b", "pb"), 0, 1, 0)),
            dock = listOf(app("d", "pd")),
        )
        val out = HomeLayoutReconciler.reconcile(start, setOf(ck("pa")), ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.items.map { it.item.id }).containsExactly(ItemId("a"))
        assertThat(out.layout.dock).isEmpty()
        assertThat(out.report.prunedApps).isEqualTo(2) // b + d
    }

    @Test fun folder_losing_members_down_to_one_dissolves() {
        val start = layout(items = listOf(placed(folder("f", ck("pa"), ck("pb")), 0, 2, 3)))
        val out = HomeLayoutReconciler.reconcile(start, setOf(ck("pa")), ids("survivor")::next) as ReconcileOutcome.Changed
        val survivor = out.layout.items.single()
        assertThat(survivor.pos).isEqualTo(CellPos(0, 2, 3)) // folder's old cell
        assertThat((survivor.item as HomeItem.App).key).isEqualTo(ck("pa"))
        assertThat(survivor.item.id).isEqualTo(ItemId("survivor"))
        assertThat(out.report.dissolvedFolders).isEqualTo(1)
        assertThat(out.report.prunedApps).isEqualTo(1) // pb
    }

    @Test fun folder_losing_all_members_is_removed() {
        val start = layout(items = listOf(placed(folder("f", ck("pa"), ck("pb")), 0, 0, 0)))
        val out = HomeLayoutReconciler.reconcile(start, emptySet(), ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.items).isEmpty()
        assertThat(out.report.removedEmptyFolders).isEqualTo(1)
    }

    @Test fun folder_with_two_survivors_stays_a_folder() {
        val start = layout(items = listOf(placed(folder("f", ck("pa"), ck("pb"), ck("pc")), 0, 0, 0)))
        val out = HomeLayoutReconciler.reconcile(start, setOf(ck("pa"), ck("pb")), ids()::next) as ReconcileOutcome.Changed
        val f = out.layout.items.single().item as HomeItem.Folder
        assertThat(f.members).containsExactly(ck("pa"), ck("pb")).inOrder()
        assertThat(out.report.dissolvedFolders).isEqualTo(0)
    }

    @Test fun trailing_empty_pages_are_trimmed_but_interior_kept() {
        // items on page 0 and page 2; page 1 interior-empty; pages = 5 → keep 3.
        val start = layout(
            items = listOf(placed(app("a", "pa"), 0, 0, 0), placed(app("c", "pc"), 2, 0, 0)),
            pages = 5,
        )
        val out = HomeLayoutReconciler.reconcile(start, setOf(ck("pa"), ck("pc")), ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.pages).isEqualTo(3) // pages 0,1,2 (interior page 1 preserved)
        assertThat(out.report.trimmedPages).isEqualTo(2)
    }

    @Test fun over_capacity_dock_is_trimmed_to_columns() {
        val dock = (0..4).map { app("d$it", "pd$it") } // 5 > columns(4)
        val installed = (0..4).map { ck("pd$it") }.toSet()
        val start = layout(dock = dock)
        val out = HomeLayoutReconciler.reconcile(start, installed, ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.dock).hasSize(4)
        assertThat(out.report.dockTrimmed).isEqualTo(1)
    }

    @Test fun reconcile_is_idempotent() {
        val start = layout(
            items = listOf(placed(folder("f", ck("pa"), ck("pb")), 0, 0, 0), placed(app("x", "px"), 0, 1, 0)),
            pages = 3,
        )
        val first = HomeLayoutReconciler.reconcile(start, setOf(ck("pa")), ids("s")::next) as ReconcileOutcome.Changed
        val second = HomeLayoutReconciler.reconcile(first.layout, setOf(ck("pa")), ids()::next)
        assertThat(second).isEqualTo(ReconcileOutcome.Unchanged) // fixed point (px was pruned in pass 1)
    }
}
