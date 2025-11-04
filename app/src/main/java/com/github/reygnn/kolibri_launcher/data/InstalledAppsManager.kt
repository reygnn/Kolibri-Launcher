package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.github.reygnn.kolibri_launcher.data.AppInfo
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
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
 * - Custom app name resolution through [CustomNamesRepository]
 * - Automatic alphabetical sorting by display name
 * - Deferred subscription with 5-second timeout (WhileSubscribed)
 *
 * **Error Handling:**
 * All operations are wrapped in comprehensive try-catch blocks to prevent crashes.
 * Errors are logged silently via [TimberWrapper] and result in empty lists or fallback
 * values. [java.util.concurrent.CancellationException] is always re-thrown to preserve coroutine cancellation.
 *
 * **Threading:**
 * - App loading runs on [kotlinx.coroutines.Dispatchers.IO]
 * - Uses [kotlinx.coroutines.SupervisorJob] to isolate failures
 * - Flow operations are thread-safe
 *
 * @property context Application context for accessing system services
 * @property packageManager Android PackageManager for querying installed apps
 * @property appNamesManager Repository for custom app name mappings
 * @property appsUpdateTrigger Shared flow for triggering refresh operations
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class InstalledAppsManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageManager: PackageManager,
    private val appNamesManager: CustomNamesRepository,
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
            } catch (catchError: Throwable) {
                TimberWrapper.silentError(catchError, "CRITICAL: Error in catch block")
                // Letzte Verteidigungslinie: Leere Liste
                emit(emptyList())
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Companion.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    override fun getInstalledApps(): Flow<List<AppInfo>> {
        return appsStateFlow
    }

    override suspend fun triggerAppsUpdate() {
        try {
            Timber.Forest.d("App update triggered.")
            Timber.Forest.d("[DATAFLOW] 2. Update triggered in InstalledAppsManager.")
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
            Timber.Forest.d("!!! PROBE: Loading apps from PackageManager... Expensive operation is RUNNING!")

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

            Timber.Forest.d("[DATAFLOW] 3. Manager is emitting a new list. Size: ${freshApps.size}")
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
                    appNamesManager.getDisplayNameForPackage(packageName, originalName)
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

        return try {
            appInfoList.sortedBy { it.displayName.lowercase() }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error sorting app list")
            appInfoList
        }
    }

    override fun purgeRepository() {
    }
}