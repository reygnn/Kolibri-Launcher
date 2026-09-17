package com.github.reygnn.nyx_launcher.home.drawer

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.launcher.common.ui.SearchQueryChangeTracker
import com.github.reygnn.launcher.common.ui.gesture.GestureFrameLayout
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.HomeViewModel
import com.github.reygnn.nyx_launcher.home.model.DrawerAppSearch
import com.github.reygnn.nyx_launcher.home.model.DrawerEntry
import com.github.reygnn.nyx_launcher.home.model.DrawerSearchResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app drawer, extracted into its own fragment so future drawer features
 * (A–Z fast-scroll, sections, context menus) have a natural home and a
 * `viewLifecycleOwner`-scoped place to collect their flows. Mirrors Kolibri's
 * AppDrawerFragment.
 *
 * Search (DRAWER_FOLDERS_SPEC §10 D-3, mirrors Kolibri): a non-blank query
 * flattens folders and filters the flat app list; a blank query restores the
 * folder view. A single match auto-launches when the user enabled it — gated by
 * [SearchQueryChangeTracker] so a StateFlow *replay* of a leftover one-match
 * query on re-open can never launch an app the user never tapped.
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

        /** Open the tapped drawer folder (show its members). */
        fun openDrawerFolder(folder: DrawerEntry.Folder)

        /** Show the drawer overflow menu (e.g. "create folder by maker"), anchored to [anchor]. */
        fun showDrawerOverflowMenu(anchor: View)

        /** Dismiss the drawer (swipe-down / back). */
        fun hideDrawer()
    }

    @Inject lateinit var iconLoader: IconLoader

    @Inject lateinit var folderRenderer: FolderIconRenderer

    private val viewModel: HomeViewModel by activityViewModels()

    private val host: Host
        get() = requireActivity() as Host

    // Held so the adapter can be cleared in onDestroyView (shared adapter-nulling
    // rule): the RecyclerView otherwise retains its item views across the
    // view-recreation cycle.
    private var drawerList: RecyclerView? = null
    private var adapter: AppDrawerAdapter? = null
    private var searchBox: EditText? = null

    // Tells a genuine keystroke apart from a StateFlow replay so only a real user
    // narrowing can auto-launch (shared :common-ui logic).
    private val searchQueryChangeTracker = SearchQueryChangeTracker()
    private var searchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view as GestureFrameLayout
        // Pixel-style drag-to-dismiss (nested scrolling): the drawer follows the
        // finger once the list is pinned at the top. dragTarget is the overlay
        // container the host animates, so a released dismiss hands off to
        // hideDrawer() from the current offset — no jump, no double slide.
        //
        // ARMED per-open in onDrawerShown() and DISARMED in onDrawerHidden() (not
        // once on attach), so a fresh pull during the host's hide slide cannot
        // cancel the hide animation — the same wedge guard Kolibri uses, now
        // shared via DrawerOverlayController.
        root.onDismissDrag = { host.hideDrawer() }

        view.findViewById<EditText>(R.id.search_edit_text).also { searchBox = it }
        val topBar = view.findViewById<View>(R.id.drawer_top_bar)
        val list = view.findViewById<RecyclerView>(R.id.drawer_panel).also { drawerList = it }
        view.findViewById<View>(R.id.drawer_overflow).setOnClickListener { host.showDrawerOverflowMenu(it) }
        // The drawer scrim (root) fills edge-to-edge behind the system bars; the top bar
        // (search + overflow) clears the status bar (top inset) and the list clears the nav
        // bar (bottom inset), each with a small base gap.
        val basePx = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + basePx)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = basePx, bottom = bars.bottom + basePx)
            insets
        }
        val drawerAdapter = AppDrawerAdapter(
            iconLoader = iconLoader,
            folderRenderer = folderRenderer,
            scope = viewLifecycleOwner.lifecycleScope,
            iconSizePx = (48 * resources.displayMetrics.density).toInt(),
            onAppClick = { app -> host.launchFromDrawer(app.key) },
            onAppLongPress = { v, app -> host.startDrawerDrag(v, app.key) },
            onFolderClick = { folder -> host.openDrawerFolder(folder) },
        ).also { adapter = it }
        list.layoutManager = GridLayoutManager(requireContext(), drawerColumns())
        list.adapter = drawerAdapter

        // Feed keystrokes into the ViewModel's query StateFlow; the collector below
        // debounces + renders. Text set programmatically (e.g. clear on hide) flows
        // through here too, which is fine — a blank query just restores folders.
        searchBox?.doAfterTextChanged { viewModel.setSearchQuery(it?.toString().orEmpty()) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Query changes: debounce, then render. Auto-launch only on a genuine
                // keystroke (isUserChange), never on the replay this collector gets
                // on every STARTED transition.
                launch {
                    viewModel.searchQuery.collect { query ->
                        val isUserChange = searchQueryChangeTracker.onQueryEmitted(query)
                        searchJob?.cancel()
                        searchJob = launch {
                            delay(AppConstants.SEARCH_DEBOUNCE_DELAY_MS)
                            renderForQuery(query, allowAutoLaunch = isUserChange)
                        }
                    }
                }
                // Folder view refresh (blank query only): membership or app changes.
                launch {
                    viewModel.drawerContent.collect {
                        if (viewModel.searchQuery.value.isBlank()) drawerAdapter.submit(it)
                    }
                }
                // Filtered view refresh under a stable query (app list changed, e.g.
                // install/uninstall). Never auto-launches — a list collapsing to one
                // match without the user typing must not launch it (the Kolibri bug).
                launch {
                    viewModel.drawerApps.collect {
                        val q = viewModel.searchQuery.value
                        if (q.isNotBlank()) renderForQuery(q, allowAutoLaunch = false)
                    }
                }
                // Re-render icons in the current variant when the theme toggle flips.
                launch {
                    viewModel.monochromeIcons.collect { drawerAdapter.notifyDataSetChanged() }
                }
            }
        }
    }

    /**
     * Render the drawer for [query]: blank restores the folder view, otherwise the
     * flat filtered list. [allowAutoLaunch] gates the single-match auto-launch — it
     * is only true on a real keystroke (see [searchQueryChangeTracker]).
     */
    private suspend fun renderForQuery(query: String, allowAutoLaunch: Boolean) {
        val drawerAdapter = adapter ?: return
        if (query.isBlank()) {
            drawerAdapter.submit(viewModel.drawerContent.value)
            return
        }
        // Read the setting fresh, and only when a genuine keystroke could auto-launch
        // (allowAutoLaunch) — the VM exposes it as a suspend getter, not a hot flow, so
        // there is no stale point-read.
        val autoLaunch = allowAutoLaunch && viewModel.isSearchAutoLaunchEnabled()
        val result = DrawerAppSearch.filterAndDecide(
            allApps = viewModel.drawerApps.value,
            query = query,
            isAutoLaunchEnabled = autoLaunch,
        )
        when (result) {
            is DrawerSearchResult.ShowList ->
                drawerAdapter.submit(result.apps.map { DrawerEntry.App(it) })
            is DrawerSearchResult.AutoLaunch -> {
                hideKeyboard()
                host.launchFromDrawer(result.app.key)
            }
        }
    }

    private fun hideKeyboard() {
        val box = searchBox ?: return
        context?.getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(box.windowToken, 0)
    }

    override fun onDestroyView() {
        // Clear the adapter so the RecyclerView doesn't retain its item views
        // across the view-recreation cycle (shared adapter-nulling rule; the
        // adapter's icon-load scope is viewLifecycleOwner-bound and already
        // cancelled here). The RecyclerView field is dropped with the view.
        searchJob?.cancel()
        searchJob = null
        drawerList?.adapter = null
        drawerList = null
        adapter = null
        searchBox = null
        searchQueryChangeTracker.reset()
        super.onDestroyView()
    }

    /**
     * Arm drag-to-dismiss. Driven by MainActivity.showDrawer() via the
     * DrawerOverlayController onShown hook. dragTarget = the overlay container
     * (this view's parent), which the host animates on show/hide.
     */
    fun onDrawerShown() {
        (view as? GestureFrameLayout)?.let { it.dragTarget = it.parent as? View }
    }

    /**
     * Disarm drag-to-dismiss before the hide slide, so a fresh pull cannot cancel
     * the host's hide animation. Driven by MainActivity.hideDrawer().
     *
     * Also clears the search so the next open starts on the folder view and its
     * tracker treats the first (blank) emission as a replay, not a keystroke.
     */
    fun onDrawerHidden() {
        (view as? GestureFrameLayout)?.dragTarget = null
        searchBox?.let { if (it.text.isNotEmpty()) it.text = null }
        hideKeyboard()
        searchQueryChangeTracker.reset()
    }

    private fun drawerColumns(): Int {
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (dp / 90f).toInt().coerceIn(3, 6)
    }
}
