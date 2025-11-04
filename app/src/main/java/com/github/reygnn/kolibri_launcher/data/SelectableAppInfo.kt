package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.data.AppInfo

data class SelectableAppInfo(
    val appInfo: AppInfo,
    val isSelected: Boolean
)