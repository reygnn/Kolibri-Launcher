package com.github.reygnn.kolibri_launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
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

        // Sentinel for a package broadcast that arrived without a data URI —
        // we can send the reload signal but must not act on the package name.
        private const val UNKNOWN_PACKAGE = "unknown"
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
            val packageName = intent.data?.schemeSpecificPart ?: UNKNOWN_PACKAGE

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

            // Relevante Action erkannt
            Timber.d("[KOLIBRI] Relevant action detected. Attempting to send signal...")

            // A genuine uninstall (not an app update). During an update the
            // system fires ACTION_PACKAGE_REMOVED with EXTRA_REPLACING=true
            // followed by ACTION_PACKAGE_ADDED for the same package — treating
            // that as an uninstall would wipe the user's swipe assignment on
            // every update. Only a non-replacing removal triggers reconcile.
            // Short-circuit keeps getBooleanExtra off the ADDED path.
            val isUninstall = action == Intent.ACTION_PACKAGE_REMOVED &&
                !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

            scope.launch {
                try {
                    withTimeout(SIGNAL_TIMEOUT_MS) {
                        processPackageUpdate(context, packageName, isUninstall, onFinish)
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

    private suspend fun processPackageUpdate(
        context: Context,
        packageName: String,
        isUninstall: Boolean,
        onFinish: () -> Unit
    ) {
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
                appUpdateSignal.sendUpdateSignal()
                Timber.d("[KOLIBRI] Update signal sent successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "[KOLIBRI] Failed to send update signal")
            }

            // On a genuine uninstall, clear any swipe assignment that pointed
            // at the removed package (TODO §24 — cleanup is event-driven, no
            // longer inferred on the swipe-launch path). Independent of the
            // reload signal above: a failure here must not skip that.
            if (isUninstall && packageName != UNKNOWN_PACKAGE) {
                try {
                    hiltEntryPoint.getClearSwipeActionsForPackageUseCase().invoke(packageName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "[KOLIBRI] Failed to reconcile swipe actions for removed package")
                }
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