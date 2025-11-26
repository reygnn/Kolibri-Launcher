package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * UseCase zum Abrufen der installierten Apps.
 * Dient als Zwischenschicht, um das ViewModel vom Repository zu entkoppeln.
 * Hier könnte später Filterlogik (z.B. System-Apps ausblenden) ergänzt werden.
 */
class GetInstalledAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository
) {
    operator fun invoke(): Flow<List<AppInfo>> {
        return installedAppsRepository.getInstalledApps()
    }
}