package com.github.reygnn.launcher.common.data.installedapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.github.reygnn.launcher.core.PackageEvent
import com.github.reygnn.launcher.core.TimberWrapper
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Package-lifecycle broadcast → typed [PackageEvent] on the shared
 * [AppUpdateSignal] bus (SHARED_INSTALLED_APPS_SPEC §2 "Freshness"; lifted from
 * Kolibri, which won over Nyx's `LauncherApps.Callback`-only refresh). The bus and
 * its consumers stay Android-free; the Intent→event mapping happens here at the
 * `:common-data` edge. Both apps register this one receiver at RUNTIME (Kolibri via
 * `KolibriLauncherApp.registerPackageUpdateReceiver`, Nyx via
 * `PackageEventCoordinator.registerReceiver`) — there is deliberately no manifest
 * `<receiver>` entry in either app. Each app process registers its own instance, but
 * they share one *class* (SIA-INV-1 / MRG-INV-1).
 *
 * [PackageEvent.Removed] is only emitted for a genuine uninstall: the replace half
 * of an in-place update (`EXTRA_REPLACING`) is filtered; the paired
 * `PACKAGE_ADDED` refreshes.
 */
class PackageUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val SIGNAL_TIMEOUT_MS = 3000L
        private const val TAG = "[INSTALLED_APPS]"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Timber.w("$TAG Receiver called with null context or intent")
            return
        }

        val pendingResult = try {
            goAsync()
        } catch (e: Throwable) {
            // No suspension point here; synchronous body.
            TimberWrapper.silentError(e, "$TAG Failed to call goAsync(), processing synchronously")
            null
        }

        try {
            handleReceive(context, intent) {
                try {
                    pendingResult?.finish()
                } catch (e: Throwable) {
                    // no suspension point (finish() is synchronous)
                    TimberWrapper.silentError(e, "$TAG Error finishing pendingResult")
                }
            }
        } catch (e: Throwable) {
            // no suspension point (handleReceive is a plain fun; the coroutine it starts is
            // fire-and-forget and guards its own cancellation)
            TimberWrapper.silentError(e, "$TAG CRITICAL error in onReceive")
            try {
                pendingResult?.finish()
            } catch (e2: Throwable) {
                TimberWrapper.silentError(e2, "$TAG Error finishing pendingResult after exception")
            }
        }
    }

    @VisibleForTesting
    fun handleReceive(context: Context, intent: Intent, onFinish: () -> Unit) {
        try {
            val action = intent.action
            val packageName = intent.data?.schemeSpecificPart ?: "unknown"

            // Log a stable hash, not the raw package name (PII).
            val packageHash = packageName.hashCode().toString(16)
            Timber.d("$TAG Receiver triggered. Action: $action, packageHash: $packageHash")

            if (action == null) {
                Timber.w("$TAG Received intent with null action")
                safeOnFinish(onFinish)
                return
            }

            if (action != Intent.ACTION_PACKAGE_ADDED &&
                action != Intent.ACTION_PACKAGE_REMOVED &&
                action != Intent.ACTION_PACKAGE_CHANGED
            ) {
                Timber.d("$TAG Irrelevant action: $action")
                safeOnFinish(onFinish)
                return
            }

            // Skip the removal half of an in-place update (replace); the paired
            // PACKAGE_ADDED refreshes. A replace is not an uninstall.
            if (action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                Timber.d("$TAG PACKAGE_REMOVED is a replace; paired ADDED will refresh. Skipping.")
                safeOnFinish(onFinish)
                return
            }

            val event = mapToPackageEvent(action, packageName)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scope.launch {
                try {
                    withTimeout(SIGNAL_TIMEOUT_MS) {
                        processPackageUpdate(context, event, onFinish)
                    }
                } catch (e: CancellationException) {
                    Timber.d("$TAG Coroutine was cancelled")
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "$TAG Error in coroutine")
                    safeOnFinish(onFinish)
                }
            }
        } catch (e: Throwable) {
            // no suspension point (synchronous setup; scope.launch does not suspend the caller)
            TimberWrapper.silentError(e, "$TAG CRITICAL error in handleReceive")
            safeOnFinish(onFinish)
        }
    }

    /**
     * Maps a relevant package action to a typed [PackageEvent]. Only ADDED/
     * REMOVED/CHANGED reach here (guards in [handleReceive]); a genuine uninstall
     * (REMOVED, replace already filtered) is the `else` case.
     */
    @VisibleForTesting
    internal fun mapToPackageEvent(action: String, packageName: String): PackageEvent =
        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> PackageEvent.Added(packageName)
            Intent.ACTION_PACKAGE_CHANGED -> PackageEvent.Changed(packageName)
            else -> PackageEvent.Removed(packageName)
        }

    private suspend fun processPackageUpdate(context: Context, event: PackageEvent, onFinish: () -> Unit) {
        try {
            val appContext = context.applicationContext ?: context

            val hiltEntryPoint = try {
                EntryPointAccessors.fromApplication(
                    appContext,
                    InstalledAppsEntryPoint::class.java,
                )
            } catch (e: Throwable) {
                // no suspension point (EntryPointAccessors.fromApplication is synchronous); the
                // suspend send() below carries its own CancellationException-first arm
                TimberWrapper.silentError(e, "$TAG Failed to access Hilt entry point")
                return
            }

            val appUpdateSignal = hiltEntryPoint.getAppUpdateSignal()
            try {
                appUpdateSignal.send(event)
                Timber.d("$TAG Update signal sent successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "$TAG Failed to send update signal")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "$TAG Error in processPackageUpdate")
        } finally {
            safeOnFinish(onFinish)
        }
    }

    private fun safeOnFinish(onFinish: () -> Unit) {
        try {
            onFinish()
        } catch (e: Throwable) {
            // no suspension point (onFinish is a plain () -> Unit callback)
            TimberWrapper.silentError(e, "$TAG Error in onFinish callback")
        }
    }
}
