package com.github.reygnn.launcher.common.data.installedapps

import androidx.annotation.VisibleForTesting
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppEnumerator
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shared installed-apps **motor** (SHARED_INSTALLED_APPS_SPEC §3): Kolibri's
 * battle-tested two-stage engine, lifted into `:common-data` and made
 * platform-agnostic. It owns everything product-neutral — the reactive
 * `Flow<AppLoad>`, the priming emit, the debounce against broadcast storms, the
 * `stateIn(WhileSubscribed)` sharing, and the fail-as-value error envelope. The
 * one thing it does NOT own is *how* apps are enumerated: that is delegated to the
 * injected [AppEnumerator] (SIA-INV-4). With §9.1 resolved that is always
 * [LauncherAppsEnumerator]; the PackageManager path this class used to carry is
 * gone.
 *
 * **Data flow.**
 * 1. External trigger via [triggerAppsUpdate] (package broadcasts + deliberate
 *    force-reloads).
 * 2. [reloadTriggers] primes once + debounces the trigger, `flatMapLatest` into
 *    [loadFromEnumerator].
 * 3. [loadFromEnumerator] calls `enumerator.enumerate()`; a throw becomes
 *    [AppLoad.Failed], a success (possibly empty) becomes [AppLoad.Loaded].
 * 4. `stateIn(WhileSubscribed)` shares the latest [AppLoad] to all collectors.
 *
 * **Error handling (SIA-INV-2).** A load error is [AppLoad.Failed], never
 * collapsed into `Loaded(emptyList())` (a `stateIn` StateFlow never delivers an
 * upstream exception to its collector, which would render downstream
 * retry/error-recovery dead). The loader catches its own throwables but emits
 * [AppLoad.Failed]. [CancellationException] is always re-thrown — including from
 * the hand-written `try { emit } catch` arms inside `Flow.catch { }`, where `emit`
 * is a suspension point. This is the house broad-catch-at-a-suspension-point idiom
 * (Kolibri Rule 11 `cancel_files`): this file is listed in that positive-list
 * detector by its `:common-data` path (alongside the other shared impls, e.g.
 * `WallpaperRepositoryImpl`), so the guard IS enforced. The global `Flow.catch`
 * scan roots only on the kolibri/nyx app/domain/data trees, not `:common-data` —
 * but the CancellationException-first arms are load-bearing regardless of lint.
 *
 * **Empty (§9.2).** `Loaded(emptyList())` is a legitimate value; the motor never
 * treats empty as failure. "empty ⇒ suspicious" is a per-app reconcile policy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class InstalledAppsRepositoryImpl @Inject constructor(
    private val enumerator: AppEnumerator,
    private val appsUpdateTrigger: MutableSharedFlow<Unit>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : InstalledAppsRepository {

    // Own sharing scope on IO. Built from the injected dispatcher so a virtual-time
    // test can drive it (the WhileSubscribed timeout is a §8 risk to re-check
    // against Nyx's drawer lifecycle).
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val appsStateFlow: StateFlow<AppLoad> = reloadTriggers(appsUpdateTrigger)
        .flatMapLatest {
            loadFromEnumerator()
        }
        .catch { e ->
            if (e is CancellationException) throw e
            try {
                // Expected transient load failure — modeled as a VALUE (Failed),
                // breadcrumb only (Timber.d, not reported). The single report site
                // is each app's no-cache reconcile branch (anti-flood).
                Timber.d(e, "Apps flow error; reporting Failed")
                emit(AppLoad.Failed(e))
            } catch (catchError: CancellationException) {
                // emit() is a suspension point: a cancelled collector lands its
                // CancellationException here — rethrow, don't log below.
                throw catchError
            } catch (catchError: Throwable) {
                TimberWrapper.silentError(catchError, "CRITICAL: emit failed in apps flow catch")
                emit(AppLoad.Failed(e))
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS),
            initialValue = AppLoad.Loaded(emptyList()),
        )

    /**
     * Immediate priming emit + debounced external triggers (DEBOUNCE_SPEC). The
     * priming `flowOf(Unit)` bypasses the debounce so the cold-start load is not
     * delayed (DBNC-INV-1); a burst of triggers within the window collapses to one
     * reload (DBNC-INV-4). Re-emitted on every (re-)subscription of the sharing
     * StateFlow. `@VisibleForTesting internal` so the debounce/priming can be
     * pinned on a virtual-time dispatcher.
     */
    @VisibleForTesting
    internal fun reloadTriggers(trigger: Flow<Unit>): Flow<Unit> =
        merge(
            flowOf(Unit),
            trigger.debounce(AppConstants.APP_RELOAD_DEBOUNCE_MS),
        )

    override fun getInstalledApps(): Flow<AppLoad> = appsStateFlow

    override suspend fun triggerAppsUpdate() {
        try {
            Timber.d("App update triggered.")
            appsUpdateTrigger.emit(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error triggering apps update")
            // Update-Fehler dürfen nicht crashen.
        }
    }

    private fun loadFromEnumerator(): Flow<AppLoad> = flow {
        try {
            // The enumerator returns the raw list (SIA-INV-3) and THROWS on a real
            // enumeration failure → mapped to Failed here. An empty result is a
            // legitimate Loaded(empty) (§9.2). No sort: the holder holds raw.
            val fresh = enumerator.enumerate()
            Timber.d("Enumerator returned ${fresh.size} apps")
            emit(AppLoad.Loaded(fresh))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.d(e, "Error enumerating apps; reporting Failed")
            emit(AppLoad.Failed(e))
        }
    }
        .catch { e ->
            if (e is CancellationException) throw e
            try {
                Timber.d(e, "Flow catch: error in loadFromEnumerator; reporting Failed")
                emit(AppLoad.Failed(e))
            } catch (catchError: CancellationException) {
                throw catchError
            } catch (catchError: Throwable) {
                TimberWrapper.silentError(catchError, "CRITICAL: emit failed in flow catch block")
            }
        }
        .flowOn(ioDispatcher)

    override suspend fun purgeRepository() {
        // NICHTS TUN! Die App-Liste kommt direkt vom System (LauncherApps); es gibt
        // keinen persistierten User-State zum Leeren. Ein Neuladen erfolgt über
        // triggerAppsUpdate().
    }
}
