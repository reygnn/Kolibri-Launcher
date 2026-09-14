package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.FolderEditResult
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Dock folders must behave IDENTICALLY to grid folders. The dock's land-on target
 * is [DropTarget.DockItem] (the counterpart of [DropTarget.Cell]); [DropTarget.DockSlot]
 * stays insert-only. Pure JVM.
 */
class HomeLayoutDockFolderParityTest {

    private val grid = GridSpec(columns = 4, rows = 6)
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun app(id: String, p: String) = HomeItem.App(ItemId(id), ck(p))
    private fun folder(id: String, vararg m: ComponentKey) = HomeItem.Folder(ItemId(id), "", m.toList())
    private fun layout(items: List<PlacedItem> = emptyList(), dock: List<HomeItem> = emptyList(), pages: Int = 1) =
        HomeLayout(grid, pages, items, dock)
    private fun seq(vararg ids: String): ItemIdFactory {
        val it = ids.iterator(); return ItemIdFactory { ItemId(it.next()) }
    }

    // ---------------- move(): create / add in the dock (MOVE_ITEM parity) ----------------

    @Test fun app_onto_a_dock_app_creates_a_dock_folder() {
        val d0 = app("d0", "pa")
        val d1 = app("d1", "pb")
        val start = layout(dock = listOf(d0, d1))
        // drag d1 ONTO d0 (DockItem index 0)
        val r = HomeLayoutTransition.move(start, d1.id, DropTarget.DockItem(0), seq("folder")::next)
        assertThat(r).isInstanceOf(MoveResult.FolderCreated::class.java)
        assertThat((r as MoveResult.FolderCreated).folder).isEqualTo(ItemId("folder"))
        val out = r.layout
        val f = out.dock.single { it.id == ItemId("folder") } as HomeItem.Folder
        assertThat(f.members).containsExactly(ck("pa"), ck("pb")).inOrder() // occupant first, then dragged
        assertThat(out.dock.any { it.id == d1.id }).isFalse() // dragged app consumed
    }

    @Test fun app_onto_a_dock_folder_is_added_as_a_member() {
        val f = folder("f", ck("pa"), ck("pb"))
        val a = app("a", "pc")
        val start = layout(items = listOf(PlacedItem(a, CellPos(0, 0, 0))), dock = listOf(f))
        val r = HomeLayoutTransition.move(start, a.id, DropTarget.DockItem(0), seq("unused")::next)
        assertThat(r).isInstanceOf(MoveResult.AddedToFolder::class.java)
        val out = r.layout!!
        val fOut = out.dock.single { it.id == ItemId("f") } as HomeItem.Folder
        assertThat(fOut.members).containsExactly(ck("pa"), ck("pb"), ck("pc")).inOrder()
        assertThat(out.items.any { it.item.id == a.id }).isFalse() // left the grid
    }

    @Test fun app_already_in_a_dock_folder_dropped_onto_it_is_a_noop() {
        val f = folder("f", ck("pa"), ck("pb"))
        val a = app("a", "pa") // pa already a member of f
        val start = layout(items = listOf(PlacedItem(a, CellPos(0, 0, 0))), dock = listOf(f))
        val r = HomeLayoutTransition.move(start, a.id, DropTarget.DockItem(0), seq("unused")::next)
        assertThat(r).isEqualTo(MoveResult.NoOp)
    }

