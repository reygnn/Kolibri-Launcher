package com.github.reygnn.kolibri_launcher.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.github.reygnn.kolibri_launcher.core.AppConstants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
    name = AppConstants.SETTINGS_DATASTORE_NAME
)

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.settingsDataStore
    }
}