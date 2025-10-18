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
import timber.log.Timber

class PackageUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        // Null-Checks für API-Kontrakt-Sicherheit
        if (context == null || intent == null) {
            Timber.w("[KOLIBRI] Receiver called with null context or intent")
            return
        }

        val pendingResult = try {
            goAsync()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "[KOLIBRI] Failed to call goAsync(), processing synchronously")
            null
        }

        try {
            handleReceive(context, intent) {
                try {
                    pendingResult?.finish()
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "[KOLIBRI] Error finishing pendingResult")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "[KOLIBRI] Critical error in onReceive")
            try {
                pendingResult?.finish()
            } catch (e2: Exception) {
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
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "[KOLIBRI] Error extracting package name")
                "unknown"
            }

            Timber.d("[KOLIBRI] Receiver triggered. Action: $action, package: $packageName")

            // Null-Check für action
            if (action == null) {
                Timber.w("[KOLIBRI] Received intent with null action")
                onFinish()
                return
            }

            if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REMOVED) {
                Timber.d("[KOLIBRI] Relevant action detected. Attempting to send signal...")

                // SupervisorJob verhindert, dass ein Fehler den gesamten Scope cancelt
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

                scope.launch {
                    try {
                        val appContext = try {
                            context.applicationContext
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "[KOLIBRI] Error getting application context")
                            context
                        }

                        val hiltEntryPoint = try {
                            EntryPointAccessors.fromApplication(
                                appContext,
                                InstalledAppsManagerEntryPoint::class.java
                            )
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "[KOLIBRI] Failed to access Hilt entry point")
                            onFinish()
                            return@launch
                        }

                        val appUpdateSignal = try {
                            hiltEntryPoint.getAppUpdateSignal()
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "[KOLIBRI] Failed to get app update signal")
                            onFinish()
                            return@launch
                        }

                        try {
                            appUpdateSignal.sendUpdateSignal()
                            Timber.d("[KOLIBRI] Update signal sent successfully")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "[KOLIBRI] Failed to send update signal")
                        }

                    } catch (e: CancellationException) {
                        Timber.d("[KOLIBRI] Coroutine was cancelled")
                        throw e
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "[KOLIBRI] Unexpected error in coroutine")
                    } finally {
                        // onFinish() wird IMMER aufgerufen, auch bei Fehler
                        try {
                            onFinish()
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "[KOLIBRI] Error in onFinish callback")
                        }
                    }
                }
            } else {
                // Irrelevante Action - finish aufrufen
                onFinish()
            }

        } catch (e: Exception) {
            TimberWrapper.silentError(e, "[KOLIBRI] Critical error in handleReceive")
            try {
                onFinish()
            } catch (e2: Exception) {
                TimberWrapper.silentError(e2, "[KOLIBRI] Error calling onFinish after exception")
            }
        }
    }
}