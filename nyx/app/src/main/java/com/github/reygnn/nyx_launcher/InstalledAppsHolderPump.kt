package com.github.reygnn.nyx_launcher

import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.KolibriLog
import com.github.reygnn.launcher.core.SyncInstalledAppsToHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's app-scoped driver for the shared [SyncInstalledAppsToHolder] (Option A: nyx
 * adopts the central in-RAM holder + keep-last-good, SIA-INV-5, previously kolibri-only).
 *
 * kolibri drives the same shared pump through `ObserveInstalledAppsUseCase`, collected
 * by a ViewModel while the launcher UI is up. Nyx has no equivalent always-on app-list
 * use case, so this thin @Singleton owns the one long-lived collection instead, started
 * from [NyxApplication.onCreate] next to [PackageEventCoordinator.start]. Draining
 * [SyncInstalledAppsToHolder.outcomes] is what keeps the holder warm: every consumer
 * (the drawer's `getCurrentApps()` point-read, `HomeViewModel.installedKeys` over
 * `rawAppsFlow`) now reads the holder, and the holder always reflects the latest load
 * plus a last-good fallback.
 *
 * POSTURE (SIA-INV-5, now cross-launcher): this makes nyx an always-warm holder rather
 * than its historical pull-on-open — the deliberate trade decided in Option A. The
 * package-event refresh ([PackageEventCoordinator] → `triggerAppsUpdate()`) drives the
 * loader to re-enumerate; this pump then lands the fresh list in the holder, so a tile
 * greys live on uninstall and the drawer stays fresh without re-priming on open.
 *
 * Crash-safety mirrors [PackageEventCoordinator]: its own [SupervisorJob] scope on the
 * IO dispatcher and a `catch` that swallows non-cancellation throwables (the shared
 * pump's feed is total, but a defensive net keeps a freak upstream error from taking
 * the process down — Rule 7).
 */
@Singleton
class InstalledAppsHolderPump @Inject constructor(
    private val sync: SyncInstalledAppsToHolder,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun start() {
        scope.launch {
            sync.outcomes()
                .catch { e ->
                    if (e is CancellationException) throw e
                    KolibriLog.w(e, "InstalledAppsHolderPump: outcomes flow failed")
                }
                .collect { /* feed is the side effect inside outcomes(); nyx needs no reaction */ }
        }
    }
}
