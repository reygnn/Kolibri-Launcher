package com.github.reygnn.nyx_launcher.home

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.ItemId
import kotlinx.coroutines.CoroutineScope

/**
 * ViewPager2 adapter: one grid page per item. Each page is its own RecyclerView +
 * [HomeGridAdapter]. A drop on a page reports (page, cellIndex, id). Empty-area
 * long-press is handled by the shared home gesture layer (see MainActivity), not
 * here — app icons keep their own long-press for drag.
 */
class HomePagerAdapter(
    private val iconLoader: IconLoader,
    private val folderRenderer: FolderIconRenderer,
    private val scope: CoroutineScope,
    private val iconSizePx: Int,
    private val columns: Int,
    private val rows: Int,
    private val onLaunch: (ComponentKey) -> Unit,
    private val onOpenFolder: (id: ItemId) -> Unit,
    private val onStartDrag: (View, ItemId) -> Unit,
    private val onMissingApp: (id: ItemId, key: ComponentKey) -> Unit,
) : RecyclerView.Adapter<HomePagerAdapter.PageHolder>() {

    private var pages: List<List<HomeCell>> = emptyList()

    // Current notification-dot packages, read by each page's HomeGridAdapter at bind
    // via the provider below. A change rebinds all pages so dots refresh.
    private var currentDots: Set<String> = emptySet()

    fun submit(pageCells: List<List<HomeCell>>) {
        // Value-equal pages (a pure icon-style change re-renders the SAME cells): skip
        // notifyDataSetChanged so it doesn't wipe a pending ICON_STYLE_PAYLOAD in the same
        // frame — the currentStyle collector calls renderLayout()→submit() then refreshIcons(),
        // and a notifyDataSetChanged here would swallow that payload → grid keeps old-style
        // icons (dock/drawer repaint via their own rebinds). A real layout change produces
        // non-equal lists and rebinds as before; the per-page DiffUtil in HomeGridAdapter is
        // untouched (this only gates the pager-level full rebind).
        if (pages == pageCells) return
        pages = pageCells
        notifyDataSetChanged()
    }

    fun submitNotificationDots(dotPackages: Set<String>) {
        if (currentDots == dotPackages) return
        currentDots = dotPackages
        // Dot-only payload: rebind pages WITHOUT reloading their icons (see onBind below).
        notifyItemRangeChanged(0, pages.size, NOTIFICATION_DOT_PAYLOAD)
    }

    /**
     * Icon-style change: re-decode every live page's icons. The cell data is unchanged, so
     * this rides an icon-style payload straight to each page's [HomeGridAdapter.refreshIcons]
     * (offscreen pages re-decode naturally when next bound). Called from MainActivity's
     * currentStyle collector — without it the grid keeps the old-style bitmaps.
     */
    fun refreshIcons() = notifyItemRangeChanged(0, pages.size, ICON_STYLE_PAYLOAD)

    /**
     * Targeted icon invalidation: re-decode only the tiles that render [pkg] on every live page
     * (an in-place icon update evicted its cache entry; the cells are value-equal so [submit]'s
     * DiffUtil would skip them). Offscreen pages re-decode naturally when next bound. Called from
     * MainActivity's pending-icon-repaint collector.
     */
    fun refreshIconsFor(pkg: String) = notifyItemRangeChanged(0, pages.size, IconRepaintPayload(pkg))

    override fun onBindViewHolder(holder: PageHolder, position: Int, payloads: MutableList<Any>) {
        // ICON_STYLE first: refreshIcons() is a full (payload-less) rebind, so it also
        // refreshes dots. Checking dot-only first would let a coalesced [DOT, ICON_STYLE]
        // payload satisfy NEITHER all{}-guard and fall through to super → a silent no-op
        // (neither dots nor icons update). "Contains ICON_STYLE" covers the mixed case.
        if (payloads.hasIconStylePayload()) {
            holder.gridAdapter.refreshIcons()
            return
        }
        // Targeted per-package repaint (possibly coalesced with a dot payload): re-decode the
        // matching tiles, and if a dot payload rode along, refresh the (whole-page) dots too so
        // the coalesced dot update isn't dropped.
        val repaintPackages = payloads.iconRepaintPackages()
        if (repaintPackages.isNotEmpty()) {
            repaintPackages.forEach { holder.gridAdapter.refreshIconsFor(it) }
            if (payloads.any { it === NOTIFICATION_DOT_PAYLOAD }) holder.gridAdapter.refreshDots()
            return
        }
        if (payloads.isDotOnlyPayload()) {
            holder.gridAdapter.refreshDots()
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val recycler = RecyclerView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Fixed grid: it's sized to fit the page exactly (cell height + bottom
            // anchoring), so it must never scroll vertically.
            layoutManager = object : GridLayoutManager(context, columns) {
                override fun canScrollVertically(): Boolean = false
            }
            clipToPadding = false
        }
        val gridAdapter = HomeGridAdapter(iconLoader, folderRenderer, scope, iconSizePx, rows, { currentDots }, onLaunch, onOpenFolder, onStartDrag, onMissingApp)
        recycler.adapter = gridAdapter
        return PageHolder(recycler, gridAdapter)
    }

    override fun getItemCount(): Int = pages.size

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.gridAdapter.submit(pages[position])
        // No per-page drag listener: grid drops are handled centrally by the home
        // root (see MainActivity.setupRemoveBar / resolveGridCell), which reliably
        // receives the drop and maps it to a cell by geometry.
    }

    class PageHolder(
        val recycler: RecyclerView,
        val gridAdapter: HomeGridAdapter,
    ) : RecyclerView.ViewHolder(recycler)
}
