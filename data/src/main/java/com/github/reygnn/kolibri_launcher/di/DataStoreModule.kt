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

/**
 * Separate DataStore<Preferences> holding ONLY the ACRA crash-report
 * consent state ([com.github.reygnn.kolibri_launcher.data.CrashReportConsentStore]).
 *
 * Deliberately a second file rather than a key in [settingsDataStore]:
 * Android Auto Backup includes the settings DataStore (so user config
 * travels to a new device) but must NOT carry the consent flag — a
 * restored install starts privacy-by-default and re-asks. Backup rules
 * exclude at file granularity, so the consent needs its own file. There
 * is intentionally NO migration from the old settings-store keys: a
 * pre-existing install's consent simply resets once (privacy-safe) and
 * the dialog re-appears. Same `internal` visibility and bootstrap
 * rationale as [settingsDataStore] (read from
 * `KolibriLauncherApp.attachBaseContext` before Hilt).
 */
internal val Context.consentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppConstants.CONSENT_DATASTORE_NAME
)

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.settingsDataStore
    }

    /**
     * The consent [consentDataStore], exposed to Hilt for the interactive
     * (post-Hilt) path — `CrashReportConsentRepositoryImpl` injects it via
     * [ConsentDataStore]. Same singleton the bootstrap `CrashReportConsentStore`
     * reaches through the extension, so both see the same file. Qualified so it
     * does not collide with the unqualified settings store above.
     */
    @Provides
    @Singleton
    @ConsentDataStore
    fun provideConsentDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.consentDataStore
    }
}
