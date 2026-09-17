package com.github.reygnn.nyx_launcher.data.di

import javax.inject.Qualifier

/**
 * Hilt qualifier for the app-usage `DataStore<Preferences>` — its OWN backing file
 * (`nyx_usage`), separate from the unqualified `home_layout` settings store. Usage
 * timestamps are the highest-frequency write (one per app launch), so keeping them out of
 * the shared settings store confines the per-launch re-serialisation to a small file
 * (mirrors kolibri's AUDIT-19 F1 split).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UsageDataStore
