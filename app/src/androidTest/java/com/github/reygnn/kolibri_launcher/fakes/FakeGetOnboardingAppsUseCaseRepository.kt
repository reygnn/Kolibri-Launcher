package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.GetOnboardingAppsUseCaseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeGetOnboardingAppsUseCaseRepository : GetOnboardingAppsUseCaseRepository {
    val mutableOnboardingAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override val onboardingAppsFlow: Flow<List<AppInfo>>
        get() = mutableOnboardingAppsFlow

    override suspend fun purgeRepository() {
        mutableOnboardingAppsFlow.value = emptyList()
    }
}