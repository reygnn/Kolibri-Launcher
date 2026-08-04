package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
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
 * 4. Emit sorted list through [kotlinx.coroutines.flow.StateFlow]
 *
 * **Key Features:**
 * - Reactive updates through [kotlinx.coroutines.flow.Flow] and [kotlinx.coroutines.flow.StateFlow]
 * - Trigger-based refresh mechanism via [kotlinx.coroutines.flow.MutableSharedFlow]
 * - Custom app name resolution through [com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository]
 * - Automatic alphabetical sorting by display name
 * - Deferred subscription with 5-second timeout (WhileSubscribed)
 *
 * **Error Handling:**
 * All operations are wrapped in comprehensive try-catch blocks to prevent crashes.
 * Errors are logged silently via [TimberWrapper] and result in empty lists or fallback
 * values. [java.util.concurrent.CancellationException] is always re-thrown to preserve coroutine cancellation.
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
 * @property customNamesRepository Repository for custom app name mappings
 * @property appsUpdateTrigger Shared flow for triggering refresh operations
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class InstalledAppsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageManager: PackageManager,
    private val customNamesRepository: CustomNamesRepository,
    private val appsUpdateTrigger: MutableSharedFlow<Unit>
) : InstalledAppsRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val appsStateFlow: StateFlow<List<AppInfo>> = appsUpdateTrigger
        .onStart {
            try {
                emit(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error emitting initial trigger")
                // Trotzdem versuchen weiterzumachen
                emit(Unit)
            }
        }
        .flatMapLatest {
            loadAppsFromPackageManager()
        }
        .catch { e ->
            try {
                TimberWrapper.silentError(e, "Error in apps state flow, emitting empty list")
                emit(emptyList())
            } catch (catchError: CancellationException) {
                // Rethrow: emit() is a suspension point, so a cancelled
                // collector lands its CancellationException here — normal
                // control flow, not the CRITICAL error below.
                throw catchError
            } catch (catchError: Throwable) {
                TimberWrapper.silentError(catchError, "CRITICAL: Error in catch block")
                // Letzte Verteidigungslinie: Leere Liste
                emit(emptyList())
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Companion.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS),
            initialValue = emptyList()
        )

    override fun getInstalledApps(): Flow<List<AppInfo>> {
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

    private fun loadAppsFromPackageManager(): Flow<List<AppInfo>> = flow {
        try {
            Timber.d("!!! PROBE: Loading apps from PackageManager... Expensive operation is RUNNING!")

            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList = try {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error querying intent activities")
                emptyList()
            }

            val freshApps = try {
                processResolveInfoList(resolveInfoList)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error processing resolve info list")
                emptyList()
            }

            Timber.d("[DATAFLOW] 3. Manager is emitting a new list. Size: ${freshApps.size}")
            emit(freshApps)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error loading apps for Flow")
            emit(emptyList())
        }
    }
        .catch { e ->
            try {
                TimberWrapper.silentError(e, "Flow catch: Error in loadAppsFromPackageManager")
                emit(emptyList())
            } catch (catchError: CancellationException) {
                // Rethrow: emit() suspends, so a cancelled collector lands its
                // CancellationException here — must propagate, not be logged as
                // the CRITICAL error below.
                throw catchError
            } catch (catchError: Throwable) {
                TimberWrapper.silentError(catchError, "CRITICAL: Error in flow catch block")
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

                val displayName = try {
                    customNamesRepository.getDisplayNameForPackage(packageName, originalName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error getting display name for $packageName")
                    originalName
                }

                appInfoList.add(
                    AppInfo(
                        originalName = originalName,
                        displayName = displayName,
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