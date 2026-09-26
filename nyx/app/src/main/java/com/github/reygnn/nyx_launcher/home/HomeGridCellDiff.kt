package com.github.reygnn.nyx_launcher.home

import androidx.recyclerview.widget.DiffUtil

/**
 * Pure DiffUtil policy for the home grid ([HomeGridAdapter.submit]). Extracted from the
 * adapter (Rule 10, mirroring [com.github.reygnn.nyx_launcher.home.drawer.DrawerEntryDiff])
 * so the diff semantics are unit-testable on the JVM without an Android framework —
 * [DiffUtil.calculateDiff] is a pure algorithm.
 *
 * POSITIONAL identity (NOT content): a slot IS its position, so DiffUtil computes NO moves
 * (which would fight the drag-view bridge in MainActivity.renderLayout) — only the cells
 * whose [HomeCell] value actually changed are rebound. This is the win for the no-prune
 * "missing" model: an uninstall flips one [HomeCell.App] `missing` flag and only that one
 * tile re-decodes/greys, instead of a `notifyDataSetChanged` re-decoding the whole page.
 *
 * The callback assumes EQUAL sizes: the grid is a dense, fixed-size list whose length is
 * constant for the life of an adapter instance, so [HomeGridAdapter.submit] handles the
 * only length change (the first submit from the empty list) with a full rebind before ever
 * reaching this callback. Contents equality is plain data-class `==`.
 */
object HomeGridCellDiff {

    fun sameItem(oldPosition: Int, newPosition: Int): Boolean = oldPosition == newPosition

    fun sameContents(old: HomeCell, new: HomeCell): Boolean = old == new

    fun callback(old: List<HomeCell>, new: List<HomeCell>): DiffUtil.Callback =
        object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = new.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                sameItem(oldItemPosition, newItemPosition)
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                sameContents(old[oldItemPosition], new[newItemPosition])
        }
}
