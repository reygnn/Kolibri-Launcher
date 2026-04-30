package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.graphics.Color
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.util.AppInfoDiffCallback

/**
 * Adapter für die App-Liste im Drawer.
 * Verwendet ListAdapter mit DiffUtil für effiziente Updates und Payload-basierte
 * partielle Updates für Farb- und Namensänderungen.
 */
class AppDrawerAdapter(
    private val onAppClicked: (AppInfo) -> Unit,
    private val onAppLongClicked: (AppInfo) -> Unit,
) : ListAdapter<AppInfo, AppDrawerAdapter.AppViewHolder>(AppInfoDiffCallback()) {

    private var textColor: Int = Color.WHITE
    private var shadowColor: Int = Color.BLACK

    companion object {
        const val PAYLOAD_COLOR_CHANGE = "color_change"
        const val PAYLOAD_NAME_CHANGE = "name_change"
    }

    /**
     * Aktualisiert die UI-Farben aller sichtbaren Items.
     * Verwendet Payloads für effiziente Updates ohne vollständigen Rebind.
     */
    fun setUiColors(textColor: Int, shadowColor: Int) {
        val colorsChanged = this.textColor != textColor || this.shadowColor != shadowColor
        if (!colorsChanged) return

        this.textColor = textColor
        this.shadowColor = shadowColor

        // notifyItemRangeChanged kann IllegalStateException werfen,
        // wenn die RecyclerView gerade scrollt/layoutet — echtes Risiko,
        // Catch + Fallback bleiben.
        try {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_COLOR_CHANGE)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error notifying color change, attempting full refresh")
            try {
                notifyDataSetChanged()
            } catch (e2: Exception) {
                TimberWrapper.silentError(e2, "Error in fallback notifyDataSetChanged")
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_drawer, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val item = try {
            getItem(position)
        } catch (e: IndexOutOfBoundsException) {
            TimberWrapper.silentError(e, "Index out of bounds at position $position")
            return
        }
        if (item != null) {
            holder.bind(item)
        } else {
            TimberWrapper.silentError("Null item at position $position")
        }
    }

    /**
     * Überschriebene Methode für Payload-basierte Updates.
     * Ermöglicht partielle Updates ohne vollständigen Rebind.
     */
    override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        val item = try {
            getItem(position)
        } catch (e: IndexOutOfBoundsException) {
            TimberWrapper.silentError(e, "Index out of bounds at position $position in payload binding")
            return
        }
        if (item == null) {
            TimberWrapper.silentError("Null item at position $position in payload binding")
            return
        }

        // when auf String-Payloads + holder.updateX-Aufrufe sind reine
        // TextView-Setter — können nicht werfen. Frühere Triple-Schachtel-
        // Catches waren tot.
        payloads.forEach { payload ->
            when (payload) {
                PAYLOAD_COLOR_CHANGE -> holder.updateColors(textColor, shadowColor)
                PAYLOAD_NAME_CHANGE -> holder.updateName(item.displayName)
            }
        }
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        // unbind ist no-op, super.onViewRecycled der Base-Klasse ist leer.
        holder.unbind()
        super.onViewRecycled(holder)
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // findViewById gibt null bei nicht gefunden — wirft nicht.
        private val appName: TextView? = itemView.findViewById(R.id.app_name)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = try {
                        // getItem kann unter Race-Bedingungen werfen,
                        // wenn die Liste mitten im Click neu gesetzt wird.
                        getItem(position)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error getting item for click at position $position")
                        return@setOnClickListener
                    }

                    if (item != null) {
                        try {
                            // User-Code-Callback — kann beliebig werfen.
                            onAppClicked(item)
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error in onAppClicked callback for ${item.packageName}")
                        }
                    }
                }
            }

            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = try {
                        getItem(position)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error getting item for long click at position $position")
                        return@setOnLongClickListener false
                    }

                    if (item != null) {
                        try {
                            onAppLongClicked(item)
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error in onAppLongClicked callback for ${item.packageName}")
                        }
                    }
                }
                true
            }
        }

        fun bind(appInfo: AppInfo) {
            // Reine TextView-Setter — können nicht werfen.
            updateName(appInfo.displayName)
            updateColors(textColor, shadowColor)
        }

        fun updateColors(textColor: Int, shadowColor: Int) {
            appName?.setTextColor(textColor)
            appName?.setShadowLayer(
                AppConstants.SHADOW_RADIUS_APPS,
                AppConstants.SHADOW_DX,
                AppConstants.SHADOW_DY,
                shadowColor,
            )
        }

        fun updateName(name: String) {
            appName?.text = name
            appName?.maxLines = 1
            appName?.ellipsize = TextUtils.TruncateAt.END
        }

        fun unbind() {
            // Cleanup-Hook für die Zukunft. Body ist absichtlich leer;
            // der frühere try{}/catch-Block um diesen leeren Body war
            // absurd und ist entfernt.
        }
    }
}
