package com.github.reygnn.launcher.common.ui.wallpaper

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Unit tests for [WallpaperCompositeCache] — the single-entry, path-keyed
 * in-memory cache for the display wallpaper bitmap. Pure JVM: the [Bitmap] is
 * mocked (only [Bitmap.isRecycled] is ever touched by the cache), no Robolectric
 * needed. Pins the get/put/recycle behavior plus [WallpaperCompositeCache.invalidate]
 * (AUDIT-20 F3) and [WallpaperCompositeCache.invalidateIfNotKey] (AUDIT-20 F12).
 */
class WallpaperCompositeCacheTest {

    private val cache = WallpaperCompositeCache()

    private fun decoded(recycled: Boolean = false): DecodedWallpaperBitmap {
        val bitmap: Bitmap = mockk { every { isRecycled } returns recycled }
        return DecodedWallpaperBitmap(bitmap, sampleSize = 1, originalWidth = 100, originalHeight = 100)
    }

    @Test
    fun `get returns the entry put under the same path`() {
        val entry = decoded()
        cache.put("file:///composite_1.webp", entry)
        assertThat(cache.get("file:///composite_1.webp")).isSameInstanceAs(entry)
    }

    @Test
    fun `get misses on a different path`() {
        cache.put("file:///composite_1.webp", decoded())
        assertThat(cache.get("file:///composite_2.webp")).isNull()
    }

    @Test
    fun `get drops a recycled bitmap`() {
        cache.put("file:///composite_1.webp", decoded(recycled = true))
        assertThat(cache.get("file:///composite_1.webp")).isNull()
    }

    @Test
    fun `invalidate drops the held entry`() {
        cache.put("file:///composite_1.webp", decoded())
        cache.invalidate()
        assertWithMessage("AUDIT-20 F3: the cache must be empty after invalidate()").that(cache.get("file:///composite_1.webp")).isNull()
    }

    @Test
    fun `invalidateIfNotKey drops a stale-key entry`() {
        cache.put("composite://portrait", decoded())
        cache.invalidateIfNotKey("composite://landscape")
        assertWithMessage("AUDIT-20 F12: an entry under a now-dead key must be dropped").that(cache.get("composite://portrait")).isNull()
    }

    @Test
    fun `invalidateIfNotKey keeps the current-key entry`() {
        val entry = decoded()
        cache.put("composite://portrait", entry)
        cache.invalidateIfNotKey("composite://portrait")
        assertWithMessage("AUDIT-20 F12: the live current-key entry must survive").that(cache.get("composite://portrait")).isSameInstanceAs(entry)
    }

    @Test
    fun `invalidateIfNotKey is a no-op on an empty cache`() {
        cache.invalidateIfNotKey("composite://anything")
        assertThat(cache.get("composite://anything")).isNull()
    }
}
