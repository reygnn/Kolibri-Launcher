package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenLockRepositoryImpl @Inject constructor() : ScreenLockRepository {
    private val _isAvailable = MutableStateFlow(false)
    override val isLockingAvailableFlow = _isAvailable.asStateFlow()
    private val _openNotificationsRequest = MutableSharedFlow<Unit>()
    override val openNotificationsRequestFlow = _openNotificationsRequest.asSharedFlow()

    /**
     * Wird vom Service aufgerufen, um seinen Zustand zu melden (verbunden/getrennt).
     */
    override fun setServiceState(isAvailable: Boolean) {
        _isAvailable.value = isAvailable
        Timber.d("Screen lock service state changed: available=$isAvailable")
    }

    /**
     * Called from the ViewModel to request opening the notification shade.
     */
    override suspend fun requestOpenNotifications() {
        // Only emit a request when the service is actually running.
        if (_isAvailable.value) {
            _openNotificationsRequest.emit(Unit)
            Timber.d("Open notifications requested")
        } else {
            Timber.w("Open notifications requested but service is not available")
        }
    }

    override suspend fun purgeRepository() {
        // NICHTS TUN!
        // Der ScreenLockRepositoryImpl hält nur flüchtigen Runtime-State:
        // - isLockingAvailableFlow: Ob der Accessibility Service verbunden ist
        // - openNotificationsRequestFlow: event stream for requests
        //
        // Es werden keine User-Einstellungen oder persistierte Daten gespeichert.
        // Der Service-State wird bei jedem App-Start neu ermittelt.
    }
}