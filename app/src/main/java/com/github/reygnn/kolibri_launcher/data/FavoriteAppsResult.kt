package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.data.AppInfo

data class FavoriteAppsResult(
    val apps: List<AppInfo>,
    val isFallback: Boolean
)