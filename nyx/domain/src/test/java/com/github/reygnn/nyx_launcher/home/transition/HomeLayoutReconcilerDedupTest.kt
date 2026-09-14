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
 * RHL-INV-4 scoped dedup (scoped IHM-INV-7). Two independent scopes:
 *  - Top-level (grid ∪ dock): one survivor, Dock > Grid precedence.
 *  - Each folder on its own: dedup only WITHIN a folder; a key may live as a tile AND
 *    in several folders at once.
 * Pure JVM.
 */
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

    @Test fun duplicate_apps_within_the_dock_keep_the_earlier_slot() {
        // Pass 2a dedups top-level apps WITHIN the dock by slot order (line 61): two dock
        // apps sharing a key keep the earlier slot, the later duplicate is dropped. The other
        // dedup tests pit dock vs grid; this pins the intra-dock !seen.add branch.
        val start = layout(dock = listOf(app("first", "pa"), app("second", "pa")))
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.dock.map { it.id }).containsExactly(ItemId("first")) // earlier slot wins
        assertThat(out.report.dedupedApps).isEqualTo(1)
    }

    @Test fun a_member_may_also_be_a_top_level_tile() {
        // pa is a top-level grid tile AND a folder member. Independent scopes → BOTH survive,
        // nothing deduped (was: member dropped under the old global rule).
        val start = layout(
            items = listOf(
                placed(app("g", "pa"), 0, 0, 0),
                placed(folder("f", ck("pa"), ck("pb"), ck("pc")), 0, 1, 0),
            ),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun a_top_level_dup_no_longer_cascades_a_folder_into_dissolve() {
        // folder [pa, pb] with pa ALSO a top-level tile. Under the old global rule the member
        // pa was dropped, cascading the folder to one member and dissolving it. Now the folder
        // scope is independent → the member stays, the folder survives, nothing changes. (The
        // WITHIN-folder cascade-to-dissolve still exists — see a_folder_of_two_identical_members.)
        val start = layout(
            items = listOf(
                placed(app("g", "pa"), 0, 0, 0),
                placed(folder("f", ck("pa"), ck("pb")), 0, 1, 0),
            ),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids("unused")::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun a_key_may_live_in_two_grid_folders_at_once() {
        // Two grid folders both list pa. Each folder is its own scope → pa survives in BOTH
        // (was: dropped from the later-positioned one).
        val installed = setOf(ck("pa"), ck("pb"), ck("pc"), ck("pd"), ck("pe"))
        val start = layout(
            items = listOf(
                placed(folder("f1", ck("pa"), ck("pb"), ck("pc")), 0, 0, 0),
                placed(folder("f2", ck("pa"), ck("pd"), ck("pe")), 0, 1, 0),
            ),
        )
        val out = HomeLayoutReconciler.reconcile(start, installed, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun a_key_may_live_in_a_dock_folder_and_a_grid_folder_at_once() {
        // pa in a dock folder AND a grid folder. Independent folder scopes → survives in both
        // (was: dropped from the grid folder by dock-over-grid precedence).
        val installed = setOf(ck("pa"), ck("pb"), ck("pc"), ck("pd"))
        val start = layout(
            items = listOf(placed(folder("fg", ck("pa"), ck("pc"), ck("pd")), 0, 0, 0)),
            dock = listOf(folder("fd", ck("pa"), ck("pb"))),
        )
        val out = HomeLayoutReconciler.reconcile(start, installed, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
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

    @Test fun folder_members_that_are_also_top_level_tiles_now_coexist() {
        // Both members of a folder are ALSO top-level grid tiles. Under the old global rule the
        // members were stripped and the folder emptied+removed; now the folder scope is
        // independent, so the members stay, the folder survives, and nothing changes. (The
        // empty-folder REMOVAL path is still reachable via prune — see the prune tests.)
        val start = layout(
            items = listOf(
                placed(app("g1", "pa"), 0, 0, 0),
                placed(app("g2", "pb"), 0, 1, 0),
                placed(folder("f", ck("pa"), ck("pb")), 0, 2, 0),
            ),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun top_level_dedups_dock_over_grid_but_a_folder_member_is_a_separate_scope() {
        // pa lives as a dock app, a grid tile, AND a grid-folder member. Top-level scope
        // (grid ∪ dock) keeps ONE: the dock app wins, the grid tile is dropped. The folder
        // member is a DIFFERENT scope → it survives. So exactly one dedup (the grid tile),
        // not two (was: grid app + folder member both dropped under the old global rule).
        val start = layout(
            items = listOf(
                placed(app("g", "pa"), 0, 0, 0),
                placed(folder("f", ck("pa"), ck("pb"), ck("pc")), 0, 1, 0),
            ),
            dock = listOf(app("d", "pa")),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next) as ReconcileOutcome.Changed
        assertThat(out.layout.dock.map { it.id }).containsExactly(ItemId("d")) // dock wins top-level
        assertThat(out.layout.items.any { it.item.id == ItemId("g") }).isFalse() // grid tile dropped
        val f = out.layout.items.first { it.item.id == ItemId("f") }.item as HomeItem.Folder
        assertThat(f.members).containsExactly(ck("pa"), ck("pb"), ck("pc")).inOrder() // member pa SURVIVES
        assertThat(out.report.dedupedApps).isEqualTo(1) // only the grid tile
    }

    @Test fun duplicate_member_keys_within_one_folder_collapse_to_the_first() {
        // dedupMembers also dedups WITHIN a single folder's own list (a plausible bad import):
        // [pa, pa, pb] → [pa, pb], first occurrence kept, order preserved. The cross-source
        // tests execute the branch but never prove intra-list collapse.
        val start = layout(items = listOf(placed(folder("f", ck("pa"), ck("pa"), ck("pb")), 0, 0, 0)))
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next) as ReconcileOutcome.Changed
        val f = out.layout.items.first { it.item.id == ItemId("f") }.item as HomeItem.Folder
        assertThat(f.members).containsExactly(ck("pa"), ck("pb")).inOrder()
        assertThat(out.report.dedupedApps).isEqualTo(1)
    }

    @Test fun a_folder_of_two_identical_members_dissolves_after_dedup() {
        // The dangerous sub-variant: [pa, pa] → dedup to [pa] → Pass 3 dissolves the 1-member
        // folder into a plain app. dedupedApps == 1 AND dissolvedFolders == 1 in one pass.
        val start = layout(items = listOf(placed(folder("f", ck("pa"), ck("pa")), 0, 0, 0)))
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids("solo")::next) as ReconcileOutcome.Changed
        assertThat(out.layout.items.any { it.item.id == ItemId("f") }).isFalse() // folder gone
        val survivor = out.layout.items.first { it.pos == CellPos(0, 0, 0) }
        assertThat((survivor.item as HomeItem.App).key).isEqualTo(ck("pa"))
        assertThat(survivor.item.id).isEqualTo(ItemId("solo"))
        assertThat(out.report.dedupedApps).isEqualTo(1)
        assertThat(out.report.dissolvedFolders).isEqualTo(1)
    }

    @Test fun no_duplicates_is_unchanged() {
        val start = layout(
            items = listOf(placed(app("a", "pa"), 0, 0, 0), placed(app("b", "pb"), 0, 1, 0)),
            dock = listOf(app("c", "pc")),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }

    @Test fun a_key_may_live_in_two_dock_folders_at_once() {
        // Two dock folders both list pa. Each folder is its own scope → pa survives in BOTH
        // (was: dropped from the later slot). Distinct from top-level dock dedup, which still
        // collapses two bare dock APPS of the same key (duplicate_apps_within_the_dock…).
        val start = layout(
            dock = listOf(
                folder("early", ck("pa"), ck("pb")),
                folder("late", ck("pa"), ck("pc"), ck("pd")),
            ),
        )
        val out = HomeLayoutReconciler.reconcile(start, allInstalled, ids()::next)
        assertThat(out).isEqualTo(ReconcileOutcome.Unchanged)
    }
}
