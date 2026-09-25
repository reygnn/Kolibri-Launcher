package com.github.reygnn.nyx_launcher.home.drawer

import androidx.recyclerview.widget.DiffUtil
import com.github.reygnn.nyx_launcher.home.model.DrawerEntry

/**
 * Pure DiffUtil policy for the drawer list ([AppDrawerAdapter.submit]). Extracted from
 * the adapter (Rule 10) so the diff semantics are unit-testable on the JVM without an
 * Android framework — [DiffUtil.calculateDiff] is a pure algorithm.
 *
 * CONTENT identity (NOT positional): an [DrawerEntry.App] is the same item across
 * submits iff its [com.github.reygnn.launcher.core.ComponentKey] matches, a
 * [DrawerEntry.Folder] iff its id matches; the two types are never the same item. This
 * is what makes search-as-you-type cheap: a keystroke that filters the list emits
 * inserts/removes for the rows that appeared/disappeared and MOVES for the survivors —
 * a move does not rebind, so an unchanged row keeps its already-decoded icon instead of
 * re-decoding on a `notifyDataSetChanged` full rebind. A hidden-flag flip or a rename is
 * a CHANGE on the same item (same key), never a remove+insert.
 *
 * Contents equality is plain data-class `==`: [DrawerEntry.App] compares its
 * [com.github.reygnn.nyx_launcher.home.model.LauncherApp] (label / customName / key) and
 * its `hidden` flag; [DrawerEntry.Folder] compares id, title and members. Notification
 * dots are NOT part of it — they ride the separate dot-only payload path in the adapter.
 */
object DrawerEntryDiff {

    fun sameItem(a: DrawerEntry, b: DrawerEntry): Boolean = when {
        a is DrawerEntry.App && b is DrawerEntry.App -> a.app.key == b.app.key
        a is DrawerEntry.Folder && b is DrawerEntry.Folder -> a.id == b.id
        else -> false // App vs Folder (or any cross-type) are never the same item
    }

    fun sameContents(a: DrawerEntry, b: DrawerEntry): Boolean = a == b

    fun callback(old: List<DrawerEntry>, new: List<DrawerEntry>): DiffUtil.Callback =
        object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = new.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                sameItem(old[oldItemPosition], new[newItemPosition])
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                sameContents(old[oldItemPosition], new[newItemPosition])
        }
}
