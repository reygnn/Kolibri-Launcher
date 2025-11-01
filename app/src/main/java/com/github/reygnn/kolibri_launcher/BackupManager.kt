package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val favoritesManager: FavoritesRepository,
    private val favoritesOrderManager: FavoritesOrderRepository,
    @ApplicationContext private val context: Context,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
) : BackupRepository {

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

    override suspend fun importFromJson(jsonString: String): ImportResult {
        return try {
            val backup = json.decodeFromString<BackupData>(jsonString)

            if (!isVersionSupported(backup.version)) {
                return ImportResult.UnsupportedVersion(backup.version)
            }

            val installedComponents = getInstalledComponents(context)
            val validFavorites = backup.settings.favoriteComponents
                .filter { it in installedComponents }
                .toSet()

            val skippedCount = backup.settings.favoriteComponents.size - validFavorites.size

            val uniquePackages = validFavorites.map { it.split('/')[0] }.toSet()
            if (uniquePackages.size > AppConstants.MAX_FAVORITES_ON_HOME) {
                return ImportResult.LimitExceeded(
                    packageCount = uniquePackages.size,
                    limit = AppConstants.MAX_FAVORITES_ON_HOME
                )
            }

            favoritesManager.saveFavoriteComponents(validFavorites.toList())

            val validOrder = backup.settings.favoritesOrder
                .filter { it in validFavorites }
            favoritesOrderManager.saveOrder(validOrder)

            Timber.i("Backup imported: ${validFavorites.size} favorites, $skippedCount skipped")

            ImportResult.Success(
                importedCount = validFavorites.size,
                skippedCount = skippedCount,
                missingApps = backup.settings.favoriteComponents - validFavorites
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
            val json = exportToJson()
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(json.toByteArray())
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

    override suspend fun loadBackupFromFile(uri: Uri): ImportResult {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return ImportResult.Error("Could not read file")

            importFromJson(json)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error loading backup from file")
            ImportResult.Error(e.message ?: "Unknown error")
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