package com.github.reygnn.nyx_launcher.home.drawer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Vertical drawer list: each row is icon + label. Tapping launches the app.
 * Same stale-binding guard as the grid (ICL-INV-9): a per-holder token gates the
 * async icon so a fast scroll never shows the wrong icon.
 */
class AppDrawerAdapter(
    private val iconLoader: IconLoader,
    private val scope: CoroutineScope,
    private val iconSizePx: Int,
    private val onClick: (LauncherApp) -> Unit,
    private val onAddToHome: (LauncherApp) -> Unit,
    private val onItemLongPress: ((view: View, app: LauncherApp) -> Unit)? = null,
    private val itemLayout: Int = R.layout.item_app_row,
) : RecyclerView.Adapter<AppDrawerAdapter.AppHolder>() {

    private var apps: List<LauncherApp> = emptyList()

    fun submit(newApps: List<LauncherApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(itemLayout, parent, false)
        return AppHolder(view)
    }

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(holder: AppHolder, position: Int) {
        val app = apps[position]
        holder.label.text = app.customName ?: app.label
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener {
            val dragHandler = onItemLongPress
            if (dragHandler != null) dragHandler(holder.itemView, app) else onAddToHome(app)
            true
        }

        val token = ++holder.bindToken
        holder.icon.setImageDrawable(null)
        scope.launch {
            val bitmap = runCatching {
                iconLoader.bitmap(IconRef.System(app.key), iconSizePx)
            }.getOrNull() ?: return@launch
            if (holder.bindToken == token) holder.icon.setImageBitmap(bitmap)
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
