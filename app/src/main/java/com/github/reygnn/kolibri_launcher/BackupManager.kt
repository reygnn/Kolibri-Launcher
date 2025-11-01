package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Backup & Restore Manager für Kolibri Launcher Settings mit selektivem Import.
 *
 * # BACKUP/RESTORE LOGIK
 *
 * ## EXPORT (Backup erstellen):
 * 1. Liest aktuelle Daten aus den Repository-Flows
 * 2. Verpackt in strukturierte BackupData
 * 3. Serialisiert zu JSON
 * 4. Schreibt in User-gewählte Datei
 *
 * ## IMPORT (Backup wiederherstellen):
 * 1. Preview: Zeigt Backup-Inhalt vor Import
 * 2. User wählt Import-Optionen (Favoriten/Order)
 * 3. Validierung (Version, Installation, Limits)
 * 4. Selektiver Import basierend auf Optionen
 * 5. Nur gewählte Daten werden überschrieben
 *
 * ## SELEKTIVER IMPORT:
 * - Import Favorites: Überschreibt favoriteComponents komplett
 * - Import Order: Filtert Order gegen aktuelle Favorites
 * - Beide: Standard-Verhalten (alles importieren)
 * - Keines: Fehler (Import-Dialog sollte dies verhindern)
 */
@Singleton
class BackupManager @Inject constructor(
    private val favoritesManager: FavoritesRepository,
    private val favoritesOrderManager: FavoritesOrderRepository,
    @ApplicationContext private val context: Context
) : BackupRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    override suspend fun exportToJson(): String {
        return try {
            val favoriteComponents = favoritesManager.favoriteComponentsFlow.first()
            val favoritesOrder = favoritesOrderManager.favoriteComponentsOrderFlow.first()

            val settings = LauncherSettings(
                favoriteComponents = favoriteComponents,
                favoritesOrder = favoritesOrder
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

    override suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult {
        return try {
            val backup = json.decodeFromString<BackupData>(jsonString)

            // Validierung: Keine Import-Optionen gewählt
            if (options.importNothing) {
                return ImportResult.Error("No import options selected")
            }

            // Version-Check
            if (!isVersionSupported(backup.version)) {
                return ImportResult.UnsupportedVersion(backup.version)
            }

            val installedComponents = getInstalledComponents(context)
            var importedCount = 0
            var skippedCount = 0
            val missingApps = mutableSetOf<String>()

            // PHASE 1: Import Favorites (wenn gewählt)
            if (options.importFavorites) {
                val validFavorites = backup.settings.favoriteComponents
                    .filter { it in installedComponents }
                    .toSet()

                skippedCount = backup.settings.favoriteComponents.size - validFavorites.size
                missingApps.addAll(backup.settings.favoriteComponents - installedComponents)

                // Package-Limit prüfen
                val uniquePackages = validFavorites.map { it.split('/')[0] }.toSet()
                if (uniquePackages.size > AppConstants.MAX_FAVORITES_ON_HOME) {
                    return ImportResult.LimitExceeded(
                        packageCount = uniquePackages.size,
                        limit = AppConstants.MAX_FAVORITES_ON_HOME
                    )
                }

                // Favorites überschreiben
                favoritesManager.saveFavoriteComponents(validFavorites.toList())
                importedCount = validFavorites.size

                Timber.i("Imported favorites: $importedCount, skipped: $skippedCount")
            }

            // PHASE 2: Import Order (wenn gewählt)
            if (options.importOrder) {
                // Hole aktuelle Favorites (entweder gerade importiert oder bestehend)
                val currentFavorites = favoritesManager.favoriteComponentsFlow.first()

                // Filtere Order: Nur Components die (1) Favorites sind UND (2) installiert
                val validOrder = backup.settings.favoritesOrder
                    .filter { it in currentFavorites && it in installedComponents }

                favoritesOrderManager.saveOrder(validOrder)

                Timber.i("Imported order: ${validOrder.size} items")
            }

            Timber.i("Selective import completed: favorites=${options.importFavorites}, order=${options.importOrder}")

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
                orderCount = backup.settings.favoritesOrder.size
            )
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error previewing backup")
            null
        }
    }

    private fun isVersionSupported(version: String): Boolean {
        return when (version) {
            "1.0.0" -> true
            else -> false
        }
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