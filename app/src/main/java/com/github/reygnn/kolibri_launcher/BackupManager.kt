package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup & Restore Manager für Kolibri Launcher Settings.
 *
 * Exportiert/Importiert:
 * - Favoriten (Component Names + Order)
 * - Versteckte Apps (Component Names)
 * - Custom App Names (Package Name -> Custom Name)
 *
 * Selektiver Import erlaubt User, einzelne Kategorien zu wählen.
 */
@Singleton
class BackupManager @Inject constructor(
    private val favoritesManager: FavoritesRepository,
    private val favoritesOrderManager: FavoritesOrderRepository,
    private val appVisibilityManager: AppVisibilityRepository,
    private val appNamesManager: AppNamesRepository,
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context
) : BackupRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    override suspend fun exportToJson(): String {
        return try {
            // Favoriten
            val favoriteComponents = favoritesManager.favoriteComponentsFlow.first()
            val favoritesOrder = favoritesOrderManager.favoriteComponentsOrderFlow.first()

            // Versteckte Apps
            val hiddenComponents = appVisibilityManager.hiddenAppsFlow.first()

            // Custom App Names (manuell aus DataStore lesen)
            val customAppNames = extractCustomAppNamesFromDataStore()

            val settings = LauncherSettings(
                favoriteComponents = favoriteComponents,
                favoritesOrder = favoritesOrder,
                hiddenComponents = hiddenComponents,
                customAppNames = customAppNames
            )

            val backup = BackupData(settings = settings)
            json.encodeToString(backup)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error exporting backup")
            throw BackupException("Export failed", e)
        }
    }

    /**
     * Extrahiert alle Custom App Names aus dem DataStore.
     * Sucht nach allen Keys mit Prefix "name_" (KEY_NAME_PREFIX).
     */
    private suspend fun extractCustomAppNamesFromDataStore(): Map<String, String> {
        return try {
            val preferences = dataStore.data.first()
            val customNames = mutableMapOf<String, String>()

            preferences.asMap().forEach { (key, value) ->
                val keyName = key.name
                if (keyName.startsWith(AppConstants.KEY_NAME_PREFIX) && value is String) {
                    // Extrahiere packageName aus "name_com.example.app"
                    val packageName = keyName.removePrefix(AppConstants.KEY_NAME_PREFIX)
                    customNames[packageName] = value
                }
            }

            Timber.d("Extracted ${customNames.size} custom app names")
            customNames

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error extracting custom app names")
            emptyMap()
        }
    }

    override suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult {
        return try {
            val backup = json.decodeFromString<BackupData>(jsonString)

            if (options.importNothing) {
                return ImportResult.Error("No import options selected")
            }

            if (!isVersionSupported(backup.version)) {
                return ImportResult.UnsupportedVersion(backup.version)
            }

            val installedComponents = getInstalledComponents(context)
            val installedPackages = installedComponents.map { it.split('/')[0] }.toSet()

            var importedCount = 0
            var skippedCount = 0
            val missingApps = mutableSetOf<String>()

            // PHASE 1: Import Favorites
            if (options.importFavorites) {
                val validFavorites = backup.settings.favoriteComponents
                    .filter { it in installedComponents }
                    .toSet()

                skippedCount += backup.settings.favoriteComponents.size - validFavorites.size
                missingApps.addAll(backup.settings.favoriteComponents - installedComponents)

                val uniquePackages = validFavorites.map { it.split('/')[0] }.toSet()
                if (uniquePackages.size > AppConstants.MAX_FAVORITES_ON_HOME) {
                    return ImportResult.LimitExceeded(
                        packageCount = uniquePackages.size,
                        limit = AppConstants.MAX_FAVORITES_ON_HOME
                    )
                }

                favoritesManager.saveFavoriteComponents(validFavorites.toList())
                importedCount += validFavorites.size

                Timber.i("Imported favorites: $importedCount")
            }

            // PHASE 2: Import Order
            if (options.importOrder) {
                val currentFavorites = favoritesManager.favoriteComponentsFlow.first()
                val validOrder = backup.settings.favoritesOrder
                    .filter { it in currentFavorites && it in installedComponents }

                favoritesOrderManager.saveOrder(validOrder)
                Timber.i("Imported order: ${validOrder.size} items")
            }

            // PHASE 3: Import Hidden Apps
            if (options.importHiddenApps) {
                val validHidden = backup.settings.hiddenComponents
                    .filter { it in installedComponents }
                    .toSet()

                val skippedHidden = backup.settings.hiddenComponents.size - validHidden.size
                if (skippedHidden > 0) {
                    Timber.i("Skipped $skippedHidden non-installed hidden apps")
                }

                // Batch-Update: Alle importierten Apps verstecken
                appVisibilityManager.updateComponentVisibilities(
                    componentsToHide = validHidden,
                    componentsToShow = emptySet()
                )

                Timber.i("Imported hidden apps: ${validHidden.size}")
            }

            // PHASE 4: Import Custom App Names
            if (options.importCustomNames) {
                var namesImported = 0
                var namesSkipped = 0

                backup.settings.customAppNames.forEach { (packageName, customName) ->
                    // Prüfe ob Package installiert ist
                    if (packageName in installedPackages) {
                        appNamesManager.setCustomNameForPackage(packageName, customName)
                        namesImported++
                    } else {
                        namesSkipped++
                    }
                }

                if (namesImported > 0) {
                    // Trigger Update damit UI sich aktualisiert
                    appNamesManager.triggerCustomNameUpdate()
                }

                Timber.i("Imported custom names: $namesImported, skipped: $namesSkipped")
            }

            Timber.i("Selective import completed: " +
                    "favorites=${options.importFavorites}, " +
                    "order=${options.importOrder}, " +
                    "hidden=${options.importHiddenApps}, " +
                    "names=${options.importCustomNames}")

            ImportResult.Success(
                importedCount = importedCount,
                skippedCount = skippedCount,
                missingApps = missingApps
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            TimberWrapper.silentError(e, "Invalid backup format")
            ImportResult.InvalidFormat
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error importing backup")
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun saveBackupToFile(uri: Uri): Boolean {
        return try {
            val jsonString = exportToJson()
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonString.toByteArray())
            }
            Timber.i("Backup saved to: $uri")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error saving backup to file")
            false
        }
    }

    override suspend fun loadBackupFromFile(uri: Uri, options: ImportOptions): ImportResult {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return ImportResult.Error("Could not read file")

            Timber.i("Loading backup from file: $uri")
            importFromJson(jsonString, options)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error loading backup from file")
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun previewBackup(uri: Uri): BackupPreview? {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return null

            val backup = json.decodeFromString<BackupData>(jsonString)

            BackupPreview(
                version = backup.version,
                timestamp = backup.timestamp,
                favoriteCount = backup.settings.favoriteComponents.size,
                orderCount = backup.settings.favoritesOrder.size,
                hiddenCount = backup.settings.hiddenComponents.size,
                customNamesCount = backup.settings.customAppNames.size
            )
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error previewing backup")
            null
        }
    }

    private fun isVersionSupported(version: String): Boolean {
        return version == "1.0.0"
    }

    private fun getInstalledComponents(context: Context): Set<String> {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
                .addCategory(Intent.CATEGORY_LAUNCHER)

            pm.queryIntentActivities(mainIntent, 0)
                .map { resolveInfo ->
                    val activityInfo = resolveInfo.activityInfo
                    "${activityInfo.packageName}/${activityInfo.name}"
                }
                .toSet()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error getting installed components")
            emptySet()
        }
    }
}