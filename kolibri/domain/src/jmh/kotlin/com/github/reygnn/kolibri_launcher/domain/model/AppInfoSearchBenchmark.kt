package com.github.reygnn.kolibri_launcher.domain.model

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
 * JMH microbenchmark for [filterByName] — the display-name search predicate that
 * runs **per keystroke** in the drawer search and the Hidden / Onboarding /
 * SwipeActions / CustomNames settings screens. It is the hottest per-interaction
 * pure loop in `:domain`: cost scales with the full installed-app count on every
 * character typed.
 *
 * Two shapes are pinned separately because their cost profiles differ:
 * - [filterDisplayName]: the common case (`includeOriginalName = false`) — one
 *   fold of the query, then a `contains` against each precomputed `displayNameLower`.
 * - [filterWithOriginalName]: the Custom Names screen (`includeOriginalName = true`)
 *   — adds a per-app `originalName.lowercase()` on the custom-named subset whose
 *   displayName match already missed (the AUDIT-15 F2 / AUDIT-16 N2 short-circuit).
 *
 * The query `"app"` is chosen so both branches do real work: non-renamed apps
 * match on displayName (first branch, short-circuits); renamed apps miss on
 * displayName ("Renamed …") but hit `originalName` ("Original App …"), so the
 * second-branch fold actually runs for that subset.
 *
 * The `@State` class is `open` (JMH generates a runtime subclass). Each benchmark
 * returns the filtered list so the JIT cannot elide the filter.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
open class AppInfoSearchBenchmark {

    /** Realistic drawer sizes — a lean phone (~50) and a loaded one (~200). */
    @Param("50", "200")
    var size: Int = 0

    private lateinit var apps: List<AppInfo>
    private val query = "app"

    @Setup
    fun setUp() {
        // Even indices are "renamed" (displayName != originalName), so the
        // includeOriginalName second branch engages for them; odd indices keep
        // displayName == originalName and match on the first branch. Both carry
        // "App" in the original label so the query hits every originalName.
        apps = (0 until size).map { i ->
            val original = "Original App $i"
            val display = if (i % 2 == 0) "Renamed $i" else original
            AppInfo(
                originalName = original,
                displayName = display,
                packageName = "com.example.app$i",
                className = "com.example.app$i.MainActivity",
            )
        }
    }

    /** Common case: drawer + Hidden/Onboarding/SwipeActions screens. */
    @Benchmark
    fun filterDisplayName(): List<AppInfo> = apps.filterByName(query)

    /** Custom Names screen: also folds originalName for the renamed subset. */
    @Benchmark
    fun filterWithOriginalName(): List<AppInfo> =
        apps.filterByName(query, includeOriginalName = true)
}
