package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.isValidUsageTimestamp
import com.github.reygnn.kolibri_launcher.di.UsageDataStore
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Named
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
class UsageExportRepositoryImpl @Inject constructor(
    @param:UsageDataStore private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context,
    @param:Named("appVersionName") private val appVersionName: String,
) : UsageExportRepository {

    companion object {
        private const val USAGE_EXPORT_VERSION = "1.0.0"
    }

    /**
     * Konvertiert Millisekunden-Timestamp zu ISO 8601 String.
     */
    private fun formatTimestamp(millis: Long): String {
        return try {
            Instant.ofEpochMilli(millis).toString()  // z.B. "2024-12-08T14:30:00.000Z"
        } catch (e: Throwable) {
            // No suspension point: guarded body is synchronous today; if a call here becomes suspend, switch to a CancellationException rethrow arm (AUDIT-12 whitelist review).
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
        } catch (e: ArithmeticException) {
            // Valid ISO 8601 but its epoch-millis overflow a Long (extreme
            // year, e.g. "+1000000000-01-01T00:00:00Z"): Instant.parse succeeds,
            // toEpochMilli() throws. Skip just this one entry (fall through to
            // the raw-Long attempt, which returns null for an ISO string)
            // instead of letting it propagate and abort the whole import —
            // preserving the graceful-skip contract that garbage strings get.
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
                        ?.filter { isValidUsageTimestamp(it, currentTime) }
                        ?.sortedDescending()
                        ?.map { formatTimestamp(it) }  // Konvertiere zu ISO 8601
                        ?: emptyList()

                    if (timestamps.isNotEmpty()) {
                        usageData[packageName] = timestamps
                    }
                }
            }

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
        sb.appendLine("    \"exportTimestamp\": ${JSONObject.quote(formatTimestamp(exportTimestamp))},")
        sb.appendLine("    \"appVersion\": ${JSONObject.quote(appVersionName)},")
        sb.appendLine("    \"usageData\": {")

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

            // Parse the JSON tree ONCE here; validation and extraction below both
            // walk this shared JSONObject instead of each re-parsing the raw string
            // (org.json parses eagerly, so a second construction re-does the full
            // parse). Malformed syntax → InvalidFormat, preserving the prior UX and
            // diagnostic. An allocation failure (OOM) is an Error, not a
            // JSONException, so it propagates to the outer Throwable catch → Error,
            // exactly as when validateJsonStructure used to construct here.
            val root = try {
                JSONObject(jsonString)
            } catch (e: JSONException) {
                TimberWrapper.silentError(e, "JSON structure validation failed")
                return UsageImportResult.InvalidFormat
            }

            if (!validateJsonStructure(root)) {
                return UsageImportResult.InvalidFormat
            }

            // Phase 2: Extract version + usage data from the parsed tree
            val (version, usageData) = try {
                parseUsageData(root)
            } catch (e: Throwable) {
                // No suspension point: guarded body is synchronous today; if a call here becomes suspend, switch to a CancellationException rethrow arm (AUDIT-12 whitelist review).
                // Widened from Exception per the breadth check: parseUsageData walks
                // the shared JSONObject and builds boxed Long lists whose live object
                // graph can run well above the raw string size (bounded only by the
                // validation caps), so an OOM here is an Error that `Exception` would
                // miss. Same shape as the BackupRepositoryImpl JSON/ZIP umbrella. (The
                // JSONObject itself is constructed once above; a construction OOM
                // propagates to the outer catch.)
                TimberWrapper.silentError(e, "Failed to parse usage export")
                return UsageImportResult.InvalidFormat
            }

            // Phase 3: version check — only USAGE_EXPORT_VERSION (1.0.0) is supported
            if (version != USAGE_EXPORT_VERSION) {
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
    private fun parseUsageData(root: JSONObject): Pair<String, Map<String, List<Long>>> {
        val version = root.optString("version", "1.0.0")

        // camelCase bevorzugt, snake_case nur als Fallback
        val usageObj = root.optJSONObject("usageData")
            ?: root.optJSONObject("usage_data")

        val usageData = mutableMapOf<String, List<Long>>()

        usageObj?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val packageName = keys.next()
                val timestampsArray = obj.getJSONArray(packageName)
                val timestamps = (0 until timestampsArray.length()).mapNotNull { i ->
                    when (val value = timestampsArray.get(i)) {
                        is Number -> value.toLong()
                        is String -> parseTimestamp(value)
                        else -> null
                    }
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

                    // Validiere und filtere importierte Timestamps.
                    // Sort BEFORE take (AUDIT-17 F3): importedTimestamps preserves the
                    // JSON file order (parseUsageData does not sort) and
                    // validateJsonStructure permits up to MAX_TIMESTAMPS_PER_APP * 2
                    // entries per package. Truncating before sorting would keep the
                    // file-order first MAX and drop newer entries for a non-descending
                    // (e.g. foreign/hand-edited) file. Keep the newest MAX instead.
                    val validImportedTimestamps = importedTimestamps
                        .filter { isValidUsageTimestamp(it, currentTime) }
                        .sortedDescending()
                        .take(AppConstants.MAX_TIMESTAMPS_PER_APP)

                    if (validImportedTimestamps.isEmpty()) {
                        packagesSkipped++
                        return@forEach
                    }

                    val finalTimestamps = if (mergeWithExisting) {
                        // Merge: Bestehende + importierte, dedupliziert, sortiert, limitiert
                        val existingTimestamps = preferences[usageKey]
                            ?.mapNotNull { it.toLongOrNull() }
                            ?.filter { isValidUsageTimestamp(it, currentTime) }
                            ?: emptyList()

                        (existingTimestamps + validImportedTimestamps)
                            .distinct()
                            .sortedDescending()
                            .take(AppConstants.MAX_TIMESTAMPS_PER_APP)
                    } else {
                        // Replace: only the imported ones. validImportedTimestamps is
                        // already sorted descending and truncated to the newest MAX.
                        validImportedTimestamps
                    }

                    preferences[usageKey] = finalTimestamps.map { it.toString() }.toSet()

                    packagesImported++
                    timestampsImported += finalTimestamps.size
                }
            }

            Timber.i("Usage import complete: $packagesImported packages, $timestampsImported timestamps")

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
    private fun validateJsonStructure(root: JSONObject): Boolean {
        return try {
            // Version muss String sein
            if (root.has("version") && root.get("version") !is String) {
                Timber.w("Type validation failed: version is not a string")
                return false
            }

            // export_timestamp / exportTimestamp kann String (ISO) oder Number sein
            val timestampKey = when {
                root.has("exportTimestamp") -> "exportTimestamp"
                root.has("export_timestamp") -> "export_timestamp"
                else -> null
            }
            if (timestampKey != null && !root.isNull(timestampKey)) {
                val value = root.get(timestampKey)
                if (value !is Number && value !is String) {
                    Timber.w("Type validation failed: $timestampKey is not a number or string")
                    return false
                }
            }

            // usage_data / usageData muss Object sein
            val usageKey = when {
                root.has("usageData") -> "usageData"
                root.has("usage_data") -> "usage_data"
                else -> null
            }

            if (usageKey != null) {
                val usageData = root.get(usageKey)
                if (usageData !is JSONObject) {
                    Timber.w("Type validation failed: $usageKey is not an object")
                    return false
                }

                // DoS-Schutz: Nicht zu viele Packages
                if (usageData.length() > AppConstants.MAX_ARRAY_ELEMENTS) {
                    Timber.w("Too many packages in $usageKey: ${usageData.length()}")
                    return false
                }

                // Jeder Wert muss ein Array von Numbers oder Strings sein
                val keys = usageData.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = usageData.get(key)

                    if (value !is org.json.JSONArray) {
                        Timber.w("Type validation failed: $key is not an array")
                        return false
                    }

                    if (value.length() > AppConstants.MAX_TIMESTAMPS_PER_APP * 2) {
                        Timber.w("Too many timestamps for $key: ${value.length()}")
                        return false
                    }

                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        if (item !is Number && item !is String) {
                            Timber.w("Type validation failed: timestamp at $key[$i] is not a number or string")
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

    override suspend fun saveToFile(uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (uriString.isBlank()) {
                TimberWrapper.silentError("Empty URI string provided")
                return@withContext false
            }

            val uri = try {
                uriString.toUri()
            } catch (e: IllegalArgumentException) {
                TimberWrapper.silentError(e, "Invalid URI format: $uriString")
                return@withContext false
            }

            val jsonString = exportToJson()

            context.contentResolver.openOutputStream(uri)?.use { output ->
                // Encode the JSON straight to the stream via a Writer instead of
                // materializing a second full copy with jsonString.toByteArray().
                // The writer wraps the caller-owned stream, so flush (don't close)
                // and let the enclosing use{} close the stream.
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                writer.write(jsonString)
                writer.flush()
                Timber.i("Usage data saved to: $uri")
                true
            } ?: run {
                TimberWrapper.silentError("Failed to open output stream for URI: $uri")
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
                    // No suspension point: guarded body is synchronous today; if a call here becomes suspend, switch to a CancellationException rethrow arm (AUDIT-12 whitelist review).
                    Timber.w(e, "Could not determine file size")
                    0L
                }

                if (fileSize > AppConstants.MAX_BACKUP_SIZE_BYTES) {
                    return@withContext UsageImportResult.Error("File too large")
                }

                // Bounded read. The statSize check above is only a fast path: a
                // streaming/pipe ContentProvider reports statSize == -1 (unknown),
                // which slips past `> MAX` and would otherwise reach an UNBOUNDED
                // readText() → OOM on a hostile/huge stream. Cap the ACTUAL read at
                // MAX_BACKUP_SIZE_BYTES instead. Read one byte past the cap so an
                // over-limit stream is detected; a legitimate file whose provider
                // simply doesn't report a size still imports (do NOT reject on a
                // non-positive statSize — real small files can report -1/0).
                val cap = AppConstants.MAX_BACKUP_SIZE_BYTES
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readNBytes((cap + 1).toInt())
                } ?: return@withContext UsageImportResult.Error("Cannot read file")

                if (bytes.size.toLong() > cap) {
                    return@withContext UsageImportResult.Error("File too large")
                }

                val jsonString = String(bytes, Charsets.UTF_8)

                importFromJson(jsonString, mergeWithExisting)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error loading usage data from file")
                UsageImportResult.Error("Load failed: ${e.message}")
            }
        }
}
