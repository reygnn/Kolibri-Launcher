package com.github.reygnn.nyx_launcher.data.icon

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.reygnn.nyx_launcher.di.IoDispatcher
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * Hand-rolled two-tier icon cache (ICON_LOADER_SPEC §4): memory (byte-LRU) over a
 * disk cache of composited WEBPs, in front of an [IconSource]. State is guarded by
 * a plain monitor ([lock]) that never suspends, so [evict]/[trim] stay synchronous
 * while [bitmap] awaits its work OUTSIDE the lock.
 *
 * - ICL-INV-1: source resolve, disk IO and decode run on [dispatcher], never Main.
 * - ICL-INV-4: request coalescing — one [Deferred] per key.
 * - ICL-INV-3: [evict] clears memory (package index) AND disk (glob).
 * - ICL-INV-8: cached bitmaps are shared; never recycled.
 */
class IconLoaderImpl @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val source: IconSource,
    preferences: PreferencesRepository,
) : IconLoader {

    private val diskDir = File(context.cacheDir, "icons")
    private val budget = LruBudget(computeBudgetBytes(context))

    private val lock = Any()
    private val memory = HashMap<CacheKey, Bitmap>()
    private val packageIndex = HashMap<String, MutableSet<CacheKey>>()
    private val inFlight = HashMap<CacheKey, Deferred<Bitmap>>()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var monochrome = false

    init {
        preferences.monochromeIcons().onEach { monochrome = it }.launchIn(scope)
    }

    override suspend fun bitmap(ref: IconRef, sizePx: Int): Bitmap {
        val mono = monochrome
        val variant = if (mono) IconVariant.THEMED else IconVariant.ADAPTIVE
        val key = IconCacheKey.of(ref, sizePx, variant)

        synchronized(lock) {
            memory[key]?.let { cached ->
                budget.touch(key, cached.allocationByteCount)
                return cached
            }
        }

        val deferred: Deferred<Bitmap> = synchronized(lock) {
            memory[key]?.let { return it }
            inFlight[key] ?: scope.async(dispatcher) {
                loadFromDiskOrResolve(key, ref, sizePx, mono)
            }.also { inFlight[key] = it }
        }

        val bitmap = try {
            deferred.await()
        } finally {
            synchronized(lock) { inFlight.remove(key) }
        }

        synchronized(lock) {
            memory[key] = bitmap
            packageIndex.getOrPut(pkgOf(ref)) { mutableSetOf() }.add(key)
            budget.touch(key, bitmap.allocationByteCount).forEach { evicted ->
                memory.remove(evicted)
                removeFromIndex(evicted)
            }
        }
        return bitmap
    }

    override fun evict(pkg: String) {
        synchronized(lock) {
            val keys = packageIndex.remove(pkg).orEmpty()
            budget.forget(keys)
            keys.forEach { memory.remove(it) }
        }
        val prefix = IconCacheKey.packagePrefix(pkg)
        scope.launch(dispatcher) {
            diskDir.listFiles { f -> f.name.startsWith("$prefix-") }?.forEach { it.delete() }
        }
    }

    override fun trim(level: Int) {
        synchronized(lock) {
            budget.trim(level).forEach { evicted ->
                memory.remove(evicted)
                removeFromIndex(evicted)
            }
        }
    }

    private suspend fun loadFromDiskOrResolve(key: CacheKey, ref: IconRef, sizePx: Int, monochrome: Boolean): Bitmap {
        val file = File(diskDir, IconCacheKey.fileName(key))
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        val bitmap = source.load(ref, sizePx, monochrome)
        runCatching { writeDisk(file, bitmap) } // best-effort
        return bitmap
    }

    private fun writeDisk(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        }
    }

    private fun pkgOf(ref: IconRef): String = when (ref) {
        is IconRef.System -> ref.key.packageName
        is IconRef.Pack -> ref.key.packageName
    }

    private fun removeFromIndex(key: CacheKey) {
        val iterator = packageIndex.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.remove(key)
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    private companion object {
        fun computeBudgetBytes(context: Context): Long {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val perProcessBytes = am.memoryClass.toLong() * 1024 * 1024
            return (perProcessBytes / 8).coerceIn(4L * 1024 * 1024, 64L * 1024 * 1024)
        }
    }
}
