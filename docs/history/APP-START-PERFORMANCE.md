# App-Start Performance — On-Device Measurements

> **ARCHIVED (2026-08-25).** Superseded by the A17-only rewrite split into two
> living docs in [`../specs/`](../specs/):
> [`PERF-BENCHMARK-SETUP`](../specs/PERF-BENCHMARK-SETUP.md) (device, tooling,
> metrics, gates, re-run recipe) and [`PERF-RESULTS`](../specs/PERF-RESULTS.md)
> (the numbers + verdict). This file is kept for provenance only — it carries the
> historical **Pixel 9a** hop distributions (§1–3, incl. the manual Perfetto
> contention run and the `experiment/direct-launch-latency` A/B) and the
> **Galaxy A36** cross-check, none of which the A17 rewrite reproduces. The A17
> §5 cold-start data was migrated forward verbatim. Do not treat anything below
> as the live reference.

On-device measurements of Kolibri's **app-launch hot path** — the latency from
tapping an app entry to the target app's `startMainActivity` binder call. This
is the launcher's *own* share of a launch; everything after the binder call
(the foreign app's process fork + `Application.onCreate` + first frame) is that
app's cold start and not the launcher's to optimise.

**TL;DR:** the MVVM launch-dispatch path (ViewModel + `Channel` event hop) is
**sub-millisecond at p50 _and_ p99, even under deliberate main-thread
contention.** A direct A/B against a hop-free build shows the hop costs a real,
consistent **~0.5–1.1 ms** — but every launch in every scenario stays **well
under one frame**, and that saving vanishes behind the target app's ~11–19 ms
cold-start binder. No launch-path micro-optimisation is worth doing. The
architecture (event bus, `@MainDispatcher`, drawer `popBackStack`) stays as-is.

> Companion agent memory: `project-launch-dispatch-latency-measured`. If the
> "should we remove the hop / use `Main.immediate`" question is raised again, it
> is already closed on measured data — re-open only if the hot-path structure
> changes (event bus removed) or on a very different device.

---

## Test setup

| | |
|---|---|
| Device | Pixel 9a (`tegu`), 60/120 Hz panel, 1080×2424 @ 420 dpi |
| Build | `0.99.174` (versionCode 194), release-equivalent, `<profileable shell="true">` |
| Tool | Perfetto (`v57.2`), `trace_processor_shell` for analysis |
| Driving | Automated `adb shell input` loops (tap / swipe-open-drawer / fling), device-side pacing |
| Screen | `svc power stayon usb` during runs (else the screen times out mid-loop) |

Instrumentation is the `LaunchTrace` slices (`ui/util/LaunchTrace.kt`), present
in release too (gated on a cheap atomic, near-free when nobody is tracing):

- `app_launch_tap` — the tap reached the click handler (adapter `onClick`).
- `app_launch_dispatch` — the Activity collector is handling the `LaunchApp`
  event (decide + optional drawer `popBackStack` + launch).
- `app_launch_startMainActivity` — the actual `LauncherApps.startMainActivity`
  binder call.

### Metrics

- **TAP→DISPATCH gap** = start of `app_launch_dispatch` − end of `app_launch_tap`.
  This *is* the `Channel` hop latency (`launchSafe` coroutine dispatch + Channel
  delivery to the collector). Used for the dispatch-latency + contention runs.
- **TAP→START** = start of `app_launch_startMainActivity` − start of
  `app_launch_tap`. Tap-handler entry → binder call. Used for the A/B, because
  the hop-free arm has no `app_launch_dispatch` slice to measure a gap against.

Percentiles are computed over the per-launch values (n listed per table); a
launch is one paired (tap, next-slice) event.

---

## 1. Dispatch-latency distribution (TAP→DISPATCH gap)

Production path, at rest (idle main thread). All values **µs**, n=320 each.

| Scenario | p50 | p90 | p95 | p99 | max |
|---|---|---|---|---|---|
| Home favorites | 415 | 479 | 552 | **856** | 1051 |
| App drawer | 432 | 483 | 524 | **632** | 1061 |

**Zero** launches exceeded even one 120 Hz frame (8.33 ms). The p99 and absolute
max are all sub-millisecond. The original (2026-08-15) run reported only the
median (n=22/13, ~0.69 ms home / ~0.58 ms drawer); these n=320 runs add the tail.

## 2. Dispatch latency under contention

Drawer launch where the tap lands **50 ms into an active fling** — RecyclerView
rebinds still churning the main thread at dispatch time. n=300, µs.

| Scenario | p50 | p90 | p95 | p99 | max |
|---|---|---|---|---|---|
| Drawer, at rest | 432 | 483 | 524 | 632 | 1061 |
| Drawer, **contention** | 506 | 721 | 765 | **839** | **1683** |

Contention shifts the median by ~75 µs and stretches the tail — the effect is
**real but light** (a short app-drawer's fling settles in <250 ms, so there is
not much rebind load to queue behind). The single worst launch (1.68 ms) was
inspected via `sched`: the main thread was **`Running`** across the entire gap,
**not `Runnable`/CPU-starved** — the launch message queued behind legitimate
frame work, not scheduler starvation. Still ~1/5 of one 120 Hz frame.

Across §1 + §2 (940 launches): **0 exceeded one frame** at 120 Hz or 60 Hz.

## 3. A/B — with vs. without the hop (TAP→START)

Direct head-to-head on throwaway branch `experiment/direct-launch-latency`: a
const-gated (`LaunchExperiment.DIRECT_LAUNCH`, R8-folded to zero overhead)
synchronous launch path that bypasses the ViewModel + `Channel` hop and calls
`startMainActivity` straight from the tap frame — the way a dumb direct-call
launcher would. The direct arm was confirmed structurally hop-free: **0**
`app_launch_dispatch` slices over 320 launches. n=320/arm, at rest, µs.

| Scenario | Arm | p50 | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| **Home** | A — with hop | 686 | 812 | 967 | 1289 | 1806 |
| | B — direct | **121** | 147 | 183 | 217 | 236 |
| **Drawer** | A — with hop | 916 | 1095 | 1165 | 1520 | 2097 |
| | B — direct | **412** | 570 | 592 | 834 | 1366 |

**Cost of the hop (Arm A − Arm B):**

| Scenario | p50 saved | p99 saved | max saved |
|---|---|---|---|
| Home | 566 µs | 1071 µs | 1570 µs |
| Drawer | 505 µs | 686 µs | 731 µs |

The hop cost is **consistent across both surfaces (~0.5–0.57 ms at p50)** — that
is the isolated `Channel`+dispatch cost, independent of the rest. Home-direct
lands at 121 µs; drawer-direct at 412 µs because the direct drawer path still
runs the synchronous `popBackStack` (drawer close) inside the tap frame.

Worst single launch as a fraction of one frame:

| | max | ×120 Hz frame | ×60 Hz frame |
|---|---|---|---|
| Home, with hop | 1806 µs | 0.22 | 0.11 |
| Drawer, with hop | 2097 µs | 0.25 | 0.13 |
| Home, direct | 236 µs | 0.03 | 0.01 |
| Drawer, direct | 1366 µs | 0.16 | 0.08 |

## 4. The dominant cost is the foreign app's cold start

From the original (2026-08-15) run, the `startMainActivity` binder → target app
process/activity start was **~11 ms (home) / ~19 ms (drawer)** — the only real
cost on a launch, and **not the launcher's to optimise**. The launcher's own
tap→binder share is ~1–2 ms, entirely within one frame.

## 5. Kolibri's own cold start (TTID) + Baseline Profile

Everything above measures **the hop** — the launcher's own share of launching a
*foreign* app (tap → `startMainActivity` binder), which is sub-frame and not
worth optimising. This section measures a **different** thing: Kolibri's **own**
process cold start — `timeToInitialDisplayMs` from process fork to the home
screen's first frame. That *is* the launcher's to optimise, because it is the
user's every-unlock experience, and it was the one hot path still running
interpreted on first use.

**TL;DR:** shipping a Baseline Profile (`androidx.baselineprofile` producer in
`:baselineprofile`, applied via `profileinstaller` in `:app`) cuts cold-start
TTID on a low-end device by **~16 % (450.1 → 378.2 ms median, −71.9 ms)**. The
gain is **structural, not a median fluke**: the profiled and unprofiled
distributions **do not overlap** — the slowest profiled cold start (407.6 ms) is
faster than the fastest unprofiled one (434.6 ms). A `verifyStartupBenchmark`
gate (sibling to `verifyLaunchBenchmark`) locks it against silent regression.

> Companion agent memory: `project-cold-start-ttid-baseline-profile`. If "is the
> Baseline Profile worth keeping / does it still fire" is raised again, it is
> closed on measured data. Re-open only if the startup hot path changes, on a
> very different device, or if the gate below starts firing (profile likely gone
> silent after a dependency bump — see gate rationale).

### Test setup

| | |
|---|---|
| Device | Galaxy A17 5G (`SM-A176B`) — real low-end hardware, not an emulator |
| Build | `0.99.198`, release, `<profileable shell="true">`, R8 minify on |
| Tool | Macrobenchmark (`benchmark-macro` 1.5.0-rc01 — the `androidx.baselineprofile` plugin needs the 1.5.0 line under AGP 9; 1.4.1 rejects `:app`), `StartupMode.COLD`, 20 iterations |
| A/B | `CompilationMode.None()` vs `CompilationMode.Partial()` (= baseline profile installed) |
| Metric | `timeToInitialDisplayMs`, per-iteration |
| Caveat | measured with a **different** default launcher (see operational note) |

Low-end is deliberate: the interpreter-vs-AOT gap the profile closes is felt on
a weak CPU and would vanish into noise on a flagship. The A17 5G is where the
number is meaningful.

### Distribution — None vs Partial

20 cold starts per arm, isolated run, all values **ms**.

| Arm | min | median | p95 | max | CoV |
|---|---|---|---|---|---|
| None (no profile) | 434.6 | 450.1 | 465.8 | 468.8 | 2.2 % |
| Partial (profiled) | 358.6 | **378.2** | 404.3 | 407.6 | 3.6 % |

The headline is **not** the 16 % — it is the **zero overlap**. Partial-max
(407.6) sits 27 ms below None-min (434.6); the two ranges are disjoint. With
CoV 2–3 % this is a clean structural separation, so no significance test is
needed (the distributions do not touch; Mann-Whitney would be ~10⁻⁶,
academic here). p95 profiled (404.3) is barely above its own median (378.2) —
the tail did not blow out, so the gain is robust, not an average over lucky runs.

### Gate — `verifyStartupBenchmark`

Same dependency-free JSON scan as `verifyLaunchBenchmark`, but the threshold and
the percentile are chosen from *this* metric's failure mode, not copied.

**Failure mode:** the profile going **silent** (a dependency bump drops the
generated rules, or a heavy init sneaks onto the startup path). When that
happens, Partial degrades to None behaviour and the median drifts back toward
**~450 ms**. So the gate must sit in the corridor **between** the two
distributions — not above both. A naive "just above the unprofiled value"
threshold (~460–480 ms) would miss the target case entirely: a dead profile
lands at ~450 ms, under 460, and passes green while broken.

**Threshold: median 420 ms.**

- 42 ms / 11 % over the healthy median (378.2) **and** over its max (407.6) →
  noise never trips it.
- **Below** the profile-loss median (~450) → catches exactly "profile gone" +
  "heavy init on the startup path".
- **Median, not maximum** (unlike `verifyLaunchBenchmark`, which gates on
  `maximum`): the dispatch gate catches a *structural sub-frame spike* where one
  extra frame in the hop is the signal. TTID is the opposite — an aggregate
  render measurement where a single slow cold start (Doze, background I/O) is
  noise and the **shifted median** is the signal.

Live-validated: reads 378.2 (profiled) correctly — name-anchored past the None
value (450.1) so it never picks up the wrong block — and passes with headroom.

### Operational note — why this is local-device-only

Cold-start TTID is **unmeasurable while Kolibri is the default home**: the home
process never dies, so Macrobenchmark's "target must not be running prior to a
cold start" precondition can never hold. The gate data is therefore produced
with a **different launcher set as default** (switch temporarily, switch back
after). This — like the benchmark itself — is why it is **local-device-only**
and deliberately **not** wired into the device-free CI job (CLAUDE.md Rule 10;
perf numbers on a hosted emulator are noise, and here they are also structurally
impossible).

### Methodology note — why the first run did not count

The **first** A17 attempt showed a much wider spread (min ~359 / max ~500 ms)
and was **discarded**. Cause: measurement interference — parallel `uiautomator`
dumps churning the device plus Doze on the earlier emulator-adjacent setup,
inflating the tail. The isolated A17 5G run (CoV 2–3 %, table above) is the
load-bearing number. Recorded here on purpose: the discarded run is *not* a
valid baseline, and a future reader must not mistake a disturbed measurement for
signal — nor distrust the gate when it correctly fires against a real regression.

---

## Verdict

- **Do NOT** switch `@MainDispatcher` to `Dispatchers.Main.immediate`.
- **Do NOT** duplicate `launchSafe` into a `launchSafeImmediate`.
- **Do NOT** make the launch path synchronous / bypass the event bus.
- **Do NOT** optimise `popBackStack`.

The hop is genuinely eliminable and genuinely saves ~0.5–1.1 ms — but the saving
is **imperceptible** (sub-frame, dwarfed by the target app's cold start), and the
direct path has a real price: it drops `recordAppLaunchUseCase`, so the drawer's
usage-based ordering would freeze. That trade-off, plus the Activity-context and
event-bus-uniformity reasons, is documented on
`AppManagementDelegate.onAppClicked`. The pre-agreed threshold — "worth it only
if the gap regularly costs ~1 full frame" — is not met by a wide margin
(measured ~0.07 frames at p50). The `experiment/direct-launch-latency` branch is
reference-only, not a merge candidate.

**Re-open only if** a custom launch animation is added (that needs the tapped
view's source bounds captured synchronously in the touch frame and flips the
trade-off — see the `onAppClicked` KDoc), the event bus is removed, or on a
substantially different device.

---

## Re-run recipe

1. `adb shell svc power stayon usb` (keep the screen alive for the loop).
2. Perfetto config: `atrace_apps: "com.github.reygnn.kolibri_launcher"` only →
   tiny trace. Add `sched/sched_switch` + `sched/sched_waking` **only** when a
   tail needs explaining (`Running` vs `Runnable` on the main thread). Use
   `write_into_file: true` so a long run does not overflow the ring buffer.
3. Drive launches with a device-side loop:
   - **Home:** `input tap <fav>` → `input keyevent HOME`.
   - **Drawer:** `HOME` → `input swipe` (open) → `input tap <row>`.
   - **Contention:** open drawer → hard `input swipe` (fling) → tap ~50 ms in.
   - Pace with device-side `sleep`; the 300 ms double-tap throttle
     (`LAUNCH_THROTTLE_MS`) means keep taps >300 ms apart.
4. For a stable **p99** use **n ≥ 300** per scenario (the original n=22 gives a
   median only).
5. Analyse with `trace_processor_shell`
   (`~/.local/share/perfetto/prebuilts/`): pair each `app_launch_tap` with the
   next `app_launch_dispatch` (gap) or `app_launch_startMainActivity` (TAP→START),
   then compute percentiles.

For the A/B specifically: flip `LaunchExperiment.DIRECT_LAUNCH`, rebuild
`assembleRelease` (const-folds the dead branch, so each arm is zero-overhead),
`adb install -r` (same family-key signature → in-place update), measure TAP→START
both ways.

---

## Regression gate — `:macrobenchmark`

The sub-frame property above is locked by an on-device Macrobenchmark
(`:macrobenchmark`, a `com.android.test` module) so it cannot silently regress —
e.g. a `suspend` call sneaking into the dispatch would add a frame to the hop
with no diff-visible change. `LaunchDispatchBenchmark` opens the app drawer and
taps the first app on the **release** build, capturing the three `LaunchTrace`
sections plus a custom `TraceMetric` (`LaunchDispatchGapMetric`) that queries the
trace for the TAP→DISPATCH gap via Perfetto SQL — `TraceSectionMetric` measures
a single slice's duration, not the gap *between* two slices, so the hop needs the
custom metric.

```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest   # needs a device
./gradlew :macrobenchmark:verifyLaunchBenchmark           # threshold gate
```

`verifyLaunchBenchmark` parses the benchmark JSON and fails if the worst-iteration
`launchDispatchGapMs` exceeds **4.0 ms** (generous, non-flaky headroom over the
~0.85 ms measured p99, still under half a 120 Hz frame; a frame-sized regression
lands well past it). Device-calibrated to the Pixel 9a — re-tune if the reference
device changes.

**Validated on-device (2026-08-16, release build, 40 iterations, Pixel 9a):**
`launchDispatchGapMs` min 0.35 / median 0.50 / **max 1.15 ms** — consistent with
the manual drawer at-rest numbers (§1). Gate result: PASS (1.15 ms « 4.0 ms).

**Confirmed down-market on a mid-range Samsung (2026-08-16, release build, 40
iterations, Galaxy A36 / SM-A366B, Snapdragon 6 Gen 3, One UI, 120 Hz):**
`launchDispatchGapMs` min 0.36 / median 0.51 / **max 0.83 ms** (CoV 0.18) — the
weaker CPU that must still hit the same 8.33 ms frame deadline as the Pixel, and
the numbers land essentially on top of the reference; the tail is even lower
(0.83 vs 1.15 ms), which fits the hop being scheduling-jitter-bound tiny
main-thread work rather than compute-bound. Gate result: PASS (0.83 ms « 4.0 ms).
This is why the 4.0 ms threshold is NOT re-tuned per device: the Pixel 9a
calibration generalizes to the slowest hardware Kolibri realistically ships to,
so one reference device is enough.

**Local device only.** It matches this project's "androidTest = real device =
local" posture (CLAUDE.md Rule 10) and is deliberately NOT run in the device-free
GitHub-Actions job — perf numbers on a hosted emulator are noise. CI only
compile-checks the module (`:macrobenchmark:compileBenchmarkSources`) so it
cannot rot against a renamed trace section. Requires a connected, unlocked device
with the launcher past onboarding (the benchmark taps a drawer app, so no
user-specific favorite is needed).

---

*Measurements: 2026-08-15 (medians) and 2026-08-16 (p99 / contention / A/B),
build 0.99.174, Pixel 9a + Galaxy A36; §5 cold-start TTID + Baseline Profile
2026-08-24, build 0.99.198, Galaxy A17 5G. Numbers are device- and
build-specific; re-measure after a hot-path change or on different hardware.*
