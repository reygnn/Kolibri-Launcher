package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager für Export/Import von App-Nutzungsdaten.
 *
 * Ermöglicht das Sichern und Wiederherstellen von Usage-Timestamps,
 * sodass diese bei Neuinstallation oder Gerätewechsel erhalten bleiben.
 *
 * **Human-Readable Format:**
 * Timestamps werden als ISO 8601 Datum exportiert (z.B. "2024-12-08T14:30:00Z"),
 * was manuelles Editieren ermöglicht. Beim Import werden sowohl ISO 8601
 * als auch rohe Millisekunden-Timestamps akzeptiert (Rückwärtskompatibilität).
 *
 * **Merge-Verhalten:**
 * Beim Import mit `mergeWithExisting = true` werden Timestamps intelligent gemergt:
 * - Duplikate werden entfernt
 * - Timestamps werden nach Aktualität sortiert
 * - MAX_TIMESTAMPS_PER_APP Limit wird eingehalten
 *
 * **Sicherheit:**
 * - OOM Protection durch Dateigrößen-Check
 * - Type Validation gegen malformed JSON
 * - Timestamp-Validierung (nicht in Zukunft, nicht zu alt)
 */
@Singleton
class AppUsageExportManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context
) : UsageExportRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val USAGE_EXPORT_VERSION = "1.0.0"

        // ISO 8601 Format mit UTC
        private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

        // Für lokale Zeitzone-Anzeige (optional, falls gewünscht)
        private val localFormatter: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }

    /**
     * Konvertiert Millisekunden-Timestamp zu ISO 8601 String.
     */
    private fun formatTimestamp(millis: Long): String {
        return try {
            Instant.ofEpochMilli(millis).toString()  // z.B. "2024-12-08T14:30:00.000Z"
        } catch (e: Throwable) {
            millis.toString()  // Fallback auf raw value
        }
    }

    /**
     * Parst ISO 8601 String oder rohen Millisekunden-Wert zu Long.
     * Unterstützt beide Formate für Rückwärtskompatibilität.
     */
    private fun parseTimestamp(value: String): Long? {
        // Versuch 1: Als ISO 8601 parsen
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (e: DateTimeParseException) {
            // Nicht ISO 8601, versuche als Zahl
        }

        // Versuch 2: Als rohe Millisekunden parsen (Rückwärtskompatibilität)
        return value.toLongOrNull()
    }

    override suspend fun exportToJson(): String {
        return try {
            val preferences = dataStore.data.first()
            val currentTime = System.currentTimeMillis()

            val usageData = mutableMapOf<String, List<String>>()

            preferences.asMap().forEach { (key, value) ->
                if (key.name.startsWith(AppConstants.KEY_USAGE_PREFIX)) {
                    val packageName = key.name.removePrefix(AppConstants.KEY_USAGE_PREFIX)

                    @Suppress("UNCHECKED_CAST")
                    val timestamps = (value as? Set<String>)
                        ?.mapNotNull { it.toLongOrNull() }
                        ?.filter { isValidTimestamp(it, currentTime) }
                        ?.sortedDescending()
                        ?.map { formatTimestamp(it) }  // Konvertiere zu ISO 8601
                        ?: emptyList()

                    if (timestamps.isNotEmpty()) {
                        usageData[packageName] = timestamps
                    }
                }
            }

            // Manuelles JSON bauen, da UsageExportData jetzt List<String> statt List<Long> hat
            buildExportJson(usageData, currentTime)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error exporting usage data")
            throw IOException("Export failed: ${e.message}", e)
        }
    }

    private fun buildExportJson(usageData: Map<String, List<String>>, exportTimestamp: Long): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("    \"version\": \"$USAGE_EXPORT_VERSION\",")
        sb.appendLine("    \"export_timestamp\": ${JSONObject.quote(formatTimestamp(exportTimestamp))},")
        sb.appendLine("    \"app_version\": ${JSONObject.quote(BuildConfig.VERSION_NAME)},")
        sb.appendLine("    \"usage_data\": {")

        val entries = usageData.entries.toList()
        entries.forEachIndexed { index, (packageName, timestamps) ->
            val timestampList = timestamps.joinToString(", ") { JSONObject.quote(it) }
            val comma = if (index < entries.size - 1) "," else ""
            sb.appendLine("        ${JSONObject.quote(packageName)}: [$timestampList]$comma")
        }

        sb.appendLine("    }")
        sb.appendLine("}")
        return sb.toString()
    }

    override suspend fun importFromJson(jsonString: String, mergeWithExisting: Boolean): UsageImportResult {
        return try {
            // Phase 1: Validation
            if (jsonString.isBlank() || !jsonString.trim().startsWith("{")) {
                return UsageImportResult.InvalidFormat
            }

            if (!validateJsonStructure(jsonString)) {
                return UsageImportResult.InvalidFormat
            }

            // Phase 2: Parse
            val (version, usageData) = try {
                parseUsageData(jsonString)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Failed to parse usage export")
                return UsageImportResult.InvalidFormat
            }

            // Phase 3: Version Check (akzeptiere 1.0.0 und 1.1.0)
            if (version != USAGE_EXPORT_VERSION && version != "1.0.0") {
                return UsageImportResult.UnsupportedVersion(version)
            }

            // Phase 4: Import
            performImport(usageData, mergeWithExisting)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error importing usage data")
            UsageImportResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Parst das JSON und extrahiert Version + Usage-Daten.
     * Unterstützt sowohl ISO 8601 Strings als auch rohe Long-Werte.
     */
    private fun parseUsageData(jsonString: String): Pair<String, Map<String, List<Long>>> {
        val root = JSONObject(jsonString)
        val version = root.optString("version", "1.0.0")

        val usageData = mutableMapOf<String, List<Long>>()

        if (root.has("usage_data")) {
            val usageObj = root.getJSONObject("usage_data")
            val keys = usageObj.keys()

            while (keys.hasNext()) {
                val packageName = keys.next()
                val timestampsArray = usageObj.getJSONArray(packageName)
                val timestamps = mutableListOf<Long>()

                for (i in 0 until timestampsArray.length()) {
                    val value = timestampsArray.get(i)
                    val parsed: Long? = when (value) {
                        is Number -> value.toLong()
                        is String -> parseTimestamp(value)
                        else -> null
                    }
                    parsed?.let { timestamps.add(it) }
                }

                if (timestamps.isNotEmpty()) {
                    usageData[packageName] = timestamps
                }
            }
        }

        return Pair(version, usageData)
    }

    private suspend fun performImport(
        usageData: Map<String, List<Long>>,
        mergeWithExisting: Boolean
    ): UsageImportResult {
        val currentTime = System.currentTimeMillis()
        var packagesImported = 0
        var timestampsImported = 0
        var packagesSkipped = 0

        try {
            dataStore.edit { preferences ->
                usageData.forEach { (packageName, importedTimestamps) ->
                    if (packageName.isBlank()) {
                        packagesSkipped++
                        return@forEach
                    }

                    val usageKey = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + packageName)

                    // Validiere und filtere importierte Timestamps
                    val validImportedTimestamps = importedTimestamps
                        .filter { isValidTimestamp(it, currentTime) }
                        .take(AppConstants.MAX_TIMESTAMPS_PER_APP)

                    if (validImportedTimestamps.isEmpty()) {
                        packagesSkipped++
                        return@forEach
                    }

                    val finalTimestamps = if (mergeWithExisting) {
                        // Merge: Bestehende + importierte, dedupliziert, sortiert, limitiert
                        val existingTimestamps = preferences[usageKey]
                            ?.mapNotNull { it.toLongOrNull() }
                            ?.filter { isValidTimestamp(it, currentTime) }
                            ?: emptyList()

                        (existingTimestamps + validImportedTimestamps)
                            .distinct()
                            .sortedDescending()
                            .take(AppConstants.MAX_TIMESTAMPS_PER_APP)
                    } else {
                        // Replace: Nur importierte verwenden
                        validImportedTimestamps.sortedDescending()
                    }

                    preferences[usageKey] = finalTimestamps.map { it.toString() }.toSet()

                    packagesImported++
                    timestampsImported += finalTimestamps.size
                }
            }

            Timber.Forest.i("Usage import complete: $packagesImported packages, $timestampsImported timestamps")

            return UsageImportResult.Success(
                packagesImported = packagesImported,
                timestampsImported = timestampsImported,
                packagesSkipped = packagesSkipped
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during usage import")
            return UsageImportResult.Error("Import failed: ${e.message}")
        }
    }

    /**
     * Validiert die JSON-Struktur gegen Type Confusion Attacks.
     * Akzeptiert jetzt sowohl Number als auch String für Timestamps.
     */
    private fun validateJsonStructure(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)

            // Version muss String sein
            if (root.has("version") && root.get("version") !is String) {
                Timber.Forest.w("Type validation failed: version is not a string")
                return false
            }

            // export_timestamp kann String (ISO) oder Number sein
            if (root.has("export_timestamp") && !root.isNull("export_timestamp")) {
                val value = root.get("export_timestamp")
                if (value !is Number && value !is String) {
                    Timber.Forest.w("Type validation failed: export_timestamp is not a number or string")
                    return false
                }
            }

            // usage_data muss Object sein
            if (root.has("usage_data")) {
                val usageData = root.get("usage_data")
                if (usageData !is JSONObject) {
                    Timber.Forest.w("Type validation failed: usage_data is not an object")
                    return false
                }

                // DoS-Schutz: Nicht zu viele Packages
                val usageObj = usageData as JSONObject
                if (usageObj.length() > AppConstants.MAX_ARRAY_ELEMENTS) {
                    Timber.Forest.w("Too many packages in usage_data: ${usageObj.length()}")
                    return false
                }

                // Jeder Wert muss ein Array von Numbers oder Strings sein
                val keys = usageObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = usageObj.get(key)

                    if (value !is org.json.JSONArray) {
                        Timber.Forest.w("Type validation failed: $key is not an array")
                        return false
                    }

                    val array = value as org.json.JSONArray
                    if (array.length() > AppConstants.MAX_TIMESTAMPS_PER_APP * 2) {
                        Timber.Forest.w("Too many timestamps for $key: ${array.length()}")
                        return false
                    }

                    for (i in 0 until array.length()) {
                        val item = array.get(i)
                        // Akzeptiere Number (alt) oder String (neu)
                        if (item !is Number && item !is String) {
                            Timber.Forest.w("Type validation failed: timestamp at $key[$i] is not a number or string")
                            return false
                        }
                    }
                }
            }

            true
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "JSON structure validation failed")
            false
        }
    }

    private fun isValidTimestamp(timestamp: Long, currentTime: Long): Boolean {
        return try {
            timestamp > 0 &&
                    timestamp <= currentTime &&
                    (currentTime - timestamp) <= AppConstants.MAX_TIMESTAMP_AGE_MS
        } catch (e: Throwable) {
            false
        }
    }

    override suspend fun saveToFile(uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) {
                Timber.Forest.e("Empty URI string provided")
                return@withContext false
            }

            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                Timber.Forest.e(e, "Invalid URI format: $uriString")
                return@withContext false
            }

            val jsonString = exportToJson()

            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonString.toByteArray())
                Timber.Forest.i("Usage data saved to: $uri")
                true
            } ?: run {
                Timber.Forest.e("Failed to open output stream for URI: $uri")
                false
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving usage data to file")
            false
        }
    }

    override suspend fun loadFromFile(uriString: String, mergeWithExisting: Boolean): UsageImportResult =
        withContext(Dispatchers.IO) {
            try {
                if (uriString.isBlank()) {
                    return@withContext UsageImportResult.Error("Invalid file location")
                }

                val uri = try {
                    uriString.toUri()
                } catch (e: IllegalArgumentException) {
                    return@withContext UsageImportResult.Error("Invalid file format")
                }

                // OOM Protection
                val fileSize = try {
                    context.contentResolver.openFileDescriptor(uri, AppConstants.MODE_READ_ONLY)?.use { pfd ->
                        pfd.statSize
                    } ?: 0L
                } catch (e: Throwable) {
                    Timber.Forest.w(e, "Could not determine file size")
                    0L
                }

                if (fileSize > AppConstants.MAX_BACKUP_SIZE_BYTES) {
                    return@withContext UsageImportResult.Error("File too large")
                }

                val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: return@withContext UsageImportResult.Error("Cannot read file")

                importFromJson(jsonString, mergeWithExisting)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error loading usage data from file")
                UsageImportResult.Error("Load failed: ${e.message}")
            }
        }
}