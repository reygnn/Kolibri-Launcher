package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.Flow

interface GetOnboardingAppsUseCaseRepository : Purgeable {
    val onboardingAppsFlow: Flow<List<AppInfo>>
}