package com.github.reygnn.nyx_launcher.data.icon

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM truth-table test for the pure disk-prune policy (§5, A1-07). No Android,
 * no clock, no files — just the selection logic.
 */
class DiskCachePruneTest {

    private val now = 1_000_000L
    private val maxBytes = 100L
    private val maxAge = 1_000L

    private fun entry(ref: String, size: Long, ageMs: Long) =
        DiskCachePrune.Entry(ref, size, now - ageMs)

    @Test
    fun within_both_bounds_deletes_nothing() {
        val entries = listOf(entry("a", 40, 10), entry("b", 40, 20))

        val doomed = DiskCachePrune.select(entries, now, maxBytes, maxAge)

        assertThat(doomed).isEmpty()
    }

    @Test
    fun files_older_than_max_age_are_dropped_regardless_of_budget() {
        val entries = listOf(
            entry("fresh", 10, 100),
            entry("stale", 10, maxAge + 1), // past the age bound
        )

        val doomed = DiskCachePrune.select(entries, now, maxBytes, maxAge)

        assertThat(doomed).containsExactly("stale")
    }

    @Test
    fun over_byte_budget_evicts_oldest_by_mtime_first_until_under_cap() {
        // Total 150 > 100; must drop 50 bytes worth, oldest (largest age) first.
        val entries = listOf(
            entry("newest", 50, 10),
            entry("middle", 50, 20),
            entry("oldest", 50, 30),
        )

        val doomed = DiskCachePrune.select(entries, now, maxBytes, maxAge)

        assertThat(doomed).containsExactly("oldest") // 100 remains, at cap
    }

    @Test
    fun age_and_byte_bounds_combine() {
        val entries = listOf(
            entry("stale", 10, maxAge + 5), // dropped by age
            entry("big_new", 80, 5),
            entry("big_old", 80, 50),        // dropped by byte-LRU (older survivor)
        )

        val doomed = DiskCachePrune.select(entries, now, maxBytes, maxAge)

        // stale gone by age; survivors 160 > 100 -> evict oldest survivor (big_old).
        assertThat(doomed).containsExactly("stale", "big_old")
    }

    @Test
    fun empty_input_is_empty_output() {
        assertThat(DiskCachePrune.select(emptyList<DiskCachePrune.Entry<String>>(), now, maxBytes, maxAge))
            .isEmpty()
    }
}