    @Test fun folder_onto_a_dock_folder_is_rejected() {
        val g = PlacedItem(folder("g", ck("pa"), ck("pb")), CellPos(0, 0, 0))
        val f = folder("f", ck("pc"), ck("pd"))
        val start = layout(items = listOf(g), dock = listOf(f))
        val r = HomeLayoutTransition.move(start, ItemId("g"), DropTarget.DockItem(0), seq("unused")::next)
        assertThat(r).isEqualTo(MoveResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE))
    }

    // ---------------- removeFromFolder(): move BETWEEN folders into the dock ----------------

    @Test fun move_member_into_a_dock_folder_shrinks_the_source() {
        val a = PlacedItem(folder("a", ck("pa"), ck("pb"), ck("pc")), CellPos(0, 0, 0)) // grid, >=3
        val b = folder("b", ck("pd"), ck("pe"))                                          // dock
        val start = layout(items = listOf(a), dock = listOf(b))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pb"), DropTarget.DockItem(0), seq("unused")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.MovedBetweenFolders::class.java)
        val moved = r as FolderEditResult.MovedBetweenFolders
        assertThat(moved.from).isEqualTo(ItemId("a"))
        assertThat(moved.to).isEqualTo(ItemId("b"))
        val out = r.layout
        val aOut = out.items.first { it.item.id == ItemId("a") }.item as HomeItem.Folder
        val bOut = out.dock.first { it.id == ItemId("b") } as HomeItem.Folder
        assertThat(aOut.members).containsExactly(ck("pa"), ck("pc")).inOrder()
        assertThat(bOut.members).containsExactly(ck("pd"), ck("pe"), ck("pb")).inOrder()
    }

    @Test fun move_last_pair_member_into_a_dock_folder_dissolves_the_source() {
        val a = PlacedItem(folder("a", ck("pa"), ck("pb")), CellPos(0, 2, 3)) // grid, exactly 2
        val b = folder("b", ck("pd"), ck("pe"))                                // dock
        val start = layout(items = listOf(a), dock = listOf(b))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pa"), DropTarget.DockItem(0), seq("survivor")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.MovedBetweenFoldersDissolve::class.java)
        val d = r as FolderEditResult.MovedBetweenFoldersDissolve
        assertThat(d.to).isEqualTo(ItemId("b"))
        assertThat(d.survivor).isEqualTo(ItemId("survivor"))
        val out = r.layout
        assertThat(out.items.any { it.item.id == ItemId("a") }).isFalse() // A retired
        val survivor = out.items.first { it.pos == CellPos(0, 2, 3) } // A's old cell
        assertThat((survivor.item as HomeItem.App).key).isEqualTo(ck("pb"))
        val bOut = out.dock.first { it.id == ItemId("b") } as HomeItem.Folder
        assertThat(bOut.members).containsExactly(ck("pd"), ck("pe"), ck("pa")).inOrder()
    }

    @Test fun move_into_a_dock_folder_that_already_has_the_member_is_a_noop() {
        val a = PlacedItem(folder("a", ck("pa"), ck("pb"), ck("pc")), CellPos(0, 0, 0))
        val b = folder("b", ck("pa"), ck("pd")) // already has pa
        val start = layout(items = listOf(a), dock = listOf(b))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pa"), DropTarget.DockItem(0), seq("unused")::next,
        )
        assertThat(r).isEqualTo(FolderEditResult.NoOp)
    }

    @Test fun extract_onto_a_dock_app_is_rejected() {
        val a = PlacedItem(folder("a", ck("pa"), ck("pb"), ck("pc")), CellPos(0, 0, 0))
        val occupant = app("d", "pz") // a plain dock app
        val start = layout(items = listOf(a), dock = listOf(occupant))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("a"), ck("pb"), DropTarget.DockItem(0), seq("unused")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.Rejected::class.java)
        assertThat((r as FolderEditResult.Rejected).reason)
            .isEqualTo(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE)
    }

    // ---------------- regression: dock-source shrink now works (replacingItem dock fix) ----------------

    @Test fun extract_from_a_big_dock_folder_shrinks_the_dock_folder() {
        // Pre-fix, replacingItem only rewrote `items`, so shrinking a >=3-member DOCK folder
        // silently no-oped. Extract pb from a dock folder onto an empty grid cell: the dock
        // folder must lose pb.
        val f = folder("f", ck("pa"), ck("pb"), ck("pc"))
        val start = layout(dock = listOf(f))
        val r = HomeLayoutTransition.removeFromFolder(
            start, ItemId("f"), ck("pb"), DropTarget.Cell(CellPos(0, 1, 1)), seq("extracted")::next,
        )
        assertThat(r).isInstanceOf(FolderEditResult.Extracted::class.java)
        val out = r.layout!!
        val fOut = out.dock.first { it.id == ItemId("f") } as HomeItem.Folder
        assertThat(fOut.members).containsExactly(ck("pa"), ck("pc")).inOrder() // pb removed
        val extracted = out.items.first { it.pos == CellPos(0, 1, 1) }
        assertThat((extracted.item as HomeItem.App).key).isEqualTo(ck("pb"))
    }
}
