package com.github.reygnn.kolibri_launcher

data class FavoriteAppsResult(
    val apps: List<AppInfo>,
    val isFallback: Boolean
)