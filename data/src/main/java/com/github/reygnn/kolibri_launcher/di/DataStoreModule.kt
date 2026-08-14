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

/**
 * Separate DataStore<Preferences> holding ONLY the time-weighted app-usage
 * timestamps (the [AppConstants.KEY_USAGE_PREFIX] keys). Split out of
 * [settingsDataStore] in AUDIT-19 F1 because usage is written on every app
 * launch and Preferences DataStore re-serialises the whole file per edit —
 * its own file confines that churn and its collector fan-out.
 *
 * No migration from the old settings-store keys (same choice as the consent
 * store): on the update that ships this, usage history starts empty and
 * rebuilds as apps are launched. `private`: nothing outside this file touches
 * the extension — runtime consumers inject the qualified `DataStore<Preferences>`
 * from [DataStoreModule.provideUsageDataStore], and unlike the consent store
 * there is no pre-Hilt bootstrap path here.
 */
private val Context.usageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppConstants.USAGE_DATASTORE_NAME
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

    /**
     * The usage [usageDataStore], qualified with [UsageDataStore] so it does
     * not collide with the unqualified settings store. Injected by
     * `AppUsageRepositoryImpl` and `UsageExportRepositoryImpl` (AUDIT-19 F1).
     */
    @Provides
    @Singleton
    @UsageDataStore
    fun provideUsageDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.usageDataStore
    }
}
