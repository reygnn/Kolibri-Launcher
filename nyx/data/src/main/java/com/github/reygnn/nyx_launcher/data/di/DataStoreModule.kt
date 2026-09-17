package com.github.reygnn.nyx_launcher.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Top-level delegate ⇒ exactly one DataStore instance per process for this file.
private val Context.homeLayoutDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "home_layout",
)

// Separate store for app-usage timestamps (highest-frequency write; see [UsageDataStore]).
// Its own name so it never aliases kolibri's "kolibri_usage" file when both apps are installed.
private val Context.usageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nyx_usage",
)

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideHomeLayoutDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.homeLayoutDataStore

    @Provides
    @Singleton
    @UsageDataStore
    fun provideUsageDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.usageDataStore
}
