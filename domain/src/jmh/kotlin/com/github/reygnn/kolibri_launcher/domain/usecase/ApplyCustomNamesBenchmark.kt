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
 * of that name-resolution map at realistic list sizes.
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
}
