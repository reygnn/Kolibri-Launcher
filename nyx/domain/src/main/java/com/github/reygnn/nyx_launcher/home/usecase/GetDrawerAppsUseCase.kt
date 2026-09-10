package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.di.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.model.AppLoadResult
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.InstalledAppsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The drawer's app list: load, then sort by display name (`customName ?: label`,
 * case-insensitive). On a load error the drawer shows empty (the reconcile path
 * is where the error actually matters). Sorting is the consumer's job, not the
 * repository's (APPLIST_SORT_SPLIT posture).
 */
class GetDrawerAppsUseCase @Inject constructor(
    private val repository: InstalledAppsRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): List<LauncherApp> = withContext(dispatcher) {
        when (val result = repository.loadInstalledApps()) {
            is AppLoadResult.Loaded ->
                result.apps.sortedBy { (it.customName ?: it.label).lowercase() }
            is AppLoadResult.Error -> emptyList()
        }
    }
}
