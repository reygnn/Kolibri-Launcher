package com.github.reygnn.nyx_launcher.data.icon

/**
 * Pure disk-cache prune policy (ICON_LOADER_SPEC §5, A1-07): given each cached
 * file's byte size and last-modified time, decide which to delete so the disk
 * cache stays byte- and age-bounded. Android-free (no `File`, no clock) so it's a
 * JVM truth-table test, mirroring [LruBudget]; [IconLoaderImpl] maps File⇄[Entry]
 * and does the IO on the Io dispatcher.
 *
 * Order: drop anything older than [maxAgeMillis] first (regardless of budget),
 * then evict the remaining oldest-by-mtime (LRU) until the total is ≤ [maxBytes].
 * mtime is treated as the access time — the impl touches it on a disk hit — so
 * "oldest mtime" means "least-recently used".
 */
object DiskCachePrune {

    data class Entry<T>(val ref: T, val sizeBytes: Long, val lastModifiedMillis: Long)

    /** Returns the refs to delete; empty when the cache is within both bounds. */
    fun <T> select(
        entries: List<Entry<T>>,
        nowMillis: Long,
        maxBytes: Long,
        maxAgeMillis: Long,
    ): List<T> {
        val doomed = mutableListOf<T>()
        val survivors = mutableListOf<Entry<T>>()
        var totalBytes = 0L
        for (e in entries) {
            if (nowMillis - e.lastModifiedMillis > maxAgeMillis) {
                doomed += e.ref
            } else {
                survivors += e
                totalBytes += e.sizeBytes
            }
        }
        if (totalBytes > maxBytes) {
            survivors.sortBy { it.lastModifiedMillis } // LRU (oldest) first
            val it = survivors.iterator()
            while (totalBytes > maxBytes && it.hasNext()) {
                val e = it.next()
                doomed += e.ref
                totalBytes -= e.sizeBytes
            }
        }
        return doomed
    }
}
