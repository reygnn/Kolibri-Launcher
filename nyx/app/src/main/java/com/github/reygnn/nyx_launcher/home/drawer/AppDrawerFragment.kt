package com.github.reygnn.nyx_launcher.home.drawer

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.doOnAttach
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.launcher.common.ui.gesture.GestureFrameLayout
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app drawer, extracted into its own fragment so future drawer features
 * (search, A–Z fast-scroll, sections, context menus) have a natural home and a
 * `viewLifecycleOwner`-scoped place to collect their flows. Mirrors Kolibri's
 * AppDrawerFragment.
 *
 * Visibility, not attach/detach: the host toggles the container's visibility so
 * a frequently opened drawer never re-inflates. The fragment therefore stays
 * added; its flow collection uses `repeatOnLifecycle(STARTED)`.
 *
 * The fragment does not own launch, drag, or show/hide — those touch the host's
 * activity (intents, drag payloads, the overlay container it lives in). It
 * signals them through [Host], which [MainActivity] implements.
 */
@AndroidEntryPoint
class AppDrawerFragment : Fragment(R.layout.fragment_app_drawer) {

    /** Implemented by the hosting activity. */
    interface Host {
        /** Launch the tapped app and dismiss the drawer. */
        fun launchFromDrawer(key: ComponentKey)

        /** Start a place-on-home drag for the long-pressed app and dismiss. */
        fun startDrawerDrag(view: View, key: ComponentKey)

        /** Dismiss the drawer (swipe-down / back). */
        fun hideDrawer()
    }

    @Inject lateinit var iconLoader: IconLoader

    private val viewModel: HomeViewModel by activityViewModels()

    private val host: Host
        get() = requireActivity() as Host

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view as GestureFrameLayout
        // Pixel-style drag-to-dismiss (nested scrolling): the drawer follows the
        // finger once the list is pinned at the top. dragTarget is the overlay
        // container the host animates, so a released dismiss hands off to
        // hideDrawer() from the current offset — no jump, no double slide.
        view.doOnAttach { root.dragTarget = view.parent as View }
        root.onDismissDrag = { host.hideDrawer() }

        val list = view.findViewById<RecyclerView>(R.id.drawer_panel)
        // The drawer scrim (root) fills edge-to-edge behind the system bars; the
        // list itself is inset so items clear the status/nav bars, with a small
        // base gap on top.
        val basePx = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + basePx, bottom = bars.bottom + basePx)
            insets
        }
        val adapter = AppDrawerAdapter(
            iconLoader = iconLoader,
            scope = viewLifecycleOwner.lifecycleScope,
            iconSizePx = (48 * resources.displayMetrics.density).toInt(),
            onClick = { app -> host.launchFromDrawer(app.key) },
            onItemLongPress = { v, app -> host.startDrawerDrag(v, app.key) },
        )
        list.layoutManager = GridLayoutManager(requireContext(), drawerColumns())
        list.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.drawerApps.collect(adapter::submit) }
                // Re-render icons in the current variant when the theme toggle flips.
                launch { viewModel.monochromeIcons.collect { adapter.notifyDataSetChanged() } }
            }
        }
    }

    private fun drawerColumns(): Int {
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (dp / 90f).toInt().coerceIn(3, 6)
    }
}
