package com.github.reygnn.kolibri_launcher.di

import javax.inject.Qualifier

/**
 * Hilt qualifier distinguishing the ACRA-consent `DataStore<Preferences>`
 * (own backing file, excluded from Auto Backup) from the unqualified
 * settings `DataStore<Preferences>`. Bound by
 * [DataStoreModule.provideConsentDataStore]; injected into
 * `CrashReportConsentRepositoryImpl`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ConsentDataStore
