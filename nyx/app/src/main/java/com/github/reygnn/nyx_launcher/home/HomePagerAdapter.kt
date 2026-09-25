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
        pages = pageCells
        notifyDataSetChanged()
    }

    fun submitNotificationDots(dotPackages: Set<String>) {
        if (currentDots == dotPackages) return
        currentDots = dotPackages
        // Dot-only payload: rebind pages WITHOUT reloading their icons (see onBind below).
        notifyItemRangeChanged(0, pages.size, NOTIFICATION_DOT_PAYLOAD)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int, payloads: MutableList<Any>) {
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
