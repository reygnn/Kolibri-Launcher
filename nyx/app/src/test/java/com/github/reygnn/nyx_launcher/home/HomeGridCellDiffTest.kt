package com.github.reygnn.nyx_launcher.home

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the home-grid diff policy ([HomeGridCellDiff]) — the reason [HomeGridAdapter.submit]
 * only rebinds the tiles that actually changed (e.g. an uninstall flips one
 * [HomeCell.App] `missing` flag and only that one tile re-decodes/greys), instead of a
 * `notifyDataSetChanged` re-decoding the whole page.
 *
 * Pure JVM: [DiffUtil.calculateDiff] is a plain algorithm with no Android framework and no
 * coroutines, so per the project convention this test correctly carries NO
 * `MainDispatcherRule` — the rule exists only for dispatcher-touching tests.
 *
 * POSITIONAL identity: `areItemsTheSame` is `oldPos == newPos`, so DiffUtil never emits a
 * move (which would fight the drag-view bridge in MainActivity.renderLayout) — a changed
 * cell is an `onChanged` at that position, never a remove+insert or a move.
 *
 * Sizes: the callback is only ever exercised on EQUAL-size lists — the grid is a dense,
 * fixed-size list and [HomeGridAdapter.submit] handles the sole length change (the first
 * submit from the empty list) with a full rebind before this callback is reached. These
 * tests therefore drive it only with equal sizes.
 */
class HomeGridCellDiffTest {

    // --- fixtures ---

    private fun app(pkg: String, missing: Boolean = false): HomeCell.App =
        HomeCell.App(ItemId(pkg), ComponentKey(pkg, "$pkg.Main"), missing)

    private val empty: HomeCell = HomeCell.Empty

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

    private fun diff(old: List<HomeCell>, new: List<HomeCell>): RecordingUpdateCallback {
        val rec = RecordingUpdateCallback()
        // detectMoves = true (default): proves positional identity produces NO moves anyway.
        DiffUtil.calculateDiff(HomeGridCellDiff.callback(old, new)).dispatchUpdatesTo(rec)
        return rec
    }

    // --- tests ---

    @Test
    fun `same list reports no content changes at any position`() {
        val old = listOf(app("com.a"), empty, app("com.c"))
        val new = listOf(app("com.a"), empty, app("com.c")) // structurally equal

        val callback = HomeGridCellDiff.callback(old, new)
        for (i in old.indices) {
            assertThat(callback.areItemsTheSame(i, i)).isTrue()
            assertThat(callback.areContentsTheSame(i, i)).isTrue()
        }

        val rec = diff(old, new)
        assertThat(rec.changed).isEmpty()
        assertThat(rec.inserted).isEmpty()
        assertThat(rec.removed).isEmpty()
        assertThat(rec.moved).isEmpty()
    }

    @Test
    fun `a single missing-flip is a change only at that position, never a move`() {
        val old = listOf(app("com.a"), app("com.b", missing = false), app("com.c"))
        val new = listOf(app("com.a"), app("com.b", missing = true), app("com.c")) // one flip at pos 1

        val callback = HomeGridCellDiff.callback(old, new)
        // areItemsTheSame is positional → true everywhere (no moves possible).
        for (i in old.indices) {
            assertThat(callback.areItemsTheSame(i, i)).isTrue()
        }
        // Contents differ ONLY at the flipped position.
        assertThat(callback.areContentsTheSame(0, 0)).isTrue()
        assertThat(callback.areContentsTheSame(1, 1)).isFalse()
        assertThat(callback.areContentsTheSame(2, 2)).isTrue()

        val rec = diff(old, new)
        assertThat(rec.changed).containsExactly(1 to 1)
        assertThat(rec.moved).isEmpty()
        assertThat(rec.inserted).isEmpty()
        assertThat(rec.removed).isEmpty()
    }

    @Test
    fun `list sizes are reported straight through`() {
        // The callback itself is size-agnostic; the adapter's early-return guarantees equal
        // sizes at the call site, so we only assert the sizes it reports for an equal pair.
        val old = listOf(app("com.a"), empty)
        val new = listOf(app("com.a"), empty)

        val callback = HomeGridCellDiff.callback(old, new)
        assertThat(callback.oldListSize).isEqualTo(2)
        assertThat(callback.newListSize).isEqualTo(2)
    }
}
