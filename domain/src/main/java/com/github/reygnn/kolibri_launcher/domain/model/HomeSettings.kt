package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.core.AppConstants

data class HomeSettings(
    val sortOrder: SortOrder = AppConstants.DEFAULT_SORT_ORDER, // project-wide default (single source of truth)
)