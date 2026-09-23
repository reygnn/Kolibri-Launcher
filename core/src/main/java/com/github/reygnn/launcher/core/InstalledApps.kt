package com.github.reygnn.launcher.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The two-stage shared installed-apps engine, contracts (SHARED_INSTALLED_APPS_SPEC
 * §3). Split across three declarations kept together here for readability; each is
 * product-neutral (`com.github.reygnn.launcher.core`, MRG-INV-6) and consumed by
 * both apps. Implementations live in `:common-data`.
 */

/**
 * Stage 1 — the reactive loader. Hot stream of the installed-app list as a typed
 * [AppLoad]: [AppLoad.Loaded] (possibly empty) or [AppLoad.Failed] on a load
 * error — never an empty list masquerading as a failure (SIA-INV-2).
 *
 * Carries Kolibri's battle-tested `Flow<AppLoad>` + external trigger shape (which
 * *won* over Nyx's pull-on-open one-shot, SHARED_INSTALLED_APPS_SPEC §2
 * "Ladevertrag"). A thin platform wrapper around [AppEnumerator]; it keeps its
 * historical `NO CONTRACT TEST (ADR)` marker (the enumerator seam is verified by
 * an androidTest against the platform, plus a JVM test of the fail-closed/debounce
 * motor).
 */
interface InstalledAppsRepository : Purgeable {
    fun getInstalledApps(): Flow<AppLoad>
    suspend fun triggerAppsUpdate()
}

/**
 * Stage 2 — the canonical in-RAM holder (SIA-INV-1: one holder, one source).
 * Holds the **raw** enumerated list (SIA-INV-3) plus a value-based last-good
 * fallback against empty-flicker (SIA-INV-5). Overlays (favorite/hidden/
 * customName/sort) are applied by each app's `Get*AppsUseCase` over [rawAppsFlow],
 * never stored here.
 */
interface InstalledAppsStateRepository : Purgeable {
    val rawAppsFlow: StateFlow<List<AppInfo>>
    fun updateApps(newApps: List<AppInfo>)
    fun getCurrentApps(): List<AppInfo>
}

/**
 * Force a re-enumeration. Neutral, trivially shared: the package-broadcast
 * pipeline (`PackageUpdateReceiver` → [AppUpdateSignal] → this) and the rare
 * deliberate force-reloads (Resume, locale change, factory reset) both funnel
 * through [InstalledAppsRepository.triggerAppsUpdate] (SHARED_INSTALLED_APPS_SPEC
 * §2 "Freshness").
 */
class RefreshAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
) {
    suspend operator fun invoke() {
        installedAppsRepository.triggerAppsUpdate()
    }
}
