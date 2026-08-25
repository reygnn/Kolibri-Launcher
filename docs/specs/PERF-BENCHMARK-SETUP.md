# Performance — Benchmark & Setup

How Kolibri's start-up performance is measured on device. This is the **setup**
half; the numbers live in [`PERF-RESULTS`](PERF-RESULTS.md).

Everything here runs on **one device — the Galaxy A17 5G** — and is
**local-device-only** (never in the device-free GitHub CI; perf numbers on a
hosted emulator are noise). See CLAUDE.md Rule 10.

---

## What is measured

Two independent things, often confused:

1. **The hop** — the launcher's *own* share of launching a **foreign** app: the
   latency from the tap reaching the click handler to the
   `LauncherApps.startMainActivity` binder call. Everything after that binder
   (the foreign app's process fork + first frame) is *that app's* cold start,
   not Kolibri's to optimise.

2. **Kolibri's own cold start (TTID)** — `timeToInitialDisplayMs` from process
   fork to the home screen's first frame. This *is* Kolibri's to optimise: it is
   the user's every-unlock experience.

---

## The device

| | |
|---|---|
| Model | Galaxy A17 5G (`SM-A176B`) — real low-end hardware, **not** an emulator |
| SoC | Exynos-class `s5e8535` |
| OS | Android 16 |
| Display | 1080×2340, ~386 dpi, adaptive **90 Hz / 60 Hz** |
| Frame budget | **11.11 ms** @ 90 Hz · **16.67 ms** @ 60 Hz |
| Build under test | `0.99.198` (versionCode 218), **release**, R8 minify on, `<profileable shell="true">` |

Low-end is deliberate. The two effects being measured — the interpreter-vs-AOT
gap the Baseline Profile closes, and any frame added to the hop — are felt on a
weak CPU and would vanish into noise on a flagship. The A17 is where the numbers
are meaningful, and it is the slowest hardware Kolibri realistically ships to, so
one reference device is enough.

> **Historical devices.** Earlier hop measurements were taken on a Pixel 9a
> (reference) and cross-checked on a Galaxy A36. Those distributions are archived
> in [`../history/APP-START-PERFORMANCE.md`](../history/APP-START-PERFORMANCE.md)
> and are **not** reproduced here — the A17 is now the single benchmark device.

---

## Tooling

The `:macrobenchmark` module (`com.android.test`) drives everything on the
**release** build via Macrobenchmark + UiAutomator.

| Benchmark class | Measures | Metric(s) |
|---|---|---|
| `LaunchDispatchBenchmark` | the hop (drawer launch) | `LaunchDispatchGapMetric` + three `LaunchTrace` sections |
| `StartupBenchmark` | Kolibri's own cold start | `timeToInitialDisplayMs`, None vs Partial |
| `DrawerScrollJankBenchmark` | drawer-fling jank | `FrameTimingMetric`, None vs Partial |
| `WallpaperCompositeBenchmark` | wallpaper composite cache A/B | (out of scope here) |

`StartupBenchmark` and `DrawerScrollJankBenchmark` are the two surfaces of the
same baseline-profile question, deliberately in **separate classes** (split
2026-08-25): their run constraints differ, and run together the jank fling churns
the device and inflates the cold-start tail (see Methodology). Each is run on its
own.

Macrobenchmark version: `benchmark-macro 1.5.0-rc01` — the `androidx.baselineprofile`
plugin needs the 1.5.0 line under AGP 9; 1.4.1 rejects `:app`.

### The `LaunchTrace` instrumentation

`ui/util/LaunchTrace.kt` emits three slices, present in **release** too (gated on
a cheap atomic, near-free when nobody traces):

- `app_launch_tap` — the tap reached the click handler (adapter `onClick`).
- `app_launch_dispatch` — the Activity collector is handling the `LaunchApp`
  event (decide + optional drawer `popBackStack` + launch).
- `app_launch_startMainActivity` — the actual `startMainActivity` binder call.

`LaunchDispatchGapMetric` (a custom `TraceMetric`) queries the trace via Perfetto
SQL for the **TAP→DISPATCH gap** = start of `app_launch_dispatch` − end of
`app_launch_tap`. That gap **is** the `Channel` hop latency (the `launchSafe`
coroutine dispatch + Channel delivery to the collector). A stock
`TraceSectionMetric` measures one slice's duration, not the gap *between* two
slices — hence the custom metric.

---

## The gates

Two dependency-free JSON-scan Gradle tasks lock the two properties against silent
regression. Each threshold is chosen from *its own* metric's failure mode, not
copied from the other.

### `verifyLaunchBenchmark` — the hop stays sub-frame

```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest   # needs a device
./gradlew :macrobenchmark:verifyLaunchBenchmark           # threshold gate
```

- **Gates on:** `launchDispatchGapMs` **maximum** (worst iteration).
- **Threshold: 4.0 ms.** Generous, non-flaky headroom over the measured A17 max
  (see results); still under half a 90 Hz frame. A structural regression — a
  `suspend` call sneaking into the dispatch adds a whole frame to the hop with no
  diff-visible change — lands well past it.
- **Maximum, not median:** the failure here is a *structural sub-frame spike*
  where one extra frame in the hop is the signal.
- The 4.0 ms threshold is **not** re-tuned per device: it was calibrated on the
  Pixel 9a and confirmed to generalise down to slower Samsung hardware, and the
  A17 lands far under it.

> **Housekeeping gotcha.** The gate walks **every** `*-benchmarkData.json` under
> `macrobenchmark/build` and reports the worst `maximum` across all of them — so
> a stale JSON from an earlier run on a different device (e.g. a leftover
> `emulator-5554` file) can dominate the reported worst even though the current
> device passed cleanly. When reading a specific device's number, read that
> device's JSON directly (`.../benchmark/connected/<model>/...-benchmarkData.json`)
> rather than trusting the gate's aggregate line, or clean the build outputs
> first.

### `verifyStartupBenchmark` — the Baseline Profile keeps firing

```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest   # runs StartupBenchmark
./gradlew :macrobenchmark:verifyStartupBenchmark          # threshold gate
```

- **Gates on:** `startupBaselineProfile` (the `CompilationMode.Partial` arm)
  `timeToInitialDisplayMs` **median**.
- **Threshold: 420 ms.** Sits in the corridor *between* the profiled and
  unprofiled distributions (see results): ~11 % over the healthy profiled median
  **and** over its max, so noise never trips it — yet **below** the unprofiled
  median, so it fails exactly when the profile goes **silent** (a dependency bump
  drops the generated rules, or heavy init sneaks onto the startup path) and the
  Partial arm degrades back toward the unprofiled number.
- **Median, not maximum** (opposite of `verifyLaunchBenchmark`): TTID is a
  whole-render aggregate, so a single slow cold start (Doze, background I/O) is
  noise and the **shifted median** is the structural signal. A naive
  "just above the unprofiled value" threshold would miss the target case — a dead
  profile lands *under* it and passes green while broken.

---

## Running the benchmarks

Preconditions on the A17: connected over USB, **unlocked**. Onboarding needs
**no** manual setup: each class clears the first-run gates itself in its
`setupBlock` (shared `dismissFirstRunGatesIfPresent()` — taps Onboarding's Done,
declines the ACRA consent dialog; a no-op once past onboarding). This is needed
because `connectedBenchmarkAndroidTest` reinstalls the target fresh each run, so
a hand-done first-run would be wiped. No seam ships in the app — the benchmarks
measure the literal `release` build (full baseline profile). The cold-start
class additionally needs **another launcher set as default home** (see the
default-home caveat below). Keep the screen alive:

```bash
adb shell svc power stayon usb
```

Run one class at a time to keep the JSON focused:

```bash
# Hop only:
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.github.reygnn.kolibri_launcher.macrobenchmark.LaunchDispatchBenchmark

# Cold start only (see the default-home caveat below):
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.github.reygnn.kolibri_launcher.macrobenchmark.StartupBenchmark

# Drawer-scroll jank only (runs with Kolibri as default home):
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.github.reygnn.kolibri_launcher.macrobenchmark.DrawerScrollJankBenchmark
```

Cold start and jank are **separate classes**, so a class-level `StartupBenchmark`
run measures cold start with no contention from the jank fling — no method-level
filtering needed (see Methodology).

Read the per-device JSON at:

```
macrobenchmark/build/outputs/connected_android_test_additional_output/\
benchmark/connected/SM-A176B - 16/\
com.github.reygnn.kolibri_launcher.macrobenchmark-benchmarkData.json
```

### Caveat — cold start is unmeasurable while Kolibri is the default home

The home process never dies, so Macrobenchmark's "target must not be running
prior to a cold start" precondition can never hold. The cold-start data is
therefore produced with a **different launcher set as default** (switch
temporarily, switch back after). This is another reason the whole measurement is
local-device-only. The **hop** benchmark has no such constraint — it launches
Kolibri to the foreground itself and taps a drawer app.

### Methodology — cold-start noise is contention, not thermal

Cold-start TTID is noise-sensitive, and it is tempting to blame heat. On the A17
that is **wrong** — verified by live-logging the SoC (`dumpsys thermalservice`,
3 s poll) across a full isolated run (2026-08-25):

| | value during a measured run |
|---|---|
| AP (SoC) temp | 31 °C at rest → **peak 44.5 °C** under load |
| `Thermal Status` | **0 (NONE) the entire run** — never throttled |
| Battery / skin | flat ~29 °C / peak ~36 °C |

The A17 does **not** thermally throttle at benchmark load, so a cooldown wait is
**not** needed for thermal reasons. The real noise source is **device
contention**:

- **Historically:** cold start and drawer-jank shared one `StartupBenchmark`
  class, so a class-level run let the jank fling churn the device alongside the
  cold-start iterations. That is why `DrawerScrollJankBenchmark` was **split into
  its own class** (2026-08-25) — the contention is now structurally gone: a
  `StartupBenchmark` run no longer touches the jank path.
- **Remaining:** background work right after a **reboot / fresh install /
  onboarding** (media scan, ART optimisation, first-run settling).

Both inflate the cold-start **tail** (outlier iterations toward ~550 ms) and lift
the median, while `Thermal Status` stays 0 throughout — the signature of
contention, not heat (throttling would raise the *whole* distribution, not
scatter individual iterations). A disturbed run of this kind is **discarded**,
not treated as signal.

**Recipe for a clean cold-start number:** run `StartupBenchmark` on its own
(command above), on a device that has been idle a few minutes after any
reboot/onboarding. Confirmed reproducible: an isolated, settled run landed the
profiled median at **376.3 ms** — within ~2 ms of the reference **378.2 ms**
(see [`PERF-RESULTS`](PERF-RESULTS.md)), with `Thermal Status` 0 throughout.

To watch it live during a run, poll in a shell loop:

```bash
adb shell dumpsys thermalservice | grep -E 'Thermal Status:|mName=AP,'
```

---

## CI posture

CI does **not** run these — it only compile-checks the module
(`:macrobenchmark:compileBenchmarkSources`) so a renamed trace section can't rot
the benchmark silently. All actual measurement is manual, on the A17.
