package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.timeWeightedUsageScore
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
 * JMH microbenchmark for the **time-weighted usage sort** composition —
 * `AppUsageRepositoryImpl.sortAppsByTimeWeightedUsage`, which re-runs on every
 * drawer emission / usage tick while `SortOrder.TIME_WEIGHTED_USAGE` is active
 * (off-main, `@DefaultDispatcher`). It sits one level ABOVE
 * [timeWeightedUsageScore] (already pinned by `UsageScoreBenchmark`): this
 * benchmark measures the per-emission **allocation shape** of the sort wrapper,
 * not the exp() math.
 *
 * ## Why it exists
 *
 * The production sort ([current]) builds, per emission:
 *   - a filtered `List<Long>` per app (`?.filter { isValidTimestamp }`),
 *   - an intermediate `List<Pair<AppInfo, Double>>` (a `Pair` + a boxed `Double`
 *     per app),
 *   - a fresh `Comparator` (`compareByDescending … thenBy …`),
 *   - a final `.map { it.first }` list.
 *
 * [candidate] keeps the per-app validity filter **byte-for-byte** (see the
 * numeric note below — it is NOT redundant with the score's own overflow guard)
 * and produces an **identical ordering**, but scores into a primitive
 * `DoubleArray` (no `Pair`, no boxed `Double`) and sorts an index array, dropping
 * the `Pair` list and the final `.map`.
 *
 * ## Numeric note — why the validity filter must stay
 *
 * With `USAGE_DECAY_LAMBDA = 1e-6`/s and `MAX_TIMESTAMP_AGE_MS = 1 year`, a
 * timestamp at exactly the age cap scores `exp(-1e-6 · 31_536_000) = exp(-31.5)
 * ≈ 2e-14` — **nonzero**. `timeWeightedUsageScore`'s own guard only zeroes at
 * `exponent < -100` (≈ 3.17 years). So dropping `isValidTimestamp` would change
 * the score (and, at a tie, the order) for timestamps aged 1–3.17 years. The
 * candidate therefore keeps the filter; the only allocation removed is the
 * `Pair`/boxing/`map` scaffolding, which is output-neutral.
 *
 * `size` is the visible-app count the sort runs over; `launches` is the launch-
 * timestamp count per app (capped at `MAX_TIMESTAMPS_PER_APP = 150` in
 * production). The `@State` class is `open` (JMH generates a runtime subclass);
 * each benchmark returns the sorted list so the JIT cannot elide the work.
 *
 * ## Measured — 2026-08-15, candidate NOT adopted
 *
 * Heavy combo (`size=200`, `launches=100`), `-prof gc`, 5 iterations:
 *
 * | variant   | throughput        | gc.alloc.rate.norm |
 * |-----------|-------------------|--------------------|
 * | current   | ~0.001 ops/µs     | 1_487_944.65 B/op  |
 * | candidate | ~0.001 ops/µs     | 1_482_600.66 B/op  |
 *
 * The `Pair`/boxing/`map` scaffolding the candidate removes is **5_344 B/op ≈
 * 0.36 %** of the per-sort allocation; throughput is indistinguishable (both
 * round to 0.001 ops/µs with a ±100 % error bar). **~99.6 % of the allocation is
 * the per-app `.filter { isValidTimestamp }` lists + the `distinct()` set inside
 * `timeWeightedUsageScore` — which stays** (dropping the filter is a scoring-
 * window behaviour change, see the numeric note above, not a free win). The path
 * is also off-main (`@DefaultDispatcher`) and only active in
 * `SortOrder.TIME_WEIGHTED_USAGE`. So production `sortAppsByTimeWeightedUsage`
 * was left unchanged; this benchmark stays as the allocation baseline + the
 * record of why the micro-rewrite is not worth it.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
open class TimeWeightedSortBenchmark {

    /** Realistic drawer sizes — a lean phone (~50) and a loaded one (~200). */
    @Param("50", "200")
    var size: Int = 0

    /** Launch timestamps per app — a light (20) vs heavy (100) usage history. */
    @Param("20", "100")
    var launches: Int = 0

    private val now = 1_000_000_000_000L
    private lateinit var apps: List<AppInfo>
    private lateinit var usageSnapshot: Map<String, List<Long>>

    @Setup
    fun setUp() {
        apps = (0 until size).map { i ->
            AppInfo(
                originalName = "App $i",
                displayName = "App $i",
                packageName = "com.example.app$i",
                className = "com.example.app$i.MainActivity",
            )
        }
        // Distinct, in-the-past timestamps within the valid window (< 1 year), so
        // every entry clears isValidTimestamp and hits the exp() path — a full
        // score for every app, the realistic worst case for the sort.
        usageSnapshot = apps.associate { app ->
            app.packageName to (0 until launches).map { j -> now - (j + 1L) * 60_000L }
        }
    }

    /** Production composition, reproduced verbatim from `AppUsageRepositoryImpl`. */
    @Benchmark
    fun current(): List<AppInfo> = sortCurrent(apps, usageSnapshot, now)

    /** Output-identical, lower-allocation variant (primitive scores + index sort). */
    @Benchmark
    fun candidate(): List<AppInfo> = sortCandidate(apps, usageSnapshot, now)
}

/** Mirror of `AppUsageRepositoryImpl.isValidTimestamp` (private there). */
private fun isValidTimestamp(timestamp: Long, currentTime: Long): Boolean =
    timestamp > 0 &&
        timestamp <= currentTime &&
        (currentTime - timestamp) <= AppConstants.MAX_TIMESTAMP_AGE_MS

/**
 * Verbatim reproduction of `AppUsageRepositoryImpl.sortAppsByTimeWeightedUsage`
 * (the `Pair`-map → `sortedWith` → `map` composition).
 */
private fun sortCurrent(
    apps: List<AppInfo>,
    usageSnapshot: Map<String, List<Long>>,
    currentTime: Long,
): List<AppInfo> {
    if (apps.isEmpty()) return emptyList()

    return apps
        .map { appInfo ->
            val timestamps = usageSnapshot[appInfo.packageName]
                ?.filter { isValidTimestamp(it, currentTime) }
                ?: emptyList()
            appInfo to timeWeightedUsageScore(timestamps, currentTime)
        }
        .sortedWith(
            compareByDescending<Pair<AppInfo, Double>> { it.second }
                .thenBy { it.first.displayNameLower },
        )
        .map { it.first }
}

/**
 * Output-identical candidate: scores into a primitive `DoubleArray` (no `Pair`,
 * no boxed `Double`) and sorts an index array, so the `Pair` intermediate and the
 * final `.map { it.first }` are gone. The per-app validity filter is kept
 * verbatim (see the class KDoc's numeric note), so the ordering is byte-identical
 * to [sortCurrent].
 */
private fun sortCandidate(
    apps: List<AppInfo>,
    usageSnapshot: Map<String, List<Long>>,
    currentTime: Long,
): List<AppInfo> {
    val n = apps.size
    if (n == 0) return emptyList()

    val scores = DoubleArray(n) { i ->
        val timestamps = usageSnapshot[apps[i].packageName]
            ?.filter { isValidTimestamp(it, currentTime) }
            ?: emptyList()
        timeWeightedUsageScore(timestamps, currentTime)
    }

    val order = (0 until n).sortedWith(
        compareByDescending<Int> { scores[it] }
            .thenBy { apps[it].displayNameLower },
    )

    return order.map { apps[it] }
}
