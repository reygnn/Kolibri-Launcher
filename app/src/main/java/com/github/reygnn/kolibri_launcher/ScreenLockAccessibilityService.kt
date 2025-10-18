package com.github.reygnn.kolibri_launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class ScreenLockAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var screenLockRepository: ScreenLockRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Flag um zu tracken ob der Service verbunden ist
    private var isConnected = false

    override fun onServiceConnected() {
        super.onServiceConnected()

        try {
            Timber.i("Accessibility service connected")
            isConnected = true

            // Prüfe ob Hilt erfolgreich injiziert hat
            if (!::screenLockRepository.isInitialized) {
                TimberWrapper.silentError("ScreenLockRepository not initialized, service cannot function")
                return
            }

            try {
                screenLockRepository.setServiceState(true)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error setting service state to true")
            }

            serviceScope.launch {
                try {
                    screenLockRepository.lockRequestFlow
                        .catch { e ->
                            TimberWrapper.silentError(e, "Error in lockRequestFlow, stopping collection")
                            // Flow stoppt bei Fehler, aber Service läuft weiter
                        }
                        .collect { request ->
                            try {
                                Timber.d("Lock request received, performing global action.")
                                val success = try {
                                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                                } catch (e: Exception) {
                                    TimberWrapper.silentError(e, "Error performing lock screen action")
                                    false
                                }

                                if (!success) {
                                    Timber.w("Failed to lock screen - action not successful")
                                }
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error processing lock request")
                                // Einzelner Fehler darf nicht die Collection stoppen
                            }
                        }
                } catch (e: CancellationException) {
                    Timber.d("Lock request collection cancelled")
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Critical error in lock request coroutine")
                }
            }

        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Critical error in onServiceConnected")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            Timber.i("Accessibility service unbound")
            cleanupService()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onUnbind")
        }

        return try {
            super.onUnbind(intent)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error calling super.onUnbind")
            false
        }
    }

    override fun onDestroy() {
        try {
            Timber.i("Accessibility service destroyed")
            cleanupService()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        }

        try {
            super.onDestroy()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error calling super.onDestroy")
        }
    }

    /**
     * Zentralisierte Cleanup-Logik für onUnbind und onDestroy
     */
    private fun cleanupService() {
        try {
            isConnected = false

            // Nur wenn Repository initialisiert ist
            if (::screenLockRepository.isInitialized) {
                try {
                    screenLockRepository.setServiceState(false)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error setting service state to false")
                }
            }

            // Scope canceln
            try {
                serviceScope.cancel()
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error cancelling service scope")
            }

        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in cleanupService")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op - aber mit Null-Safety
    }

    override fun onInterrupt() {
        // No-op - aber absicherbar für zukünftige Implementierung
        try {
            Timber.d("Accessibility service interrupted")
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onInterrupt")
        }
    }
}