package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.LayoutEdit
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** HOME_EDIT_USECASES_SPEC — place / remove / renameFolder. Pure JVM. */
class HomeLayoutTransitionEditTest {

    private val grid = GridSpec(columns = 4, rows = 6)

    private fun ck(pkg: String) = ComponentKey(pkg, "$pkg.Main")
    private fun app(id: String, pkg: String = id) = HomeItem.App(ItemId(id), ck(pkg))
    private fun folder(id: String, vararg m: ComponentKey) = HomeItem.Folder(ItemId(id), "", m.toList())
    private fun placed(item: HomeItem, page: Int, x: Int, y: Int) = PlacedItem(item, CellPos(page, x, y))
    private fun layout(items: List<PlacedItem> = emptyList(), dock: List<HomeItem> = emptyList(), pages: Int = 1) =
        HomeLayout(grid, pages, items, dock)
    private fun seq(vararg ids: String): ItemIdFactory {
        val it = ids.iterator(); return ItemIdFactory { ItemId(it.next()) }
    }

    // ---- place ----

    @Test fun place_new_app_on_empty_cell_creates_item() {
        val start = layout()
        val r = HomeLayoutTransition.place(start, ck("pa"), DropTarget.Cell(CellPos(0, 1, 1)), seq("new")::next)
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.items).hasSize(1)
        assertThat(out.items.single().item.id).isEqualTo(ItemId("new"))
        assertThat((out.items.single().item as HomeItem.App).key).isEqualTo(ck("pa"))
    }

    @Test fun place_new_app_onto_app_creates_folder() {
        val b = app("b", "pb")
        val start = layout(items = listOf(placed(b, 0, 1, 0)))
        val r = HomeLayoutTransition.place(start, ck("pc"), DropTarget.Cell(CellPos(0, 1, 0)), seq("freshApp", "folder")::next)
        assertThat(r).isInstanceOf(MoveResult.FolderCreated::class.java)
        val fc = r as MoveResult.FolderCreated
        assertThat(fc.folder).isEqualTo(ItemId("folder"))
        val f = fc.layout!!.items.single().item as HomeItem.Folder
        assertThat(f.members).containsExactly(ck("pb"), ck("pc")).inOrder()
    }

    @Test fun place_new_app_onto_folder_adds_member() {
        val f = folder("f", ck("pa"), ck("pb"))
        val start = layout(items = listOf(placed(f, 0, 0, 0)))
        val r = HomeLayoutTransition.place(start, ck("pc"), DropTarget.Cell(CellPos(0, 0, 0)), seq("freshApp")::next)
        assertThat(r).isInstanceOf(MoveResult.AddedToFolder::class.java)
        val fOut = r.layout!!.items.single().item as HomeItem.Folder
        assertThat(fOut.members).containsExactly(ck("pa"), ck("pb"), ck("pc")).inOrder()
    }

    @Test fun place_already_placed_app_moves_it_without_duplicating() {
        val a = app("a", "pa")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = HomeLayoutTransition.place(start, ck("pa"), DropTarget.Cell(CellPos(0, 2, 2)), seq("unused")::next)
        assertThat(r).isInstanceOf(MoveResult.Moved::class.java)
        val out = r.layout!!
        assertThat(out.items).hasSize(1) // moved, not duplicated (HEU-INV-1)
        assertThat(out.items.single().pos).isEqualTo(CellPos(0, 2, 2))
        assertThat(out.items.single().item.id).isEqualTo(ItemId("a")) // same id
    }

    @Test fun place_app_that_is_a_folder_member_is_noop() {
        val f = folder("f", ck("pa"), ck("pb"))
        val start = layout(items = listOf(placed(f, 0, 0, 0)))
        val r = HomeLayoutTransition.place(start, ck("pa"), DropTarget.Cell(CellPos(0, 1, 1)), seq("unused")::next)
        assertThat(r).isEqualTo(MoveResult.NoOp) // uniqueness: no duplicate
    }

    // ---- remove ----

    @Test fun remove_app_frees_its_cell() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = HomeLayoutTransition.remove(start, a.id)
        assertThat(r).isInstanceOf(LayoutEdit.Changed::class.java)
        assertThat(r.layout!!.items).isEmpty()
    }

    @Test fun remove_folder_drops_only_the_folder_item() {
        val f = folder("f", ck("pa"), ck("pb"))
        val start = layout(items = listOf(placed(f, 0, 0, 0)))
        val r = HomeLayoutTransition.remove(start, f.id)
        assertThat(r).isInstanceOf(LayoutEdit.Changed::class.java)
        assertThat(r.layout!!.items).isEmpty() // members were apps, not lost (HEU-INV-2)
    }

    @Test fun remove_unknown_id_is_noop() {
        val start = layout(items = listOf(placed(app("a"), 0, 0, 0)))
        val r = HomeLayoutTransition.remove(start, ItemId("ghost"))
        assertThat(r).isEqualTo(LayoutEdit.NoOp)
    }

    // ---- renameFolder ----

    @Test fun rename_folder_sets_title() {
        val f = folder("f", ck("pa"), ck("pb"))
        val start = layout(items = listOf(placed(f, 0, 0, 0)))
        val r = HomeLayoutTransition.renameFolder(start, f.id, "Games")
        assertThat(r).isInstanceOf(LayoutEdit.Changed::class.java)
        val fOut = r.layout!!.items.single().item as HomeItem.Folder
        assertThat(fOut.title).isEqualTo("Games")
    }

    @Test fun rename_folder_same_title_is_noop() {
        val f = HomeItem.Folder(ItemId("f"), "Games", listOf(ck("pa"), ck("pb")))
        val start = layout(items = listOf(placed(f, 0, 0, 0)))
        val r = HomeLayoutTransition.renameFolder(start, f.id, "Games")
        assertThat(r).isEqualTo(LayoutEdit.NoOp)
    }

    @Test fun rename_non_folder_id_is_noop() {
        val a = app("a")
        val start = layout(items = listOf(placed(a, 0, 0, 0)))
        val r = HomeLayoutTransition.renameFolder(start, a.id, "Nope")
        assertThat(r).isEqualTo(LayoutEdit.NoOp)
    }
}
