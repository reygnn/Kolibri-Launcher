package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a folder's derived 2×2 preview from up to four member icons on a faint
 * rounded background (IHM-INV-2: never stored as an IconRef). Member bitmaps come
 * from the shared [IconLoader] cache.
 *
 * A small count-bounded LRU caches the composed preview, keyed by the ordered
 * members + size ([IconCacheKey.folder]), so it isn't redrawn on every bind.
 * Changing membership changes the key (self-invalidating); a package update calls
 * [clear] via the coordinator so a member's new icon isn't shown stale. Only a
 * COMPLETE composite is cached — a transient member-icon load failure yields an
 * uncached partial so the next bind retries instead of pinning a blank quadrant.
 */
@Singleton
class FolderIconRenderer @Inject constructor(
    private val iconLoader: IconLoader,
    @IoDispatcher dispatcher: CoroutineDispatcher,
    preferences: PreferencesRepository,
) {
    init {
        // Promptly drop cached previews when the style changes so old-style composites
        // don't linger in memory until LRU eviction. This is a memory optimisation, NOT
        // the correctness mechanism: render() keys by IconLoader.currentStyle (the same
        // authority the member bitmaps decode under), so even if this collector leads or
        // lags IconLoader's own, a style switch always yields a cache miss under the new
        // style and recomposes — a mixed-style composite can never stick (F12).
        preferences.iconStyle()
            .onEach { clear() }
            .launchIn(CoroutineScope(SupervisorJob() + dispatcher))
    }
    private val lock = Any()
    private val cache = object : LinkedHashMap<CacheKey, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Bitmap>): Boolean =
            size > MAX_ENTRIES
    }

    suspend fun render(members: List<ComponentKey>, sizePx: Int): Bitmap {
        // Key by IconLoader's current style — the SAME snapshot the member bitmaps below
        // decode under — so the composite key can never disagree with its contents (F12).
        val key = IconCacheKey.folder(members, sizePx, iconLoader.currentStyle)
        synchronized(lock) { cache[key]?.let { return it } }
        val (composed, complete) = compose(members, sizePx)
        // Only cache a COMPLETE composite. If a member icon failed to load transiently it
        // leaves a blank quadrant; caching that partial bitmap would pin the gap until an
        // unrelated invalidation (clear() / membership change) — it would never self-heal
        // (F11). Leaving an incomplete composite uncached lets the next bind re-attempt the
        // failed member and fill the quadrant once its icon is available.
        if (complete) synchronized(lock) { cache[key] = composed }
        return composed
    }

    /** Drop all cached previews (on package change / memory trim). */
    fun clear() = synchronized(lock) { cache.clear() }

    /**
     * Composes the 2×2 preview. Returns the bitmap plus whether it is COMPLETE — i.e.
     * every member that should have been drawn actually loaded. An incomplete result
     * (a transient member-icon load failure) must not be cached (see [render]).
     */
    private suspend fun compose(members: List<ComponentKey>, sizePx: Int): Pair<Bitmap, Boolean> {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF } // ~20% white
        val radius = sizePx * 0.18f
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, bg)

        val pad = (sizePx * 0.10f).toInt()
        val cell = (sizePx - pad * 3) / 2 // 2 cells + 3 paddings span the size
        var complete = true
        members.take(4).forEachIndexed { index, key ->
            val bitmap = runCatching { iconLoader.bitmap(IconRef.System(key), cell) }.getOrNull()
            if (bitmap == null) {
                complete = false // transient load failure → leave this quadrant blank, don't cache
                return@forEachIndexed
            }
            val col = index % 2
            val row = index / 2
            val left = (pad + col * (cell + pad)).toFloat()
            val top = (pad + row * (cell + pad)).toFloat()
            canvas.drawBitmap(bitmap, left, top, null)
        }
        return out to complete
    }

    private companion object {
        const val MAX_ENTRIES = 64
    }
}
