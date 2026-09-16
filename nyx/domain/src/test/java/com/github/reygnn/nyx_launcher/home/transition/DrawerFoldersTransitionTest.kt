package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DrawerDropTarget
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the pure drawer transitions (DRAWER_FOLDERS_SPEC §8): create / add / extract
 * over [DrawerFolders], the DFOLD-INV-3 "at most one drawer folder per app" rule, and
 * the DFOLD-INV-1 auto-dissolve. No placement, no ids beyond the injected factory.
 */
class DrawerFoldersTransitionTest {

    private fun key(p: String) = ComponentKey(p, "$p.Main")
    private fun folder(id: String, vararg members: String) =
        DrawerFolder(DrawerFolderId(id), "", members.map { key(it) })
    private fun folders(vararg f: DrawerFolder) = DrawerFolders(f.toList())
    private val newId = { DrawerFolderId("new") }

    // ---- drop: OntoApp (create) ----

    @Test
    fun `OntoApp creates a folder, target first then dragged`() {
        val result = DrawerFoldersTransition.drop(
            DrawerFolders.EMPTY, source = key("a"), DrawerDropTarget.OntoApp(key("b")), newId,
        )
        assertThat(result).isEqualTo(folders(DrawerFolder(DrawerFolderId("new"), "", listOf(key("b"), key("a")))))
    }

    @Test
    fun `OntoApp onto itself is a no-op`() {
        val result = DrawerFoldersTransition.drop(
            DrawerFolders.EMPTY, source = key("a"), DrawerDropTarget.OntoApp(key("a")), newId,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `creating a folder strips both apps from an existing folder (INV-3)`() {
        // f1 = [a, x, y]; folding b onto a must pull a out of f1 (which survives on x,y).
        val result = DrawerFoldersTransition.drop(
            folders(folder("f1", "a", "x", "y")),
            source = key("b"), DrawerDropTarget.OntoApp(key("a")), newId,
        )
        assertThat(result).isEqualTo(
            folders(
                folder("f1", "x", "y"),
                DrawerFolder(DrawerFolderId("new"), "", listOf(key("a"), key("b"))),
            ),
        )
    }

    // ---- drop: OntoFolder (add) ----

    @Test
    fun `OntoFolder adds the source as a member`() {
        val result = DrawerFoldersTransition.drop(
            folders(folder("f1", "b", "c")),
            source = key("a"), DrawerDropTarget.OntoFolder(DrawerFolderId("f1")), newId,
        )
        assertThat(result).isEqualTo(folders(folder("f1", "b", "c", "a")))
    }

    @Test
    fun `OntoFolder for an app already in that folder is a no-op`() {
        val result = DrawerFoldersTransition.drop(
            folders(folder("f1", "b", "a")),
            source = key("a"), DrawerDropTarget.OntoFolder(DrawerFolderId("f1")), newId,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `OntoFolder with an unknown folder is a no-op`() {
        val result = DrawerFoldersTransition.drop(
            folders(folder("f1", "b", "c")),
            source = key("a"), DrawerDropTarget.OntoFolder(DrawerFolderId("nope")), newId,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `adding to a folder moves the app out of its previous folder (INV-3)`() {
        // f1 = [a, b, x] survives losing a; f2 gains a.
        val result = DrawerFoldersTransition.drop(
            folders(folder("f1", "a", "b", "x"), folder("f2", "c", "d")),
            source = key("a"), DrawerDropTarget.OntoFolder(DrawerFolderId("f2")), newId,
        )
        assertThat(result).isEqualTo(folders(folder("f1", "b", "x"), folder("f2", "c", "d", "a")))
    }

    @Test
    fun `moving the app out of its previous folder dissolves it when it drops below two (INV-3 plus INV-1)`() {
        // f1 = [a, b] dissolves when a leaves (b goes loose); f2 gains a.
        val result = DrawerFoldersTransition.drop(
            folders(folder("f1", "a", "b"), folder("f2", "c", "d")),
            source = key("a"), DrawerDropTarget.OntoFolder(DrawerFolderId("f2")), newId,
        )
        assertThat(result).isEqualTo(folders(folder("f2", "c", "d", "a")))
    }

    // ---- extract ----

    @Test
    fun `extract shrinks a folder with three or more members`() {
        val result = DrawerFoldersTransition.extract(folders(folder("f1", "a", "b", "c")), DrawerFolderId("f1"), key("b"))
        assertThat(result).isEqualTo(folders(folder("f1", "a", "c")))
    }

    @Test
    fun `extract dissolves a two-member folder`() {
        val result = DrawerFoldersTransition.extract(folders(folder("f1", "a", "b")), DrawerFolderId("f1"), key("a"))
        assertThat(result).isEqualTo(DrawerFolders.EMPTY)
    }

    @Test
    fun `extract from an unknown folder is a no-op`() {
        val result = DrawerFoldersTransition.extract(folders(folder("f1", "a", "b")), DrawerFolderId("nope"), key("a"))
        assertThat(result).isNull()
    }

    @Test
    fun `extract of a non-member is a no-op`() {
        val result = DrawerFoldersTransition.extract(folders(folder("f1", "a", "b")), DrawerFolderId("f1"), key("c"))
        assertThat(result).isNull()
    }

    // ---- rename ----

    @Test
    fun `rename sets a new title without touching id or members (INV-5)`() {
        val before = folders(folder("f1", "a", "b"))
        val result = DrawerFoldersTransition.rename(before, DrawerFolderId("f1"), "Work")
        val renamed = result!!.folders.single()
        assertThat(renamed.title).isEqualTo("Work")
        assertThat(renamed.id).isEqualTo(DrawerFolderId("f1"))
        assertThat(renamed.members).containsExactly(key("a"), key("b")).inOrder()
    }

    @Test
    fun `rename to the same title is a no-op`() {
        val before = folders(DrawerFolder(DrawerFolderId("f1"), "Work", listOf(key("a"), key("b"))))
        assertThat(DrawerFoldersTransition.rename(before, DrawerFolderId("f1"), "Work")).isNull()
    }

    @Test
    fun `rename of an unknown folder is a no-op`() {
        val before = folders(folder("f1", "a", "b"))
        assertThat(DrawerFoldersTransition.rename(before, DrawerFolderId("nope"), "X")).isNull()
    }
}
