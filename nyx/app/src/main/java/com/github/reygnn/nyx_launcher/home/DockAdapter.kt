package com.github.reygnn.nyx_launcher.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.ItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Horizontal dock row of apps/folders. Tap launches/opens; long-press drags. */
class DockAdapter(
    private val iconLoader: IconLoader,
    private val folderRenderer: FolderIconRenderer,
    private val scope: CoroutineScope,
    private val iconSizePx: Int,
    private val onLaunch: (ComponentKey) -> Unit,
    private val onOpenFolder: (id: ItemId) -> Unit,
    private val onIconLongPress: (view: View, id: ItemId) -> Unit,
) : RecyclerView.Adapter<DockAdapter.DockHolder>() {

    private var items: List<HomeCell> = emptyList()

    fun submit(newItems: List<HomeCell>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DockHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dock_icon, parent, false)
        return DockHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DockHolder, position: Int) {
        val cell = items[position]
        val token = ++holder.bindToken
        holder.icon.setImageDrawable(null)

        when (cell) {
            HomeCell.Empty -> Unit // dock has no empties
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

    override fun onViewRecycled(holder: DockHolder) {
        holder.bindToken++
        holder.icon.setImageDrawable(null)
    }

    class DockHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.dock_icon)
        var bindToken: Int = 0
    }
}
