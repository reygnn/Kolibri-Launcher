# Performance — Results

On-device start-up numbers for Kolibri, measured on the **Galaxy A17 5G**
(`SM-A176B`). Setup, tooling and gate rationale live in
[`PERF-BENCHMARK-SETUP`](PERF-BENCHMARK-SETUP.md).

**TL;DR**

- **The hop is free.** The launcher's own tap→dispatch latency is **0.65 ms
  median / 1.30 ms max** — a tenth of one frame. Nothing to optimise.
- **The foreign app's cold start dominates** a launch (~16 ms), and that is not
  Kolibri's to optimise.
- **The Baseline Profile is worth keeping.** It cuts Kolibri's *own* cold start
  by **~16 % (450 → 378 ms median)** with **zero distribution overlap**.
- Both regression gates **PASS** with wide headroom.

---

## 1. The hop — launcher's own launch share

`LaunchDispatchBenchmark`, release build, **40 iterations**, drawer launch at
rest. All values **ms**.

| Slice | min | median | max | what it is |
|---|---|---|---|---|
| **`launchDispatchGapMs`** | 0.614 | **0.646** | **1.297** | the hop: TAP→DISPATCH (ViewModel + `Channel`) |
| `app_launch_tap` | 0.123 | 0.155 | 0.269 | tap-handler duration |
| `app_launch_dispatch` | 13.752 | 17.041 | 20.143 | dispatch section (contains the foreign binder) |
| `app_launch_startMainActivity` | 12.777 | 16.152 | 19.131 | the `startMainActivity` binder call |

**The hop (the only value that could regress invisibly) is sub-frame:**

| | ms | ×90 Hz frame (11.11 ms) | ×60 Hz frame (16.67 ms) |
|---|---|---|---|
| hop median | 0.646 | 0.058 | 0.039 |
| hop **max** | 1.297 | **0.117** | **0.078** |

The worst single launch across 40 iterations is **~1/9 of one 90 Hz frame**.

**Where a launch's time actually goes** (medians): the launcher's *own*
synchronous share is `tap` (0.16) + the hop (0.65) + the dispatch work beyond the
binder (`dispatch` − `startMainActivity` ≈ 17.04 − 16.15 ≈ **0.9**) ≈ **~1.7 ms
total** — comfortably inside one frame. The remaining **~16 ms** is the
`startMainActivity` binder forking the *foreign* app's process (§2).

## 2. The dominant cost is the foreign app's cold start

The `startMainActivity` binder blocks ~**16 ms median** (up to ~19 ms) while the
target app's process is created — visible above as the near-equal
`app_launch_dispatch` and `app_launch_startMainActivity` sections. That is the
foreign app's cold start, **not the launcher's to optimise**. Any saving on the
launcher's ~1.7 ms own share is dwarfed by it.

## 3. Kolibri's own cold start (TTID) + Baseline Profile

`StartupBenchmark`, **20 iterations per arm**, `StartupMode.COLD`,
`timeToInitialDisplayMs`. `None` = no profile; `Partial` = Baseline Profile
installed (`:baselineprofile` producer, applied via `profileinstaller`). All
values **ms**.

| Arm | min | median | p95 | max | CoV |
|---|---|---|---|---|---|
| None (no profile) | 434.6 | 450.1 | 465.8 | 468.8 | 2.2 % |
| Partial (profiled) | 358.6 | **378.2** | 404.3 | 407.6 | 3.6 % |

**Median improvement: −71.9 ms (~16 %).** The headline is not the 16 % — it is
the **zero overlap**: the slowest profiled cold start (407.6) is faster than the
fastest unprofiled one (434.6). The two ranges are disjoint by 27 ms. With CoV
2–3 %, that is a clean **structural** separation, not an average over lucky runs
(p95 profiled, 404.3, barely exceeds its own median). No significance test is
needed — the distributions do not touch.

## 4. Gate results

| Gate | Metric | Threshold | A17 value | Result |
|---|---|---|---|---|
| `verifyLaunchBenchmark` | `launchDispatchGapMs` max | 4.0 ms | **1.297 ms** | **PASS** (« 4.0) |
| `verifyStartupBenchmark` | `startupBaselineProfile` median | 420 ms | **378.2 ms** | **PASS** (« 420) |

> When reading `verifyLaunchBenchmark`'s console line, note the stale-JSON
> gotcha in [`PERF-BENCHMARK-SETUP`](PERF-BENCHMARK-SETUP.md#the-gates): it
> aggregates the worst `maximum` across *all* JSONs under `build`, so a leftover
> file from another device can inflate the reported worst. The 1.297 ms above is
> read straight from the A17's own `benchmarkData.json`.

---

## Verdict

The hop is genuinely eliminable and would genuinely save ~0.65 ms — but the
saving is **imperceptible** (a tenth of a frame, dwarfed by the foreign app's
~16 ms cold start), and a direct path has a real price: it would drop
`recordAppLaunchUseCase`, freezing the drawer's usage-based ordering. That
trade-off, plus the Activity-context and event-bus-uniformity reasons, is
documented on `AppManagementDelegate.onAppClicked`.

- **Do NOT** switch `@MainDispatcher` to `Dispatchers.Main.immediate`.
- **Do NOT** duplicate `launchSafe` into a `launchSafeImmediate`.
- **Do NOT** make the launch path synchronous / bypass the event bus.
- **Do NOT** optimise `popBackStack`.
- **KEEP** the Baseline Profile — the cold-start gain is structural.

**Re-open the hop question only if** a custom launch animation is added (it needs
the tapped view's source bounds captured synchronously in the touch frame, which
flips the trade-off — see the `onAppClicked` KDoc), the event bus is removed, or
on a substantially different device.

---

*Measured 2026-08-25 on a Galaxy A17 5G (`SM-A176B`, Android 16), build
`0.99.198` (versionCode 218), release. §3 cold-start data carried forward from
the 2026-08-24 A17 run (build `0.99.198`). Numbers are device- and
build-specific; re-measure after a hot-path change. Historical Pixel 9a / Galaxy
A36 hop distributions are archived in
[`../history/APP-START-PERFORMANCE.md`](../history/APP-START-PERFORMANCE.md).*
