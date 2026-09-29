package com.github.reygnn.nyx_launcher.home.wallpaper

import android.graphics.Bitmap
import com.github.reygnn.launcher.common.ui.wallpaper.DecodedWallpaperBitmap
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Unit tests for [WallpaperLayerBitmapCache] — the per-layer decode cache that kills
 * the delete-a-layer flicker. Pure JVM: the [Bitmap] is mocked (only
 * `allocationByteCount` for sizing and `isRecycled` for the drop check are touched),
 * no Robolectric. Pins get/put/replace, the recycled-drop, LRU (access-order)
 * eviction, the never-recycle-on-eviction invariant, the keep-one-oversized rule,
 * and clear.
 */
class WallpaperLayerBitmapCacheTest {

    private fun decoded(bytes: Int = 10, recycled: Boolean = false): DecodedWallpaperBitmap {
        val bitmap = mockk<Bitmap>(relaxUnitFun = true) {
            every { allocationByteCount } returns bytes
            every { isRecycled } returns recycled
        }
        return DecodedWallpaperBitmap(bitmap, sampleSize = 1, originalWidth = 100, originalHeight = 100)
    }

    @Test
    fun `get returns the entry put under the same key`() {
        val cache = WallpaperLayerBitmapCache()
        val entry = decoded()
        cache.put("file:///a.png", entry)
        assertThat(cache.get("file:///a.png")).isSameInstanceAs(entry)
    }

    @Test
    fun `get misses on an unknown key`() {
        val cache = WallpaperLayerBitmapCache()
        cache.put("file:///a.png", decoded())
        assertThat(cache.get("file:///b.png")).isNull()
    }

    @Test
    fun `get drops a recycled bitmap`() {
        val cache = WallpaperLayerBitmapCache()
        cache.put("file:///a.png", decoded(recycled = true))
        assertThat(cache.get("file:///a.png")).isNull()
    }

    @Test
    fun `put replaces the entry for the same key`() {
        val cache = WallpaperLayerBitmapCache()
        cache.put("file:///a.png", decoded())
        val replacement = decoded()
        cache.put("file:///a.png", replacement)
        assertThat(cache.get("file:///a.png")).isSameInstanceAs(replacement)
    }

    @Test
    fun `LRU eviction drops the least-recently-used entry`() {
        // Budget holds two 10-byte entries (20) but not three (30).
        val cache = WallpaperLayerBitmapCache(maxBytes = 25)
        val a = decoded(bytes = 10)
        val b = decoded(bytes = 10)
        val c = decoded(bytes = 10)
        cache.put("file:///a.png", a)
        cache.put("file:///b.png", b)
        cache.get("file:///a.png") // touch A → B is now the LRU entry
        cache.put("file:///c.png", c) // over budget → evict B
        assertThat(cache.get("file:///b.png")).isNull()
        assertThat(cache.get("file:///a.png")).isSameInstanceAs(a)
        assertThat(cache.get("file:///c.png")).isSameInstanceAs(c)
    }

    @Test
    fun `eviction drops the reference without recycling`() {
        val cache = WallpaperLayerBitmapCache(maxBytes = 15)
        val a = decoded(bytes = 10)
        cache.put("file:///a.png", a)
        cache.put("file:///b.png", decoded(bytes = 10)) // over budget → evict A
        assertThat(cache.get("file:///a.png")).isNull()
        verify(exactly = 0) { a.bitmap.recycle() }
    }

    @Test
    fun `a single oversized layer is kept`() {
        val cache = WallpaperLayerBitmapCache(maxBytes = 5)
        val a = decoded(bytes = 10)
        cache.put("file:///a.png", a)
        assertThat(cache.get("file:///a.png")).isSameInstanceAs(a)
    }

    @Test
    fun `clear empties the cache`() {
        val cache = WallpaperLayerBitmapCache()
        cache.put("file:///a.png", decoded())
        cache.clear()
        assertThat(cache.get("file:///a.png")).isNull()
    }

    @Test
    fun `replacing a key does not inflate byte accounting and evict a sibling`() {
        // Budget holds two 10-byte entries (20) but not three (30).
        val cache = WallpaperLayerBitmapCache(maxBytes = 25)
        val a = decoded(bytes = 10)
        val b = decoded(bytes = 10)
        cache.put("file:///a.png", a)
        cache.put("file:///b.png", b) // total 20
        // Replace A repeatedly. If put() failed to subtract the replaced entry's bytes,
        // currentBytes would climb past the budget and evict the LRU sibling B.
        repeat(5) { cache.put("file:///a.png", decoded(bytes = 10)) }
        assertThat(cache.get("file:///b.png")).isSameInstanceAs(b)
    }

    @Test
    fun `putIfCurrent stores while the generation is unchanged`() {
        val cache = WallpaperLayerBitmapCache()
        val gen = cache.generation()
        val entry = decoded()
        assertThat(cache.putIfCurrent("file:///a.png", entry, gen)).isTrue()
        assertThat(cache.get("file:///a.png")).isSameInstanceAs(entry)
    }

    @Test
    fun `putIfCurrent drops a decode captured before a clear`() {
        val cache = WallpaperLayerBitmapCache()
        val gen = cache.generation()
        cache.clear() // bumps generation — the wallpaper this decode was for is gone
        assertThat(cache.putIfCurrent("file:///a.png", decoded(), gen)).isFalse()
        assertThat(cache.get("file:///a.png")).isNull()
    }
}
