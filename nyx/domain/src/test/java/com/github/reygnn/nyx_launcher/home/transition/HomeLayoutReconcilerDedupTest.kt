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

/** RHL-INV-4 dedup precedence: Dock > Grid > Folder. Pure JVM. */
class HomeLayoutReconcilerDedupTest {

    private val grid = GridSpec(columns = 4, rows = 6)
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun app(id: String, p: String) = HomeItem.App(ItemId(id), ck(p))
    private fun folder(id: String, vararg m: ComponentKey) = HomeItem.Folder(ItemId(id), "", m.toList())
    private fun placed(item: HomeItem, page: Int, x: Int, y: Int) = PlacedItem(item, CellPos(page, x, y))
    private fun layout(items: List<PlacedItem> = emptyList(), dock: List<HomeItem> = emptyList(), pages: Int = 1) =
        HomeLayout(grid, pages, items, dock)
    private fun ids(vararg xs: String): ItemIdFactory { val i = xs.iterator(); return ItemIdFactory { ItemId(i.next()) } }
    private val allInstalled = setOf(ck("pa"), ck("pb"), ck("pc"), ck("pd"))

    @Test fun duplicate_top_level_apps_keep_dock_over_grid() {
        val start = layout(
            items = listOf(placed(app("g", "pa"), 0, 0, 0)),
            dock = listOf(app("d", "pa")), // same key pa in dock
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.dock.map { it.id }).containsExactly(ItemId("d")) // dock kept
        assertThat(out.layout.items).isEmpty() // grid duplicate dropped
        assertThat(out.report.dedupedApps).isEqualTo(1)
    }

    @Test fun duplicate_grid_apps_keep_lowest_position() {
        val start = layout(
            items = listOf(placed(app("late", "pa"), 0, 2, 0), placed(app("early", "pa"), 0, 0, 0)),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.items.map { it.item.id }).containsExactly(ItemId("early")) // (0,0) wins
    }

    @Test fun member_duplicating_a_top_level_app_is_dropped() {
        // pa is top-level (grid) AND a folder member → member dropped (Grid > Folder).
        val start = layout(
            items = listOf(
                placed(app("g", "pa"), 0, 0, 0),
                placed(folder("f", ck("pa"), ck("pb"), ck("pc")), 0, 1, 0),
            ),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next) as ReconcileOutcome.Changed
        val f = out.layout.items.first { it.item.id == ItemId("f") }.item as HomeItem.Folder
        assertThat(f.members).containsExactly(ck("pb"), ck("pc")).inOrder() // pa removed from folder
        assertThat(out.report.dedupedApps).isEqualTo(1)
    }

    @Test fun dedup_can_cascade_into_a_dissolve() {
        // folder [pa, pb]; pa also top-level → member pa dropped → folder has 1 → dissolves.
        val start = layout(
            items = listOf(
                placed(app("g", "pa"), 0, 0, 0),
                placed(folder("f", ck("pa"), ck("pb")), 0, 1, 0),
            ),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids("survivor")::next) as ReconcileOutcome.Changed
        assertThat(out.report.dedupedApps).isEqualTo(1)
        assertThat(out.report.dissolvedFolders).isEqualTo(1)
        val survivor = out.layout.items.first { it.pos == CellPos(0, 1, 0) }
        assertThat((survivor.item as HomeItem.App).key).isEqualTo(ck("pb"))
    }

    @Test fun a_key_shared_by_two_grid_folders_survives_in_the_lower_positioned_one() {
        // Two grid folders both list pa; the earlier one (page,y,x order) keeps it, the
        // later one drops it. Both stay folders (>=2 survivors), so no dissolve masks it.
        val installed = setOf(ck("pa"), ck("pb"), ck("pc"), ck("pd"), ck("pe"))
        val start = layout(
            items = listOf(
                placed(folder("f1", ck("pa"), ck("pb"), ck("pc")), 0, 0, 0),
                placed(folder("f2", ck("pa"), ck("pd"), ck("pe")), 0, 1, 0),
            ),
        )
        val out = HomeLayoutReconciler.reconcile(start, installed, ids()::next) as ReconcileOutcome.Changed
        val f1 = out.layout.items.first { it.item.id == ItemId("f1") }.item as HomeItem.Folder
        val f2 = out.layout.items.first { it.item.id == ItemId("f2") }.item as HomeItem.Folder
        assertThat(f1.members).containsExactly(ck("pa"), ck("pb"), ck("pc")).inOrder() // keeps pa
        assertThat(f2.members).containsExactly(ck("pd"), ck("pe")).inOrder() // pa dropped
        assertThat(out.report.dedupedApps).isEqualTo(1)
    }

    @Test fun a_key_shared_by_a_dock_folder_and_a_grid_folder_survives_in_the_dock_folder() {
        // Dedup pass 2c runs dock folders before grid folders → the dock folder wins pa.
        val installed = setOf(ck("pa"), ck("pb"), ck("pc"), ck("pd"))
        val start = layout(
            items = listOf(placed(folder("fg", ck("pa"), ck("pc"), ck("pd")), 0, 0, 0)),
            dock = listOf(folder("fd", ck("pa"), ck("pb"))),
        )
        val out = HomeLayoutReconciler.reconcile(start, installed, ids()::next) as ReconcileOutcome.Changed
        val fd = out.layout.dock.first { it.id == ItemId("fd") } as HomeItem.Folder
        val fg = out.layout.items.first { it.item.id == ItemId("fg") }.item as HomeItem.Folder
        assertThat(fd.members).containsExactly(ck("pa"), ck("pb")).inOrder() // dock folder keeps pa
        assertThat(fg.members).containsExactly(ck("pc"), ck("pd")).inOrder() // pa dropped from grid folder
        assertThat(out.report.dedupedApps).isEqualTo(1)
    }

    @Test fun dedup_is_idempotent() {
        val start = layout(
            items = listOf(placed(app("g", "pa"), 0, 0, 0)),
            dock = listOf(app("d", "pa")),
        )
        val first = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next) as ReconcileOutcome.Changed
        val second = HomeLayoutReconciler.reconcile(first.layout, allInstalled, ids()::next)
        assertThat(second).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun no_duplicates_is_unchanged() {
        val start = layout(
            items = listOf(placed(app("a", "pa"), 0, 0, 0), placed(app("b", "pb"), 0, 1, 0)),
            dock = listOf(app("c", "pc")),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }
}
