package com.github.reygnn.kolibri_launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary pins for [isValidUsageTimestamp], the plausibility gate both
 * [com.github.reygnn.kolibri_launcher.data.AppUsageRepositoryImpl] and
 * [com.github.reygnn.kolibri_launcher.data.UsageExportRepositoryImpl] apply to raw
 * stored launch times. It had no dedicated test; the repo tests only exercised a
 * timestamp "too old by a whole day", which never pins the three off-by-one edges
 * the rule turns on. A flipped `<`/`<=` or `>`/`>=` here silently changes whether a
 * launch counts toward usage scores and exports — these lock all three edges.
 */
class UsageTimestampTest {

    // Fixed reference "now" (well past MAX age, so `now - MAX` stays positive).
    private val now = 1_700_000_000_000L

    @Test
    fun `a timestamp exactly now is valid`() {
        // `timestamp <= currentTime` is inclusive: a launch recorded this instant counts.
        assertTrue(isValidUsageTimestamp(now, now))
    }

    @Test
    fun `one millisecond in the future is rejected`() {
        assertFalse(isValidUsageTimestamp(now + 1, now))
    }

    @Test
    fun `zero is rejected — must be strictly positive`() {
        assertFalse(isValidUsageTimestamp(0L, now))
    }

    @Test
    fun `a negative timestamp is rejected`() {
        assertFalse(isValidUsageTimestamp(-1L, now))
    }

    @Test
    fun `the oldest allowed timestamp — exactly MAX age — is valid`() {
        // currentTime - timestamp == MAX_TIMESTAMP_AGE_MS, and the age guard is `<=`.
        val oldest = now - AppConstants.MAX_TIMESTAMP_AGE_MS
        assertTrue(isValidUsageTimestamp(oldest, now))
    }

    @Test
    fun `one millisecond older than MAX age is rejected`() {
        val tooOld = now - AppConstants.MAX_TIMESTAMP_AGE_MS - 1
        assertFalse(isValidUsageTimestamp(tooOld, now))
    }
}
