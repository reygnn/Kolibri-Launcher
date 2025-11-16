package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.Flow

interface GetOnboardingAppsUseCaseRepository : Purgeable {
    val onboardingAppsFlow: Flow<List<AppInfo>>
}