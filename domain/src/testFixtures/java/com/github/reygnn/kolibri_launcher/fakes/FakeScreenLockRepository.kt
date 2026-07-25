package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-06 09:26

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake für ScreenLockRepository.
 *
 * HINWEIS: Default-Werte unterscheiden sich vom echten Manager:
 * - Echter Manager: isLockingAvailableFlow startet mit FALSE (Service nicht verbunden)
 * - Dieser Fake: isLockingAvailableFlow startet mit TRUE (Test-Convenience)
 *
 * Grund: Die meisten Tests prüfen Funktionalität bei verfügbarem Service.
 * Startet der Fake mit TRUE, entfällt `setServiceState(true)` in jedem Test.
 * Tests für "Service unavailable" setzen explizit `setServiceState(false)`.
 */
class FakeScreenLockRepository : ScreenLockRepository, Purgeable {

    override val isLockingAvailableFlow = MutableStateFlow(true)

    private val openNotificationsRequest = MutableSharedFlow<Unit>()
    override val openNotificationsRequestFlow: Flow<Unit> = openNotificationsRequest

    override suspend fun requestOpenNotifications() {
        if (isLockingAvailableFlow.value) {
            openNotificationsRequest.emit(Unit)
        }
    }

    override fun setServiceState(isAvailable: Boolean) {
        isLockingAvailableFlow.value = isAvailable
    }

    override suspend fun purgeRepository() {
        isLockingAvailableFlow.value = true  // Reset auf Fake-Default, nicht Manager-Default
    }
}