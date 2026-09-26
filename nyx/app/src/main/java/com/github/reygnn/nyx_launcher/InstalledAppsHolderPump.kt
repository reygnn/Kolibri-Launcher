package com.github.reygnn.nyx_launcher

import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.SyncInstalledAppsToHolder
import com.github.reygnn.launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
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
 * Reporting + resilience (kolibri parity, plus a nyx-specific restart):
 * - [SyncInstalledAppsToHolder.Outcome.FailedNoCache] (a cold-start load failed and the
 *   holder never held apps → the launcher shows no apps) is the single report site, sent
 *   through [TimberWrapper.reportToAcra] exactly like kolibri's `ObserveInstalledAppsUseCase`.
 *   The quiet outcomes (Loaded / EmptyLoaded / FailedKeptLastGood) need no reaction.
 * - A freak upstream error must not permanently freeze the holder. Unlike kolibri's
 *   ViewModel-lifecycle collection (which self-heals when the UI returns), this pump is a
 *   process-lifetime @Singleton with one collection, so [retryWhen] re-subscribes it (which
 *   re-primes the feed) after a short backoff, reporting via [TimberWrapper.silentError].
 *   Cancellation always propagates. Own [SupervisorJob] scope on the IO dispatcher (Rule 7).
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
                .onEach { outcome ->
                    if (outcome is SyncInstalledAppsToHolder.Outcome.FailedNoCache) {
                        TimberWrapper.reportToAcra(outcome.cause, "App load failed and no cache available")
                    }
                }
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) return@retryWhen false // propagate cancellation
                    TimberWrapper.silentError(cause, "InstalledAppsHolderPump: outcomes flow failed, restarting")
                    delay(RESTART_DELAY_MS)
                    true
                }
                .collect { /* feed is the side effect inside outcomes(); the report above is the only reaction */ }
        }
    }

    private companion object {
        const val RESTART_DELAY_MS = 1000L
    }
}
