package com.github.reygnn.nyx_launcher.data.icon

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.IconStyle
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
import java.util.concurrent.atomic.AtomicBoolean
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
 * - §5: the disk cache is byte- and age-bounded — a lazy mtime-LRU prune
 *   ([DiskCachePrune]) runs on the Io dispatcher at init and after writes, so it
 *   can't grow unbounded across sizes/variants/stale content-hashes (A1-07).
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
    // Reverse of [packageIndex] so an LRU eviction removes from exactly one set
    // instead of scanning the whole index (A1-15). An evicted key may belong to a
    // different package than the insertion that triggered it, so its package must
    // be looked up, not derived from the inserting ref.
    private val keyToPackage = HashMap<CacheKey, String>()
    private val inFlight = HashMap<CacheKey, Deferred<Bitmap>>()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // At most one disk prune runs at a time; writes just re-arm it (§5, A1-07).
    private val pruneScheduled = AtomicBoolean(false)

    @Volatile
    private var style = IconStyle.COLOR

    init {
        preferences.iconStyle().onEach { style = it }.launchIn(scope)
        schedulePrune() // cold-start sweep of files accumulated across runs
    }

    override suspend fun bitmap(ref: IconRef, sizePx: Int): Bitmap {
        val currentStyle = style
        val variant = when (currentStyle) {
            IconStyle.COLOR -> IconVariant.ADAPTIVE
            IconStyle.MONOCHROME -> IconVariant.THEMED
            IconStyle.GRAYSCALE -> IconVariant.GRAYSCALE
        }
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
                loadFromDiskOrResolve(key, ref, sizePx, currentStyle)
            }.also { inFlight[key] = it }
        }

        val bitmap = try {
            deferred.await()
        } finally {
            synchronized(lock) { inFlight.remove(key) }
        }

        synchronized(lock) {
            memory[key] = bitmap
            val pkg = pkgOf(ref)
            packageIndex.getOrPut(pkg) { mutableSetOf() }.add(key)
            keyToPackage[key] = pkg
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
            keys.forEach { memory.remove(it); keyToPackage.remove(it) }
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

    private suspend fun loadFromDiskOrResolve(key: CacheKey, ref: IconRef, sizePx: Int, style: IconStyle): Bitmap {
        val file = File(diskDir, IconCacheKey.fileName(key))
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let {
                // Touch mtime so it tracks access, not creation — keeps the
                // prune a true LRU (§5). Best-effort.
                file.setLastModified(System.currentTimeMillis())
                return it
            }
        }
        val bitmap = source.load(ref, sizePx, style)
        runCatching { writeDisk(file, bitmap) }.onSuccess { schedulePrune() } // best-effort
        return bitmap
    }

    /**
     * Arm a single lazy disk prune on the Io dispatcher (§5, A1-07). Throttled via
     * [pruneScheduled] so a burst of writes coalesces into one sweep.
     */
    private fun schedulePrune() {
        if (pruneScheduled.compareAndSet(false, true)) {
            scope.launch(dispatcher) {
                try {
                    pruneDisk()
                } finally {
                    pruneScheduled.set(false)
                }
            }
        }
    }

    private fun pruneDisk() {
        val files = diskDir.listFiles()?.filter { it.isFile } ?: return
        val entries = files.map { DiskCachePrune.Entry(it, it.length(), it.lastModified()) }
        DiskCachePrune.select(entries, System.currentTimeMillis(), DISK_MAX_BYTES, DISK_MAX_AGE_MS)
            .forEach { it.delete() }
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
        val pkg = keyToPackage.remove(key) ?: return
        val set = packageIndex[pkg] ?: return
        set.remove(key)
        if (set.isEmpty()) packageIndex.remove(pkg)
    }

    private companion object {
        // Disk cache bounds (§5). Composited WEBPs are small; 32 MiB holds a large
        // multi-size/variant working set, and 30 days drops icons of long-gone apps.
        const val DISK_MAX_BYTES = 32L * 1024 * 1024
        const val DISK_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        fun computeBudgetBytes(context: Context): Long {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val perProcessBytes = am.memoryClass.toLong() * 1024 * 1024
            return (perProcessBytes / 8).coerceIn(4L * 1024 * 1024, 64L * 1024 * 1024)
        }
    }
}
