package com.github.reygnn.launcher.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The ONE shared pump that keeps the in-RAM holder ([InstalledAppsStateRepository])
 * fed from the reactive loader ([InstalledAppsRepository]), with the keep-last-good
 * arbitration (SIA-INV-5) — now CROSS-LAUNCHER, the successor to the state where the
 * pump lived only in kolibri (`ObserveInstalledAppsUseCase`) and nyx read the loader
 * directly without a holder.
 *
 * It lives here in `:core`, product-neutral, so both launchers get IDENTICAL
 * retention by construction (one implementation, not two): the same rule as
 * [LazySlotMembership] applied to the freshness/retention seam rather than the
 * missing-membership seam. Overlays (favorite / hidden / customName / sort / the home
 * layout) are NOT its business — each app applies those in its own `Get*UseCase` over
 * [InstalledAppsStateRepository.rawAppsFlow] / [InstalledAppsStateRepository.getCurrentApps],
 * exactly where SHARED_INSTALLED_APPS_SPEC draws the line. This piece does only the
 * feed + the empty/failed arbitration, and reports each load as an [Outcome] so an app
 * can layer its own reaction on top (kolibri maps it to `AppLoadResult` + an ACRA veto;
 * nyx just drains it to keep the holder warm).
 *
 * The feed itself is the deliberate side effect inside [outcomes]: it is total and
 * cannot throw (the holder ops are CANT_THROW, Rule 11), so `map` is safe here. What
 * each case does mirrors the historical kolibri pump exactly:
 * - [AppLoad.Loaded] non-empty → `updateApps(apps)` (also refreshes last-good in the
 *   holder), [Outcome.Loaded].
 * - [AppLoad.Loaded] empty → `updateApps(emptyList())` records the empty snapshot in
 *   `rawAppsFlow`; the holder KEEPS its last-good for the point-read consumers
 *   ([InstalledAppsStateRepository.getCurrentApps] falls back). [Outcome.EmptyLoaded].
 * - [AppLoad.Failed] → the holder is NOT touched (last-good stays in place). Whether
 *   this is user-visible depends on whether the holder ever held apps: [Outcome.FailedNoCache]
 *   (cold-start failure, genuinely nothing to show — a developer-must-act fault) vs.
 *   [Outcome.FailedKeptLastGood] (a transient glitch masked by last-good — stay quiet).
 */
class SyncInstalledAppsToHolder @Inject constructor(
    private val loader: InstalledAppsRepository,
    private val holder: InstalledAppsStateRepository,
) {

    /**
     * Hot: mirrors the shared loader into the holder for as long as it is collected,
     * emitting one [Outcome] per load. Collecting this flow IS what keeps the holder
     * warm — an app runs exactly one long-lived collection of it (nyx: an app-scoped
     * pump; kolibri: `ObserveInstalledAppsUseCase` wrapping it).
     */
    fun outcomes(): Flow<Outcome> =
        loader.getInstalledApps().map { load ->
            when (load) {
                is AppLoad.Loaded -> {
                    // Records the snapshot (empty or not); the holder itself only
                    // advances its last-good on a NON-empty list (SIA-INV-5).
                    holder.updateApps(load.apps)
                    if (load.apps.isEmpty()) Outcome.EmptyLoaded else Outcome.Loaded
                }

                is AppLoad.Failed -> {
                    // Do NOT write the holder: leave the last known state in place so a
                    // transient failure never blanks a consumer. The only distinction is
                    // whether we have ANYTHING to show.
                    if (holder.getCurrentApps().isEmpty()) {
                        Outcome.FailedNoCache(load.cause)
                    } else {
                        Outcome.FailedKeptLastGood
                    }
                }
            }
        }

    /**
     * The per-load result, product-neutral. An app maps these to its own reaction; a
     * launcher that needs nothing (just the warm holder) drains the flow and ignores them.
     */
    sealed interface Outcome {
        /** A non-empty list was loaded and stored. */
        data object Loaded : Outcome

        /** A genuinely empty list was loaded and recorded; last-good is preserved. */
        data object EmptyLoaded : Outcome

        /** A load failed but the holder still has a last-good list — nothing to surface. */
        data object FailedKeptLastGood : Outcome

        /** A load failed and the holder has never held apps — the launcher shows nothing. */
        data class FailedNoCache(val cause: Throwable) : Outcome
    }
}
