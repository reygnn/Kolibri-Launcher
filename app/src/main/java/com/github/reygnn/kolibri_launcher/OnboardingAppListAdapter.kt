package com.github.reygnn.kolibri_launcher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.databinding.ItemAppSelectableTextBinding

class OnboardingAppListAdapter(
    private val onAppClicked: (AppInfo) -> Unit
) : ListAdapter<SelectableAppInfo, OnboardingAppListAdapter.ViewHolder>(SelectableAppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppSelectableTextBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding, onAppClicked)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemAppSelectableTextBinding,
        onAppClicked: (AppInfo) -> Unit // Callback wird an den Konstruktor übergeben
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: SelectableAppInfo? = null

        init {
            itemView.setOnClickListener {
                currentItem?.let {
                    onAppClicked(it.appInfo)
                }
            }
        }

        fun bind(item: SelectableAppInfo) {
            this.currentItem = item

            binding.appLabel.text = item.appInfo.displayName
            binding.selectionCheckbox.isChecked = item.isSelected
        }
    }

    class SelectableAppDiffCallback : DiffUtil.ItemCallback<SelectableAppInfo>() {
        override fun areItemsTheSame(oldItem: SelectableAppInfo, newItem: SelectableAppInfo): Boolean {
            return oldItem.appInfo.componentName == newItem.appInfo.componentName
        }

        override fun areContentsTheSame(oldItem: SelectableAppInfo, newItem: SelectableAppInfo): Boolean {
            return oldItem == newItem
        }
    }
}