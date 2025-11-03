package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.AppInfo
import com.github.reygnn.kolibri_launcher.data.Purgeable
import kotlinx.coroutines.flow.Flow

interface GetOnboardingAppsUseCaseRepository : Purgeable {
    val onboardingAppsFlow: Flow<List<AppInfo>>
}