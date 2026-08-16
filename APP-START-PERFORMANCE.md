# App-Start Performance — On-Device Measurements

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

*Measurements: 2026-08-15 (medians) and 2026-08-16 (p99 / contention / A/B).
Build 0.99.174, Pixel 9a. Numbers are device- and build-specific; re-measure
after a hot-path change or on different hardware.*
