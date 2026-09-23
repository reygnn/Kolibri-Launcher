package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.launcher.core.AppInfo

data class FavoriteAppsResult(
    val apps: List<AppInfo>,
    val isFallback: Boolean
)
