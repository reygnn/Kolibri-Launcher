package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.launcher.core.SyncInstalledAppsToHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.github.reygnn.launcher.core.KolibriLog
import javax.inject.Inject

class ObserveInstalledAppsUseCase @Inject constructor(
    private val syncInstalledAppsToHolder: SyncInstalledAppsToHolder,
) {

    /**
     * Activates the flow that loads the installed apps, updates the central state,
     * and emits an [AppLoadResult] telling the ViewModel whether a user-visible
     * error occurred.
     *
     * The load + holder feed + keep-last-good arbitration now live in the shared
     * [SyncInstalledAppsToHolder] (`:core`), used verbatim by nyx too — parity by
     * construction, one implementation of the retention seam. This use case is the
     * kolibri-SPECIFIC layer on top: it maps each [SyncInstalledAppsToHolder.Outcome]
     * to the [AppLoadResult] the kolibri UI consumes, does the ACRA report on a
     * cold-start no-cache failure (kolibri's consent-gated pipeline; nyx does not
     * report here), and keeps the crash-safety net around the collection (Rule 7/9).
     *
     * Stored user assignments (favorites / swipe / hidden / custom names) are NOT
     * reconciled here: nothing is auto-pruned against the load. A reference to a
     * no-longer-installed app is kept and handled lazily at the point of use
     * (the Windows-shortcut model, root TODO.md). The shared pump does no reconcile
     * either — it only feeds the holder.
     */
    operator fun invoke(): Flow<AppLoadResult> = flow {
        try {
            syncInstalledAppsToHolder.outcomes()
                .collect { outcome ->
                    try {
                        when (outcome) {
                            SyncInstalledAppsToHolder.Outcome.Loaded ->
                                // The holder has the fresh list; the UI has nothing to do.
                                emit(AppLoadResult.Success)

                            SyncInstalledAppsToHolder.Outcome.EmptyLoaded ->
                                // A genuinely empty load was recorded without emitting
                                // Success (IAL-INV-3). No error either: an empty device
                                // is not a fault.
                                KolibriLog.w("Loaded an empty app list; recording the empty snapshot without emitting Success.")

                            SyncInstalledAppsToHolder.Outcome.FailedKeptLastGood ->
                                // A glitch recovered from last-good stays silent, so a
                                // package settling during a system update does not flood
                                // ACRA (Rule-9).
                                KolibriLog.d("App load failed; keeping last good list")

                            is SyncInstalledAppsToHolder.Outcome.FailedNoCache -> {
                                // The holder has genuinely never held apps (a cold start
                                // that failed): the single report site — a developer-must-act
                                // fault (launcher shows no apps) — through reportToAcra
                                // (ACRA_REPORT intent tag, report-by-intent §23).
                                TimberWrapper.reportToAcra(outcome.cause, "App load failed and no cache available")
                                emit(AppLoadResult.Error(AppLoadResult.Failure.NotLoaded))
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
