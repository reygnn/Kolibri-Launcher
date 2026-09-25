package com.github.reygnn.nyx_launcher.home.drawer

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DrawerEntry
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the drawer diff policy ([DrawerEntryDiff]) — the reason [AppDrawerAdapter.submit]
 * is cheap under frequent use / search-as-you-type: survivors are moved/kept (icons not
 * re-decoded), and only genuinely new-or-changed rows bind.
 *
 * Pure JVM: [DiffUtil.calculateDiff] is a plain algorithm with no Android framework and
 * no coroutines, so per the project convention this test correctly carries NO
 * `MainDispatcherRule` — the rule exists only for dispatcher-touching tests
 * (TESTING_CONVENTIONS.kt: "every test that doesn't have it doesn't need it").
 *
 * Mutation-checked: reverting [DrawerEntryDiff.sameItem] to positional/whole-entry
 * identity turns [hidden flip is a change, not remove+insert] and
 * [a reorder moves survivors, never rebinds them] red.
 */
class DrawerEntryDiffTest {

    // --- fixtures ---

    private fun app(pkg: String, hidden: Boolean = false, label: String = pkg): DrawerEntry.App =
        DrawerEntry.App(LauncherApp(ComponentKey(pkg, "$pkg.Main"), label), hidden)

    private fun folder(id: String, title: String = id): DrawerEntry.Folder =
        DrawerEntry.Folder(DrawerFolderId(id), title, members = emptyList())

    private class RecordingUpdateCallback : ListUpdateCallback {
        val inserted = mutableListOf<Pair<Int, Int>>()
        val removed = mutableListOf<Pair<Int, Int>>()
        val moved = mutableListOf<Pair<Int, Int>>()
        val changed = mutableListOf<Pair<Int, Int>>()
        override fun onInserted(position: Int, count: Int) { inserted += position to count }
        override fun onRemoved(position: Int, count: Int) { removed += position to count }
        override fun onMoved(fromPosition: Int, toPosition: Int) { moved += fromPosition to toPosition }
        override fun onChanged(position: Int, count: Int, payload: Any?) { changed += position to count }
    }

    private fun diff(old: List<DrawerEntry>, new: List<DrawerEntry>): RecordingUpdateCallback {
        val rec = RecordingUpdateCallback()
        // detectMoves = true (default) so a reorder is a move, not remove+insert.
        DiffUtil.calculateDiff(DrawerEntryDiff.callback(old, new)).dispatchUpdatesTo(rec)
        return rec
    }

    // --- tests ---

    @Test
    fun `search filter removes the filtered row and does not rebind the survivors`() {
        val old = listOf(app("com.a"), app("com.b"), app("com.c"))
        val new = listOf(app("com.a"), app("com.c")) // "com.b" filtered out by the query

        val rec = diff(old, new)

        assertEquals(listOf(1 to 1), rec.removed) // exactly the filtered row is removed
        assertTrue("survivors must not rebind", rec.changed.isEmpty())
        assertTrue(rec.inserted.isEmpty())
    }

    @Test
    fun `hidden flip is a change, not remove+insert`() {
        val old = listOf(app("com.a", hidden = false))
        val new = listOf(app("com.a", hidden = true)) // same key → same item, contents differ

        val rec = diff(old, new)

        assertEquals(listOf(0 to 1), rec.changed)
        assertTrue(rec.removed.isEmpty())
        assertTrue(rec.inserted.isEmpty())
    }

    @Test
    fun `a rename is a change on the same row`() {
        val old = listOf(app("com.a", label = "Alpha"))
        val new = listOf(app("com.a", label = "Beta")) // same key, new label

        val rec = diff(old, new)

        assertEquals(listOf(0 to 1), rec.changed)
        assertTrue(rec.removed.isEmpty() && rec.inserted.isEmpty())
    }

    @Test
    fun `a reorder moves survivors, never rebinds them`() {
        val old = listOf(app("com.a"), app("com.b"))
        val new = listOf(app("com.b"), app("com.a")) // swapped

        val rec = diff(old, new)

        assertTrue("a reorder must produce a move", rec.moved.isNotEmpty())
        assertTrue("moved rows keep their decoded icon (no rebind)", rec.changed.isEmpty())
        assertTrue(rec.inserted.isEmpty() && rec.removed.isEmpty())
    }

    @Test
    fun `an app and a folder are never treated as the same row`() {
        val old = listOf<DrawerEntry>(folder("f1"))
        val new = listOf<DrawerEntry>(app("com.a"))

        val rec = diff(old, new)

        // Cross-type: the folder is removed and the app inserted — never a content change.
        assertTrue("cross-type rows must never be a content change", rec.changed.isEmpty())
        assertEquals(1, rec.removed.sumOf { it.second })
        assertEquals(1, rec.inserted.sumOf { it.second })
    }
}
