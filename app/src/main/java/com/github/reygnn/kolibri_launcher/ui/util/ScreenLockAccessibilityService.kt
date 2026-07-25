package com.github.reygnn.kolibri_launcher.ui.util

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
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

/**
 * AccessibilityService for the notification-drawer action (swipe-down).
 *
 * **Crash-safety profile (§9.15 sweep, 2026-05-08):** every catch in this
 * file is now `Throwable`-broad. The file is end-to-end system-callback
 * driven (AccessibilityService lifecycle, GLOBAL_ACTION_* IPC, Hilt
 * injection at service connect). System-Callback-Boundaries gehen nach
 * Rule-11-four-category-frame auf Throwable: ein durchschlagender `Error`
 * landet sonst ohne Diagnose-Stack-Trace im System-Thread.
 */
@AndroidEntryPoint
class ScreenLockAccessibilityService : AccessibilityService() {
    @Inject
    lateinit var screenLockRepository: ScreenLockRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error setting service state to true")
            }

            serviceScope.launch {
                try {
                    screenLockRepository.openNotificationsRequestFlow
                        .catch { e ->
                            TimberWrapper.silentError(e, "Error in openNotificationsRequestFlow, stopping collection")
                        }
                        .collect { request ->
                            try {
                                Timber.d("Open notifications request received, performing global action.")
                                val success = try {
                                    performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                                } catch (e: Throwable) {
                                    TimberWrapper.silentError(e, "Error performing open notifications action")
                                    false
                                }

                                if (!success) {
                                    Timber.w("Failed to open notifications - action not successful")
                                }
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error processing open notifications request")
                            }
                        }
                } catch (e: CancellationException) {
                    Timber.d("Open notifications request collection cancelled")
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Critical error in open notifications request coroutine")
                }
            }

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Critical error in onServiceConnected")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            Timber.i("Accessibility service unbound")
            cleanupService()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onUnbind")
        }

        return try {
            super.onUnbind(intent)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error calling super.onUnbind")
            false
        }
    }

    override fun onDestroy() {
        try {
            Timber.i("Accessibility service destroyed")
            cleanupService()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        }

        try {
            super.onDestroy()
        } catch (e: Throwable) {
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
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting service state to false")
                }
            }

            // Scope canceln
            try {
                serviceScope.cancel()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error cancelling service scope")
            }

        } catch (e: Throwable) {
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
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onInterrupt")
        }
    }
}
