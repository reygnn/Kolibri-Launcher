package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeScreenLockRepository : ScreenLockRepository, Purgeable {

    override val isLockingAvailableFlow = MutableStateFlow(true)
    private val lockRequest = MutableSharedFlow<Unit>()
    override val lockRequestFlow: Flow<Unit> = lockRequest
    override suspend fun requestLock() {
        lockRequest.emit(Unit)
    }

    private val openNotificationsRequest = MutableSharedFlow<Unit>()
    override val openNotificationsRequestFlow: Flow<Unit> = openNotificationsRequest
    override suspend fun requestOpenNotifications() {
        openNotificationsRequest.emit(Unit)
    }

    override fun setServiceState(isAvailable: Boolean) {
        isLockingAvailableFlow.value = isAvailable
    }

    override suspend fun purgeRepository() {
        isLockingAvailableFlow.value = true
    }
}