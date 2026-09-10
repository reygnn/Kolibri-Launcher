package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.github.reygnn.nyx_launcher.di.IoDispatcher
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
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
 * [clear] via the coordinator so a member's new icon isn't shown stale.
 */
@Singleton
class FolderIconRenderer @Inject constructor(
    private val iconLoader: IconLoader,
    @IoDispatcher dispatcher: CoroutineDispatcher,
    preferences: PreferencesRepository,
) {
    @Volatile
    private var monochrome = false

    init {
        preferences.monochromeIcons()
            .onEach { monochrome = it }
            .launchIn(CoroutineScope(SupervisorJob() + dispatcher))
    }
    private val lock = Any()
    private val cache = object : LinkedHashMap<CacheKey, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Bitmap>): Boolean =
            size > MAX_ENTRIES
    }

    suspend fun render(members: List<ComponentKey>, sizePx: Int): Bitmap {
        val key = IconCacheKey.folder(members, sizePx, monochrome)
        synchronized(lock) { cache[key]?.let { return it } }
        val composed = compose(members, sizePx)
        synchronized(lock) { cache[key] = composed }
        return composed
    }

    /** Drop all cached previews (on package change / memory trim). */
    fun clear() = synchronized(lock) { cache.clear() }

    private suspend fun compose(members: List<ComponentKey>, sizePx: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF } // ~20% white
        val radius = sizePx * 0.18f
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, bg)

        val pad = (sizePx * 0.10f).toInt()
        val cell = (sizePx - pad * 3) / 2 // 2 cells + 3 paddings span the size
        members.take(4).forEachIndexed { index, key ->
            val bitmap = runCatching { iconLoader.bitmap(IconRef.System(key), cell) }.getOrNull()
                ?: return@forEachIndexed
            val col = index % 2
            val row = index / 2
            val left = (pad + col * (cell + pad)).toFloat()
            val top = (pad + row * (cell + pad)).toFloat()
            canvas.drawBitmap(bitmap, left, top, null)
        }
        return out
    }

    private companion object {
        const val MAX_ENTRIES = 64
    }
}
