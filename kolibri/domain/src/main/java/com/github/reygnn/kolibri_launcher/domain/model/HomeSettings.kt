package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.launcher.core.AppConstants

data class HomeSettings(
    val sortOrder: SortOrder = SettingsDefaults.DEFAULT_SORT_ORDER, // project-wide default (single source of truth)
)