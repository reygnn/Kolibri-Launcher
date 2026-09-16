package com.github.reygnn.nyx_launcher.home.drawer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.loadIconGated
import com.github.reygnn.nyx_launcher.home.model.DrawerEntry
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.model.displayName
import kotlinx.coroutines.CoroutineScope

/**
 * Drawer list of [DrawerEntry]s (item_app_grid: icon + label): a pinned block of
 * folders on top, then the loose apps (DRAWER_FOLDERS_SPEC §5/§10). Two view types:
 * an app tile (tap launches, long-press starts a place-on-home drag) and a folder
 * tile (composed folder icon via [FolderIconRenderer], tap opens it). Token-gated
 * async icons (ICL-INV-9) via the shared [loadIconGated] so a fast scroll never
 * shows the wrong icon.
 */
class AppDrawerAdapter(
    private val iconLoader: IconLoader,
    private val folderRenderer: FolderIconRenderer,
    private val scope: CoroutineScope,
    private val iconSizePx: Int,
    private val onAppClick: (LauncherApp) -> Unit,
    private val onAppLongPress: (view: View, app: LauncherApp) -> Unit,
    private val onFolderClick: (DrawerEntry.Folder) -> Unit,
) : RecyclerView.Adapter<AppDrawerAdapter.EntryHolder>() {

    private var entries: List<DrawerEntry> = emptyList()

    fun submit(newEntries: List<DrawerEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemViewType(position: Int): Int = when (entries[position]) {
        is DrawerEntry.App -> TYPE_APP
        is DrawerEntry.Folder -> TYPE_FOLDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
        // App and folder tiles share item_app_grid (icon + label); only the bind differs.
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_grid, parent, false)
        return EntryHolder(view)
    }

    override fun onBindViewHolder(holder: EntryHolder, position: Int) {
        when (val entry = entries[position]) {
            is DrawerEntry.App -> bindApp(holder, entry.app)
            is DrawerEntry.Folder -> bindFolder(holder, entry)
        }
    }

    private fun bindApp(holder: EntryHolder, app: LauncherApp) {
        holder.label.text = app.displayName
        holder.itemView.setOnClickListener { onAppClick(app) }
        holder.itemView.setOnLongClickListener { onAppLongPress(holder.itemView, app); true }

        val token = ++holder.bindToken
        holder.icon.loadIconGated(scope, token, { holder.bindToken }) {
            iconLoader.bitmap(IconRef.System(app.key), iconSizePx)
        }
    }

    private fun bindFolder(holder: EntryHolder, folder: DrawerEntry.Folder) {
        holder.label.text = folder.title.ifBlank {
            holder.itemView.context.getString(R.string.folder_default_title)
        }
        holder.itemView.setOnClickListener { onFolderClick(folder) }
        holder.itemView.setOnLongClickListener { false } // folder drag/reorder is v2 (§13)

        val token = ++holder.bindToken
        holder.icon.loadIconGated(scope, token, { holder.bindToken }) {
            folderRenderer.render(folder.members, iconSizePx)
        }
    }

    override fun onViewRecycled(holder: EntryHolder) {
        holder.bindToken++
        holder.icon.setImageDrawable(null)
    }

    class EntryHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
        var bindToken: Int = 0
    }

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_FOLDER = 1
    }
}
