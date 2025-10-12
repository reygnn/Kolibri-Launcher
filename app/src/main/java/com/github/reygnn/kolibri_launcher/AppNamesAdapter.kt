package com.github.reygnn.kolibri_launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.databinding.ItemAppRenameableBinding

class AppNamesAdapter(
    private val onAppClicked: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppNamesAdapter.AppViewHolder>(AppInfoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppRenameableBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding, onAppClicked)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class AppViewHolder(
        private val binding: ItemAppRenameableBinding,
        onAppClicked: (AppInfo) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: AppInfo? = null

        init {
            binding.root.setOnClickListener {
                currentItem?.let { onAppClicked(it) }
            }
        }

        fun bind(appInfo: AppInfo) {
            currentItem = appInfo

            binding.displayNameText.text = appInfo.displayName

            if (appInfo.displayName != appInfo.originalName) {
                binding.originalNameText.visibility = View.VISIBLE
                binding.originalNameText.text = appInfo.originalName
            } else {
                binding.originalNameText.visibility = View.GONE
            }
        }
    }

}