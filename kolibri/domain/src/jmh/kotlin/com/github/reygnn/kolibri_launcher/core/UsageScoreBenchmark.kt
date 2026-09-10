package com.github.reygnn.kolibri_launcher.core

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/**
 * JMH microbenchmark for [timeWeightedUsageScore] — the exponential-decay usage
 * score, the most arithmetically expensive per-element work in the codebase after
 * [ColorMath.calculateLuminance]. It runs once per visible app inside
 * `AppUsageRepositoryImpl.sortAppsByTimeWeightedUsage`, which re-runs on every
 * drawer emission / usage tick while `SortOrder.TIME_WEIGHTED_USAGE` is active —
 * so the real per-refresh cost is roughly this × the installed-app count.
 *
 * `count` is the number of launch timestamps for one app: 20 is a typical active
 * app, 100 a heavy one (the production cap is `MAX_TIMESTAMPS_PER_APP`). The pass
 * does a `distinct()` set build plus a transcendental `exp()` per timestamp.
 *
 * The `@State` class is `open` (JMH generates a runtime subclass); the benchmark
 * returns the score so the JIT cannot elide the math.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
open class UsageScoreBenchmark {

    /** Launch-timestamp count for one app — typical (20) vs heavy (100). */
    @Param("20", "100")
    var count: Int = 0

    private val now = 1_000_000_000_000L
    private lateinit var timestamps: List<Long>

    @Setup
    fun setUp() {
        // Distinct, in-the-past timestamps spread over the recent window so every
        // element clears the future-guard and hits the exp() path (no early-out).
        // Deterministic stride — no RNG — keeps the input reproducible.
        timestamps = (0 until count).map { i -> now - (i + 1L) * 60_000L }
    }

    @Benchmark
    fun score(): Double = timeWeightedUsageScore(timestamps, now)
}
