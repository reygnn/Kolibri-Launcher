package com.github.reygnn.kolibri_launcher.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.github.reygnn.launcher.core.AppConstants
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
 * Separate DataStore<Preferences> holding ONLY the time-weighted app-usage
 * timestamps (the [AppConstants.KEY_USAGE_PREFIX] keys). Split out of
 * [settingsDataStore] in AUDIT-19 F1 because usage is written on every app
 * launch and Preferences DataStore re-serialises the whole file per edit —
 * its own file confines that churn and its collector fan-out.
 *
 * No in-code migration from the old settings-store keys — a deliberate
 * project-wide policy (CLAUDE.md §5): Kolibri ships NO data-migration logic. A
 * store move is handled by the USER instead: export in the old version → factory
 * reset → update → restore. Usage timestamps survive that via the separate,
 * re-importable UsageExport (`UsageExportRepository`, JSON so store-agnostic);
 * launcher settings via the normal backup. On an update WITHOUT that reset the
 * new store simply starts empty and the TIME_WEIGHTED_USAGE sort rebuilds as apps
 * are launched. This is NOT the same as the consent store's reset: that reset is
 * a desired privacy behaviour, whereas here the user preserves the data via the
 * export path — do not conflate the two. `private`: nothing outside this file
 * touches the extension — runtime consumers inject the qualified
 * `DataStore<Preferences>` from [DataStoreModule.provideUsageDataStore], and
 * unlike the consent store there is no pre-Hilt bootstrap path here.
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
