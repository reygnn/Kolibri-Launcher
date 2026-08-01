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
 * The app's general settings / user-config store (favorites, layout, colours,
 * …) — one of two Preferences DataStores. The ACRA crash-report consent flag is
 * NOT here; it lives in the separate [consentDataStore] (its own file, excluded
 * from Auto Backup). `private` because nothing outside this file touches the
 * extension: runtime consumers inject `DataStore<Preferences>` from
 * [DataStoreModule.provideSettingsDataStore], and the pre-Hilt bootstrap only
 * ever reaches [consentDataStore], never this store.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppConstants.SETTINGS_DATASTORE_NAME
)

/**
 * Separate DataStore<Preferences> holding ONLY the ACRA crash-report
 * consent state ([com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentBootstrap]).
 *
 * Deliberately a second file rather than a key in [settingsDataStore]:
 * Android Auto Backup includes the settings DataStore (so user config
 * travels to a new device) but must NOT carry the consent flag — a
 * restored install starts privacy-by-default and re-asks. Backup rules
 * exclude at file granularity, so the consent needs its own file. There
 * is intentionally NO migration from the old settings-store keys: a
 * pre-existing install's consent simply resets once (privacy-safe) and
 * the dialog re-appears. `internal` (not `private`) so the pre-Hilt accessor
 * [com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentBootstrap] —
 * which reads this store from `KolibriLauncherApp.attachBaseContext` before Hilt
 * is up — can reach the extension across files.
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
     * [ConsentDataStore]. Same singleton the bootstrap `ConsentBootstrap`
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
