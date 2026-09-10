package com.github.reygnn.nyx_launcher.data.icon

/**
 * Pure, byte-bounded LRU policy — the heart of the memory cache and Android-free
 * so it's a JVM truth-table test (ICL-INV-6). Bounded by BYTES, not item count
 * (ICL-INV-2): a 512px icon weighs 16× a 128px one. Weights are supplied by the
 * caller (`Bitmap.allocationByteCount`), keeping this class framework-free.
 *
 * Not thread-safe on its own — the [IconLoader] impl guards it with its mutex.
 */
class LruBudget(private val maxBytes: Long) {

    // access-order LinkedHashMap: get/put move the entry to MRU; iteration is LRU-first.
    private val entries = object : LinkedHashMap<CacheKey, Int>(16, 0.75f, true) {}
    private var bytes = 0L

    val totalBytes: Long get() = bytes
    fun keys(): Set<CacheKey> = entries.keys.toSet()

    /** Record an access/insert of [key] weighing [weightBytes]; returns evicted keys. */
    fun touch(key: CacheKey, weightBytes: Int): List<CacheKey> {
        val previous = entries.put(key, weightBytes)
        bytes += weightBytes - (previous ?: 0)
        return evictDownTo(maxBytes)
    }

    /** onTrimMemory: higher level ⇒ more aggressive. Disk is untouched (ICL-INV-7). */
    fun trim(level: Int): List<CacheKey> = when {
        level >= TRIM_UI_HIDDEN -> evictAll()          // app not visible ⇒ drop memory
        level >= TRIM_RUNNING_LOW -> evictDownTo(maxBytes / 2)
        else -> emptyList()
    }

    /** Remove [keys] without eviction accounting side effects (used by evict(pkg)). */
    fun forget(keys: Collection<CacheKey>) {
        for (k in keys) entries.remove(k)?.let { bytes -= it }
    }

    private fun evictDownTo(limit: Long): List<CacheKey> {
        if (bytes <= limit) return emptyList()
        val evicted = mutableListOf<CacheKey>()
        val it = entries.entries.iterator()
        while (it.hasNext() && bytes > limit && entries.size > 1) {
            val e = it.next()
            it.remove()
            bytes -= e.value
            evicted += e.key
        }
        return evicted
    }

    private fun evictAll(): List<CacheKey> {
        val all = entries.keys.toList()
        entries.clear()
        bytes = 0
        return all
    }

    private companion object {
        // Mirror of ComponentCallbacks2 levels (kept as ints so this stays Android-free).
        const val TRIM_RUNNING_LOW = 10
        const val TRIM_UI_HIDDEN = 20
    }
}
