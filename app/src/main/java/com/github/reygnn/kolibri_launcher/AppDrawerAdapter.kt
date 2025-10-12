package com.github.reygnn.kolibri_launcher

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter für die App-Liste im Drawer.
 * Verwendet ListAdapter mit DiffUtil für effiziente Updates und Payload-basierte
 * partielle Updates für Farb- und Namensänderungen.
 */
class AppDrawerAdapter(
    private val onAppClicked: (AppInfo) -> Unit,
    private val onAppLongClicked: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppDrawerAdapter.AppViewHolder>(AppInfoDiffCallback()) {

    private var textColor: Int = Color.WHITE
    private var shadowColor: Int = Color.BLACK

    companion object {
        internal const val PAYLOAD_COLOR_CHANGE = "color_change"
        internal const val PAYLOAD_NAME_CHANGE = "name_change"
    }

    /**
     * Aktualisiert die UI-Farben aller sichtbaren Items.
     * Verwendet Payloads für effiziente Updates ohne vollständigen Rebind.
     */
    fun setUiColors(textColor: Int, shadowColor: Int) {
        try {
            val colorsChanged = this.textColor != textColor || this.shadowColor != shadowColor

            if (!colorsChanged) {
                return
            }

            this.textColor = textColor
            this.shadowColor = shadowColor

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
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in setUiColors")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        return try {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_drawer, parent, false)
            AppViewHolder(view)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error creating ViewHolder, creating fallback")
            // Fallback: Erstelle einen einfachen TextView
            val fallbackView = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(16, 16, 16, 16)
            }
            AppViewHolder(fallbackView)
        }
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        try {
            val item = getItem(position)
            if (item != null) {
                holder.bind(item)
            } else {
                TimberWrapper.silentError("Null item at position $position")
            }
        } catch (e: IndexOutOfBoundsException) {
            TimberWrapper.silentError(e, "Index out of bounds at position $position")
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error binding ViewHolder at position $position")
        }
    }

    /**
     * Überschriebene Methode für Payload-basierte Updates.
     * Ermöglicht partielle Updates ohne vollständigen Rebind.
     */
    override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: MutableList<Any>) {
        try {
            if (payloads.isEmpty()) {
                super.onBindViewHolder(holder, position, payloads)
            } else {
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

                try {
                    payloads.forEach { payload ->
                        try {
                            when (payload) {
                                PAYLOAD_COLOR_CHANGE -> holder.updateColors(textColor, shadowColor)
                                PAYLOAD_NAME_CHANGE -> holder.updateName(item.displayName)
                            }
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error processing payload: $payload for position $position")
                        }
                    }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error iterating payloads at position $position")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onBindViewHolder with payloads at position $position")
        }
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        try {
            holder.unbind()
            super.onViewRecycled(holder)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error recycling ViewHolder")
        }
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appName: TextView? = try {
            itemView.findViewById(R.id.app_name)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error finding app_name TextView")
            null
        }

        init {
            try {
                itemView.setOnClickListener {
                    try {
                        val position = bindingAdapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            val item = try {
                                getItem(position)
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error getting item for click at position $position")
                                return@setOnClickListener
                            }

                            if (item != null) {
                                try {
                                    onAppClicked(item)
                                } catch (e: Exception) {
                                    TimberWrapper.silentError(e, "Error in onAppClicked callback for ${item.packageName}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in click listener")
                    }
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error setting click listener")
            }

            try {
                itemView.setOnLongClickListener {
                    try {
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
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in long click listener")
                        false
                    }
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error setting long click listener")
            }
        }

        fun bind(appInfo: AppInfo) {
            try {
                updateName(appInfo.displayName)
                updateColors(textColor, shadowColor)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error binding app: ${appInfo.packageName}")
                // Fallback: Versuche wenigstens den Namen zu setzen
                try {
                    appName?.text = appInfo.packageName
                } catch (e2: Exception) {
                    TimberWrapper.silentError(e2, "Critical error in bind fallback")
                }
            }
        }

        fun updateColors(textColor: Int, shadowColor: Int) {
            try {
                appName?.setTextColor(textColor)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error setting text color")
            }

            try {
                appName?.setShadowLayer(
                    AppConstants.SHADOW_RADIUS_APPS,
                    AppConstants.SHADOW_DX,
                    AppConstants.SHADOW_DY,
                    shadowColor
                )
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error setting shadow layer")
            }
        }

        fun updateName(name: String) {
            try {
                appName?.text = name
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error updating name: $name")
            }
        }

        fun unbind() {
            try {
                // Cleanup falls nötig in der Zukunft
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error unbinding ViewHolder")
            }
        }
    }
}