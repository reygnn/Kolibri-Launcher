package com.github.reygnn.launcher.core

import com.google.common.truth.Truth.assertThat
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
        assertThat(timeWeightedUsageScore(emptyList(), now)).isWithin(0.0).of(0.0)
    }

    @Test
    fun `a launch at now contributes ~1_0`() {
        assertThat(timeWeightedUsageScore(listOf(now), now)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `duplicate timestamps are de-duped`() {
        // [now, now] must score like a single launch, not double.
        assertThat(timeWeightedUsageScore(listOf(now, now), now)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `distinct recent launches sum`() {
        // Δt = 0 s (exp 1.0) + Δt = 1000 s (exp(-0.001) ≈ 0.999).
        val score = timeWeightedUsageScore(listOf(now, now - 1_000_000L), now)
        assertThat(score).isWithin(1e-6).of(1.0 + Math.exp(-0.001))
    }

    @Test
    fun `future timestamps are ignored`() {
        // A launch 5 s in the future contributes 0.0 (clock-skew guard).
        assertThat(timeWeightedUsageScore(listOf(now + 5_000L), now)).isWithin(0.0).of(0.0)
        // Mixed: the future one drops out, the now one stays at ~1.0.
        assertThat(timeWeightedUsageScore(listOf(now, now + 5_000L), now)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `far-past launches decay to zero via the overflow guard`() {
        // Δt ≈ 2e9 s → exponent -2000 < -100 → 0.0.
        assertThat(timeWeightedUsageScore(listOf(now - 2_000_000_000_000L), now)).isWithin(0.0).of(0.0)
    }

    @Test
    fun `score is never negative`() {
        // All-future input collapses to 0.0, never below.
        val score = timeWeightedUsageScore(listOf(now + 1L, now + 2L, now + 3L), now)
        assertThat(score >= 0.0).isTrue()
    }

    @Test
    fun `score is always finite — never NaN or infinite`() {
        // The coerce guards must neutralize any NaN/Infinity out of exp(). A mix of
        // now, a future skew, a far-past overflow launch and a recent launch drives
        // every branch of the decay math at once; the result must stay finite.
        val score = timeWeightedUsageScore(
            listOf(now, now + 5_000L, now - 2_000_000_000_000L, now - 1_000_000L),
            now,
        )
        assertThat(score.isFinite()).isTrue()
    }
}
