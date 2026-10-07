package com.github.reygnn.nyx_launcher.di

import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperFlattenTheme
import com.github.reygnn.nyx_launcher.R
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The theme for the shared `WallpaperFlattener`'s off-screen AppCompat view (3b-6b) — Nyx flattens
 * since 3b-6 (`CachedWallpaperComposite`), Kolibri provides its counterpart in `AppModule`. An own
 * small object module, as Nyx' DI keeps `@Binds` (abstract) and `@Provides` (object) apart.
 */
@Module
@InstallIn(SingletonComponent::class)
object WallpaperFlattenThemeModule {
    // Theme.Nyx on purpose: the theme of MainActivity, which shows the wallpaper — so the detached
    // layer views get the same styles as the live view. Not Theme.Nyx.Settings (settings only).
    @Provides
    @WallpaperFlattenTheme
    fun provideWallpaperFlattenTheme(): Int = R.style.Theme_Nyx
}
