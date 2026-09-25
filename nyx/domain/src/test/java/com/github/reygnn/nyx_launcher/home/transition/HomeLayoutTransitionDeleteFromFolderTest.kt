package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.LayoutEdit
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [HomeLayoutTransition.deleteFromFolder] — deleting a DEAD folder member with NO placement
 * (the folder-internal analog of removing a missing top-level tile). Pure JVM. Membership
 * policy (shrink / dissolve / not-a-member) is the shared [FolderMembership]; these pin that
 * delete applies it WITHOUT relocating the removed member anywhere.
 */
class HomeLayoutTransitionDeleteFromFolderTest {

    private val grid = GridSpec(columns = 4, rows = 6)

    private fun ck(pkg: String) = ComponentKey(pkg, "$pkg.Main")
    private fun folder(id: String, vararg m: ComponentKey, page: Int, x: Int, y: Int) =
        PlacedItem(HomeItem.Folder(ItemId(id), "", m.toList()), CellPos(page, x, y))
    private fun app(id: String, pkg: String, page: Int, x: Int, y: Int) =
        PlacedItem(HomeItem.App(ItemId(id), ck(pkg)), CellPos(page, x, y))
    private fun layout(items: List<PlacedItem> = emptyList(), pages: Int = 1) =
        HomeLayout(grid, pages, items, dock = emptyList())

    /** An id factory that fails if asked for an id it wasn't given — pins that delete mints
     *  no id in the shrink / no-op cases, and exactly the survivor id on dissolve. */
    private fun seq(vararg ids: String): ItemIdFactory {
        val it = ids.iterator(); return ItemIdFactory { ItemId(it.next()) }
    }

    @Test fun delete_from_big_folder_shrinks_it_without_placing_the_member() {
        val start = layout(listOf(folder("f", ck("pa"), ck("pb"), ck("pc"), page = 0, x = 0, y = 0)))

        val r = HomeLayoutTransition.deleteFromFolder(start, ItemId("f"), ck("pb"), seq()::next)

        assertThat(r).isInstanceOf(LayoutEdit.Changed::class.java)
        val out = r.layout!!
        val fOut = out.items.first { it.item.id == ItemId("f") }.item as HomeItem.Folder
        assertThat(fOut.members).containsExactly(ck("pa"), ck("pc")).inOrder() // pb gone, order kept
        // The deleted member is discarded, never promoted to a top-level tile.
        assertThat(out.items.any { (it.item as? HomeItem.App)?.key == ck("pb") }).isFalse()
    }

    @Test fun delete_from_two_member_folder_dissolves_and_promotes_survivor() {
        val start = layout(listOf(folder("f", ck("pa"), ck("pb"), page = 0, x = 2, y = 3)))

        val r = HomeLayoutTransition.deleteFromFolder(start, ItemId("f"), ck("pa"), seq("survivor")::next)

        assertThat(r).isInstanceOf(LayoutEdit.Changed::class.java)
        val out = r.layout!!
        assertThat(out.items.any { it.item.id == ItemId("f") }).isFalse() // folder gone
        val survivor = out.items.first { it.pos == CellPos(0, 2, 3) } // folder's old cell
        assertThat((survivor.item as HomeItem.App).key).isEqualTo(ck("pb"))
        assertThat(survivor.item.id).isEqualTo(ItemId("survivor"))
        // The deleted member (pa) is discarded, not placed.
        assertThat(out.items.any { (it.item as? HomeItem.App)?.key == ck("pa") }).isFalse()
    }

    @Test fun dissolve_reuses_an_existing_top_level_tile_for_the_survivor() {
        // Survivor pb is ALSO already a top-level tile → no duplicate minted (scoped IHM-INV-7),
        // so the id factory is never asked (seq has no ids to give).
        val start = layout(
            listOf(
                folder("f", ck("pa"), ck("pb"), page = 0, x = 2, y = 3),
                app("pbTile", "pb", page = 0, x = 0, y = 0),
            ),
        )

        val r = HomeLayoutTransition.deleteFromFolder(start, ItemId("f"), ck("pa"), seq()::next)

        assertThat(r).isInstanceOf(LayoutEdit.Changed::class.java)
        val out = r.layout!!
        assertThat(out.items.any { it.item.id == ItemId("f") }).isFalse()
        val pbTiles = out.items.filter { (it.item as? HomeItem.App)?.key == ck("pb") }
        assertThat(pbTiles).hasSize(1) // the pre-existing tile, reused
        assertThat(pbTiles.first().item.id).isEqualTo(ItemId("pbTile"))
    }

    @Test fun deleting_a_non_member_is_a_noop() {
        val start = layout(listOf(folder("f", ck("pa"), ck("pb"), page = 0, x = 0, y = 0)))

        val r = HomeLayoutTransition.deleteFromFolder(start, ItemId("f"), ck("nope"), seq()::next)

        assertThat(r).isEqualTo(LayoutEdit.NoOp)
    }

    @Test fun deleting_from_a_non_folder_id_is_a_noop() {
        val start = layout(listOf(app("a", "pa", page = 0, x = 0, y = 0)))

        val r = HomeLayoutTransition.deleteFromFolder(start, ItemId("a"), ck("pa"), seq()::next)

        assertThat(r).isEqualTo(LayoutEdit.NoOp)
    }
}
