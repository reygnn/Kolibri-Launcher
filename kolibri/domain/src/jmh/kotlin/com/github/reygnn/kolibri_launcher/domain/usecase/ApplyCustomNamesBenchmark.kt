package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
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
 * JMH microbenchmark for [applyCustomNames] — the single name-application point
 * (REACTIVE_APPLIST_SPEC RAL-1) that runs on every reactive re-derivation of the
 * app list (rename, install/uninstall, custom-name change). Since the RAL-4
 * map-only flip it is a pure `map { copy(displayName = …) }` with NO terminal
 * sort (display order is now each consumer's own concern), so this pins the cost
 * of that name-resolution map at realistic list sizes. The `namedPercent` param
 * covers BOTH the common no-custom-names fast path (0% → the input list is
 * returned unchanged, zero copies) and the mixed 50% case, so the empty-map win
 * of the map-only optimization is no longer hidden by a single 50%-named arm.
 *
 * *Historical note:* this benchmark once carried a second `mapOnly` arm to isolate
 * the terminal sort's cost — the "dead sort" the RAL-4 flip removed. With the sort
 * gone, `applyCustomNames` IS the map, so the arm became redundant and was dropped.
 * The measured ~9.7 µs @200 dead-sort delta lives in git + `APPLIST_SORT_SPLIT_SPEC.md`.
 *
 * Reproducible-baseline harness, not a CI gate: `./gradlew :domain:jmh` writes
 * `build/reports/jmh/results.json`. JMH numbers are host-dependent, so a
 * committed baseline is diffed by hand rather than auto-failing a build.
 *
 * The `@State` class is `open` on purpose: JMH generates a runtime subclass, and
 * a `final` Kotlin class would fail that at run time. The `@Benchmark` returns
 * its result so the JIT cannot dead-code-eliminate the map.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
open class ApplyCustomNamesBenchmark {

    /** Realistic drawer sizes — a lean phone (~50) and a loaded one (~200). */
    @Param("50", "200")
    var size: Int = 0

    /**
     * Percentage of packages that carry a custom name. `0` is the common real
     * case (a user with NO custom names): the map-only fast path returns the input
     * list unchanged, zero copies — the case the earlier single-50%-arm benchmark
     * hid. `50` keeps the mixed-branch (only the named half copied) measurement.
     */
    @Param("0", "50")
    var namedPercent: Int = 0

    private lateinit var apps: List<AppInfo>
    private lateinit var customNames: Map<String, String>

    @Setup
    fun setUp() {
        apps = (0 until size).map { i ->
            val original = "App ${size - i}"
            AppInfo(
                originalName = original,
                displayName = original,
                packageName = "com.example.app$i",
                className = "com.example.app$i.MainActivity",
            )
        }
        // Build the custom-name map to the requested coverage: 0% → empty (the
        // fast path returns the input unchanged), 50% → every other package
        // renamed (exercises both branches).
        customNames = if (namedPercent == 0) {
            emptyMap()
        } else {
            (0 until size step 2).associate { i ->
                "com.example.app$i" to "Custom ${size - i}"
            }
        }
    }

    @Benchmark
    fun applyCustomNames(): List<AppInfo> = applyCustomNames(apps, customNames)
}
