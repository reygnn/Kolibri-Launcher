package com.github.reygnn.kolibri_launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pins for [timeWeightedUsageScore], the exponential-decay usage score
 * extracted from `AppUsageRepositoryImpl` (`:data`). Asserts the published
 * behaviour — a launch "now" contributes ~1.0, duplicates de-dupe, future and
 * far-past launches contribute nothing, and the score is never negative — rather
 * than values re-derived from the function itself.
 *
 * λ is [AppConstants.USAGE_DECAY_LAMBDA] = 1e-6 per second, so exponent =
 * -1e-6 · Δt(seconds). Δt = 0 → exp(0) = 1.0; the overflow guard zeroes any
 * launch older than 1e8 s (≈ 3.17 years, exponent < -100).
 */
class UsageScoreTest {

    private val now = 1_000_000_000_000L // fixed reference "now" in epoch millis

    @Test
    fun `empty list scores zero`() {
        assertEquals(0.0, timeWeightedUsageScore(emptyList(), now), 0.0)
    }

    @Test
    fun `a launch at now contributes ~1_0`() {
        assertEquals(1.0, timeWeightedUsageScore(listOf(now), now), 1e-9)
    }

    @Test
    fun `duplicate timestamps are de-duped`() {
        // [now, now] must score like a single launch, not double.
        assertEquals(1.0, timeWeightedUsageScore(listOf(now, now), now), 1e-9)
    }

    @Test
    fun `distinct recent launches sum`() {
        // Δt = 0 s (exp 1.0) + Δt = 1000 s (exp(-0.001) ≈ 0.999).
        val score = timeWeightedUsageScore(listOf(now, now - 1_000_000L), now)
        assertEquals(1.0 + Math.exp(-0.001), score, 1e-6)
    }

    @Test
    fun `future timestamps are ignored`() {
        // A launch 5 s in the future contributes 0.0 (clock-skew guard).
        assertEquals(0.0, timeWeightedUsageScore(listOf(now + 5_000L), now), 0.0)
        // Mixed: the future one drops out, the now one stays at ~1.0.
        assertEquals(1.0, timeWeightedUsageScore(listOf(now, now + 5_000L), now), 1e-9)
    }

    @Test
    fun `far-past launches decay to zero via the overflow guard`() {
        // Δt ≈ 2e9 s → exponent -2000 < -100 → 0.0.
        assertEquals(0.0, timeWeightedUsageScore(listOf(now - 2_000_000_000_000L), now), 0.0)
    }

    @Test
    fun `score is never negative`() {
        // All-future input collapses to 0.0, never below.
        val score = timeWeightedUsageScore(listOf(now + 1L, now + 2L, now + 3L), now)
        assertTrue(score >= 0.0)
    }
}
