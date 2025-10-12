package com.github.reygnn.kolibri_launcher

import androidx.recyclerview.widget.DiffUtil

/**
 * Standard DiffUtil.ItemCallback für AppInfo.
 * Wird von allen Adaptern verwendet, die AppInfo-Listen anzeigen.
 */
class AppInfoDiffCallback : DiffUtil.ItemCallback<AppInfo>() {

    override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
        return oldItem.componentName == newItem.componentName
    }

    override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
        return oldItem == newItem
    }
}