# Performance — Results

On-device start-up numbers for Kolibri, measured on the **Galaxy A17 5G**
(`SM-A176B`). Setup, tooling and gate rationale live in
[`PERF-BENCHMARK-SETUP`](PERF-BENCHMARK-SETUP.md).

**TL;DR**

- **The hop is free.** The launcher's own tap→dispatch latency is **0.57 ms
  median / 1.43 ms max** — an eighth of one frame. Nothing to optimise.
- **The foreign app's cold start dominates** a launch (~25 ms), and that is not
  Kolibri's to optimise.
- **The Baseline Profile is worth keeping.** It cuts Kolibri's *own* cold start
  by **~11 % (613 → 547 ms median)**, with the profiled and unprofiled **5–95 %
  bands disjoint**.
- Both regression gates **PASS** with wide headroom.

---

## 1. The hop — launcher's own launch share

`LaunchDispatchBenchmark`, release build, **40 iterations**, drawer launch at
rest. All values **ms**.

| Slice | min | median | max | what it is |
|---|---|---|---|---|
| **`launchDispatchGapMs`** | 0.404 | **0.566** | **1.434** | the hop: TAP→DISPATCH (ViewModel + `Channel`) |
| `app_launch_tap` | 0.072 | 0.096 | 0.368 | tap-handler duration |
| `app_launch_dispatch` | 13.036 | 25.045 | 44.009 | dispatch section (contains the foreign binder) |
| `app_launch_startMainActivity` | 12.761 | 24.702 | 43.658 | the `startMainActivity` binder call |

**The hop (the only value that could regress invisibly) is sub-frame:**

| | ms | ×90 Hz frame (11.11 ms) | ×60 Hz frame (16.67 ms) |
|---|---|---|---|
| hop median | 0.566 | 0.051 | 0.034 |
| hop **max** | 1.434 | **0.129** | **0.086** |

The worst single launch across 40 iterations is **~1/8 of one 90 Hz frame**.

**Where a launch's time actually goes** (medians): the launcher's *own*
synchronous share is `tap` (0.10) + the hop (0.57) + the dispatch work beyond the
binder (`dispatch` − `startMainActivity` ≈ 25.05 − 24.70 ≈ **0.35**) ≈ **~1.0 ms
total** — comfortably inside one frame. The remaining **~25 ms** is the
`startMainActivity` binder forking the *foreign* app's process (§2).

## 2. The dominant cost is the foreign app's cold start

The `startMainActivity` binder blocks ~**25 ms median** (up to ~44 ms) while the
target app's process is created — visible above as the near-equal
`app_launch_dispatch` and `app_launch_startMainActivity` sections. That is the
foreign app's cold start, **not the launcher's to optimise**. Any saving on the
launcher's ~1.0 ms own share is dwarfed by it.

## 3. Kolibri's own cold start (TTID) + Baseline Profile

`StartupBenchmark`, **3×20 iterations per arm, pooled (60)**, `StartupMode.COLD`,
`timeToInitialDisplayMs`. `None` = no profile; `Partial` = Baseline Profile
installed (`:baselineprofile` producer, applied via `profileinstaller`). All
values **ms**.

| Arm | min | median | p95 | max | CoV |
|---|---|---|---|---|---|
| None (no profile) | 581.0 | 612.9 | 666.1 | 708.8 | 4.0 % |
| Partial (profiled) | 521.0 | **547.4** | 569.4 | 642.0 | 3.1 % |

**Median improvement: −65.4 ms (~11 %).** The headline is the near-clean
separation: the profiled **p95 (569.4)** still beats the unprofiled **p5 (587.0)**
— the 5–95 % bands are disjoint by ~18 ms. The Partial median is rock-steady
across the three runs (545.5 / 549.7 / 547.1), so this is a **structural**
separation, not an average over lucky runs. Only lone outlier iterations touch:
the raw min/max ranges overlap (Partial max 642.0 > None min 581.0) solely on a
single Partial outlier — unlike the retired, faster reference unit whose ranges
were fully disjoint (27 ms), this slower unit's tails just graze. No significance
test is needed — the working distributions do not overlap.

## 3b. Kolibri fully drawn (TTFD) + the favorites-ready tail

`StartupBenchmark`, same **3×20 pooled (60)** `StartupMode.COLD` run, now also
emitting `timeToFullDisplayMs` — Kolibri calls `reportFullyDrawn()` one frame
after the favorites first paint (the "ready for interaction" point). All values
**ms**; both TTID and TTFD are from the SAME 60 iterations. Numbers below are
**after** the favorites provisional-resolution was parallelized (see the delta
note); the pre-parallelization figures are kept in that note for comparison.

| Arm | metric | min | median | p95 | max | CoV |
|---|---|---|---|---|---|---|
| None (no profile) | TTID | 456.8 | 482.3 | 521.2 | 606.9 | 4.5 % |
| None (no profile) | TTFD | 632.8 | 671.3 | 718.8 | 827.2 | 5.0 % |
| Partial (profiled) | TTID | 370.2 | **413.1** | 445.6 | 458.2 | 4.5 % |
| Partial (profiled) | TTFD | 551.3 | **606.4** | 666.3 | 761.8 | 5.9 % |

**Cold start to ready ≈ 0.61 s** (Partial TTFD median 606). The **TTFD − TTID gap
is now ~193 ms** (606−413) — the PackageManager favorites-render tail. TTID is
unchanged by the favorites work (413, first frame); the gap is where the win landed.

