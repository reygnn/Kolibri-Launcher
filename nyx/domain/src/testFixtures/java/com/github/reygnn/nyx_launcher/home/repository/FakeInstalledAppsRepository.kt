package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.AppLoadResult
import com.github.reygnn.nyx_launcher.home.model.LauncherApp

/** Returns a fixed [AppLoadResult]; the [List] ctor wraps it as [AppLoadResult.Loaded]. */
class FakeInstalledAppsRepository(
    private val result: AppLoadResult,
) : InstalledAppsRepository {
    constructor(apps: List<LauncherApp>) : this(AppLoadResult.Loaded(apps))
    override suspend fun loadInstalledApps(): AppLoadResult = result
}
