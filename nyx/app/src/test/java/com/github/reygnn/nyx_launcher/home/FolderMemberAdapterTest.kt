package com.github.reygnn.nyx_launcher.home

import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
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

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun ck(p: String) = ComponentKey.of(p, "$p.Main")
    private val a = ck("com.a")
    private val b = ck("com.b")

    private fun adapter(installed: Set<ComponentKey>) = FolderMemberAdapter(
        iconLoader = mockk<IconLoader>(relaxed = true),
        scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        iconSizePx = 96,
        onLaunch = {},
        onStartDrag = { _, _ -> },
        installed = installed,
    )

    /**
     * Records adapter rebinds. [rebinds] counts dispatches (as before); [changed] records the
     * exact positions of every onItemRangeChanged so a targeted repaint can be distinguished from
     * a blanket range (a collapsing counter would pass for both).
     */
    private class RebindCounter : RecyclerView.AdapterDataObserver() {
        var rebinds = 0
        val changed = mutableListOf<Int>()
        override fun onChanged() { rebinds++ }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            rebinds++
            for (i in positionStart until positionStart + itemCount) changed += i
        }
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
    fun `submit skips the rebind when the members are unchanged`() {
        // §Audit-2 N11: value-equal short-circuit mirroring DockAdapter.submit — re-submitting the
        // same members must not re-decode every tile; a changed list still rebinds.
        val adapter = adapter(installed = emptySet())
        val counter = RebindCounter().also { adapter.registerAdapterDataObserver(it) }

        adapter.submit(listOf(a, b))  // first content → rebind
        assertThat(counter.rebinds).isEqualTo(1)
        adapter.submit(listOf(a, b))  // identical → no rebind
        assertThat(counter.rebinds).isEqualTo(1)
        adapter.submit(listOf(a))     // changed → rebind
        assertThat(counter.rebinds).isEqualTo(2)
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
    fun `refreshIconsFor rebinds only the matching member position, not the whole range`() {
        // F1 targeted repaint: an in-place icon update for com.a must re-decode ONLY a's tile
        // (position 0), leaving b untouched — so the value-equal short-circuit's win is preserved.
        // Asserting the exact changed position (not just a count) rules out a blanket-range
        // regression: notifyItemRangeChanged(0, size) would also give rebinds == 1.
        val adapter = adapter(installed = emptySet())
        adapter.submit(listOf(a, b))
        val counter = RebindCounter().also { adapter.registerAdapterDataObserver(it) }

        adapter.refreshIconsFor("com.a")            // members = [a, b] → only position 0
        assertThat(counter.changed).containsExactly(0)

        adapter.refreshIconsFor("com.z")            // no member matches → no change at all
        assertThat(counter.changed).containsExactly(0) // unchanged from before
    }
}
