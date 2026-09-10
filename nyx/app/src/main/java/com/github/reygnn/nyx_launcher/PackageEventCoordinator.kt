package com.github.reygnn.nyx_launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.di.IoDispatcher
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-lifecycle glue for package + memory events (the only wiring that turns the
 * pure reconcile + hand-rolled cache into live behaviour):
 *
 * - package removed/changed → [IconLoader.evict] (drop stale icons, ICL-INV-3)
 *   then [ReconcileHomeLayoutUseCase] (prune the layout, fail-closed).
 * - [start] also runs one reconcile for cold-start catch-up (changes that
 *   happened while Nyx wasn't running).
 * - [onTrimMemory] forwards to [IconLoader.trim] (ICL-INV-7).
 *
 * Uses [LauncherApps.Callback] — the launcher-idiomatic API — not a manifest
 * broadcast receiver.
 */
@Singleton
class PackageEventCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iconLoader: IconLoader,
    private val folderRenderer: FolderIconRenderer,
    private val reconcile: ReconcileHomeLayoutUseCase,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val launcherApps: LauncherApps
        get() = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = onChanged(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = onChanged(packageName)

        // Adding a package can't invalidate an existing icon or orphan a layout
        // item; the drawer re-queries on next open. Nothing to do.
        override fun onPackageAdded(packageName: String, user: UserHandle) = Unit

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = Unit

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = Unit
    }

    fun start() {
        launcherApps.registerCallback(callback)
        scope.launch { reconcile() } // cold-start catch-up
    }

    fun onTrimMemory(level: Int) {
        iconLoader.trim(level)
        folderRenderer.clear()
    }

    private fun onChanged(packageName: String) {
        iconLoader.evict(packageName)
        folderRenderer.clear() // a member's icon may have changed
        scope.launch { reconcile() }
    }
}
