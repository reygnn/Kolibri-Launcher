package com.github.reygnn.kolibri_launcher

import androidx.lifecycle.LiveData

interface GetDrawerAppsUseCaseRepository : Purgeable {
    val drawerApps: LiveData<List<AppInfo>>
}