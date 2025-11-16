package com.github.reygnn.kolibri_launcher.domain.repository

import androidx.lifecycle.LiveData
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable

interface GetDrawerAppsUseCaseRepository : Purgeable {
    val drawerApps: LiveData<List<AppInfo>>
}