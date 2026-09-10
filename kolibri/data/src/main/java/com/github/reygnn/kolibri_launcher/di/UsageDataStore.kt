package com.github.reygnn.kolibri_launcher.di

import javax.inject.Qualifier

/**
 * Hilt qualifier distinguishing the app-usage `DataStore<Preferences>`
 * (own backing file, [com.github.reygnn.kolibri_launcher.core.AppConstants.USAGE_DATASTORE_NAME])
 * from the unqualified settings `DataStore<Preferences>`. Bound by
 * [DataStoreModule.provideUsageDataStore]; injected into
 * `AppUsageRepositoryImpl` and `UsageExportRepositoryImpl`.
 *
 * Split out in AUDIT-19 F1: usage timestamps are the highest-frequency
 * write (one per app launch), and keeping them out of the shared settings
 * store confines the per-launch re-serialisation + collector fan-out to a
 * small file.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UsageDataStore
