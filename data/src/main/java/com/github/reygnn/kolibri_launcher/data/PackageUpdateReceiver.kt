package com.github.reygnn.kolibri_launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.github.reygnn.kolibri_launcher.core.PackageEvent
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
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
    fun handleReceive(context: Context, intent: Intent, onFinish: () -> Unit) {
        try {
            val action = intent.action
            val packageName = intent.data?.schemeSpecificPart ?: "unknown"

            // Log a stable hash of the package name rather than the raw value.
            // Raw package names are PII (e.g. "com.gambling.bigwin") and
            // even though Timber.d() is filtered to debug builds, the
            // launcher's install list shouldn't end up in anyone's logcat.
            // Hex hashCode keeps add/remove pairs correlatable across log lines.
            val packageHash = packageName.hashCode().toString(16)
            Timber.d("[KOLIBRI] Receiver triggered. Action: $action, packageHash: $packageHash")

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

            // Skip the removal half of an in-place update. During a replace,
            // the system sends PACKAGE_REMOVED(EXTRA_REPLACING=true) followed by
            // PACKAGE_ADDED(EXTRA_REPLACING=true), both post-commit. Acting on
            // the REMOVED half would fire a redundant reconcile sweep during the
            // most volatile moment of the update; the paired PACKAGE_ADDED still
            // refreshes (and it is the safe, app-present path). Standard launcher
            // practice — a replace is not an uninstall.
            if (action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                Timber.d("[KOLIBRI] PACKAGE_REMOVED is a replace (update); paired ADDED will refresh. Skipping.")
                safeOnFinish(onFinish)
                return
            }

            // Relevante Action erkannt
            Timber.d("[KOLIBRI] Relevant action detected. Attempting to send signal...")

            // Map the Intent to a typed event at this :data edge; the bus stays
            // Android-free. Only ADDED/REMOVED reach here (guards above), and a
            // replace-removal was already filtered, so REMOVED is a genuine
            // uninstall.
            val event = mapToPackageEvent(action, packageName)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

            scope.launch {
                try {
                    withTimeout(SIGNAL_TIMEOUT_MS) {
                        processPackageUpdate(context, event, onFinish)
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

    /**
     * Maps a relevant package action to a typed [PackageEvent]. Only ADDED/
     * REMOVED reach the caller (guards in [handleReceive]); a genuine uninstall
     * (REMOVED, replace already filtered) is the non-ADDED case.
     */
    @VisibleForTesting
    internal fun mapToPackageEvent(action: String, packageName: String): PackageEvent =
        if (action == Intent.ACTION_PACKAGE_ADDED) {
            PackageEvent.Added(packageName)
        } else {
            PackageEvent.Removed(packageName)
        }

    private suspend fun processPackageUpdate(context: Context, event: PackageEvent, onFinish: () -> Unit) {
        try {
            val appContext = context.applicationContext ?: context

            // EXPECTED: Hilt entry-point access can throw if Hilt is not yet
            // initialised in the receiving process (cold-start race for
            // package broadcasts). Bail early on failure — onFinish() still
            // runs from the finally{} below.
            val hiltEntryPoint = try {
                EntryPointAccessors.fromApplication(
                    appContext,
                    InstalledAppsRepositoryEntryPoint::class.java
                )
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "[KOLIBRI] Failed to access Hilt entry point")
                return
            }

            val appUpdateSignal = hiltEntryPoint.getAppUpdateSignal()

            try {
                appUpdateSignal.send(event)
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