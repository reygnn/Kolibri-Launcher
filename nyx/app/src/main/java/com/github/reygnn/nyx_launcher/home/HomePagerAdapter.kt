package com.github.reygnn.nyx_launcher.home

import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.ItemId
import kotlinx.coroutines.CoroutineScope

/**
 * ViewPager2 adapter: one grid page per item. Each page is its own RecyclerView +
 * [HomeGridAdapter]. A drop on a page reports (page, cellIndex, id); a long-press
 * on empty page area opens the drawer.
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
    private val onOpenDrawer: () -> Unit,
    private val onDropOnPage: (page: Int, cellIndex: Int, payload: DragPayload) -> Unit,
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

        holder.recycler.setOnLongClickListener {
            onOpenDrawer()
            true
        }
        holder.recycler.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DROP) {
                val payload = event.localState as? DragPayload
                val child = holder.recycler.findChildViewUnder(event.x, event.y)
                val index = child?.let(holder.recycler::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
                if (payload != null && index != RecyclerView.NO_POSITION) {
                    onDropOnPage(position, index, payload)
                }
                true
            } else {
                true
            }
        }
    }

    class PageHolder(
        val recycler: RecyclerView,
        val gridAdapter: HomeGridAdapter,
    ) : RecyclerView.ViewHolder(recycler)
}
