# :domain JMH microbenchmarks

Reproducible JVM microbenchmarks for pure-Kotlin hot paths in `:domain`.
This is the benchmark counterpart to the JVM-first test philosophy: no
`androidTest`, no device, no emulator — the code under test is pure Kotlin,
so JMH measures it on the JVM directly.

## Run

```bash
./gradlew :domain:jmh                              # full suite (canonical baseline)
```

Console prints a summary table; a machine-readable copy lands at
`domain/build/reports/jmh/results.json` (git-ignored, under `build/`).

The full suite is 30+ min end-to-end (`luminancePass` alone is ~0.4 ms/call
over two sizes with warmup + forks). For a one-off check of a single function,
filter by a **regex over the benchmark's fully-qualified name**:

```bash
./gradlew :domain:jmh -PjmhInclude=ApplyCustomNames    # one class
./gradlew :domain:jmh -PjmhInclude=filterByName,score  # several (comma-separated)
./gradlew :domain:jmh -PjmhInclude=AppInfoSearchBenchmark.filterDisplayName  # one arm
./gradlew :domain:jmh -PjmhExclude=Luminance           # everything except the slow one
```

`jmhInclude` / `jmhExclude` are the plugin's `includes` / `excludes` regex lists
wired to a `-P` property (the me.champeau.jmh plugin has no built-in `-P` filter;
`-Pjmh.includes` is a silent no-op). A **filtered run is partial**, so it writes
to `results-filtered.json` instead of `results.json` — the committed baseline is
never clobbered by a subset. Only the unfiltered full run updates the canonical
`results.json`.

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
| `applyCustomNames` (map-only) | 50  | ~0.60 ops/µs (~1.7 µs/call) |
| `applyCustomNames` (map-only) | 200 | ~0.15 ops/µs (~6.7 µs/call) |
| `luminancePass`      | 1024 px | ~0.009 ops/µs (~111 µs/call) |
| `luminancePass`      | 4096 px | ~0.0023 ops/µs (~444 µs/call) |
| `filterDisplayName`      | 50  | ~2.5 ops/µs (~0.4 µs/call) |
| `filterDisplayName`      | 200 | ~0.61 ops/µs (~1.6 µs/call) |
| `filterWithOriginalName` | 50  | ~0.85 ops/µs (~1.2 µs/call) |
| `filterWithOriginalName` | 200 | ~0.21 ops/µs (~4.7 µs/call) |
| `score` (usage)      | 20 ts  | ~1.3 ops/µs (~0.8 µs/call) |
| `score` (usage)      | 100 ts | ~0.25 ops/µs (~4.0 µs/call) |
| `construct` (AppInfo)     | 50  | ~0.57 ops/µs (~1.8 µs/call) |
| `construct` (AppInfo)     | 200 | ~0.14 ops/µs (~7.1 µs/call) |
| `constructBare` (no vals) | 50  | ~2.3 ops/µs (~0.4 µs/call) |
| `constructBare` (no vals) | 200 | ~0.62 ops/µs (~1.6 µs/call) |
| `copyDisplayName`         | 200 | ~0.16 ops/µs (~6.3 µs/call) |
| `copyIsFavorite`          | 200 | ~0.16 ops/µs (~6.4 µs/call) |
| `equalContentSharedStrings`   | 200 | ~0.72 ops/µs (~1.4 µs/call) |
| `equalContentDistinctStrings` | 200 | ~0.24 ops/µs (~4.1 µs/call) |
| `firstElementDiffers`         | 200 | ~135 ops/µs (~0.007 µs/call) |
| `sameListInstance`            | 200 | ~1474 ops/µs (~0.001 µs/call) |

`applyCustomNames` is now a pure name-resolution `map` (no terminal sort) since
the RAL-4 map-only flip — measured ~1.7 µs @50, ~6.7 µs @200, µs-scale off the
Main thread (fresh single-benchmark run via `-PjmhInclude=ApplyCustomNamesBenchmark`).

*Historical:* the benchmark once carried a second `mapOnly` arm to price the
former terminal sort (the "dead sort" for drawer/favorites/recents). The
`applyCustomNames − mapOnly` delta measured the sort at ~9.7 µs @200 (~62 % of the
then-`applyCustomNames`, ~15.8 µs). That is exactly the sort the map-only flip
removed and every consumer now owns — so the arm became redundant and was dropped.
Full account in `APPLIST_SORT_SPLIT_SPEC.md`.

