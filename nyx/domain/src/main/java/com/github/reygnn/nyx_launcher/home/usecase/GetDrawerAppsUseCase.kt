package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.model.displayName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The drawer's app list: read the shared in-RAM HOLDER, project to [LauncherApp], then
 * sort by display name (case-insensitive). Sorting is the consumer's job, not the
 * repository's (APPLIST_SORT_SPLIT posture).
 *
 * OPTION A (SIA-INV-5, now cross-launcher): nyx reads the central
 * [InstalledAppsStateRepository] like kolibri, instead of priming the loader directly.
 * [InstalledAppsHolderPump] keeps the holder fed, so this is normally an instant
 * point-read via [InstalledAppsStateRepository.getCurrentApps] — which carries the
 * keep-last-good fallback, so a transient empty/failed reload never blanks the drawer
 * (the gap the old pull-on-open path had). To preserve the historical "first open waits
 * for the first real load" behaviour on a cold start (holder not fed yet), we wait for
 * the first NON-EMPTY [InstalledAppsStateRepository.rawAppsFlow] value up to the prime
 * timeout, then fall back to [InstalledAppsStateRepository.getCurrentApps] (last-good,
 * possibly empty on a genuinely-empty device — a latency edge, not a hang).
 *
 * OVERLAY GAP (consumer TODO, unchanged by Option A): the shared [AppInfo] carries no
 * `customName` — it is a per-app overlay (SIA-INV-3). This projection sets
 * `customName = null`, so the custom-name feature is NOT wired here yet. When nyx lifts
 * a custom-names store, apply it over this projection (join by [AppInfo.key]) before the
 * sort; the sort already keys on [LauncherApp.displayName] (`customName ?: label`).
 */
class GetDrawerAppsUseCase @Inject constructor(
    private val stateRepository: InstalledAppsStateRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): List<LauncherApp> = withContext(dispatcher) {
        // Normally the holder is already warm (InstalledAppsHolderPump); the wait only
        // bites on a fast cold open before the first enumeration lands. On timeout we
        // still return getCurrentApps() — the last-good snapshot (SIA-INV-5), empty only
        // on a genuinely-empty device.
        withTimeoutOrNull(AppConstants.INSTALLED_APPS_PRIME_TIMEOUT_MS) {
            stateRepository.rawAppsFlow.first { it.isNotEmpty() }
        }
        stateRepository.getCurrentApps()
            .map { it.toLauncherApp() }
            .sortedBy { it.displayName.lowercase() }
    }
}

/**
 * Projects the neutral shared [AppInfo] to Nyx's domain [LauncherApp]. `label` is
 * the system label ([AppInfo.originalName], not the pre-computed `displayName`),
 * and `customName` is left null: it is a per-app overlay applied at the consumer,
 * never carried in the shared model (SIA-INV-3, MRG-INV-9).
 */
private fun AppInfo.toLauncherApp(): LauncherApp =
    LauncherApp(key = key, label = originalName, customName = null)
