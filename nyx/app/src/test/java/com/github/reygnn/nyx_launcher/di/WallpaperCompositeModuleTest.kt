package com.github.reygnn.nyx_launcher.di

import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins Nyx' composite binding (3b-6d, audit A9a): Nyx binds [WallpaperComposite.None] — its
 * long-lived Activity view would pay the flatten without showing the composite. Changing it is a
 * decision (O7-K), not a side effect: this test fails until the decision is made explicitly.
 */
class WallpaperCompositeModuleTest {

    @Test
    fun nyx_binds_no_display_composite() {
        assertThat(WallpaperCompositeModule.provideWallpaperComposite()).isInstanceOf(WallpaperComposite.None::class.java)
    }
}
