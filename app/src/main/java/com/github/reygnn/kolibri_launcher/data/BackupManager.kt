package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.ui.SwipeSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.IOException
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
 * - Swipe Actions (Left/Right Component Names)
 *
 * Selektiver Import erlaubt User, einzelne Kategorien zu wählen.
 */
@Singleton
class BackupManager @Inject constructor(
    private val favoritesManager: FavoritesRepository,
    private val favoritesOrderManager: FavoritesOrderRepository,
    private val appVisibilityManager: HiddenAppsRepository,
    private val appNamesManager: CustomNamesRepository,
    private val installedAppsManager: InstalledAppsRepository,
    private val swipeActionsManager: SwipeActionsRepository,
    private val settingsManager: SettingsRepository,
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
            val customAppNames = appNamesManager.getAllCustomNames()

            // Swipe Actions exportieren
            val swipeLeftApp = swipeActionsManager.swipeLeftAppFlow.first()
            val swipeRightApp = swipeActionsManager.swipeRightAppFlow.first()
            val textColor = settingsManager.textColorFlow.first()
            val textShadowEnabled = settingsManager.textShadowEnabledFlow.first()
            val chipBackgroundColor = settingsManager.chipBackgroundColorFlow.first()

            val settings = LauncherSettings(
                favoriteComponents = favoriteComponents,
                favoritesOrder = favoritesOrder,
                hiddenComponents = hiddenComponents,
                customAppNames = customAppNames,
                swipeLeftApp = swipeLeftApp,
                swipeRightApp = swipeRightApp,
                textColor = textColor,
                chipBackgroundColor = chipBackgroundColor,
                textShadowEnabled = textShadowEnabled
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
            val installedApps = installedAppsManager.getInstalledApps().first()
            val installedComponents = installedApps.map { it.componentName }.toSet()

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

                Timber.Forest.i("Imported favorites: $importedCount (skipped: ${backup.settings.favoriteComponents.size - validFavorites.size})")
            }

            // ===== PHASE 2: Import Order =====
            if (options.importOrder) {
                val currentFavorites = favoritesManager.favoriteComponentsFlow.first()

                // OPTIMIERT: HashSet für Lookup
                val currentFavoritesSet = currentFavorites.toHashSet()

                val validOrder = backup.settings.favoritesOrder
                    .filter { it in currentFavoritesSet && it in installedComponentsSet }

                favoritesOrderManager.saveOrder(validOrder)
                Timber.Forest.i("Imported order: ${validOrder.size} items")
            }

            // ===== PHASE 3: Import Hidden Apps =====
            if (options.importHiddenApps) {
                // OPTIMIERT: filterTo mit HashSet
                val validHidden = backup.settings.hiddenComponents
                    .filterTo(HashSet()) { it in installedComponentsSet }

                val skippedHidden = backup.settings.hiddenComponents.size - validHidden.size
                if (skippedHidden > 0) {
                    Timber.Forest.i("Skipped $skippedHidden non-installed hidden apps")
                }

                // Batch-Update: Alle importierten Apps verstecken
                appVisibilityManager.updateComponentVisibilities(
                    componentsToHide = validHidden,
                    componentsToShow = emptySet()
                )

                Timber.Forest.i("Imported hidden apps: ${validHidden.size}")
            }

            // ===== PHASE 4: Import Custom App Names =====
            if (options.importCustomNames) {
                // OPTIMIERT: filterKeys nutzt jetzt HashSet (O(1) statt O(n))
                val validNames = backup.settings.customAppNames
                    .filterKeys { it in installedPackagesSet }

                val skippedNames = backup.settings.customAppNames.size - validNames.size

                if (validNames.isNotEmpty()) {
                    appNamesManager.setCustomNamesInBatch(validNames)
                    Timber.Forest.i("Imported custom names: ${validNames.size}, skipped: $skippedNames")
                } else {
                    Timber.Forest.i("No custom names to import")
                }
            }

            // ===== PHASE 5: Import Swipe Actions =====
            if (options.importSwipeActions) {
                var swipeImportedCount = 0
                var swipeSkippedCount = 0

                // Import Left Swipe
                val leftApp = backup.settings.swipeLeftApp
                if (leftApp != null) {
                    if (leftApp in installedComponentsSet) {
                        swipeActionsManager.setSwipeAction(SwipeSlot.LEFT, leftApp)
                        swipeImportedCount++
                        Timber.Forest.i("Imported swipe left: $leftApp")
                    } else {
                        swipeActionsManager.setSwipeAction(SwipeSlot.LEFT, null)
                        swipeSkippedCount++
                        missingApps.add(leftApp)
                        Timber.Forest.i("Skipped swipe left (not installed): $leftApp")
                    }
                }

                // Import Right Swipe
                val rightApp = backup.settings.swipeRightApp
                if (rightApp != null) {
                    if (rightApp in installedComponentsSet) {
                        swipeActionsManager.setSwipeAction(SwipeSlot.RIGHT, rightApp)
                        swipeImportedCount++
                        Timber.Forest.i("Imported swipe right: $rightApp")
                    } else {
                        swipeActionsManager.setSwipeAction(SwipeSlot.RIGHT, null)
                        swipeSkippedCount++
                        missingApps.add(rightApp)
                        Timber.Forest.i("Skipped swipe right (not installed): $rightApp")
                    }
                }

                if (swipeImportedCount > 0 || swipeSkippedCount > 0) {
                    Timber.Forest.i("Imported swipe actions: $swipeImportedCount, skipped: $swipeSkippedCount")
                }
            }

            // ===== PHASE 6: Import Theme Settings =====
            if (options.importThemeSettings) {
                var themeImported = false

                // Importiere Textfarbe (nur wenn im Backup vorhanden)
                backup.settings.textColor?.let {
                    settingsManager.setTextColor(it)
                    themeImported = true
                }

                // Importiere Chip-Hintergrundfarbe (nur wenn im Backup vorhanden)
                backup.settings.chipBackgroundColor?.let {
                    settingsManager.setChipBackgroundColor(it)
                    themeImported = true
                }

                // Importiere Textschatten (nur wenn im Backup vorhanden)
                backup.settings.textShadowEnabled?.let {
                    settingsManager.setTextShadowEnabled(it)
                    themeImported = true
                }


                if (themeImported) {
                    Timber.Forest.i("Imported theme settings.")
                }
            }

            Timber.Forest.i(
                "Import completed - Favorites: %b (%d), Order: %b, Hidden: %b, Names: %b, Swipes: %b",
                options.importFavorites,
                importedCount,
                options.importOrder,
                options.importHiddenApps,
                options.importCustomNames,
                options.importSwipeActions
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
            // 1. Validiere URI-String
            if (uriString.isBlank()) {
                Timber.Forest.e("Empty URI string provided")
                throw BackupException("Invalid file location")
            }

            // 2. Parse URI mit expliziter Exception-Behandlung
            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.Forest.e(e, "Invalid URI format: $uriString")
                throw BackupException("Invalid file location format", e)
            }

            // 3. Prüfe URI-Scheme
            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf("content", "file")) {
                Timber.Forest.e("Unsupported URI scheme: $scheme")
                throw BackupException("Unsupported file location type")
            }

            // 4. Exportiere Backup-Daten
            val jsonString = exportToJson()

            // 5. Prüfe Backup-Größe (optional, aber empfohlen)
            val backupSizeBytes = jsonString.toByteArray().size
            if (backupSizeBytes > 10 * 1024 * 1024) { // 10 MB Limit
                Timber.Forest.w("Backup size is very large: ${backupSizeBytes / 1024 / 1024} MB")
            }

            // 6. Schreibe zu File
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonString.toByteArray())
                Timber.Forest.i("Backup saved successfully to: $uri (${backupSizeBytes / 1024} KB)")
                true
            } ?: run {
                Timber.Forest.e("Failed to open output stream for URI: $uri")
                throw BackupException("Cannot write to selected location")
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: SecurityException) {
            Timber.Forest.e(e, "Permission denied for URI")
            throw BackupException("No permission to write to this location", e)
        } catch (e: IOException) {
            Timber.Forest.e(e, "I/O error while saving backup")
            throw BackupException("Failed to write file (storage full or unavailable?)", e)
        } catch (e: Exception) {
            Timber.Forest.e(e, "Unexpected error saving backup")
            throw BackupException("Failed to save backup: ${e.message}", e)
        }
    }

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult {
        return try {
            // 1. Validiere URI-String
            if (uriString.isBlank()) {
                Timber.Forest.e("Empty URI string provided")
                return ImportResult.Error("Invalid file location")
            }

            // 2. Parse URI mit expliziter Exception-Behandlung
            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.Forest.e(e, "Invalid URI format: $uriString")
                return ImportResult.Error("Invalid file location format")
            }

            // 3. Prüfe URI-Scheme
            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf("content", "file")) {
                Timber.Forest.e("Unsupported URI scheme: $scheme")
                return ImportResult.Error("Unsupported file location type")
            }

            // 4. Lese File
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: run {
                Timber.Forest.e("Failed to open input stream for URI: $uri")
                return ImportResult.Error("Cannot read from selected location")
            }

            // 5. Validiere File-Größe
            if (jsonString.length > 10 * 1024 * 1024) { // 10 MB
                Timber.Forest.e("Backup file too large: ${jsonString.length / 1024 / 1024} MB")
                return ImportResult.Error("Backup file is too large")
            }

            // 6. Validiere JSON-Format (basic check)
            if (!jsonString.trim().startsWith("{")) {
                Timber.Forest.e("File does not appear to be valid JSON")
                return ImportResult.InvalidFormat
            }

            Timber.Forest.i("Loading backup from file: $uri (${jsonString.length / 1024} KB)")

            // 7. Import durchführen
            importFromJson(jsonString, options)

        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Timber.Forest.e(e, "Permission denied for URI")
            ImportResult.Error("No permission to read from this location")
        } catch (e: IOException) {
            Timber.Forest.e(e, "I/O error while loading backup")
            ImportResult.Error("Failed to read file (file corrupted or unavailable?)")
        } catch (e: Exception) {
            Timber.Forest.e(e, "Unexpected error loading backup")
            ImportResult.Error("Failed to load backup: ${e.message ?: "Unknown error"}")
        }
    }

    override suspend fun previewBackup(uriString: String): BackupPreview? {
        return try {
            // 1. Validiere URI-String
            if (uriString.isBlank()) {
                Timber.Forest.e("Empty URI string provided for preview")
                return null
            }

            // 2. Parse URI
            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.Forest.e(e, "Invalid URI format for preview: $uriString")
                return null
            }

            // 3. Prüfe URI-Scheme
            val scheme = uri.scheme
            if (scheme == null || scheme !in listOf("content", "file")) {
                Timber.Forest.e("Unsupported URI scheme for preview: $scheme")
                return null
            }

            // 4. Lese File
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                // Limitiere Preview auf erste 1 MB (für Performance)
                val maxPreviewSize = 1024 * 1024
                val buffer = ByteArray(maxPreviewSize)
                val bytesRead = input.read(buffer)

                if (bytesRead < 0) {
                    Timber.Forest.e("Empty file for preview")
                    return null
                }

                String(buffer, 0, bytesRead)
            } ?: run {
                Timber.Forest.e("Failed to open input stream for preview")
                return null
            }

            // 5. Validiere JSON-Format (basic)
            if (!jsonString.trim().startsWith("{")) {
                Timber.Forest.e("File does not appear to be valid JSON")
                return null
            }

            // 6. Parse Backup
            val backup = try {
                json.decodeFromString<BackupData>(jsonString)
            } catch (e: SerializationException) {
                Timber.Forest.e(e, "Failed to parse backup file for preview")
                return null
            }

            // 7. Erstelle Preview
            val preview = BackupPreview(
                version = backup.version,
                timestamp = backup.timestamp,
                favoriteCount = backup.settings.favoriteComponents.size,
                orderCount = backup.settings.favoritesOrder.size,
                hiddenCount = backup.settings.hiddenComponents.size,
                customNamesCount = backup.settings.customAppNames.size,
                hasSwipeLeft = backup.settings.swipeLeftApp != null,
                hasSwipeRight = backup.settings.swipeRightApp != null,
                hasThemeSettings = backup.settings.textColor != null ||
                        backup.settings.chipBackgroundColor != null ||
                        backup.settings.textShadowEnabled != null
            )

            Timber.Forest.i("Preview created: version=${preview.version}, favorites=${preview.favoriteCount}, swipes=L:${preview.hasSwipeLeft}/R:${preview.hasSwipeRight}")
            preview

        } catch (e: SecurityException) {
            Timber.Forest.e(e, "Permission denied for preview")
            null
        } catch (e: Exception) {
            Timber.Forest.e(e, "Unexpected error while creating preview")
            null
        }
    }

    private fun isVersionSupported(version: String): Boolean {
        return version == "1.0.0"
    }
}