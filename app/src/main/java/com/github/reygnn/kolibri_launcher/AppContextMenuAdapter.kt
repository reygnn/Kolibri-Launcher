package com.github.reygnn.kolibri_launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

// Definiere die verschiedenen View-Typen, die der Adapter anzeigen kann.
private const val VIEW_TYPE_ACTION = 0
private const val VIEW_TYPE_SEPARATOR = 1

class AppContextMenuAdapter(
    private val onItemClicked: (AppContextMenuAction) -> Unit
) : ListAdapter<AppContextMenuAction, RecyclerView.ViewHolder>(ActionDiffCallback()) {

    /**
     * Bestimmt, welches Layout für welches Element in der Liste verwendet werden soll.
     */
    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is AppContextMenuAction.Separator -> VIEW_TYPE_SEPARATOR
            else -> VIEW_TYPE_ACTION // Gilt für Shortcut und LauncherAction
        }
    }

    /**
     * Erstellt den korrekten ViewHolder basierend auf dem View-Typ.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ACTION -> {
                val view = inflater.inflate(R.layout.item_context_menu_action, parent, false)
                ActionViewHolder(view)
            }
            VIEW_TYPE_SEPARATOR -> {
                val view = inflater.inflate(R.layout.item_context_menu_separator, parent, false)
                SeparatorViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    /**
     * Bindet die Daten an den jeweiligen ViewHolder.
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ActionViewHolder -> {
                holder.bind(item)
                // Wichtig: Nur klickbare Elemente bekommen einen OnClickListener.
                holder.itemView.setOnClickListener { onItemClicked(item) }
            }
            is SeparatorViewHolder -> {
                // Keine Daten zu binden, keine Klicks zu behandeln.
            }
        }
    }

    /**
     * ViewHolder für klickbare Aktionen (Shortcuts und LauncherActions).
     */
    class ActionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val labelView: TextView = itemView.findViewById(R.id.actionLabel)

        fun bind(action: AppContextMenuAction) {
            labelView.text = when (action) {
                is AppContextMenuAction.Shortcut -> action.shortcutInfo.shortLabel
                is AppContextMenuAction.LauncherAction -> action.label
                // Dieser Fall sollte nie eintreten, da der Separator seinen eigenen ViewHolder hat.
                is AppContextMenuAction.Separator -> ""
            }
        }
    }

    /**
     * Ein einfacher ViewHolder, der nur das Layout für den Trenner hält.
     */
    class SeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    /**
     * DiffUtil Callback, der nun alle drei Typen der sealed class kennt.
     */
    private class ActionDiffCallback : DiffUtil.ItemCallback<AppContextMenuAction>() {
        override fun areItemsTheSame(oldItem: AppContextMenuAction, newItem: AppContextMenuAction): Boolean {
            return when {
                oldItem is AppContextMenuAction.Shortcut && newItem is AppContextMenuAction.Shortcut ->
                    oldItem.shortcutInfo.id == newItem.shortcutInfo.id
                oldItem is AppContextMenuAction.LauncherAction && newItem is AppContextMenuAction.LauncherAction ->
                    oldItem.id == newItem.id
                oldItem is AppContextMenuAction.Separator && newItem is AppContextMenuAction.Separator ->
                    true // Es gibt nur einen Separator-Typ.
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: AppContextMenuAction, newItem: AppContextMenuAction): Boolean {
            // Data classes und Objects haben eine korrekte equals()-Implementierung.
            return oldItem == newItem
        }
    }
}