package com.github.reygnn.kolibri_launcher.domain.model

data class FavoriteAppsResult(
    val apps: List<AppInfo>,
    val isFallback: Boolean
)