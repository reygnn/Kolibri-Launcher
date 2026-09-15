package com.github.reygnn.nyx_launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.annotation.VisibleForTesting
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-lifecycle glue for package + memory events (the only wiring that turns the
 * pure reconcile + hand-rolled cache into live behaviour):
 *
 * - package removed/changed → [IconLoader.evict] (drop stale icons, ICL-INV-3)
 *   immediately, then a debounced [ReconcileHomeLayoutUseCase] (prune the layout,
 *   fail-closed).
 * - [start] also runs one reconcile for cold-start catch-up (changes that
 *   happened while Nyx wasn't running). The device grid is re-fit separately,
 *   from the real home-grid area, in MainActivity.
 * - [onTrimMemory] forwards to [IconLoader.trim] (ICL-INV-7).
 *
 * Reconcile requests are coalesced through a conflated flow debounced by
 * [AppConstants.APP_RELOAD_DEBOUNCE_MS]: a package-event storm (system update,
 * app restore, bulk install) fires many callbacks, and each reconcile is a full
 * [LauncherApps] enumeration plus an atomic DataStore read-modify-write. The
 * debounce collapses the storm to a single reconcile once it settles. The
 * priming `flowOf(Unit)` bypasses the debounce, so the cold-start catch-up runs
 * immediately. Per-package icon eviction stays immediate — it is cheap and must
 * drop a stale icon promptly.
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

    // Conflated: an event landing while a debounce window is already pending only
    // needs to keep the window alive, not queue extra reconciles.
    private val reconcileRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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

    @OptIn(FlowPreview::class) // Flow.debounce(Long)
    fun start() {
        scope.launch {
            merge(
                flowOf(Unit), // cold-start catch-up: immediate, bypasses the debounce
                reconcileRequests.debounce(AppConstants.APP_RELOAD_DEBOUNCE_MS),
            ).collect {
                // Guard the single long-lived collector: a throwing reconcile (e.g. a
                // transient DataStore IOException) must not tear the collector down and
                // silently stop all future reconciles. Log and continue; cancellation
                // still propagates. Mirrors the house idiom (LaunchSafe, NyxResetManager).
                try {
                    reconcile()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "PackageEventCoordinator: reconcile failed, skipping")
                }
            }
        }
        launcherApps.registerCallback(callback)
    }

    fun onTrimMemory(level: Int) {
        iconLoader.trim(level)
        folderRenderer.clear()
    }

    private fun onChanged(packageName: String) {
        iconLoader.evict(packageName)
        folderRenderer.clear() // a member's icon may have changed
        requestReconcile()
    }

    /** Coalesced, debounced reconcile trigger. Visible for testing the debounce. */
    @VisibleForTesting
    internal fun requestReconcile() {
        reconcileRequests.tryEmit(Unit)
    }
}
