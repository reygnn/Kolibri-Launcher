package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.launcher.core.DefaultDispatcher
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.model.displayName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The drawer's app list: prime the shared loader, project to [LauncherApp], then
 * sort by display name (case-insensitive). On any non-usable load the drawer
 * shows empty (the reconcile path is where a load error actually matters).
 * Sorting is the consumer's job, not the repository's (APPLIST_SORT_SPLIT posture).
 *
 * MIGRATION (SHARED_INSTALLED_APPS_SPEC §2 "Ladevertrag"): Nyx's former one-shot
 * `InstalledAppsRepository.loadInstalledApps(): AppLoadResult` is replaced by the
 * shared reactive `Flow<AppLoad>` that won over Nyx's pull-on-open shape. Because
 * that flow is a `WhileSubscribed` `StateFlow` seeded with `Loaded(emptyList())`,
 * a cold reader must wait for the first *non-empty* `Loaded` rather than take the
 * conflated initial value — the canonical prime pattern
 * ([AppConstants.INSTALLED_APPS_PRIME_TIMEOUT_MS]). A persistent failure surfaces
 * as `AppLoad.Failed`, which never satisfies the predicate, so the timeout bounds
 * that case too; either way the drawer falls back to empty.
 *
 * OVERLAY GAP (consumer TODO): the shared [AppInfo] carries no `customName` — it
 * is a per-app overlay (SIA-INV-3). This projection sets `customName = null`, so
 * the custom-name feature is NOT wired here yet. When Nyx lifts its custom-names
 * store to a Klasse-B overlay, apply it over this projection (join by
 * [AppInfo.key]) before the sort; the sort already keys on
 * [LauncherApp.displayName] (`customName ?: label`), so it needs no change then.
 */
class GetDrawerAppsUseCase @Inject constructor(
    private val repository: InstalledAppsRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): List<LauncherApp> = withContext(dispatcher) {
        // [F3] We read the shared LOADER directly, not the shared holder
        // (InstalledAppsStateRepository). Nyx's pull-on-open pattern needs no
        // holder, so there is intentionally NO keep-last-good here (SIA-INV-5 does
        // not cover Nyx); the drawer just re-primes on each open.
        // [F4] The prime waits for the first NON-EMPTY Loaded. On a real device
        // (always >= 1 launchable app) this returns fast; a genuinely-empty device
        // never satisfies the predicate and falls through the 10 s timeout to an
        // empty drawer — a latency edge, not a hang.
        val loaded: AppLoad.Loaded? = withTimeoutOrNull(AppConstants.INSTALLED_APPS_PRIME_TIMEOUT_MS) {
            repository.getInstalledApps()
                .filterIsInstance<AppLoad.Loaded>()
                .first { it.apps.isNotEmpty() }
        }
        loaded?.apps
            ?.map { it.toLauncherApp() }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()
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
