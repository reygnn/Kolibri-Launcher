package com.github.reygnn.nyx_launcher.home.drawer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.loadIconGated
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import kotlinx.coroutines.CoroutineScope

/**
 * Drawer grid list (item_app_grid): each item is icon + label. Tap launches;
 * long-press starts a place-on-home drag. Token-gated async icon (ICL-INV-9) via
 * the shared [loadIconGated] so a fast scroll never shows the wrong icon.
 */
class AppDrawerAdapter(
    private val iconLoader: IconLoader,
    private val scope: CoroutineScope,
    private val iconSizePx: Int,
    private val onClick: (LauncherApp) -> Unit,
    private val onItemLongPress: (view: View, app: LauncherApp) -> Unit,
) : RecyclerView.Adapter<AppDrawerAdapter.AppHolder>() {

    private var apps: List<LauncherApp> = emptyList()

    fun submit(newApps: List<LauncherApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_grid, parent, false)
        return AppHolder(view)
    }

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(holder: AppHolder, position: Int) {
        val app = apps[position]
        holder.label.text = app.customName ?: app.label
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener { onItemLongPress(holder.itemView, app); true }

        val token = ++holder.bindToken
        holder.icon.loadIconGated(scope, token, { holder.bindToken }) {
            iconLoader.bitmap(IconRef.System(app.key), iconSizePx)
        }
    }

    override fun onViewRecycled(holder: AppHolder) {
        holder.bindToken++
        holder.icon.setImageDrawable(null)
    }

    class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
        var bindToken: Int = 0
    }
}
