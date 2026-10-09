package com.github.reygnn.kolibri_launcher.di

import com.github.reygnn.launcher.feature.wallpaper.CachedWallpaperComposite
import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Kolibri binds the shared [WallpaperComposite] interface to [CachedWallpaperComposite]: one
 * singleton (scoped on the class) for the delegate's write side and the Activity's read side
 * (3a-8). The composite pays off here because HomeFragment rebuilds its render surface on
 * drawer → home.
 *
 * Same module name and place as in Nyx (`nyx/app/.../di/WallpaperCompositeModule`, which binds
 * [WallpaperComposite.None]): the choice of composite is a per-app decision of the host, so it
 * lives in each app module, not in the data layer (SPEC_NYX_REWRITE D3).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WallpaperCompositeModule {
    @Binds
    abstract fun bindWallpaperComposite(impl: CachedWallpaperComposite): WallpaperComposite
}
