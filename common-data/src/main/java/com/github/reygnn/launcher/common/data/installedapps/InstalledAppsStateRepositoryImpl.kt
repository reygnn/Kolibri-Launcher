package com.github.reygnn.launcher.common.data.installedapps

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The canonical in-RAM holder (SIA-INV-1: one holder, one source), lifted into
 * `:common-data`. Sits between the loader ([InstalledAppsRepositoryImpl]) and each
 * app's consumers, holding the **raw** enumerated list (SIA-INV-3) and a
 * value-based last-good fallback against empty-flicker (SIA-INV-5).
 *
 * **Fail-safe (SIA-INV-5).** [getCurrentApps] falls back to [lastSuccessfulAppList]
 * when the live value is empty, so a transient empty never reaches a point-read
 * consumer. Value-based (`.ifEmpty {}`), not a second cache with its own
 * invalidation, and not a try/catch (the operations cannot throw — CANT_THROW,
 * Rule 11). [rawAppsFlow] itself still reflects the explicit current value
 * (including empty), so a reactive consumer that wants to react to "genuinely
 * empty" can.
 */
@Singleton
class InstalledAppsStateRepositoryImpl @Inject constructor() : InstalledAppsStateRepository {

    private val _rawAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override val rawAppsFlow: StateFlow<List<AppInfo>> = _rawAppsFlow

    @Volatile
    private var lastSuccessfulAppList: List<AppInfo> = emptyList()

    override fun updateApps(newApps: List<AppInfo>) {
        if (newApps.isNotEmpty()) {
            lastSuccessfulAppList = newApps
        }
        _rawAppsFlow.value = newApps
    }

    override fun getCurrentApps(): List<AppInfo> =
        _rawAppsFlow.value.ifEmpty { lastSuccessfulAppList }

    override suspend fun purgeRepository() {
        // NICHTS TUN! Der Holder hält nur System-abgeleiteten State (installierte
        // Apps), keinen persistierten User-State. Reload via triggerAppsUpdate().
        Timber.d("InstalledAppsStateRepositoryImpl: purge requested, no-op (system data)")
    }
}
