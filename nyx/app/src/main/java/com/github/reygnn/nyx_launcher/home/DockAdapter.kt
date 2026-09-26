package com.github.reygnn.nyx_launcher.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.ItemId
import kotlinx.coroutines.CoroutineScope

/** Horizontal dock row of apps/folders. Tap launches/opens; long-press drags. */
class DockAdapter(
    private val iconLoader: IconLoader,
    private val folderRenderer: FolderIconRenderer,
    private val scope: CoroutineScope,
    private val iconSizePx: Int,
    private val onLaunch: (ComponentKey) -> Unit,
    private val onOpenFolder: (id: ItemId) -> Unit,
    private val onIconLongPress: (view: View, id: ItemId) -> Unit,
    private val onMissingApp: (id: ItemId, key: ComponentKey) -> Unit,
) : RecyclerView.Adapter<DockAdapter.DockHolder>() {

    private var items: List<HomeCell> = emptyList()
    private var dotPackages: Set<String> = emptySet()

    fun submit(newItems: List<HomeCell>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun submitNotificationDots(newDots: Set<String>) {
        if (dotPackages == newDots) return
        dotPackages = newDots
        // Dot-only payload: update dots without reloading dock icons.
        notifyItemRangeChanged(0, items.size, NOTIFICATION_DOT_PAYLOAD)
    }

    override fun onBindViewHolder(holder: DockHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isDotOnlyPayload()) {
            holder.dot.visibility =
                if (items[position].hasNotificationDot(dotPackages)) View.VISIBLE else View.GONE
            return
        }
        super.onBindViewHolder(holder, position, payloads)
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
        holder.dot.visibility = View.GONE
        // A dragged dock item is hidden during the drag; never leave a reused
        // holder invisible.
        holder.itemView.visibility = View.VISIBLE

        // Dock has no empties; App/Folder wiring is shared with the grid (A1-06).
        bindLaunchableCell(
            holder.itemView, holder.icon, holder.dot, cell, dotPackages, scope, token, { holder.bindToken },
            iconLoader, folderRenderer, iconSizePx, onLaunch, onOpenFolder, onIconLongPress, onMissingApp,
        )
    }

    override fun onViewRecycled(holder: DockHolder) {
        holder.bindToken++
        holder.icon.setImageDrawable(null)
        holder.icon.alpha = 1f // parity with grid/folder recycle; defensive vs a leaked missing-tile alpha
    }

    class DockHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.dock_icon)
        val dot: View = view.findViewById(R.id.dock_dot)
        var bindToken: Int = 0
    }
}
