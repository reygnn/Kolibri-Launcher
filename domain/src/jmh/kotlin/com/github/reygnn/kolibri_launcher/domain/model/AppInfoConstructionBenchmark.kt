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
 * JMH microbenchmark for [AppInfo] **construction** — the cost side of the
 * AUDIT-14 §208/§212 precompute trade.
 *
 * [AppInfo.displayNameLower] and [AppInfo.componentName] are body `val`s, so
 * they are computed once per instance rather than per read. That is a clear win
 * for reads (the sort comparator, the hidden-filter membership test, DiffUtil
 * identity) — but it is paid on **every** construction, and constructions are far
 * from rare: each reactive re-derivation of the app list rebuilds every element
 * at least twice, because `copy()` runs through the constructor.
 *
 * The two production `copy` shapes are pinned separately because they differ in
 * how much of the recomputation is actually *useful*:
 * - [copyDisplayName]: the `applyCustomNames` shape — `copy(displayName = …)`.
 *   Both body vals legitimately need to be redone (`displayNameLower` depends on
 *   the changed field; `componentName` does not, and is pure waste here).
 * - [copyIsFavorite]: the `GetFavoriteAppsUseCase.processApps` shape —
 *   `copy(isFavorite = …)`. Neither body val's inputs changed, so the
 *   `lowercase()` **and** the componentName concat re-run for nothing. This is
 *   the shape where the precompute is most clearly working against itself.
 *
 * [construct] is the from-scratch cost (what the enumeration in `:data` pays per
 * app), and [constructBare] is a reference shape carrying the same five fields
 * with **no** body vals — the delta between the two is the per-instance price of
 * the two precomputes. `BareAppInfo` is a shape reference for that one
 * measurement, NOT a copy of production logic, so it carries no drift risk
 * against [AppInfo]'s behaviour.
 *
 * `className` is a fully-qualified name in every benchmark, so `componentName`
 * takes the common non-`startsWith(".")` branch; the short-form branch is the
 * rare Android spelling and only adds one more concat.
 *
 * The `@State` class is `open` (JMH generates a runtime subclass). Every
 * benchmark returns its list so the JIT cannot elide the construction.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
open class AppInfoConstructionBenchmark {

    /** Realistic drawer sizes — a lean phone (~50) and a loaded one (~200). */
    @Param("50", "200")
    var size: Int = 0

    /** Pre-built input strings, so string formatting stays out of the measurement. */
    private lateinit var originalNames: Array<String>
    private lateinit var packageNames: Array<String>
    private lateinit var classNames: Array<String>

    /** Pre-built instances — the input for the two `copy` shapes. */
    private lateinit var apps: List<AppInfo>

    @Setup
    fun setUp() {
        originalNames = Array(size) { i -> "Original App $i" }
        packageNames = Array(size) { i -> "com.example.app$i" }
        classNames = Array(size) { i -> "com.example.app$i.MainActivity" }

        apps = (0 until size).map { i ->
            AppInfo(
                originalName = originalNames[i],
                displayName = originalNames[i],
                packageName = packageNames[i],
                className = classNames[i],
            )
        }
    }

    /** From-scratch construction: what the `:data` enumeration pays per app. */
    @Benchmark
    fun construct(): List<AppInfo> = (0 until size).map { i ->
        AppInfo(
            originalName = originalNames[i],
            displayName = originalNames[i],
            packageName = packageNames[i],
            className = classNames[i],
        )
    }

    /**
     * Same five fields, no body vals. The delta against [construct] is the
     * per-instance cost of `displayNameLower` + `componentName`.
     */
    @Benchmark
    fun constructBare(): List<BareAppInfo> = (0 until size).map { i ->
        BareAppInfo(
            originalName = originalNames[i],
            displayName = originalNames[i],
            packageName = packageNames[i],
            className = classNames[i],
        )
    }

    /**
     * `applyCustomNames` shape. The map lookup is deliberately omitted (it is
     * already pinned by `ApplyCustomNamesBenchmark`) so this isolates the
     * constructor work; `lowercase()` scans the same content either way.
     */
    @Benchmark
    fun copyDisplayName(): List<AppInfo> =
        apps.map { it.copy(displayName = it.originalName) }

    /**
     * `GetFavoriteAppsUseCase.processApps` shape: nothing either body val
     * depends on has changed, yet both are recomputed.
     */
    @Benchmark
    fun copyIsFavorite(): List<AppInfo> =
        apps.map { it.copy(isFavorite = true) }
}

/**
 * Reference shape for [AppInfoConstructionBenchmark.constructBare]: [AppInfo]'s
 * five constructor fields with no precomputed body vals. Exists only to price
 * the precomputes — it is not a stand-in for [AppInfo] and has no behaviour to
 * keep in sync.
 */
data class BareAppInfo(
    val originalName: String,
    val displayName: String,
    val packageName: String,
    val className: String,
    val isFavorite: Boolean = false,
)
