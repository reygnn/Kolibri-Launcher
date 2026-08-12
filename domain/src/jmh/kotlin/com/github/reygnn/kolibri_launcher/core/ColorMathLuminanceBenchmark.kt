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
 * JMH microbenchmark for [ColorMath.calculateLuminance] driven the way the
 * wallpaper AUTO-classifier drives it: a per-pixel WCAG luminance pass over a
 * sampled bitmap. `WallpaperBitmapLuminanceImpl.classify` scales the wallpaper
 * to `SAMPLE_SIZE² = 32×32 = 1024` pixels and calls `calculateLuminance` once
 * per opaque pixel — the bitmap load/scale is Android (`:data`), but the math
 * itself is pure Kotlin and lives here in `:domain/core`.
 *
 * This pins the `pow`-heavy sRGB→linear cost, a very different profile from the
 * string-sort in [com.github.reygnn.kolibri_launcher.domain.usecase.ApplyCustomNamesBenchmark].
 * `size` = 1024 is the real production sample count; 4096 shows the O(n) scaling.
 *
 * The `@State` class is `open` (JMH generates a runtime subclass). The loop
 * accumulates into a returned `Double` so the JIT cannot elide the math.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
open class ColorMathLuminanceBenchmark {

    /** 1024 = the production SAMPLE_SIZE² (32×32); 4096 shows O(n) scaling. */
    @Param("1024", "4096")
    var size: Int = 0

    private lateinit var pixels: IntArray

    @Setup
    fun setUp() {
        // Deterministic full-alpha ARGB spread across the RGB gamut so every
        // channel exercises BOTH sRgbToLinear branches (the linear <0.03928 tail
        // and the pow path), mirroring a real varied wallpaper sample. No RNG —
        // a fixed stride keeps the input reproducible across runs.
        pixels = IntArray(size) { i ->
            val r = (i * 7) and 0xFF
            val g = (i * 13) and 0xFF
            val b = (i * 29) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    @Benchmark
    fun luminancePass(): Double {
        var acc = 0.0
        for (argb in pixels) {
            acc += ColorMath.calculateLuminance(argb)
        }
        return acc
    }
}
