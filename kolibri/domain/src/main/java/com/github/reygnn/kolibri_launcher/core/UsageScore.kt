package com.github.reygnn.kolibri_launcher.core

import kotlin.math.exp

/**
 * Pure exponential-decay time-weighted usage score for a single app.
 *
 * Sums `e^(-λ·Δt)` over the app's distinct launch timestamps (λ =
 * [AppConstants.USAGE_DECAY_LAMBDA], Δt in seconds since each launch), so recent
 * and frequent launches score highest and old ones decay toward zero. Higher =
 * more recently / more often used.
 *
 * Pure Long/Double arithmetic plus [kotlin.math.exp] (returns NaN rather than
 * throwing) and the safe-coerce extensions — it cannot throw, so callers need no
 * try/catch around it (the surrounding DataStore read is the only failure mode).
 *
 * Extracted from `AppUsageRepositoryImpl` (`:data`) so the scoring math lives in
 * the pure `:domain` layer next to its inputs (the λ constant and the coerce
 * extensions) and can be JVM-unit-tested and JMH-benchmarked without an Android
 * runtime. The caller feeds already-validated timestamps (future / expired ones
 * filtered upstream); the future-guard here is a defensive second line, not the
 * primary filter.
 *
 * @param timestamps launch times in epoch millis (duplicates are de-duped)
 * @param currentTime reference "now" in epoch millis
 * @return score ≥ 0.0; 0.0 for an empty list
 */
fun timeWeightedUsageScore(timestamps: List<Long>, currentTime: Long): Double {
    if (timestamps.isEmpty()) return 0.0

    return timestamps.distinct().sumOf { launchTime ->
        val timeDifferenceMs = currentTime - launchTime

        // Clock-skew guard: ignore timestamps from the future (clock changed).
        if (timeDifferenceMs < 0) return@sumOf 0.0

        val timeDifferenceSec = (timeDifferenceMs / 1000.0).coerceAtLeastSafe(0.0)
        val exponent = -AppConstants.USAGE_DECAY_LAMBDA * timeDifferenceSec

        // Overflow guard for exp().
        when {
            exponent < -100.0 -> 0.0 // Too old — score is effectively 0.
            exponent > 100.0 -> 1.0  // Cannot happen with a negative lambda, but stay safe.
            else -> exp(exponent).coerceInSafe(0.0, 1.0)
        }
    }.coerceAtLeastSafe(0.0)
}
