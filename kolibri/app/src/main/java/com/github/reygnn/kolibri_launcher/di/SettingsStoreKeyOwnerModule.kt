package com.github.reygnn.kolibri_launcher.di

import com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes `:app`-side owners of settings-store keys to the storage-cleanup
 * keep-list (`Set<OwnsSettingsStoreKeys>`, declared via `@Multibinds` in
 * `RepositoryModule`).
 *
 * [AnrReporter] is not a repository, but it persists its ANR-dedup watermark
 * (`anr_reporter_last_reported_ts`) in the settings store — so it MUST be in the
 * keep-list, or the blacklist cleanup would delete the watermark and re-report
 * already-reported ANRs. This is exactly the cross-module straggler the
 * `checkConventions` settings-key guard exists to catch.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsStoreKeyOwnerModule {

    @Provides
    @IntoSet
    fun provideAnrReporterKeysOwner(impl: AnrReporter): OwnsSettingsStoreKeys = impl
}
