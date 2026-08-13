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
 * app list (rename, install/uninstall, custom-name change). It maps every app to
 * `copy(displayName = …)` and then `sortedBy { displayNameLower }`, so this pins
 * the cost of the map + the terminal sort at realistic list sizes.
 *
 * Two arms:
 * - [applyCustomNames] — the full production function (map + terminal sort).
 * - [mapOnly] — the map alone, byte-identical to the production `map { … }`
 *   step but WITHOUT the `.sortedBy`. The difference between the two arms is the
 *   cost of that terminal sort — and for the three self-sorting reactive
 *   consumers (drawer / favorites / recents) that sort is DEAD WORK (RAL-1a):
 *   their own downstream sort discards it. Splitting `applyCustomNames` into a
 *   sorted/unsorted pair to skip it has been deferred/closed three times
 *   (AUDIT-14 F1 §5.3, AUDIT-15 F3) on the argument that the dead sort is "µs,
 *   off-Main, in the noise". [mapOnly] turns that argument from an estimate into
 *   a measured delta; it does NOT reopen the decision (a split fragments the
 *   RAL-1 invariant — see the `applyCustomNames` KDoc), it just prices what the
 *   split would save so the closure rests on a number.
 *
 * `mapOnly` replicates the production `map` body inline rather than calling a
 * shared unsorted helper, because no such helper exists (splitting the function
 * is exactly what RAL-1a rejected). It is therefore a benchmark-local mirror of
 * one line, not a second copy of the name-resolution logic; if the `map` body in
 * [applyCustomNames] ever changes, this arm must be updated in lockstep or the
 * delta stops isolating the sort.
 *
 * Reproducible-baseline harness, not a CI gate: `./gradlew :domain:jmh` writes
 * `build/reports/jmh/results.json`. JMH numbers are host-dependent, so a
 * committed baseline is diffed by hand rather than auto-failing a build.
 *
 * The `@State` class is `open` on purpose: JMH generates a runtime subclass, and
 * a `final` Kotlin class would fail that at run time. Each `@Benchmark` returns
 * its result so the JIT cannot dead-code-eliminate the map/sort.
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

    private lateinit var apps: List<AppInfo>
    private lateinit var customNames: Map<String, String>

    @Setup
    fun setUp() {
        // Reverse-ordered original labels so the terminal sort has real work to
        // do (a pre-sorted input would flatter the sort's timsort fast path).
        apps = (0 until size).map { i ->
            val original = "App ${size - i}"
            AppInfo(
                originalName = original,
                displayName = original,
                packageName = "com.example.app$i",
                className = "com.example.app$i.MainActivity",
            )
        }
        // Half the packages carry a custom name (even indices): exercises both
        // branches of `customNames[packageName] ?: originalName` and forces the
        // custom-named entries to a different sort position than their original.
        customNames = (0 until size step 2).associate { i ->
            "com.example.app$i" to "Custom ${size - i}"
        }
    }

    @Benchmark
    fun applyCustomNames(): List<AppInfo> = applyCustomNames(apps, customNames)

    /**
     * The production `map` step without the terminal `.sortedBy`. Mirror of the
     * one line in [applyCustomNames]; the [applyCustomNames] − [mapOnly] delta is
     * the dead-sort cost (RAL-1a). Keep this body identical to the production map.
     */
    @Benchmark
    fun mapOnly(): List<AppInfo> =
        apps.map { it.copy(displayName = customNames[it.packageName] ?: it.originalName) }
}
