package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.data.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenLockManager @Inject constructor() : ScreenLockRepository {

    // Hält den Zustand, der vom Service gemeldet wird
    private val _isAvailable = MutableStateFlow(false)
    override val isLockingAvailableFlow = _isAvailable.asStateFlow()

    // Kanal, um Sperranfragen an den Service zu senden
    private val _lockRequest = MutableSharedFlow<Unit>()
    override val lockRequestFlow = _lockRequest.asSharedFlow()

    /**
     * Wird vom Service aufgerufen, um seinen Zustand zu melden (verbunden/getrennt).
     */
    override fun setServiceState(isAvailable: Boolean) {
        try {
            _isAvailable.value = isAvailable
            Timber.Forest.d("Screen lock service state changed: available=$isAvailable")
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting screen lock service state")
            // Nicht kritisch - behalte den alten Zustand
        }
    }

    /**
     * Wird vom ViewModel aufgerufen, um eine Sperre anzufordern.
     */
    override suspend fun requestLock() {
        try {
            // Sende nur dann eine Anfrage, wenn der Service auch wirklich läuft
            if (_isAvailable.value) {
                _lockRequest.emit(Unit)
                Timber.Forest.d("Screen lock requested")
            } else {
                Timber.Forest.w("Screen lock requested but service is not available")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error requesting screen lock")
            // Nicht kritisch - Lock wird einfach nicht ausgeführt
        }
    }

    override fun purgeRepository() {
        // Für Tests - keine Implementierung nötig in Production
    }
}