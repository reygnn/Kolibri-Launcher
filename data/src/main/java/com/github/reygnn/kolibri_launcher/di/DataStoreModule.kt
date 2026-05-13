package com.github.reygnn.kolibri_launcher.di

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.reygnn.kolibri_launcher.core.AppConstants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.annotation.VisibleForTesting
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The single DataStore<Preferences> instance for the whole app.
 *
 * Internal visibility (not private) so a small number of bootstrap-time
 * call sites that run before Hilt can reach it without a second
 * provider:
 *   - [com.github.reygnn.kolibri_launcher.data.CrashReportConsentStore]
 *     reads / writes the ACRA consent flag here. The methods are
 *     called from KolibriLauncherApp.attachBaseContext, which runs
 *     before Hilt is initialised, so they can't take it via @Inject.
 *
 * All other consumers must keep going through Hilt by injecting
 * `DataStore<Preferences>` produced by [DataStoreModule.provideSettingsDataStore].
 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppConstants.SETTINGS_DATASTORE_NAME,
    produceMigrations = { listOf(removeStaleReadabilityModeKey()) },
)

/**
 * One-shot cleanup: removes the orphan `text_readability_mode` key
 * left behind by the readabilityMode feature kill. The setting and
 * its read/write code were deleted, but DataStore still holds the
 * historical value on every device the app ever ran on. Nothing
 * reads the key anymore, so the bytes are harmless — this just
 * sweeps them so DataStore-export / diagnostic dumps don't show a
 * confusing dangling entry.
 *
 * `shouldMigrate` returns false once the key is gone, so the
 * migration is a no-op on every subsequent DataStore open and on
 * fresh installs. Per the precedent documented in CLAUDE.md §5
 * (the historical DataMigrationManager), this whole helper can be
 * removed once enough time has passed that every install has run
 * through it at least once. Git history under `data/di/` will keep
 * the implementation reachable if the same shape is needed again.
 */
@VisibleForTesting
internal fun removeStaleReadabilityModeKey(): DataMigration<Preferences> {
    val staleKey = stringPreferencesKey("text_readability_mode")
    return object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            currentData.contains(staleKey)

        override suspend fun migrate(currentData: Preferences): Preferences =
            currentData.toMutablePreferences().apply { remove(staleKey) }.toPreferences()

        override suspend fun cleanUp() = Unit
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.settingsDataStore
    }
}
