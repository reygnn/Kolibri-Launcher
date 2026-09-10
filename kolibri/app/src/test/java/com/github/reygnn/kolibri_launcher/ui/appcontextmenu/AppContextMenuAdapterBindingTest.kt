package com.github.reygnn.kolibri_launcher.ui.appcontextmenu

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppContextMenuAction
import com.github.reygnn.kolibri_launcher.domain.model.LauncherActionLabel
import com.github.reygnn.kolibri_launcher.domain.model.LauncherShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Characterization net for [AppContextMenuAdapter], written against the
 * current (pre-refactor) code so the AUDIT-15 F6 cleanup — hoisting the
 * per-bind `setOnClickListener` into the ViewHolder and resolving the item
 * via `bindingAdapterPosition`, plus a payload for the colour update — can
 * be done with a safety net.
 *
 * What is pinned here is the behaviour the refactor must PRESERVE:
 * position→action click routing, separators being non-interactive, the
 * label text (Shortcut `shortLabel` vs. the `R.string.*` mapped from a
 * [LauncherActionLabel]), and the colour push including its same-colour
 * no-op, its colour-only payload rebind, and the empty-payload full-bind
 * fall-through. Deliberately NOT pinned is whether an unbound holder's
 * `performClick()` returns true or false: the current code sets no listener
 * until bind (so it returns false), while the hoisted-listener design would
 * register a listener that no-ops on `NO_POSITION` (returning true). Only
 * "no action is routed" survives both designs, so only that is asserted.
 *
 * Robolectric is required for layout inflation + `findViewById` + real
 * `TextView` colour state; the adapter logic itself is plain Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
class AppContextMenuAdapterBindingTest {

