package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed store for custom app-name overrides.
 *
 * Users set personalized display names for installed apps; they are persisted in
 * DataStore keyed by package name (`name_<packageName>`).
 *
 * **Reactive by [customNamesFlow] (REACTIVE_APPLIST_SPEC).** State is exposed as a
 * cold `Flow<Map<packageName, customName>>`. A rename is a single DataStore edit;
 * DataStore re-emits, [customNamesFlow] ticks, and every display site folds the
 * name in via `applyCustomNames` (`combine`) — no PackageManager re-enumeration is
 * triggered. This replaced the previous `MutableSharedFlow<Unit>` event-bus design
 * (which forced a full re-enumeration on every rename); the enumeration no longer
 * bakes custom names in at all (see `InstalledAppsRepositoryImpl.processResolveInfoList`).
 *
 * Point-read helpers ([getDisplayNameForPackage], [hasCustomNameForPackage],
 * [getAllCustomNames]) remain for callers that need a one-shot answer (backup
 * export, reconcile). [getAllCustomNames] shares its decode with [customNamesFlow]
 * ([toCustomNamesMap]) so the two read paths cannot drift (DSR-INV-1).
 *
 * **Error Handling:**
 * All mutating operations return Boolean success indicators and log failures
 * silently. [java.util.concurrent.CancellationException] is always re-thrown for
 * proper coroutine cancellation.
 *
 * @property dataStore Preferences DataStore for persisting custom names
 */
@Singleton
class CustomNamesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : CustomNamesRepository, OwnsSettingsStoreKeys {

    // Custom-name keys are dynamic per-package: `name_<pkg>` built from
    // KEY_NAME_PREFIX. The cleanup keeps every key under this prefix.
    override fun ownedKeyPrefixes(): Set<String> = setOf(AppConstants.KEY_NAME_PREFIX)

    /**
     * Reactive `packageName -> customName` view (REACTIVE_APPLIST_SPEC RAL-1).
     * Cold, fail-open read (DATASTORE_READ_SPEC): an [java.io.IOException] on the
     * store recovers to the empty mapping rather than killing the flow.
     * [distinctUntilChanged] suppresses re-emission when the mapping is
     * unchanged. The [toCustomNamesMap] transform is shared with
     * [getAllCustomNames], so flow and snapshot cannot drift (DSR-INV-1).
     */
    override val customNamesFlow: Flow<Map<String, String>> =
        dataStore.readFlowFailOpen("Error reading custom names flow") { it.toCustomNamesMap() }
            .distinctUntilChanged()

