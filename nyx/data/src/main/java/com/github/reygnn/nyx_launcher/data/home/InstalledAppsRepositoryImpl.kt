package com.github.reygnn.nyx_launcher.data.home

import android.content.pm.LauncherApps
import android.os.Process
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.nyx_launcher.home.model.AppLoadResult
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.InstalledAppsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Enumerates launchable activities via [LauncherApps] (v1: primary user only).
 * Wraps the query in [AppLoadResult] so a thrown OR empty enumeration becomes
 * [AppLoadResult.Error], never a silent empty list (RHL-INV-1): an empty result
 * on a real device signals a failed/partial load, and collapsing it to
 * `Loaded(emptyList())` would let reconcile prune and persist the whole home
 * layout. [LauncherApps] is injected so the fail-closed policy is unit-testable.
 */
class InstalledAppsRepositoryImpl @Inject constructor(
    private val launcherApps: LauncherApps,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : InstalledAppsRepository {

    override suspend fun loadInstalledApps(): AppLoadResult = withContext(dispatcher) {
        runCatching {
            launcherApps.getActivityList(null, Process.myUserHandle()).map { info ->
                LauncherApp(
                    key = ComponentKey(
                        packageName = info.componentName.packageName,
                        className = info.componentName.className,
                    ),
                    label = info.label.toString(),
                    customName = null,
                )
            }
        }.fold(
            onSuccess = { apps ->
                if (apps.isEmpty()) {
                    AppLoadResult.Error(AppLoadResult.Reason.ENUMERATION_EMPTY)
                } else {
                    AppLoadResult.Loaded(apps)
                }
            },
            onFailure = { AppLoadResult.Error(AppLoadResult.Reason.ENUMERATION_FAILED) },
        )
    }
}
