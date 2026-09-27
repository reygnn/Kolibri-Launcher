package com.github.reygnn.nyx_launcher.home

import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the live-update entry points that let an OPEN folder overlay track changes instead of
 * snapshotting at open: [FolderMemberAdapter.updateInstalled] (re-grey/un-grey on install
 * changes, value-equal guarded) and [FolderMemberAdapter.refreshIcons] (re-decode on a style
 * change). Both must rebind the member range so the visible icons actually update.
 *
 * Robolectric so the real androidx RecyclerView.Adapter observable dispatches; the tested
 * methods never touch the icon loader (only onBind does), so it is a relaxed mock.
 */
@RunWith(RobolectricTestRunner::class)
class FolderMemberAdapterTest {

    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private val a = ck("com.a")
    private val b = ck("com.b")

    private fun adapter(installed: Set<ComponentKey>) = FolderMemberAdapter(
        iconLoader = mockk<IconLoader>(relaxed = true),
        scope = TestScope(),
        iconSizePx = 96,
        onLaunch = {},
        onStartDrag = { _, _ -> },
        installed = installed,
    )

    /** Counts adapter rebinds (notifyDataSetChanged + notifyItemRangeChanged). */
    private class RebindCounter : RecyclerView.AdapterDataObserver() {
        var rebinds = 0
        override fun onChanged() { rebinds++ }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { rebinds++ }
    }

    @Test
    fun `updateInstalled rebinds only when the installed set actually changes`() {
        val adapter = adapter(installed = setOf(a))
        adapter.submit(listOf(a, b))
        val counter = RebindCounter().also { adapter.registerAdapterDataObserver(it) }

        adapter.updateInstalled(setOf(a))          // unchanged → value-equal guard → no rebind
        assertThat(counter.rebinds).isEqualTo(0)

        adapter.updateInstalled(setOf(a, b))       // changed → rebind (un-greys b)
        assertThat(counter.rebinds).isEqualTo(1)

        adapter.updateInstalled(setOf(a, b))       // unchanged again → no rebind
        assertThat(counter.rebinds).isEqualTo(1)
    }

    @Test
    fun `refreshIcons rebinds the member range`() {
        val adapter = adapter(installed = emptySet())
        adapter.submit(listOf(a, b))
        val counter = RebindCounter().also { adapter.registerAdapterDataObserver(it) }

        adapter.refreshIcons()
        assertThat(counter.rebinds).isEqualTo(1)
    }

    @Test
    fun `refreshIconsFor rebinds only the matching member, not the rest`() {
        // F1 targeted repaint: an in-place icon update for com.a must re-decode only a's tile,
        // leaving b untouched (so the value-equal short-circuit's win is preserved).
        val adapter = adapter(installed = emptySet())
        adapter.submit(listOf(a, b))
        val counter = RebindCounter().also { adapter.registerAdapterDataObserver(it) }

        adapter.refreshIconsFor("com.a")   // one matching position → one item-change
        assertThat(counter.rebinds).isEqualTo(1)

        adapter.refreshIconsFor("com.z")   // no member matches → no rebind
        assertThat(counter.rebinds).isEqualTo(1)
    }
}
