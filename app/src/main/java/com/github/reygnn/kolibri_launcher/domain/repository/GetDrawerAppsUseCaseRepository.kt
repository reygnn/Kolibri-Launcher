package com.github.reygnn.kolibri_launcher.domain.repository

import androidx.lifecycle.LiveData
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

interface GetDrawerAppsUseCaseRepository : Purgeable {
    val drawerApps: LiveData<List<AppInfo>>
}