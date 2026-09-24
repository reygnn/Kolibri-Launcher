package com.github.reygnn.nyx_launcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.VisibleForTesting
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.launcher.common.data.installedapps.PackageUpdateReceiver
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppUpdateSignal
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
 * - package added/removed/changed → [IconLoader.evict] (drop stale icons, ICL-INV-3)
 *   immediately, then a debounced [ReconcileHomeLayoutUseCase] (prune the layout,
 *   fail-closed).
 * - [start] also runs one reconcile for cold-start catch-up (changes that
 *   happened while Nyx wasn't running). The device grid is re-fit separately,
 *   from the real home-grid area, in MainActivity.
 * - [onTrimMemory] forwards to [IconLoader.trim] (ICL-INV-7).
 *
 * **Freshness source (SHARED_INSTALLED_APPS_SPEC §2, F2 route (a)):** package
 * events arrive over the SHARED pipeline that both apps now use — the
 * `:common-data` [PackageUpdateReceiver] maps the broadcast to a [PackageEvent] on
 * the [AppUpdateSignal] bus; this coordinator collects that bus. This replaces
 * Nyx's former `LauncherApps.Callback`, which drove the same reconcile + icon
 * eviction from a Nyx-only mechanism: the bus carries the package name too, so it
 * fully subsumes the callback (keeping both would double-drive every event). The
 * bus→trigger step mirrors Kolibri's `AppManagementDelegate` collector, minus
 * Kolibri's reconcile overlays — Nyx's reconcile is [ReconcileHomeLayoutUseCase].
 *
 * The receiver is registered in CODE (Nyx has no manifest `<receiver>`; it
 * registered the old callback at runtime too), matching Kolibri's
 * `KolibriLauncherApp.registerPackageUpdateReceiver`.
 *
 * Reconcile requests are coalesced through a conflated flow debounced by
 * [AppConstants.APP_RELOAD_DEBOUNCE_MS]: a package-event storm (system update,
 * app restore, bulk install) fires many events, and each reconcile reads the
 * shared enumerator plus an atomic DataStore read-modify-write. The debounce
 * collapses the storm to a single reconcile once it settles. The priming
 * `flowOf(Unit)` bypasses the debounce, so the cold-start catch-up runs
 * immediately. Per-package icon eviction stays immediate — it is cheap and must
 * drop a stale icon promptly. The reconcile reads the shared enumerator directly
 * (a one-shot read in [ReconcileHomeLayoutUseCase]), so it always sees a fresh
 * list without this coordinator having to prime the shared loader.
 */
@Singleton
class PackageEventCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iconLoader: IconLoader,
    private val folderRenderer: FolderIconRenderer,
    private val reconcile: ReconcileHomeLayoutUseCase,
    private val appUpdateSignal: AppUpdateSignal,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Conflated: an event landing while a debounce window is already pending only
    // needs to keep the window alive, not queue extra reconciles.
    private val reconcileRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Registered in start(); no unregister — the coordinator is a process-lifetime
    // @Singleton, same as the former callback (and Kolibri's receiver).
    private val packageUpdateReceiver = PackageUpdateReceiver()

    @OptIn(FlowPreview::class) // Flow.debounce(Long)
    fun start() {
        // The debounced reconcile pump: cold-start catch-up (immediate) + coalesced
        // package-event reconciles.
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

        // The shared freshness bus (F2 route (a)): broadcast → :common-data
        // PackageUpdateReceiver → AppUpdateSignal → here. Each PackageEvent carries
        // the changed package, so this does everything the old LauncherApps.Callback
        // did — per-package icon eviction — and then requests a debounced reconcile.
        // The reconcile reads the shared enumerator directly (a one-shot enumerate()),
        // so no explicit re-enumeration trigger is needed here. Same guard idiom as
        // the reconcile pump.
        scope.launch {
            appUpdateSignal.events.collect { event ->
                try {
                    iconLoader.evict(event.packageName) // ICL-INV-3, targeted
                    folderRenderer.clear() // a member's icon may have changed
                    requestReconcile() // debounced layout prune (reconcile reads the enumerator directly)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "PackageEventCoordinator: package-event handling failed")
                }
            }
        }

        registerReceiver()
    }

    fun onTrimMemory(level: Int) {
        iconLoader.trim(level)
        folderRenderer.clear()
    }

    private fun registerReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            context.registerReceiver(packageUpdateReceiver, filter, Context.RECEIVER_EXPORTED)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "PackageEventCoordinator: could not register PackageUpdateReceiver")
        }
    }

    /** Coalesced, debounced reconcile trigger. Visible for testing the debounce. */
    @VisibleForTesting
    internal fun requestReconcile() {
        reconcileRequests.tryEmit(Unit)
    }
}
