package com.github.reygnn.kolibri_launcher.ui.util

import androidx.recyclerview.widget.DiffUtil
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.appdrawer.AppDrawerAdapter

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

    override fun getChangePayload(oldItem: AppInfo, newItem: AppInfo): Any? {
        if (oldItem.displayName != newItem.displayName) {
            return AppDrawerAdapter.PAYLOAD_NAME_CHANGE
        }
        return null
    }
}