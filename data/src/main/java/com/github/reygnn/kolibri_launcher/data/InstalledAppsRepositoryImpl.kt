package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.annotation.VisibleForTesting
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Manager for loading and monitoring installed applications on the device.
 *
 * This singleton is responsible for querying the Android PackageManager to retrieve
 * all launchable applications and providing them as a reactive data stream. It acts
 * as the primary data source for the app list in the launcher.
 *
 * **Data Flow:**
 * 1. External trigger via [triggerAppsUpdate]
 * 2. Query PackageManager for LAUNCHER apps
 * 3. Process and enrich app metadata (names, display names)
 * 4. Emit the result as a typed [AppLoad] through a [kotlinx.coroutines.flow.StateFlow]
 *
 * **Error Handling (INSTALLED_APPS_LOAD_SPEC Belang A):**
 * A load error is represented as [AppLoad.Failed], NOT collapsed into an empty
 * list. Collapsing would make "empty" ambiguous and, because a `stateIn`
 * StateFlow never delivers an upstream exception to its collector, would render
 * the downstream retry/error-recovery dead. The loader still catches its own
 * throwables (it must not crash) but emits [AppLoad.Failed] instead of
 * `Loaded(emptyList())`. [java.util.concurrent.CancellationException] is always
 * re-thrown to preserve coroutine cancellation.
 *
 * That last guarantee includes the two hand-written `try { emit(...) } catch`
 * blocks inside the `Flow.catch { }` recovery arms (AUDIT-12 #7/#8): `emit` is a
 * suspension point, so a cancelled collector would otherwise be reported as a
 * `CRITICAL` error. This file is in the `cancel_files` whitelist of
 * `./gradlew checkConventions`, which is the regression guard for that: any
 * broad catch reachable from a suspend point must rethrow `CancellationException`
 * or the build fails. It is NOT unit-tested — the suspension point is the
 * framework `emit` inside a recovery arm that the surrounding inner catches make
 * nearly unreachable, so no injectable seam exists to land a cancellation there
 * deterministically; a test would be contrived timing theater. The structural
 * linter check is the honest guard here (see `app/src/test/CLAUDE.md`:
 * "ehrliches KDoc > zu viele Tests").
 *
 * **Threading:**
 * - App loading runs on [kotlinx.coroutines.Dispatchers.IO]
 * - Uses [kotlinx.coroutines.SupervisorJob] to isolate failures
 * - Flow operations are thread-safe
 *
 * @property context Application context for accessing system services
 * @property packageManager Android PackageManager for querying installed apps
 * @property appsUpdateTrigger Shared flow for triggering refresh operations
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class InstalledAppsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageManager: PackageManager,
    private val appsUpdateTrigger: MutableSharedFlow<Unit>
) : InstalledAppsRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val appsStateFlow: StateFlow<AppLoad> = reloadTriggers(appsUpdateTrigger)
        .flatMapLatest {
            loadAppsFromPackageManager()
        }
        .catch { e ->
            if (e is CancellationException) throw e
            try {
                // Expected transient load failure — modeled as a VALUE (Failed),
                // NOT reported here (Timber.d is a debug breadcrumb, not delivered
                // to ACRA). The single report site is the use-case's no-cache branch
                // (INSTALLED_APPS_LOAD_SPEC Rule-9 / anti-flood): reporting here would
                // fire on every glitch, including ones fully recovered from cache.
                Timber.d(e, "Apps flow error; reporting Failed")
                emit(AppLoad.Failed(e))
            } catch (catchError: CancellationException) {
                // Rethrow: emit() is a suspension point, so a cancelled
                // collector lands its CancellationException here — normal
                // control flow, not the last-resort emit below.
                throw catchError
            } catch (catchError: Throwable) {
                // emit() itself failing IS genuinely unexpected — keep silentError.
                TimberWrapper.silentError(catchError, "CRITICAL: emit failed in apps flow catch")
                emit(AppLoad.Failed(e))
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Companion.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS),
            initialValue = AppLoad.Loaded(emptyList())
        )

    /**
     * The reload-trigger stream that feeds the loader: an immediate priming emit
     * plus the external [trigger]s debounced (DEBOUNCE_SPEC). The priming
     * [flowOf] `Unit` bypasses the debounce, so the initial / cold-start load is
     * NOT delayed by the window (DBNC-INV-1); a burst of triggers within the
     * window collapses to a single reload (DBNC-INV-4). Re-emitted on every
     * (re-)subscription of the sharing [StateFlow], matching the previous
     * `onStart { emit(Unit) }` priming. Latest-wins (the downstream
     * `flatMapLatest`) is unchanged.
     *
     * Since REACTIVE_APPLIST_SPEC the [trigger] carries ONLY real reload causes —
     * package broadcasts (the debounce storm source) plus the rare, deliberate
     * force-reloads (Resume via `AppManagementDelegate.refreshInstalledApps`,
     * factory reset) (RAL-INV-3). Renames and app launches no longer feed it:
     * they re-derive reactively via `customNamesFlow` / `usageFlow`. So the
     * debounce now sits exactly on the storm source it was written for.
     *
     * `@VisibleForTesting internal` so the debounce/priming behavior can be
     * pinned in isolation — the production [appsStateFlow] shares on an internal
     * `Dispatchers.IO` scope that a virtual-time test cannot drive directly.
     */
    @VisibleForTesting
    internal fun reloadTriggers(trigger: Flow<Unit>): Flow<Unit> =
        merge(
            flowOf(Unit),
            trigger.debounce(AppConstants.APP_RELOAD_DEBOUNCE_MS),
        )

    override fun getInstalledApps(): Flow<AppLoad> {
        return appsStateFlow
    }

    override suspend fun triggerAppsUpdate() {
        try {
            Timber.d("App update triggered.")
            Timber.d("[DATAFLOW] 2. Update triggered in InstalledAppsRepositoryImpl.")
            appsUpdateTrigger.emit(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error triggering apps update")
            // Nicht weiteren Error werfen - Update-Fehler sollten nicht crashen
        }
    }

    private fun loadAppsFromPackageManager(): Flow<AppLoad> = flow {
        try {
            Timber.d("!!! PROBE: Loading apps from PackageManager... Expensive operation is RUNNING!")

            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            // A PackageManager query / processing failure is a real LOAD failure,
            // not "zero apps": let it throw straight to the outer catch, which turns
            // the whole emission into AppLoad.Failed (no per-step silentError — that
            // would report the same failure multiple times, INSTALLED_APPS_LOAD_SPEC
            // Rule-9 / anti-flood). Per-item resilience lives inside
            // processResolveInfoList (its for-loop continue).
            val resolveInfoList = packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            )
            val freshApps = processResolveInfoList(resolveInfoList)

            Timber.d("[DATAFLOW] 3. Manager is emitting a new list. Size: ${freshApps.size}")
            emit(AppLoad.Loaded(freshApps))

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Expected transient failure → Failed as a VALUE, breadcrumb only
            // (Timber.d, not reported). The report fires once, downstream, only when
            // recovery from cache is impossible (use-case no-cache branch).
            Timber.d(e, "Error loading apps; reporting Failed")
            emit(AppLoad.Failed(e))
        }
    }
        .catch { e ->
            if (e is CancellationException) throw e
            try {
                Timber.d(e, "Flow catch: error in loadAppsFromPackageManager; reporting Failed")
                emit(AppLoad.Failed(e))
            } catch (catchError: CancellationException) {
                // Rethrow: emit() suspends, so a cancelled collector lands its
                // CancellationException here — must propagate, not be logged below.
                throw catchError
            } catch (catchError: Throwable) {
                // emit() itself failing IS genuinely unexpected — keep silentError.
                TimberWrapper.silentError(catchError, "CRITICAL: emit failed in flow catch block")
            }
        }
        .flowOn(Dispatchers.IO)

    suspend fun processResolveInfoList(resolveInfoList: List<ResolveInfo>): List<AppInfo> {
        val appInfoList = mutableListOf<AppInfo>()

        for (info in resolveInfoList) {
            try {
                val activityInfo = info.activityInfo
                if (activityInfo == null) {
                    TimberWrapper.silentError("ActivityInfo is null for ResolveInfo")
                    continue
                }

                val packageName = activityInfo.packageName
                val className = activityInfo.name

                val originalName = try {
                    val label = info.loadLabel(packageManager).toString()
                    label.ifBlank {
                        packageName  // ✅ Fallback bei leerem Label
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error loading label for $packageName")
                    packageName
                }

                // Custom names are no longer baked in here (REACTIVE_APPLIST_SPEC
                // migration step 2b): the enumeration emits the ORIGINAL label, and
                // every display site folds the custom name in reactively via
                // applyNames(customNamesFlow). A rename no longer forces a
                // re-enumeration.
                appInfoList.add(
                    AppInfo(
                        originalName = originalName,
                        displayName = originalName,
                        packageName = packageName,
                        className = className
                    )
                )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error processing ResolveInfo")
            }
        }

        return appInfoList.sortedBy { it.displayName.lowercase() }
    }

    override suspend fun purgeRepository() {
        // NICHTS TUN!
        // Der InstalledAppsRepositoryImpl liest die App-Liste direkt vom Android PackageManager.
        // Diese System-Daten können und sollten nicht geleert werden.
        // Installierte Apps sind System-Informationen, keine User-Einstellungen.
        // Ein Neuladen der App-Liste erfolgt über triggerAppsUpdate().

        // Für Tests: Diese Methode existiert nur für das Purgeable-Interface,
        // hat aber keine Auswirkung, da keine persistierten Daten vorhanden sind.
    }
}
