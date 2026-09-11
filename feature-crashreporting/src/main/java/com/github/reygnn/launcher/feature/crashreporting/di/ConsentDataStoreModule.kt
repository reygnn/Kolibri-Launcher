package com.github.reygnn.launcher.feature.crashreporting.di

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
 * Separate DataStore<Preferences> holding ONLY the ACRA crash-report consent
 * state. Owned by :feature-crashreporting (moved out of :data with the slice so
 * the shared feature does not depend back on any app's :data module).
 *
 * Deliberately a second file rather than a key in the settings store: Android
 * Auto Backup includes the settings DataStore but must NOT carry the consent
 * flag — a restored install starts privacy-by-default and re-asks. Backup rules
 * exclude at file granularity, so the consent needs its own file. NO migration
 * from old settings-store keys — consent simply resets once (privacy-safe) and
 * the dialog re-appears. `internal` (not `private`) so the pre-Hilt accessor
 * [com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap] —
 * which reads this store before Hilt is up — can reach the extension across files.
 */
internal val Context.consentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppConstants.CONSENT_DATASTORE_NAME
)

@Module
@InstallIn(SingletonComponent::class)
object ConsentDataStoreModule {

    /**
     * The consent [consentDataStore], exposed to Hilt for the interactive
     * (post-Hilt) path — `CrashReportConsentRepositoryImpl` injects it via
     * [ConsentDataStore]. Same singleton the bootstrap `ConsentBootstrap`
     * reaches through the extension, so both see the same file.
     */
    @Provides
    @Singleton
    @ConsentDataStore
    fun provideConsentDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.consentDataStore
    }
}
