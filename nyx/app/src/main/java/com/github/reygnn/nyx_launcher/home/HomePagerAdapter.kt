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
    private val onLaunch: (ComponentKey) -> Unit,
    private val onOpenFolder: (id: ItemId) -> Unit,
    private val onStartDrag: (View, ItemId) -> Unit,
) : RecyclerView.Adapter<HomePagerAdapter.PageHolder>() {

    private var pages: List<List<HomeCell>> = emptyList()

    fun submit(pageCells: List<List<HomeCell>>) {
        pages = pageCells
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val recycler = RecyclerView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            layoutManager = GridLayoutManager(context, columns)
            clipToPadding = false
        }
        val gridAdapter = HomeGridAdapter(iconLoader, folderRenderer, scope, iconSizePx, onLaunch, onOpenFolder, onStartDrag)
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
