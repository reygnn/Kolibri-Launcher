package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import kotlinx.coroutines.flow.Flow

interface GetOnboardingAppsUseCaseRepository : Purgeable {
    val onboardingAppsFlow: Flow<List<AppInfo>>
}