package com.github.reygnn.nyx_launcher.di

import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Nyx binds the shared [WallpaperComposite] interface to [WallpaperComposite.None] — no display
 * composite (SPEC_NYX_REWRITE 3b-6d, the user's decision, option A).
 *
 * Why: a composite only pays off in a host that rebuilds its render surface, like Kolibri's
 * HomeFragment on drawer → home. Nyx hosts the wallpaper in a long-lived Activity view and renders
 * only on a state change, after the editor and on a configuration change — it never had the
 * drawer → home flash. With a composite, Nyx would flatten on the MAIN thread at every cold start
 * (exactly in the first-paint window) and hold an extra full-screen bitmap, while the composite
 * would hardly ever reach the screen. This is a per-app binding of the shared interface, not
 * different logic: the write side ([com.github.reygnn.launcher.feature.wallpaper.WallpaperOperations])
 * and the read side ([com.github.reygnn.nyx_launcher.home.wallpaper.NyxWallpaperRenderSource]) stay
 * as built in 3b-6, and the layer cache does the display work, as before 3b-6.
 *
 * Should a Nyx host ever rebuild its surface, binding
 * [com.github.reygnn.launcher.feature.wallpaper.CachedWallpaperComposite] here is the one change.
 */
@Module
@InstallIn(SingletonComponent::class)
object WallpaperCompositeModule {
    @Provides
    @Singleton
    fun provideWallpaperComposite(): WallpaperComposite = WallpaperComposite.None()
}
