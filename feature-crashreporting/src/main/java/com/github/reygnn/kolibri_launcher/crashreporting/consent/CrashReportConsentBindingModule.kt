package com.github.reygnn.kolibri_launcher.crashreporting.consent

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the crash-report consent collaborators within :feature-crashreporting so
 * BOTH apps get them (they were split across Kolibri's :data RepositoryModule and
 * :app AppModule). Keeps the dependency one-way (:app → :feature → :core), no
 * :data ↔ :feature cycle, and makes ConsentController providable in every app.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CrashReportConsentBindingModule {
    @Binds
    abstract fun bindCrashReportConsentRepository(
        impl: CrashReportConsentRepositoryImpl,
    ): CrashReportConsentRepository

    @Binds
    abstract fun bindAcraToggle(impl: AcraToggleImpl): AcraToggle

    @Binds
    abstract fun bindConsentSaveFailureNotifier(
        impl: ConsentSaveFailureNotifierImpl,
    ): ConsentSaveFailureNotifier
}
