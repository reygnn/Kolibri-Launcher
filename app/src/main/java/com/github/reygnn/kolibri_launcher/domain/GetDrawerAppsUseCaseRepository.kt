package com.github.reygnn.kolibri_launcher.domain

import androidx.lifecycle.LiveData
import com.github.reygnn.kolibri_launcher.AppInfo
import com.github.reygnn.kolibri_launcher.data.Purgeable

interface GetDrawerAppsUseCaseRepository : Purgeable {
    val drawerApps: LiveData<List<AppInfo>>
}