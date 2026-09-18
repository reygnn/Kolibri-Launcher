package com.github.reygnn.nyx_launcher.home.wallpaper

import android.graphics.Bitmap
import com.github.reygnn.launcher.common.ui.wallpaper.DecodedWallpaperBitmap
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        assertSame(entry, cache.get("file:///a.png"))
    }

    @Test
    fun `get misses on an unknown key`() {
        val cache = WallpaperLayerBitmapCache()
        cache.put("file:///a.png", decoded())
        assertNull(cache.get("file:///b.png"))
    }

    @Test
    fun `get drops a recycled bitmap`() {
        val cache = WallpaperLayerBitmapCache()
        cache.put("file:///a.png", decoded(recycled = true))
        assertNull(cache.get("file:///a.png"))
    }

    @Test
    fun `put replaces the entry for the same key`() {
        val cache = WallpaperLayerBitmapCache()
        cache.put("file:///a.png", decoded())
        val replacement = decoded()
        cache.put("file:///a.png", replacement)
        assertSame(replacement, cache.get("file:///a.png"))
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
        assertNull(cache.get("file:///b.png"))
        assertSame(a, cache.get("file:///a.png"))
        assertSame(c, cache.get("file:///c.png"))
    }

    @Test
    fun `eviction drops the reference without recycling`() {
        val cache = WallpaperLayerBitmapCache(maxBytes = 15)
        val a = decoded(bytes = 10)
        cache.put("file:///a.png", a)
        cache.put("file:///b.png", decoded(bytes = 10)) // over budget → evict A
        assertNull(cache.get("file:///a.png"))
        verify(exactly = 0) { a.bitmap.recycle() }
    }

    @Test
    fun `a single oversized layer is kept`() {
        val cache = WallpaperLayerBitmapCache(maxBytes = 5)
        val a = decoded(bytes = 10)
        cache.put("file:///a.png", a)
        assertSame(a, cache.get("file:///a.png"))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = WallpaperLayerBitmapCache()
        cache.put("file:///a.png", decoded())
        cache.clear()
        assertNull(cache.get("file:///a.png"))
    }
}
