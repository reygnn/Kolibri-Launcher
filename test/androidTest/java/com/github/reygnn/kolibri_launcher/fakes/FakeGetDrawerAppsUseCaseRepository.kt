package com.github.reygnn.kolibri_launcher.fakes

import androidx.lifecycle.MutableLiveData
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.GetDrawerAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable

class FakeGetDrawerAppsUseCaseRepository : GetDrawerAppsUseCaseRepository, Purgeable {
    override val drawerApps = MutableLiveData<List<AppInfo>>()
    override suspend fun purgeRepository() {
        drawerApps.postValue(emptyList())
    }
}