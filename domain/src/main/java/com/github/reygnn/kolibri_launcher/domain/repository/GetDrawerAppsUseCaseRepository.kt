package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import kotlinx.coroutines.flow.Flow

interface GetDrawerAppsUseCaseRepository : Purgeable {
    val drawerApps: Flow<List<AppInfo>>
}
