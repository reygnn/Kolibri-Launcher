package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bootstraps schema migrations for the app's persisted user state on each
 * startup. Reads the currently-stored data version, compares it against
 * [TARGET_DATA_VERSION], and runs the migration path if needed.
 *
 * ## Why this class uses SharedPreferences
 *
 * CLAUDE.md Rule 5 says DataStore is the only app storage. This class is
 * the one explicit exception: the migration mechanism cannot bootstrap
 * itself through the very thing it might need to migrate.
 *
 * Concretely, the version flag is stored in a tiny SharedPreferences
 * file (`kolibri_data_version`, single Int key `data_version`). It must
 * be readable *before* anything else opens DataStore — if we kept the
 * version flag inside DataStore, then on a corrupted-DataStore launch
 * we would have no way to detect that we are starting fresh vs. that
 * a real version-1 DataStore has just failed to load. The
 * SharedPreferences-on-disk file decouples "what version is the on-disk
 * state at" from "what state can we currently load".
 *
 * Tradeoff: this is exactly the kind of `SharedPreferences` access that
 * triggers StrictMode noise. It's accepted here because the read is
 * tiny (one Int), happens once on app start in `Dispatchers.IO`, and
 * the alternative (DataStore-bootstrap) is not safe.
 *
 * Don't try to migrate this away. If you spot the SharedPreferences
 * call in `getCurrentVersion` / `updateVersionInPreferences` and want
 * to "clean it up", read this KDoc and CLAUDE.md Rule 5 first.
 */
@Singleton
class DataMigrationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val VERSION_PREFS_NAME = "kolibri_data_version"
        private const val KEY_DATA_VERSION = "data_version"
        private const val TARGET_DATA_VERSION = 1
    }

    // WICHTIG: Kein 'by lazy' hier, das den Main Thread blockieren könnte.
    // Wir holen die Prefs explizit im IO-Context.

    suspend fun runMigrationIfNeeded() {
        // Wrapper, um sicherzustellen, dass alles im Hintergrund passiert
        withContext(Dispatchers.IO) {
            try {
                val currentVersion = getCurrentVersion()

                if (currentVersion < TARGET_DATA_VERSION) {
                    Timber.i("Migration needed from version $currentVersion to $TARGET_DATA_VERSION")
                    runMigration(currentVersion)
                } else {
                    Timber.d("No migration needed. Current version: $currentVersion")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Critical error during migration check")
            }
        }
    }

    private suspend fun runMigration(currentVersion: Int) {
        try {
            when {
                currentVersion == 0 -> {
                    Timber.i("First launch detected, setting version without clearing DataStore")
                }
                currentVersion < TARGET_DATA_VERSION -> {
                    Timber.i("Old data version detected: $currentVersion. Clearing DataStore...")
                    try {
                        dataStore.edit { it.clear() }
                        Timber.i("DataStore cleared successfully")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Cleanup hat möglicherweise mitten im DataStore aufgehört
                        // (manche Keys gelöscht, andere nicht). Würden wir weiter
                        // unten updateVersionInPreferences(TARGET) rufen, sähe der
                        // nächste App-Start die Migration als erledigt — bei in
                        // Wirklichkeit halb-leerem DataStore. Lieber kontrolliert
                        // sterben, beim Neustart wird Migration ehrlich nochmal
                        // versucht.
                        TimberWrapper.silentDeath(
                            e,
                            "Migration cleanup failed — refusing to mark version as migrated"
                        )
                    }
                }
                else -> {
                    return
                }
            }
            updateVersionInPreferences(TARGET_DATA_VERSION)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during migration")
        }
    }

    /**
     * Liest die Version sicher im IO-Thread.
     */
    private fun getCurrentVersion(): Int {
        return try {
            val prefs = context.getSharedPreferences(VERSION_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getInt(KEY_DATA_VERSION, 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ein silent return 0 würde "First launch detected" triggern und
            // den Onboarding-Flow über echte User-Daten kippen. Lieber
            // kontrolliert sterben — beim Neustart kommt der Read-Versuch
            // nochmal, und wenn er weiter failt, häuft sich der ACRA-Report.
            TimberWrapper.silentDeath(
                e,
                "Cannot read data version — refusing to assume first launch"
            )
        }
    }

    private fun updateVersionInPreferences(version: Int) {
        try {
            val prefs = context.getSharedPreferences(VERSION_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                putInt(KEY_DATA_VERSION, version)
                // commit() ist hier okay, da wir bereits im Dispatchers.IO Block sind (via runMigrationIfNeeded)
                // commit() schreibt sofort, apply() asynchron. Bei Migrationen ist Sicherheit > Speed.
                commit()
            }
            Timber.i("Data version updated to $version")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to update version in preferences")
        }
    }

    /**
     * Prüft "First Launch" Status.
     * ACHTUNG: Muss suspend sein, um StrictMode zu vermeiden!
     */
    suspend fun isFirstLaunch(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                getCurrentVersion() < TARGET_DATA_VERSION
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error checking first launch status")
                true
            }
        }
    }
}