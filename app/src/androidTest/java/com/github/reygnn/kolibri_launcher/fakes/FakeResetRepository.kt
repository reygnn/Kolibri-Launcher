package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeResetRepository @Inject constructor() : ResetRepository, Purgeable {

    var resetAllDataCalled = false
    var resetUserDataCalled = false
    var resetSettingsCalled = false
    var resetAppUsageDataCalled = false

    override suspend fun resetAllData(): Boolean {
        resetAllDataCalled = true
        return true
    }

    override suspend fun resetUserData(): Boolean {
        resetUserDataCalled = true
        return true
    }

    override suspend fun resetSettings(): Boolean {
        resetSettingsCalled = true
        return true
    }

    override suspend fun resetAppUsageData(): Boolean {
        resetAppUsageDataCalled = true
        return true
    }

    override suspend fun purgeRepository() {
        resetAllDataCalled = false
        resetUserDataCalled = false
        resetSettingsCalled = false
        resetAppUsageDataCalled = false
    }
}