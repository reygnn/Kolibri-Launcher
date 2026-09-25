package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.github.reygnn.launcher.core.KolibriLog
import javax.inject.Inject

class ObserveInstalledAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val installedAppsStateRepository: InstalledAppsStateRepository,
) {

    /**
     * Activates the flow that loads the installed apps, updates the central state,
     * and emits an [AppLoadResult] telling the ViewModel whether a user-visible
     * error occurred.
     *
     * Stored user assignments (favorites / swipe / hidden / custom names) are NOT
     * reconciled here: nothing is auto-pruned against the load. A reference to a
     * no-longer-installed app is kept and handled lazily at the point of use
     * (the Windows-shortcut model, root TODO.md).
     *
     * The loader yields a typed [AppLoad] (INSTALLED_APPS_LOAD_SPEC Belang A):
     * a load failure arrives as [AppLoad.Failed], not as a collapsed empty list.
     * This makes the keep-last-good / error recovery LIVE (it used to sit behind a
     * `.catch`/`.retry` on a `stateIn` StateFlow that never delivers upstream
     * exceptions, so it was dead code). The old `.retry(IOException)` is gone: it
     * never fired in production, and the motivating PackageManager failures are not
     * `IOException` anyway. The `isEmpty()` guard STAYS (IAL-INV-3): a genuinely
     * empty load records the empty snapshot without emitting Success, and never
     * runs on the `stateIn` cold-start init.
     */
    operator fun invoke(): Flow<AppLoadResult> = flow {
        try {
            installedAppsRepository.getInstalledApps()
                .collect { load ->
                    try {
                        when (load) {
                            is AppLoad.Failed -> {
                                // Load failed (a distinguishable value, not an empty
                                // list). Keep-last-good now has exactly ONE home
                                // (INSTALLED_APPS_LOAD_SPEC Belang B / IAL-INV-4): the
                                // state holder. Its rawAppsFlow (a StateFlow) already
                                // retains the last emitted list, and its own last-good
                                // cache backs the point-read consumers (swipe/recent),
                                // so a transient failure writes NOTHING here — it just
                                // leaves the last known state in place. Not re-writing
                                // also restores the pre-AppLoad behavior for the
                                // Loaded(empty) → Failed corner, where the Commit-1
                                // updateApps(cachedApps) would wrongly revive a stale
                                // list over a genuinely-empty device.
                                //
                                // Report ONLY when the holder has genuinely never held
                                // apps (a cold start that failed): a glitch recovered
                                // from cache stays silent, so a package settling during
                                // a system update does not flood ACRA (Rule-9). The
                                // loader logs a debug breadcrumb only; this no-cache
                                // branch is the single report site — a genuine
                                // developer-must-act fault (launcher shows no apps), so
                                // it goes through reportToAcra (ACRA_REPORT intent tag,
                                // report-by-intent §23) rather than a bare WARN, which
                                // an untagged log no longer delivers.
                                if (installedAppsStateRepository.getCurrentApps().isEmpty()) {
                                    TimberWrapper.reportToAcra(load.cause, "App load failed and no cache available")
                                    emit(AppLoadResult.Error(AppLoadResult.Failure.NotLoaded))
                                } else {
                                    KolibriLog.d("App load failed; keeping last good list")
                                }
                            }

                            is AppLoad.Loaded -> {
                                val realApps = load.apps
                                if (realApps.isEmpty()) {
                                    KolibriLog.w("Loaded an empty app list; recording the empty snapshot without emitting Success.")
                                    installedAppsStateRepository.updateApps(emptyList())
                                    return@collect
                                }

                                // No store reconcile: stored user assignments
                                // (favorites / swipe / hidden / custom names) are NEVER
                                // auto-pruned against the load. A reference to an app that
                                // is no longer installed stays put and is handled lazily at
                                // the point of use (a "missing" home favorite, a swipe toast)
                                // — the Windows-shortcut model (root TODO.md, the
                                // no-auto-prune / lazy-user-confirmed-removal option). The
                                // whole deletion-gate apparatus (app-presence / install-session
                                // inspector / deletion-gate) is therefore gone: there is nothing
                                // to silently lose, so nothing to fail-safe against.
                                //
                                // Update the central state holder.
                                installedAppsStateRepository.updateApps(realApps)

                                // Signal success (the UI has nothing to do).
                                emit(AppLoadResult.Success)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error processing collected apps")
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error in ObserveInstalledAppsUseCase")
        }
    }

}
