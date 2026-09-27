package com.github.reygnn.nyx_launcher.home

import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [HomeGridAdapter.refreshIconsFor] (F1 targeted icon repaint): re-decode ONLY the cells that
 * render the changed package (an app cell of that package, or a folder cell whose present members
 * include it), and nothing on a non-match. The exact changed positions are asserted so a
 * blanket-range regression (re-decode the whole page) can't pass. The observer is registered AFTER
 * the initial submit so its DiffUtil inserts aren't counted.
 *
 * Robolectric for the real RecyclerView.Adapter observable; the loader is a relaxed mock (only
 * onBind touches it).
 */
@RunWith(RobolectricTestRunner::class)
class HomeGridAdapterTest {

    private fun ck(p: String) = ComponentKey(p, "$p.Main")

    private fun adapter() = HomeGridAdapter(
        iconLoader = mockk<IconLoader>(relaxed = true),
        folderRenderer = mockk<FolderIconRenderer>(relaxed = true),
        scope = TestScope(),
        iconSizePx = 96,
        rows = 5,
        dotPackages = { emptySet() },
        onLaunch = {},
        onOpenFolder = {},
        onIconLongPress = { _, _ -> },
        onMissingApp = { _, _ -> },
    )

    private class ChangeRecorder : RecyclerView.AdapterDataObserver() {
        val changed = mutableListOf<Int>()
        override fun onChanged() { changed += FULL_REBIND }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            for (i in positionStart until positionStart + itemCount) changed += i
        }
        companion object { const val FULL_REBIND = -1 }
    }

    @Test
    fun `refreshIconsFor repaints only the matching app cell`() {
        val adapter = adapter()
        adapter.submit(
            listOf(
                HomeCell.App(ItemId("1"), ck("com.a")),
                HomeCell.App(ItemId("2"), ck("com.b")),
            )
        )
        val rec = ChangeRecorder().also { adapter.registerAdapterDataObserver(it) }

        adapter.refreshIconsFor("com.b")
        assertThat(rec.changed).containsExactly(1) // position 1 only, NOT a blanket [0, 1]
    }

    @Test
    fun `refreshIconsFor repaints a folder cell that renders the package via a present member`() {
        val adapter = adapter()
        adapter.submit(
            listOf(
                HomeCell.Folder(ItemId("f"), members = listOf(ck("com.a"), ck("com.b"))),
                HomeCell.App(ItemId("2"), ck("com.c")),
            )
        )
        val rec = ChangeRecorder().also { adapter.registerAdapterDataObserver(it) }

        adapter.refreshIconsFor("com.a") // present folder member → the folder composite re-renders
        assertThat(rec.changed).containsExactly(0) // the folder tile, not the app cell
    }

    @Test
    fun `refreshIconsFor is a no-op for a package no cell renders`() {
        val adapter = adapter()
        adapter.submit(listOf(HomeCell.App(ItemId("1"), ck("com.a")), HomeCell.Empty))
        val rec = ChangeRecorder().also { adapter.registerAdapterDataObserver(it) }

        adapter.refreshIconsFor("com.z")
        assertThat(rec.changed).isEmpty()
    }
}
