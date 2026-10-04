package com.github.reygnn.nyx_launcher.data.di

import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperDisplayKeys
import com.github.reygnn.launcher.feature.wallpaper.WallpaperDisplayKeys
import com.github.reygnn.launcher.core.SettingsStore
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

    /**
     * The same home_layout store under the shared qualifier (3b-2): the display-settings store and
     * the FAB store in :feature-wallpaper read it as [SettingsStore], like Kolibri's settings store.
     */
    @Provides
    @SettingsStore
    fun provideQualifiedSettingsDataStore(store: DataStore<Preferences>): DataStore<Preferences> = store

    /** Nyx's key names for the wallpaper display settings (3b-2, unchanged on disk). */
    @Provides
    fun provideWallpaperDisplayKeys(): WallpaperDisplayKeys = NyxWallpaperDisplayKeys

    @Provides
    @Singleton
    @UsageDataStore
    fun provideUsageDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.usageDataStore
}
