package com.github.reygnn.kolibri_launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class PackageUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val SIGNAL_TIMEOUT_MS = 3000L
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        // Null-Checks für API-Kontrakt-Sicherheit
        if (context == null || intent == null) {
            Timber.w("[KOLIBRI] Receiver called with null context or intent")
            return
        }

        val pendingResult = try {
            goAsync()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "[KOLIBRI] Failed to call goAsync(), processing synchronously")
            null
        }

        try {
            handleReceive(context, intent) {
                try {
                    pendingResult?.finish()
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "[KOLIBRI] Error finishing pendingResult")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "[KOLIBRI] CRITICAL error in onReceive")
            try {
                pendingResult?.finish()
            } catch (e2: Throwable) {
                TimberWrapper.silentError(e2, "[KOLIBRI] Error finishing pendingResult after exception")
            }
        }
    }

    @VisibleForTesting
    internal fun handleReceive(context: Context, intent: Intent, onFinish: () -> Unit) {
        try {
            val action = intent.action
            val packageName = try {
                intent.data?.schemeSpecificPart ?: "unknown"
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "[KOLIBRI] Error extracting package name")
                "unknown"
            }

            Timber.d("[KOLIBRI] Receiver triggered. Action: $action, package: $packageName")

            if (action == null) {
                Timber.w("[KOLIBRI] Received intent with null action")
                safeOnFinish(onFinish)
                return
            }

            if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REMOVED) {
                Timber.d("[KOLIBRI] Irrelevant action: $action")
                safeOnFinish(onFinish)
                return
            }

            // Relevante Action erkannt
            Timber.d("[KOLIBRI] Relevant action detected. Attempting to send signal...")

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

            scope.launch {
                try {
                    withTimeout(SIGNAL_TIMEOUT_MS) {
                        processPackageUpdate(context, onFinish)
                    }
                } catch (e: CancellationException) {
                    Timber.d("[KOLIBRI] Coroutine was cancelled")
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "[KOLIBRI] Error in coroutine")
                    safeOnFinish(onFinish)
                }
            }

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "[KOLIBRI] CRITICAL error in handleReceive")
            safeOnFinish(onFinish)
        }
    }

    private suspend fun processPackageUpdate(context: Context, onFinish: () -> Unit) {
        try {
            val appContext = try {
                context.applicationContext ?: context
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "[KOLIBRI] Error getting application context")
                context
            }

            val hiltEntryPoint = try {
                EntryPointAccessors.fromApplication(
                    appContext,
                    InstalledAppsManagerEntryPoint::class.java
                )
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "[KOLIBRI] Failed to access Hilt entry point")
                return
            }

            val appUpdateSignal = try {
                hiltEntryPoint.getAppUpdateSignal()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "[KOLIBRI] Failed to get app update signal")
                return
            }

            try {
                appUpdateSignal.sendUpdateSignal()
                Timber.d("[KOLIBRI] Update signal sent successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "[KOLIBRI] Failed to send update signal")
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "[KOLIBRI] Error in processPackageUpdate")
        } finally {
            // onFinish() wird IMMER aufgerufen
            safeOnFinish(onFinish)
        }
    }

    private fun safeOnFinish(onFinish: () -> Unit) {
        try {
            onFinish()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "[KOLIBRI] Error in onFinish callback")
        }
    }
}