`luminancePass` is the pure WCAG luminance math the wallpaper AUTO-classifier
runs per opaque pixel (`WallpaperBitmapLuminanceImpl.classify`, 32×32 = 1024
sampled pixels). At the production sample size the `pow`-heavy pass alone is
~0.1 ms per layer (before the Android bitmap decode/scale), scaling linearly —
off-Main on wallpaper change, so not hot, but now pinned.

`filterByName` is the per-keystroke search predicate (`AppInfoSearch.filterByName`),
the hottest per-interaction pure loop — it runs on every character typed in the
drawer search and four settings screens. The numbers confirm the fold-once
optimisation (AUDIT-15 F2 / AUDIT-16 N2): the common `filterDisplayName` path
stays under ~2 µs even at 200 apps, and the Custom-Names `includeOriginalName`
path (extra per-app `originalName.lowercase()` on the renamed subset) is ~3×
that but still single-digit µs.

`score` is the exponential-decay usage score (`UsageScore.timeWeightedUsageScore`,
extracted from `AppUsageRepositoryImpl` so the math is pure `:domain`). It runs
once per visible app inside `sortAppsByTimeWeightedUsage`, so per drawer refresh
in TIME_WEIGHTED mode the scoring cost is roughly this × the app count — ~0.15 ms
for 200 apps at ~20 timestamps each (~0.8 µs/app), on top of the DataStore read.
The `exp()` per timestamp keeps it the priciest per-element math after luminance.

`construct` / `constructBare` price the cost SIDE of the AUDIT-14 §208/§212
precompute trade — the reads those body vals speed up were the argument for
them; this is the bill. The two precomputes (`displayNameLower` = `lowercase()`,
`componentName` = a `startsWith(".")` check + concat) dominate construction:
`constructBare` (same five fields, no body vals) is ~1.6 µs at 200 apps vs
`construct`'s ~7.1 µs — so the vals are ~4× the from-scratch field-copy cost,
~28 ns per instance. That is the price a `:data` enumeration pays per app. The
trade still nets positive because the reads are more numerous than the writes
(the sort comparator alone reads `displayNameLower` O(N·log N) times, the
hidden-filter + DiffUtil read `componentName` O(N) each per derivation), so
paying the fold once beats folding per comparison — the whole point of §208.

`copyDisplayName` / `copyIsFavorite` are the two production `copy()` shapes, and
they cost ~6.3–6.4 µs at 200 apps — essentially a full `construct`, because
`copy()` runs the constructor and therefore re-derives BOTH body vals every
time. `copyDisplayName` (the `applyCustomNames` map) at least needs the
`displayNameLower` redo; `copyIsFavorite` (the `GetFavoriteAppsUseCase.processApps`
`copy(isFavorite = …)`) redoes both for nothing — neither val's inputs changed.
It is the clearest spot where the precompute works against itself, but it is
accepted: a `copy()` cannot selectively keep a body val, and each favorites
derivation does exactly one such pass off the Main thread — µs-scale, in the
noise, same verdict as the RAL-1a dead sort. Pinned so the claim is a number,
not a guess.

The `equal*` set is the work `distinctUntilChanged()` does per emission on the
drawer + favorites flows — and that guard fires on EVERY `settingsDataStore`
write, including the usage write on every app launch (AUDIT-14 F1). The steady
state is cheap: `equalContentSharedStrings` (~1.4 µs at 200) is the realistic
case, because `copy()` carries the `String` fields over by reference so every
comparison hits `String.equals`' identity fast path. `equalContentDistinctStrings`
(~4.1 µs) is the post-re-enumeration case — equal content, fresh instances,
character scan — ~3× costlier but still single-digit µs and far rarer.
`firstElementDiffers` (~0.007 µs) shows the early-out is effectively free, and
`sameListInstance` (~0.001 µs) is the unreachable identity floor. Net: the guard
is worth it — even its worst realistic case is cheaper than the adapter churn it
prevents. Note the cost is position-sensitive (a change to the LAST app costs a
full traversal, same as the equal case), but the verdict holds at these sizes.

## Adding a benchmark

- `@State(Scope.Benchmark)` classes must be `open` — JMH generates a runtime
  subclass; a `final` Kotlin class fails at run time.
- Return the result from each `@Benchmark` (or consume a `Blackhole`) so the
  JIT cannot dead-code-eliminate the work.
- Keep the source set pure-JVM: `src/jmh/` has no Android SDK on its
  classpath, exactly like the rest of `:domain`.
