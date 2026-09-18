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
    private var dotPackages: Set<String> = emptySet()

    fun submit(newEntries: List<DrawerEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    fun submitNotificationDots(newDots: Set<String>) {
        if (dotPackages == newDots) return
        dotPackages = newDots
        notifyDataSetChanged()
    }

    /**
     * The entry currently shown at [position], or null if out of range. This is the
     * list actually on screen — the folder view for a blank query, the flat filtered
     * list during a search — so a drop's adapter position resolves against what the
     * user sees, not a separate source flow.
     */
    fun entryAt(position: Int): DrawerEntry? = entries.getOrNull(position)

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
            is DrawerEntry.App -> bindApp(holder, entry)
            is DrawerEntry.Folder -> bindFolder(holder, entry)
        }
    }

    private fun bindApp(holder: EntryHolder, entry: DrawerEntry.App) {
        val app = entry.app
        holder.label.text = app.displayName
        holder.dot.visibility = if (app.key.packageName in dotPackages) View.VISIBLE else View.GONE
        // Reveal mode only: a hidden app is shown dimmed so it reads as "hidden" at a glance.
        // In the normal view hidden apps are filtered out, so this is 1f there.
        holder.itemView.alpha = if (entry.hidden) HIDDEN_ALPHA else 1f
        // App tile: TalkBack reads the label (the app name); a revealed hidden app also gets a
        // "hidden" cue, since the dim alone is invisible to TalkBack.
        holder.itemView.contentDescription = if (entry.hidden) {
            holder.itemView.context.getString(R.string.drawer_app_hidden_a11y, app.displayName)
        } else {
            null
        }
        holder.itemView.setOnClickListener { onAppClick(app) }
        holder.itemView.setOnLongClickListener { onAppLongPress(holder.itemView, app); true }

        val token = ++holder.bindToken
        holder.icon.loadIconGated(scope, token, { holder.bindToken }) {
            iconLoader.bitmap(IconRef.System(app.key), iconSizePx)
        }
    }

    private fun bindFolder(holder: EntryHolder, folder: DrawerEntry.Folder) {
        holder.itemView.alpha = 1f // folders are never hidden; reset in case the view was recycled from a dimmed app
        holder.dot.visibility =
            if (folder.members.any { it.packageName in dotPackages }) View.VISIBLE else View.GONE
        val title = folder.title.ifBlank {
            holder.itemView.context.getString(R.string.folder_default_title)
        }
        holder.label.text = title
        // The composed folder icon is decorative (contentDescription=@null), and the
        // label alone reads identically to an app of the same name. Announce the role
        // and member count so TalkBack distinguishes a folder from an app.
        holder.itemView.contentDescription = holder.itemView.resources.getQuantityString(
            R.plurals.drawer_folder_a11y, folder.members.size, title, folder.members.size,
        )
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
        val dot: View = view.findViewById(R.id.app_dot)
        val label: TextView = view.findViewById(R.id.app_label)
        var bindToken: Int = 0
    }

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_FOLDER = 1

        // Dim factor for a revealed hidden app tile (overflow "show hidden").
        const val HIDDEN_ALPHA = 0.4f
    }
}