    /**
     * Setzt einen benutzerdefinierten Namen für eine App. Wenn der Name leer ist, wird er entfernt.
     * A successful edit re-emits [customNamesFlow] via DataStore; consumers re-derive reactively.
     */
    override suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean {
        // Die Logik, ob ein Name entfernt oder gesetzt wird, ist hier gekapselt.
        val isSuccessful = if (customName.isBlank()) {
            // Rufe die interne Logik zum Entfernen auf, um doppelten Trigger-Code zu vermeiden.
            removeCustomNameInternal(packageName)
        } else {
            // Logik zum Setzen/Aktualisieren des Namens.
            try {
                val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
                dataStore.edit { preferences ->
                    preferences[nameKey] = customName.trim()
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error setting custom name for package: $packageName")
                false
            }
        }

        // A successful edit re-emits customNamesFlow via DataStore, so consumers
        // re-derive the display reactively — no PackageManager re-enumeration is
        // triggered any more (REACTIVE_APPLIST_SPEC).
        return isSuccessful
    }

    /**
     * Entfernt einen benutzerdefinierten Namen für eine App.
     * Diese Methode wird vom ViewModel aufgerufen.
     */
    override suspend fun removeCustomNameForPackage(packageName: String): Boolean {
        return removeCustomNameInternal(packageName)
    }

    /**
     * Die eigentliche Logik zum Entfernen, wiederverwendet von setCustomName
     * (Blank-Pfad) und removeCustomNameForPackage.
     */
    private suspend fun removeCustomNameInternal(packageName: String): Boolean {
        return try {
            val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
            dataStore.edit { preferences ->
                preferences.remove(nameKey)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing custom name for package: $packageName")
            false
        }
    }

    /**
     * Gibt den benutzerdefinierten Anzeigenamen zurück, falls vorhanden,
     * andernfalls den übergebenen Originalnamen.
     */
    override suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String {
        return try {
            val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
            val preferences = dataStore.data.first()
            preferences[nameKey] ?: originalName
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting display name for package: $packageName")
            originalName
        }
    }

    /**
     * Prüft, ob für eine App ein benutzerdefinierter Name existiert.
     */
    override suspend fun hasCustomNameForPackage(packageName: String): Boolean {
        return try {
            val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
            val preferences = dataStore.data.first()
            preferences.contains(nameKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error checking custom name for package: $packageName")
            false
        }
    }

    /**
     * Gibt alle benutzerdefinierten App-Namen als Map zurück.
     * Für Backup/Export-Zwecke.
     */
    override suspend fun getAllCustomNames(): Map<String, String> {
        return try {
            val preferences = dataStore.data.first()
            val customNames = preferences.toCustomNamesMap()

            Timber.d("Retrieved ${customNames.size} custom app names")
            customNames
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting all custom names")
            emptyMap()
        }
    }

    /**
     * Setzt mehrere benutzerdefinierte Namen gleichzeitig (Batch-Operation).
     * Optimiert für Backup-Import - triggert nur einmal am Ende statt nach jedem Namen.
     */
    override suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean {
        if (names.isEmpty()) return true

        return try {
            // Alle Namen in einer DataStore-Transaction setzen
            dataStore.edit { preferences ->
                names.forEach { (packageName, customName) ->
                    val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
                    if (customName.isNotBlank()) {
                        preferences[nameKey] = customName.trim()
                    }
                }
            }

            Timber.i("Batch set ${names.size} custom app names")

            // The single DataStore edit re-emits customNamesFlow once; no
            // enumeration trigger (REACTIVE_APPLIST_SPEC).
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting custom names in batch")
            false
        }
    }

    override suspend fun reconcileCustomNames(
        installedPackageNames: List<String>,
        isStillPresent: suspend (String) -> Boolean,
    ) {
        // FAIL-CLOSED read (propagates; NOT the swallow-to-empty getAllCustomNames).
        // Candidate read and delete are the same authority; no try/catch — errors
        // propagate to the caller's runCleanup (RECONCILE_FIX_SPEC R-INV-2).
        val current = dataStore.data.first()
        val assignedPackages = current.customNameKeys().mapTo(HashSet()) { it.customNamePackage() }
        val orphans = assignedPackages - installedPackageNames.toSet()
        if (orphans.isEmpty()) return

        // isStillPresent receives a PACKAGE name (custom names are package-based).
        val verifiedAbsent = orphans.filterNotTo(HashSet()) { isStillPresent(it) }
        if (verifiedAbsent.isEmpty()) return

        dataStore.edit { preferences ->
            // Value-scoped: key == package identity, so remove(key) can only touch
            // the verified-absent package. Re-snapshot inside the edit.
            val toRemove = preferences.customNameKeys()
                .filter { it.customNamePackage() in verifiedAbsent }
            if (toRemove.isNotEmpty()) {
                // Log only the count, never package names (PII).
                Timber.w("Removed ${toRemove.size} orphaned custom names")
                toRemove.forEach { preferences.remove(it) }
            }
        }
        // Runs inside the app-load pipeline; the DataStore edit alone re-emits
        // customNamesFlow, which is the only reactive channel now.
    }

    override suspend fun purgeRepository() {
        try {
            dataStore.edit { preferences ->
                preferences.customNameKeys().forEach { preferences.remove(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to purge CustomNamesRepositoryImpl repository")
        }
    }

    /**
     * True for a DataStore key that holds a custom name, i.e. carries the
     * [AppConstants.KEY_NAME_PREFIX] prefix ("name_<packageName>"). The single
     * definition of the key convention, shared by read/cleanup/purge.
     */
    private fun Preferences.Key<*>.isCustomNameKey(): Boolean =
        name.startsWith(AppConstants.KEY_NAME_PREFIX)

    /** The package name encoded in a `name_<packageName>` key. */
    private fun Preferences.Key<*>.customNamePackage(): String =
        name.removePrefix(AppConstants.KEY_NAME_PREFIX)

    /**
     * The single `Preferences -> Map<packageName, customName>` decode, shared by
     * the reactive [customNamesFlow] and the [getAllCustomNames] snapshot so the
     * two read paths cannot drift (DSR-INV-1). Single pass over the snapshot:
     * read key + value together instead of re-fetching each value via
     * `preferences[key]`.
     */
    private fun Preferences.toCustomNamesMap(): Map<String, String> =
        asMap().entries.mapNotNull { (key, value) ->
            if (key.isCustomNameKey() && value is String) key.customNamePackage() to value else null
        }.toMap()

    /**
     * All keys holding a custom name. Shared by cleanup/purge. Works on both a
     * read snapshot and the
     * [androidx.datastore.preferences.core.MutablePreferences] inside `edit`.
     */
    private fun Preferences.customNameKeys(): List<Preferences.Key<*>> =
        asMap().keys.filter { it.isCustomNameKey() }
}