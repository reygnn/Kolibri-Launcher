package com.github.reygnn.kolibri_launcher.crashreporting.consent

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the crash-report consent repository within :feature-crashreporting.
 *
 * Moved here from Kolibri's :data `RepositoryModule` so :data no longer
 * references any crash-reporting type — that keeps the dependency one-way
 * (:app → :feature → :core), with no :data ↔ :feature cycle.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CrashReportConsentBindingModule {
    @Binds
    abstract fun bindCrashReportConsentRepository(
        impl: CrashReportConsentRepositoryImpl,
    ): CrashReportConsentRepository
}
