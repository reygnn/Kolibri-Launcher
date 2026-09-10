package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import javax.inject.Inject

class ShowAppUseCase @Inject constructor(
    private val repository: HiddenAppsRepository
) {
    suspend operator fun invoke(app: AppInfo) {
        repository.showComponent(app.componentName)
    }
}