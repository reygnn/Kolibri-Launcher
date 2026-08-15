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
 * JMH microbenchmark for the per-package String→Long parse that
 * `AppUsageRepositoryImpl.parseUsageSnapshot` performs — the work the
 * usage-snapshot refactor (`usageSnapshotFlow`) moved OUT of the per-sort path.
 *
 * Before the refactor, the time-weighted drawer sort re-read the store AND
 * re-parsed every package's timestamp strings to `Long` on EVERY re-sort (per app
 * launch in TIME_WEIGHTED_USAGE mode). Now the parse runs ONCE per real usage
 * change, in the flow, and the sort consumes the already-parsed snapshot. This
 * benchmark quantifies exactly that parse cost — i.e. what each avoided per-sort
 * re-parse now saves.
 *
 * It mirrors the production parse (`Set<String>.mapNotNull { toLongOrNull() }` per
 * package) over `packages` packages × [TIMESTAMPS_PER_APP] epochs; a
 * self-contained replica of the trivial parse op, in the same style as the other
 * pure-function benchmarks in this source set. `@State` is `open` because JMH
 * generates a runtime subclass; the `@Benchmark` returns its result so the JIT
 * cannot dead-code-eliminate the parse.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
open class UsageSnapshotParseBenchmark {

    /** Number of packages with usage data — a lean device (~20) and a loaded one (~100). */
    @Param("20", "100")
    var packages: Int = 0

    private lateinit var raw: Map<String, Set<String>>

    @Setup
    fun setUp() {
        // Fixed base epoch (no wall-clock dependency): the parse cost does not
        // depend on the actual values, only on count and digit length.
        val base = 1_700_000_000_000L
        raw = (0 until packages).associate { p ->
            "com.example.app$p" to (0 until TIMESTAMPS_PER_APP)
                .map { (base - it * 60_000L).toString() }
                .toSet()
        }
    }

    @Benchmark
    fun parse(): Map<String, List<Long>> {
        val result = HashMap<String, List<Long>>(raw.size)
        for ((pkg, timestamps) in raw) {
            val parsed = timestamps.mapNotNull { it.toLongOrNull() }
            if (parsed.isNotEmpty()) result[pkg] = parsed
        }
        return result
    }

    private companion object {
        /** Typical timestamps per app; well under `MAX_TIMESTAMPS_PER_APP` (150). */
        const val TIMESTAMPS_PER_APP = 30
    }
}