    // The action layout pulls Material3 theme attrs (textAppearanceBodyLarge,
    // selectableItemBackground), so inflation needs a Material3-themed context —
    // the application context is unthemed. Every view here (parent + the
    // RecyclerViews) uses this wrapper so onCreateViewHolder's parent.context
    // can resolve those attrs.
    private val context: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.AppTheme,
    )
    private val parent = FrameLayout(context)

    private fun adapter(onItemClicked: (AppContextMenuAction) -> Unit = {}) =
        AppContextMenuAdapter(onItemClicked)

    private fun launcherAction(
        id: String = AppContextMenuAction.ACTION_ID_APP_INFO,
        label: LauncherActionLabel = LauncherActionLabel.AppInfo,
    ) = AppContextMenuAction.LauncherAction(id = id, label = label)

    private fun shortcut(id: String = "s1", shortLabel: String? = "New chat") =
        AppContextMenuAction.Shortcut(
            LauncherShortcut(id = id, packageName = "com.example", shortLabel = shortLabel),
        )

    private val separator = AppContextMenuAction.Separator

    /** Lay a RecyclerView out so bound child views exist and are clickable. */
    private fun laidOutRecyclerView(adapter: AppContextMenuAdapter): RecyclerView {
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 1000, 2000)
        return recyclerView
    }

    // ---------- view-type dispatch ----------

    @Test
    fun `actions share a view type and the separator has its own`() {
        val adapter = adapter()
        adapter.submitList(listOf(launcherAction(), separator, shortcut()))

        val actionType = adapter.getItemViewType(0)
        val separatorType = adapter.getItemViewType(1)
        val shortcutType = adapter.getItemViewType(2)

        assertEquals(actionType, shortcutType)
        assertNotEquals(actionType, separatorType)
    }

    @Test
    fun `onCreateViewHolder builds the type-matching holder`() {
        val adapter = adapter()
        adapter.submitList(listOf(launcherAction(), separator))

        val actionHolder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        val separatorHolder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(1))

        assertTrue(actionHolder is AppContextMenuAdapter.ActionViewHolder)
        assertTrue(separatorHolder is AppContextMenuAdapter.SeparatorViewHolder)
    }

    // ---------- label binding ----------

    @Test
    fun `LauncherAction binds the string resource mapped from its label`() {
        val adapter = adapter()
        adapter.submitList(listOf(launcherAction(label = LauncherActionLabel.AddToFavorites)))

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)

        assertEquals(
            context.getString(R.string.add_to_favorites),
            (holder.itemView as TextView).text.toString(),
        )
    }

    @Test
    fun `Shortcut binds its shortLabel verbatim`() {
        val adapter = adapter()
        adapter.submitList(listOf(shortcut(shortLabel = "New chat")))

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)

        assertEquals("New chat", (holder.itemView as TextView).text.toString())
    }

    // ---------- colour push ----------

    @Test
    fun `setActionTextColor applies the colour to a bound action label`() {
        val color = 0xFF112233.toInt()
        val adapter = adapter()
        adapter.submitList(listOf(launcherAction()))
        adapter.setActionTextColor(color)

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)

        assertEquals(color, (holder.itemView as TextView).textColors.defaultColor)
    }

    @Test
    fun `a colour payload rebind updates the colour without re-resolving the label`() {
        val adapter = adapter()
        adapter.submitList(listOf(launcherAction(label = LauncherActionLabel.AddToFavorites)))

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        val boundText = (holder.itemView as TextView).text.toString()

        val color = 0xFF778899.toInt()
        adapter.setActionTextColor(color)
        // Non-empty payload -> colour-only branch (the marker's identity is
        // irrelevant; the override applies the current colour for any payload).
        adapter.onBindViewHolder(holder, 0, mutableListOf(Any()))

        val labelView = holder.itemView as TextView
        assertEquals(color, labelView.textColors.defaultColor)
        assertEquals(boundText, labelView.text.toString())
    }

    @Test
    fun `an empty payload falls through to a full bind`() {
        val adapter = adapter()
        adapter.submitList(listOf(launcherAction(label = LauncherActionLabel.AddToFavorites)))

        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0, mutableListOf())

        assertEquals(
            context.getString(R.string.add_to_favorites),
            (holder.itemView as TextView).text.toString(),
        )
    }

    @Test
    fun `setActionTextColor with the same colour does not re-notify`() {
        val adapter = adapter()
        adapter.submitList(listOf(launcherAction()))

        var changeNotifications = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                changeNotifications++
            }
        })

        val color = 0xFF445566.toInt()
        adapter.setActionTextColor(color) // first push: notifies the bound range
        adapter.setActionTextColor(color) // same colour: early-return, no notify

        assertEquals(1, changeNotifications)
    }

    // ---------- click routing (the core invariant for the F6 hoist) ----------

    @Test
    fun `a click on a bound action row routes exactly that action`() {
        val received = mutableListOf<AppContextMenuAction>()
        val adapter = adapter(onItemClicked = { received.add(it) })

        val first = launcherAction(id = AppContextMenuAction.ACTION_ID_APP_INFO)
        val second = launcherAction(
            id = AppContextMenuAction.ACTION_ID_HIDE_APP,
            label = LauncherActionLabel.HideAppFromDrawer,
        )
        adapter.submitList(listOf(first, second))
        val recyclerView = laidOutRecyclerView(adapter)

        (recyclerView.getChildAt(1) as TextView).performClick()

        assertEquals(listOf(second), received)
    }

    @Test
    fun `a click on a separator row routes nothing`() {
        val received = mutableListOf<AppContextMenuAction>()
        val adapter = adapter(onItemClicked = { received.add(it) })
        adapter.submitList(listOf(launcherAction(), separator))
        val recyclerView = laidOutRecyclerView(adapter)

        val consumed = recyclerView.getChildAt(1).performClick()

        // The separator holder wires no listener in either design.
        assertFalse(consumed)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a click on an unbound action holder routes nothing`() {
        val received = mutableListOf<AppContextMenuAction>()
        val adapter = adapter(onItemClicked = { received.add(it) })
        adapter.submitList(listOf(launcherAction()))

        // Created but never bound / attached -> no position to resolve.
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        (holder as AppContextMenuAdapter.ActionViewHolder).itemView.performClick()

        assertTrue(received.isEmpty())
    }
}
