# :domain JMH microbenchmarks

Reproducible JVM microbenchmarks for pure-Kotlin hot paths in `:domain`.
This is the benchmark counterpart to the JVM-first test philosophy: no
`androidTest`, no device, no emulator — the code under test is pure Kotlin,
so JMH measures it on the JVM directly.

## Run

```bash
./gradlew :domain:jmh
```

Console prints a summary table; a machine-readable copy lands at
`domain/build/reports/jmh/results.json` (git-ignored, under `build/`).

Config lives in `domain/build.gradle.kts` (`jmh { }` block): 3 warmup + 5
measurement iterations, 1 fork, JSON output. Per-benchmark annotations
override those defaults.

## Not a CI gate — a baseline for manual diff

JMH numbers are **host-dependent**: absolute scores differ across machines,
so there is no auto-fail threshold (same stance as the `checkConventions`
regression checks — "manual rerun, not a CI gate"). The reproducible artefact
is the JSON; capture a baseline on a fixed reference machine and diff a fresh
run against it by hand when investigating a suspected regression.

## Reference numbers (indicative only)

Captured once on the author's build host — **for shape, not as an absolute
pass/fail line** (your host will differ):

| Benchmark            | size    | Score            |
|----------------------|---------|------------------|
| `applyCustomNames`   | 50      | ~0.27 ops/µs (~3.7 µs/call) |
| `applyCustomNames`   | 200     | ~0.055 ops/µs (~18 µs/call) |
| `luminancePass`      | 1024 px | ~0.009 ops/µs (~111 µs/call) |
| `luminancePass`      | 4096 px | ~0.0023 ops/µs (~444 µs/call) |

`applyCustomNames` confirms its KDoc (REACTIVE_APPLIST_SPEC RAL-1a / AUDIT-15
F3): the map + terminal `sortedBy` is µs-scale over 50–200 apps, i.e. "in the
noise" off the Main thread.

`luminancePass` is the pure WCAG luminance math the wallpaper AUTO-classifier
runs per opaque pixel (`WallpaperBitmapLuminanceImpl.classify`, 32×32 = 1024
sampled pixels). At the production sample size the `pow`-heavy pass alone is
~0.1 ms per layer (before the Android bitmap decode/scale), scaling linearly —
off-Main on wallpaper change, so not hot, but now pinned.

## Adding a benchmark

- `@State(Scope.Benchmark)` classes must be `open` — JMH generates a runtime
  subclass; a `final` Kotlin class fails at run time.
- Return the result from each `@Benchmark` (or consume a `Blackhole`) so the
  JIT cannot dead-code-eliminate the work.
- Keep the source set pure-JVM: `src/jmh/` has no Android SDK on its
  classpath, exactly like the rest of `:domain`.
