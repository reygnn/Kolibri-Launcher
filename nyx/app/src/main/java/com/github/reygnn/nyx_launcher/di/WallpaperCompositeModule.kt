package com.github.reygnn.nyx_launcher.di

import com.github.reygnn.launcher.feature.wallpaper.CachedWallpaperComposite
import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The display composite for Nyx (SPEC_NYX_REWRITE 3b-6, E3): the shared [CachedWallpaperComposite],
 * one singleton for the write side (the shared edit session's operations) and the read side
 * (`NyxWallpaperRenderSource`) — as in Kolibri.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WallpaperCompositeModule {
    @Binds
    abstract fun bindWallpaperComposite(impl: CachedWallpaperComposite): WallpaperComposite
}
