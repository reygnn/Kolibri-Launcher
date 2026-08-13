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
 * JMH microbenchmark for `List<AppInfo>` **equality** — the work
 * `Flow.distinctUntilChanged()` does on every emission.
 *
 * Both reactive app-list pipelines terminate in `.distinctUntilChanged()`
 * (`GetDrawerAppsUseCase.drawerApps`, `GetFavoriteAppsUseCase.favoriteApps`), and
 * it fires far more often than the list actually changes: the shared
 * `settingsDataStore` re-emits its flows on **every** write — including the usage
 * write on every single app launch (AUDIT-14 F1). So the dominant case is a
 * comparison that traverses the whole list only to conclude "identical, discard".
 * That guard exists purely as an optimisation, which is exactly the kind of code
 * worth measuring rather than assuming.
 *
 * Four cases, because their costs differ by orders of magnitude and the
 * production path hits more than one of them:
 *
 * - [equalContentSharedStrings] — the **steady state**. A re-derivation builds
 *   fresh [AppInfo] wrappers via `copy()`, but the `String` fields are carried
 *   over by reference from the previous list, so every field comparison hits
 *   `String.equals`' identity fast path. Full traversal, O(1) per field.
 * - [equalContentDistinctStrings] — the **post-re-enumeration** case. After a
 *   PackageManager sweep in `:data` the labels are fresh `String` instances with
 *   equal content, so each comparison scans characters. Same traversal, much
 *   costlier per element.
 * - [firstElementDiffers] — the early-out: `AbstractList.equals` bails at the
 *   first mismatch. Note this makes the guard's cost position-sensitive — a
 *   change to the *last* app costs a full traversal, same as the equal case.
 * - [sameListInstance] — the identity fast path (`o == this`), O(1). Production
 *   never reaches it, since `applyCustomNames`' `map` + `sortedBy` allocates a
 *   new list on every re-derivation; it is here as the floor to compare against.
 *
 * Note that [AppInfo.displayNameLower] and [AppInfo.componentName] are body
 * `val`s and therefore **excluded** from the generated `equals` (AUDIT-14 §208),
 * so each element comparison is four `String`s plus one `Boolean` — not six.
 *
 * The `@State` class is `open` (JMH generates a runtime subclass). Every
 * benchmark returns the comparison result so the JIT cannot elide it.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
open class AppInfoListEqualityBenchmark {

    /** Realistic drawer sizes — a lean phone (~50) and a loaded one (~200). */
    @Param("50", "200")
    var size: Int = 0

    private lateinit var baseline: List<AppInfo>
    private lateinit var sharedStringsTwin: List<AppInfo>
    private lateinit var distinctStringsTwin: List<AppInfo>
    private lateinit var firstDiffers: List<AppInfo>

    @Setup
    fun setUp() {
        baseline = (0 until size).map { i ->
            AppInfo(
                originalName = "Original App $i",
                displayName = "Original App $i",
                packageName = "com.example.app$i",
                className = "com.example.app$i.MainActivity",
            )
        }

        // Steady state: new AppInfo wrappers, same String instances. This is what
        // `copy()` produces, so it is the shape distinctUntilChanged actually sees
        // between two re-derivations of an unchanged list.
        sharedStringsTwin = baseline.map { it.copy() }

        // Post-re-enumeration: equal content, fresh String instances. The
        // String(CharArray) constructor guarantees a new object, so no identity
        // shortcut is available and equals has to compare characters.
        distinctStringsTwin = baseline.map { app ->
            AppInfo(
                originalName = String(app.originalName.toCharArray()),
                displayName = String(app.displayName.toCharArray()),
                packageName = String(app.packageName.toCharArray()),
                className = String(app.className.toCharArray()),
            )
        }

        // Early-out case: index 0 differs, everything after it is irrelevant.
        firstDiffers = sharedStringsTwin.toMutableList().also { list ->
            list[0] = list[0].copy(displayName = "Renamed")
        }
    }

    /** Steady state: full traversal, every field comparison an identity hit. */
    @Benchmark
    fun equalContentSharedStrings(): Boolean = baseline == sharedStringsTwin

    /** After a `:data` re-enumeration: full traversal, character-by-character. */
    @Benchmark
    fun equalContentDistinctStrings(): Boolean = baseline == distinctStringsTwin

    /** Early-out at index 0 — the cheapest possible "changed" verdict. */
    @Benchmark
    fun firstElementDiffers(): Boolean = baseline == firstDiffers

    /** Identity floor; unreachable in production, kept as the comparison point. */
    @Benchmark
    fun sameListInstance(): Boolean = baseline == baseline
}
