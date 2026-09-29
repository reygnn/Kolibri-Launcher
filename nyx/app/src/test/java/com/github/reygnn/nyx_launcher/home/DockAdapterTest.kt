package com.github.reygnn.nyx_launcher.home

import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [DockAdapter.refreshIconsFor] (F1 targeted icon repaint): an in-place icon update must
 * re-decode ONLY the tiles that render the changed package — an app cell of that package, or a
 * folder cell whose present members include it — and be a no-op otherwise. Recording the exact
 * changed positions (not just a count) rules out a blanket-range regression that would re-decode
 * every dock icon yet still "pass" a collapsing counter.
 *
 * Robolectric so the real androidx RecyclerView.Adapter observable dispatches; the tested method
 * never touches the icon loader (only onBind does), so it is a relaxed mock.
 */
@RunWith(RobolectricTestRunner::class)
class DockAdapterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun ck(p: String) = ComponentKey(p, "$p.Main")

    private fun adapter() = DockAdapter(
        iconLoader = mockk<IconLoader>(relaxed = true),
        folderRenderer = mockk<FolderIconRenderer>(relaxed = true),
        scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        iconSizePx = 96,
        onLaunch = {},
        onOpenFolder = {},
        onIconLongPress = { _, _ -> },
        onMissingApp = { _, _ -> },
    )

    /** Records the exact positions reported by onItemRangeChanged (targeted vs blanket). */
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

        adapter.refreshIconsFor("com.a")
        assertThat(rec.changed).containsExactly(0) // position 0 only, NOT a blanket [0, 1]
    }

    @Test
    fun `refreshIconsFor repaints a folder cell that renders the package via a present member`() {
        val adapter = adapter()
        adapter.submit(
            listOf(
                HomeCell.App(ItemId("1"), ck("com.a")),
                HomeCell.Folder(ItemId("f"), members = listOf(ck("com.b"), ck("com.c"))),
            )
        )
        val rec = ChangeRecorder().also { adapter.registerAdapterDataObserver(it) }

        adapter.refreshIconsFor("com.c") // present folder member → the folder composite re-renders
        assertThat(rec.changed).containsExactly(1) // the folder tile, not the app cell
    }

    @Test
    fun `refreshIconsFor is a no-op for a package no tile renders`() {
        val adapter = adapter()
        adapter.submit(listOf(HomeCell.App(ItemId("1"), ck("com.a"))))
        val rec = ChangeRecorder().also { adapter.registerAdapterDataObserver(it) }

        adapter.refreshIconsFor("com.z")
        assertThat(rec.changed).isEmpty()
    }
}
