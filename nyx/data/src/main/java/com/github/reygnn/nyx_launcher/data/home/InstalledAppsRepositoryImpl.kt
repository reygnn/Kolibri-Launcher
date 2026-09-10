package com.github.reygnn.nyx_launcher.data.home

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import com.github.reygnn.nyx_launcher.di.IoDispatcher
import com.github.reygnn.nyx_launcher.home.model.AppLoadResult
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.InstalledAppsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Enumerates launchable activities via [LauncherApps] (v1: primary user only).
 * Wraps the query in [AppLoadResult] so a thrown enumeration becomes
 * [AppLoadResult.Error], never a silent empty list (RHL-INV-1).
 */
class InstalledAppsRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : InstalledAppsRepository {

    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

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
            onSuccess = { AppLoadResult.Loaded(it) },
            onFailure = { AppLoadResult.Error(AppLoadResult.Reason.ENUMERATION_FAILED) },
        )
    }
}
