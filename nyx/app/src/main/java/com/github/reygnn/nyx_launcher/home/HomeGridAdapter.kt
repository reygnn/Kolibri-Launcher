package com.github.reygnn.nyx_launcher.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.ItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Renders one page of [HomeCell]s. Apps show their icon and launch on tap;
 * folders show the 2×2 composite and open on tap. Long-press an icon begins a
 * drag; empty cells fall through to the page's long-press (opens the drawer).
 * Async images are gated by a per-holder token (ICL-INV-9).
 */
class HomeGridAdapter(
    private val iconLoader: IconLoader,
    private val folderRenderer: FolderIconRenderer,
    private val scope: CoroutineScope,
    private val iconSizePx: Int,
    private val rows: Int,
    private val onLaunch: (ComponentKey) -> Unit,
    private val onOpenFolder: (id: ItemId) -> Unit,
    private val onIconLongPress: (view: View, id: ItemId) -> Unit,
) : RecyclerView.Adapter<HomeGridAdapter.CellHolder>() {

    private var cells: List<HomeCell> = emptyList()

    /** The page RecyclerView, so cells can stretch to fill its height (see onBind). */
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
    }

    fun submit(newCells: List<HomeCell>) {
        cells = newCells
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_cell, parent, false)
        return CellHolder(view)
    }

    override fun getItemCount(): Int = cells.size

    override fun onBindViewHolder(holder: CellHolder, position: Int) {
        val cell = cells[position]
        val token = ++holder.bindToken
        holder.icon.setImageDrawable(null)

        // Fixed cell height (device-consistent), grid bottom-anchored: the rows
        // sit flush above the dock and any leftover height becomes the page's top
        // padding (dead space at the top). Keeps the dock-to-bottom-row distance
        // identical across devices. Height is known during the layout pass.
        recyclerView?.let { rv ->
            val h = rv.height
            if (h > 0) {
                val cellHeight = homeCellHeightPx(h, rows, rv.resources.displayMetrics.density)
                val topPad = h - rows * cellHeight
                if (rv.paddingTop != topPad) {
                    rv.setPadding(rv.paddingLeft, topPad, rv.paddingRight, rv.paddingBottom)
                }
                if (holder.itemView.layoutParams.height != cellHeight) {
                    holder.itemView.updateLayoutParams { height = cellHeight }
                }
            }
        }

        when (cell) {
            HomeCell.Empty -> {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                holder.itemView.isClickable = false
                holder.itemView.isLongClickable = false
            }
            is HomeCell.App -> {
                holder.itemView.setOnClickListener { onLaunch(cell.key) }
                holder.itemView.setOnLongClickListener { onIconLongPress(holder.itemView, cell.id); true }
                scope.launch {
                    val bmp = runCatching { iconLoader.bitmap(IconRef.System(cell.key), iconSizePx) }.getOrNull()
                        ?: return@launch
                    if (holder.bindToken == token) holder.icon.setImageBitmap(bmp)
                }
            }
            is HomeCell.Folder -> {
                holder.itemView.setOnClickListener { onOpenFolder(cell.id) }
                holder.itemView.setOnLongClickListener { onIconLongPress(holder.itemView, cell.id); true }
                scope.launch {
                    val bmp = runCatching { folderRenderer.render(cell.members, iconSizePx) }.getOrNull()
                        ?: return@launch
                    if (holder.bindToken == token) holder.icon.setImageBitmap(bmp)
                }
            }
        }
    }

    override fun onViewRecycled(holder: CellHolder) {
        holder.bindToken++
        holder.icon.setImageDrawable(null)
    }

    class CellHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.cell_icon)
        var bindToken: Int = 0
    }
}

/** Target home-grid cell edge in dp — device-consistent (see [homeCellHeightPx]). */
internal const val HOME_CELL_TARGET_DP = 110f

// Clamp ranges for the device-derived grid (MainActivity.applyDeviceGrid).
internal const val HOME_MIN_COLUMNS = 3
internal const val HOME_MAX_COLUMNS = 6
internal const val HOME_MIN_ROWS = 4
internal const val HOME_MAX_ROWS = 8

/**
 * Fixed cell height in px for a page [pageHeightPx] high with [rows] rows: the
 * ~[HOME_CELL_TARGET_DP] target, but never taller than an equal split, so on a
 * short screen the rows still fit. The leftover (`pageHeightPx − rows·result`)
 * becomes the page's top padding, bottom-anchoring the grid above the dock.
 * Shared by [HomeGridAdapter] (rendering) and MainActivity.resolveGridCell (drop
 * mapping) so both agree on the geometry.
 */
internal fun homeCellHeightPx(pageHeightPx: Int, rows: Int, density: Float): Int {
    if (rows <= 0 || pageHeightPx <= 0) return 0
    val target = (HOME_CELL_TARGET_DP * density).toInt()
    return minOf(target, pageHeightPx / rows)
}