> **Parallelization delta (the driver: `perf(favorites)`).** Before parallelizing
> the provisional favorite-label resolution, the same cool session read **Partial
> TTFD 751 / None TTFD 830**, with a **~341 ms** TTFD−TTID gap. Parallelizing the
> per-favorite `resolveLabel` (each a `withContext(IO)` + scoped PackageManager
> query, previously run sequentially) cut the gap to **~193 ms** and Partial TTFD to
> **606 (−145 ms, −19 %)** — measured with 3 seeded favorites; the win scales with
> favorite count. The effect is present in BOTH arms (None 830 → 671), confirming it
> is code, not the Baseline Profile. The sibling `perf(coldstart)` change (moving
> `getWallpaperColors` off the Main thread) showed **no** measurable TTID move
> (413 vs 410) — kept as structural hygiene, not for a number.

> **Session note — read before comparing to §3.** This run had
> `thermalThrottleSleepSeconds = 0` on every iteration (cool, idle device), and its
> Partial TTID median (413) sits **~134 ms below §3's 547** on the *same* unit. §3
> was measured in a warmer/busier state. So the **absolute** TTFD values are on the
> fast end; the robust, state-independent signals are the **None−Partial deltas** and
> the **~193 ms tail**, not the raw milliseconds. The TTFD gate (§4) is therefore NOT
> set from this session's fast numbers — it is anchored to §3's warm TTID baseline
> plus this (post-parallelization) gap, so it stays a consistent sibling of the
> 580 ms TTID gate. Re-measuring TTID and TTFD in ONE warm session would let both
> gates be re-baselined together.

## 3c. Rejected: parallelizing the full-app `loadLabel` enumeration

Tried on branch `perf/enumerate-parallel-labels` (2026-08-29, build `0.99.204`):
`InstalledAppsRepositoryImpl.processResolveInfoList` rewritten from a sequential
`for` loop to `coroutineScope { … async { Semaphore(4).withPermit { loadLabel } } }`
on `Dispatchers.IO`, to shrink the enumeration wall time. Measured, then **rejected.**

A/B vs `main` (Perfetto `drawer_apps_enumerate`, 8 cold starts each; StartupBenchmark 20 iter):

| Metric | main | branch | Δ |
|---|---|---|---|
| `drawer_apps_enumerate` | 102 ms | 85 ms | **−17 %** |
| `favorites_first_paint` (from `bindApplication`) | 93 ms | 94 ms | ~0 (+1 ms) |
| `reportFullyDrawn` / TTFD | — | — | no reproducible move (StartupBench +48 ms vs manual capture −16 ms = noise) |

**Why rejected.** The −17 % is real but lands on a **user-invisible** metric: the
livelabel provisional-favorites path (§3b) already decouples first paint from the
full enumeration, so `favorites_first_paint` is unchanged and TTFD shows no
reproducible movement. It is a **drawer-open / authoritative-reconciliation lever,
not a cold-start lever** — not worth adding coroutine/`Semaphore`/async-trace
complexity to a hot path. The A17 carries only ~50 launcher apps (not the assumed
~150) + cap 4 + the unparallelizable `queryIntentActivities`/sort (Amdahl) → limited
headroom.

Durable facts (independent of the decision): on this path `ResolveInfo.loadLabel` is
**in-process** APK-resource work (arsc parse + I/O via `getResourcesForApplication`,
since `queryIntentActivities` populates `applicationInfo`), **NOT** a binder IPC — so
the cost is CPU + flash I/O bound by core count, not the system_server binder pool;
and concurrent `loadLabel` across threads is thread-safe (`ResourcesManager` /
`AssetManager` locks). **Do not re-attempt** without a device that actually carries
~150 apps and a metric tied to drawer-open, not TTID/TTFD.

## 4. Gate results

| Gate | Metric | Threshold | A17 value | Result |
|---|---|---|---|---|
| `verifyLaunchBenchmark` | `launchDispatchGapMs` max | 4.0 ms | **1.434 ms** | **PASS** (« 4.0) |
| `verifyStartupBenchmark` | `startupBaselineProfile` `timeToInitialDisplayMs` median | 580 ms | **547.4 ms** (§3) / 413.1 (§3b idle session) | **PASS** (« 580) |
| `verifyStartupFullyDrawnBenchmark` | `startupBaselineProfile` `timeToFullDisplayMs` median | 790 ms | **606.4 ms** (§3b, post-parallelization) | **PASS** (« 790) |

> When reading `verifyLaunchBenchmark`'s console line, note the stale-JSON
> gotcha in [`PERF-BENCHMARK-SETUP`](PERF-BENCHMARK-SETUP.md#the-gates): it
> aggregates the worst `maximum` across *all* JSONs under `build`, so a leftover
> file from another device can inflate the reported worst. The 1.434 ms above is
> read straight from the A17's own `benchmarkData.json`.

---

## Verdict

The hop is genuinely eliminable and would genuinely save ~0.57 ms — but the
saving is **imperceptible** (an eighth of a frame, dwarfed by the foreign app's
~25 ms cold start), and a direct path has a real price: it would drop
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

*Measured 2026-08-28 on a Galaxy A17 5G (`SM-A176B`, `s5e8535`, Android 16), build
`0.99.202` (versionCode 222), release, on the current reference unit `R5GL51D5VHZ`.
The earlier unit `R5GL71YWEPH` — on which the original ~378 ms / ~0.65 ms numbers
were taken — was retired; this unit is ~170 ms slower on cold start, verified as
**device, not code** by re-running build `0.99.198` on it (identical 546 / 607 ms
Partial / None). §3 cold-start is pooled from three isolated 20-iteration runs.
Numbers are device- and build-specific; re-measure after a hot-path change.
Historical Pixel 9a / Galaxy A36 hop distributions — and the retired-unit A17
numbers — are archived in
[`../history/APP-START-PERFORMANCE.md`](../history/APP-START-PERFORMANCE.md).*
