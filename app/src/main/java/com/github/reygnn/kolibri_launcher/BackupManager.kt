package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup & Restore Manager für Kolibri Launcher Settings.
 *
 * CLEAN ARCHITECTURE - 100% Interface-basiert:
 * - Kein direkter DataStore-Zugriff
 * - Nutzt nur Repository-Interfaces
 * - Alle Manager-Flows triggern automatisch
 * - UI updated sich reaktiv
 *
 * Exportiert/Importiert:
 * - Favoriten (Component Names + Order)
 * - Versteckte Apps (Component Names)
 * - Custom App Names (Package Name → Custom Name)
 *
 * Selektiver Import erlaubt User, einzelne Kategorien zu wählen.
 */
@Singleton
class BackupManager @Inject constructor(
    private val favoritesManager: FavoritesRepository,
    private val favoritesOrderManager: FavoritesOrderRepository,
    private val appVisibilityManager: AppVisibilityRepository,
    private val appNamesManager: AppNamesRepository,
    private val appDataSourceManager: AppDataSource,
    @param:ApplicationContext private val context: Context
) : BackupRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    override suspend fun exportToJson(): String {
        return try {
            // Alle Daten über Interfaces holen - 100% sauber!
            val favoriteComponents = favoritesManager.favoriteComponentsFlow.first()
            val favoritesOrder = favoritesOrderManager.favoriteComponentsOrderFlow.first()
            val hiddenComponents = appVisibilityManager.hiddenAppsFlow.first()
            val customAppNames = appNamesManager.getAllCustomNames()  // ← NEU: Saubere Interface-Methode

            val settings = LauncherSettings(
                favoriteComponents = favoriteComponents,
                favoritesOrder = favoritesOrder,
                hiddenComponents = hiddenComponents,
                customAppNames = customAppNames
            )

            val backup = BackupData(
                settings = settings,
                timestamp = System.currentTimeMillis()
            )
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
            // 1. Parse JSON
            val backup = json.decodeFromString<BackupData>(jsonString)

            // 2. Validiere Options
            if (options.importNothing) {
                return ImportResult.Error("No import options selected")
            }

            // 3. Validiere Version
            if (!isVersionSupported(backup.version)) {
                return ImportResult.UnsupportedVersion(backup.version)
            }

            // 4. Hole installierte Apps (einmalig)
            val installedComponents = appDataSourceManager.getInstalledComponents()

            // PERFORMANCE-OPTIMIERUNG: Convert zu HashSet für O(1) Lookups
            val installedComponentsSet = installedComponents.toHashSet()
            val installedPackagesSet = installedComponents
                .mapTo(HashSet()) { it.split('/')[0] }

            var importedCount = 0
            var skippedCount = 0
            val missingApps = mutableSetOf<String>()

            // ===== PHASE 1: Import Favorites =====
            if (options.importFavorites) {
                // OPTIMIERT: Nutze HashSet für schnelle Lookups
                val validFavorites = backup.settings.favoriteComponents
                    .filterTo(HashSet()) { it in installedComponentsSet }

                skippedCount += backup.settings.favoriteComponents.size - validFavorites.size
                missingApps.addAll(backup.settings.favoriteComponents - installedComponentsSet)

                // Prüfe Package-Limit (nicht Component-Limit!)
                val uniquePackages = validFavorites
                    .mapTo(HashSet()) { it.split('/')[0] }

                if (uniquePackages.size > AppConstants.MAX_FAVORITES_ON_HOME) {
                    return ImportResult.LimitExceeded(
                        packageCount = uniquePackages.size,
                        limit = AppConstants.MAX_FAVORITES_ON_HOME
                    )
                }

                favoritesManager.saveFavoriteComponents(validFavorites.toList())
                importedCount += validFavorites.size

                Timber.i("Imported favorites: $importedCount (skipped: ${backup.settings.favoriteComponents.size - validFavorites.size})")
            }

            // ===== PHASE 2: Import Order =====
            if (options.importOrder) {
                val currentFavorites = favoritesManager.favoriteComponentsFlow.first()

                // OPTIMIERT: HashSet für Lookup
                val currentFavoritesSet = currentFavorites.toHashSet()

                val validOrder = backup.settings.favoritesOrder
                    .filter { it in currentFavoritesSet && it in installedComponentsSet }

                favoritesOrderManager.saveOrder(validOrder)
                Timber.i("Imported order: ${validOrder.size} items")
            }

            // ===== PHASE 3: Import Hidden Apps =====
            if (options.importHiddenApps) {
                // OPTIMIERT: filterTo mit HashSet
                val validHidden = backup.settings.hiddenComponents
                    .filterTo(HashSet()) { it in installedComponentsSet }

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

            // ===== PHASE 4: Import Custom App Names =====
            if (options.importCustomNames) {
                // OPTIMIERT: filterKeys nutzt jetzt HashSet (O(1) statt O(n))
                val validNames = backup.settings.customAppNames
                    .filterKeys { it in installedPackagesSet }

                val skippedNames = backup.settings.customAppNames.size - validNames.size

                if (validNames.isNotEmpty()) {
                    appNamesManager.setCustomNamesInBatch(validNames)
                    Timber.i("Imported custom names: ${validNames.size}, skipped: $skippedNames")
                } else {
                    Timber.i("No custom names to import")
                }
            }

            Timber.i(
                "Import completed - Favorites: %b (%d), Order: %b, Hidden: %b, Names: %b",
                options.importFavorites,
                importedCount,
                options.importOrder,
                options.importHiddenApps,
                options.importCustomNames
            )

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

    override suspend fun saveBackupToFile(uriString: String): Boolean {
        return try {
            val uri = uriString.toUri()
            val jsonString = exportToJson()

            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonString.toByteArray())
            }
            Timber.i("Backup saved to: $uri")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // Fängt auch IllegalArgumentException von Uri.parse()
            TimberWrapper.silentError(e, "Error saving backup to file")
            false
        }
    }

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult {
        return try {
            // 1. Validiere URI-String
            if (uriString.isBlank()) {
                Timber.e("Empty URI string provided")
                return ImportResult.Error("Invalid file location")
            }

            // 2. Parse URI mit expliziter Exception-Behandlung
            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Invalid URI format: $uriString")
                return ImportResult.Error("Invalid file location format")
            }

            // 3. Prüfe URI-Scheme
            if (uri.scheme !in listOf("content", "file")) {
                Timber.e("Unsupported URI scheme: ${uri.scheme}")
                return ImportResult.Error("Unsupported file location type")
            }

            // 4. Lese File
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: run {
                Timber.e("Failed to open input stream for URI: $uri")
                return ImportResult.Error("Cannot read from selected location")
            }

            // 5. Validiere File-Größe
            if (jsonString.length > 10 * 1024 * 1024) { // 10 MB
                Timber.e("Backup file too large: ${jsonString.length / 1024 / 1024} MB")
                return ImportResult.Error("Backup file is too large")
            }

            // 6. Validiere JSON-Format (basic check)
            if (!jsonString.trim().startsWith("{")) {
                Timber.e("File does not appear to be valid JSON")
                return ImportResult.InvalidFormat
            }

            Timber.i("Loading backup from file: $uri (${jsonString.length / 1024} KB)")

            // 7. Import durchführen
            importFromJson(jsonString, options)

        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied for URI")
            ImportResult.Error("No permission to read from this location")
        } catch (e: java.io.IOException) {
            Timber.e(e, "I/O error while loading backup")
            ImportResult.Error("Failed to read file (file corrupted or unavailable?)")
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error loading backup")
            ImportResult.Error("Failed to load backup: ${e.message ?: "Unknown error"}")
        }
    }

    override suspend fun previewBackup(uriString: String): BackupPreview? {
        return try {
            // 1. Validiere URI-String
            if (uriString.isBlank()) {
                Timber.e("Empty URI string provided for preview")
                return null
            }

            // 2. Parse URI
            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Invalid URI format for preview: $uriString")
                return null
            }

            // 3. Prüfe URI-Scheme
            if (uri.scheme !in listOf("content", "file")) {
                Timber.e("Unsupported URI scheme for preview: ${uri.scheme}")
                return null
            }

            // 4. Lese File
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                // Limitiere Preview auf erste 1 MB (für Performance)
                val maxPreviewSize = 1024 * 1024
                val buffer = ByteArray(maxPreviewSize)
                val bytesRead = input.read(buffer)

                if (bytesRead < 0) {
                    Timber.e("Empty file for preview")
                    return null
                }

                String(buffer, 0, bytesRead)
            } ?: run {
                Timber.e("Failed to open input stream for preview")
                return null
            }

            // 5. Validiere JSON-Format (basic)
            if (!jsonString.trim().startsWith("{")) {
                Timber.e("File does not appear to be valid JSON")
                return null
            }

            // 6. Parse Backup
            val backup = try {
                json.decodeFromString<BackupData>(jsonString)
            } catch (e: SerializationException) {
                Timber.e(e, "Failed to parse backup file for preview")
                return null
            }

            // 7. Erstelle Preview
            val preview = BackupPreview(
                version = backup.version,
                timestamp = backup.timestamp,
                favoriteCount = backup.settings.favoriteComponents.size,
                orderCount = backup.settings.favoritesOrder.size,
                hiddenCount = backup.settings.hiddenComponents.size,
                customNamesCount = backup.settings.customAppNames.size
            )

            Timber.i("Preview created: version=${preview.version}, favorites=${preview.favoriteCount}")
            preview

        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied for preview")
            null
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error while creating preview")
            null
        }
    }

    private fun isVersionSupported(version: String): Boolean {
        return version == "1.0.0"
    }
}