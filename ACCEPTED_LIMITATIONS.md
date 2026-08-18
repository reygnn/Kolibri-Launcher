# Accepted Limitations

This document tracks known UX or behavioural limitations that are
*intentional consequences* of architectural decisions. They are not
bugs to be fixed; they are costs we have priced in. The purpose of
this file is to remind future-us why we accepted them, so we don't
re-litigate the decision every time the limitation is noticed.

---

## 1. AppDrawer AUTO-mode classifier ignores layer composition

- **Status:** 🟢 Resolved again (v4.3) via the in-memory composite-luminance signal; 🟡 two *transient* residuals (see 1a / 1b)
- **Frequency:** Only the two transient windows below; steady state is fully composite-classified
- **Affected:** Multi-layer Kolibri-internal wallpapers in AUTO mode, only during a cold-start / a wallpaper change

### RESOLVED AGAIN (2026-08-18, v4.3) — composite luminance via a clean IoC signal

v4 (in-memory composite, no disk) had briefly **re-opened** this (see the history note
at the bottom): the composite lived only in an `:app` cache the `:domain` classifier
can't read, so it fell back to the `layers[0]` heuristic. v4.3 restores composite
classification **without disk and without a layering breach**: the composite warm
(`WallpaperDelegate`) samples the SOFTWARE composite's luminance during the flatten and
pushes it into a `CompositeLuminanceSignal` — a `:domain`/`core` port, fed by `:app`,
read by `ClassifyWallpaperUseCase`, exactly mirroring the existing
`SystemWallpaperColorsSignal` (dependency rule `:app → :domain` intact; the classifier
`combine`s it as a third signal). The pixel gate (coverage + median WCAG) is the same
code as the old file path, so the classification result is identical to Option D — just
sourced from the in-memory composite instead of an on-disk file.

Two **transient** residuals are accepted (both self-heal, neither is a persisted lie):

- **1a. Cold-start gap.** Right after process start (or an app-data wipe) the in-memory
  cache is empty and no warm has run yet, so `CompositeLuminanceSignal` has no value. The
  classifier uses the `layers[0]` bottom-layer heuristic (below) until the first composite
  warm emits — ~1 s after home is visible — then corrects. **Once per process life.** (The
  old on-disk path had the same gap until its file decode completed.)

- **1b. Eventual consistency on a wallpaper change.** On an edit / restore that changes
  the layer set, the signal still holds the *previous* composite's luminance until the new
  warm completes (~the flatten duration, sub-second). The AUTO surface may reflect the old
  classification for that window, then self-corrects when the new warm emits. Rotate/fold
  is exempt — luminance is resolution-independent, so a metrics-only re-flatten produces the
  same value. This is the price of not verifying the luminance against the exact current
  `compositeKey` (the `:domain` classifier can't compute the key — no display metrics — so
  it trusts "latest warm = current wallpaper", which the key-gated warm guarantees except in
  this brief window).

Both are strictly narrower and less harmful than the pre-v4.3 state (a *permanent*
possible mis-classification via `layers[0]`), and both are dominated by the manual
LIGHT/DARK override.

### (Historical) RE-OPENED (2026-08-18) — composite classification removed with the on-disk composite (v4)

v4 deleted the on-disk composite entirely (in-memory `:app` cache only) after three review
rounds showed the on-disk lifecycle was not cleanly solvable. That temporarily reverted
composite classification to the `layers[0]` heuristic for every multi-layer wallpaper — the
gap that v4.3 (above) closed with the signal.

### (Historical) UPDATE — resolved for composited wallpapers (Option D)

The re-evaluation trigger below predicted this: "the wallpaper editor grows a
'preview composite as bitmap' step … the classifier could reuse the result for
free." That is exactly Option D (`WALLPAPER_DRAWER_HOME_REBUILD_SPEC`): a multi-
layer wallpaper is flattened to a single composite bitmap on edit-commit (and
lazily for existing/restored wallpapers). `ClassifyWallpaperUseCase.pickDominantUri`
now classifies **the composite** when one exists — the resolved composition of all
layers + blend + alpha — so both soft spots below are handled. The composite is
sampled SOFTWARE (256²) by `WallpaperBitmapLuminance`, and its coverage gate still
routes a mostly-transparent composite to the system signal (correct — the system
wallpaper shows through). The historical description below still applies to the
**composite-less** fall-back (a pre-Option-D state, a just-restored backup, or the
window before the lazy backfill runs).

### Explanation

`ClassifyWallpaperUseCase` decides between LIGHT and DARK by
inspecting *one* signal at a time, in priority order:

1. Kolibri-internal wallpaper, but only `layers[0]` (the bottom-
   most layer in render order) — and only when it's "opaque
   enough to dominate perception". Two cooperating gates make that
   call: a **layer-level** alpha gate (`WallpaperLayerState.alpha`
   ≥ 0.8 and Normal blend) and a **pixel-level** coverage gate
   (≥ 50% of pixels with alpha ≥ 80% inside the bitmap itself).
   Either gate failing routes the classifier to the next signal.
2. System-wallpaper `colorHints` (`HINT_SUPPORTS_DARK_TEXT`).
3. Fallback: DARK.

The classifier never composites multiple Kolibri layers together
to estimate the user-perceived background. When `layers[0]` fails
either dominance gate, the classifier punts to the system
signal — even if a *higher* layer (e.g. `layers[1]`) is fully
opaque and dominates the actual composition.

### When the heuristic punts (the two known soft spots)

- **Transparent `layers[0]` + opaque `layers[1]+`.** A user with
  `[transparent grey detail, opaque blue background]` would have
  the blue dominate visually. The classifier sees `layers[0].alpha
  < 0.8`, falls through to the system signal, and may pick the
  wrong surface. (In practice the typical Kolibri-multi-layer
  setup is "opaque background at index 0, detail overlays above"
  — the inverse — so this case is uncommon.)
- **Opaque `layers[0]` with non-Normal blend.** An opaque layer
  with e.g. MULTIPLY at alpha 1.0 over a bright underlying image
  visually darkens the result, but the classifier punts to the
  system signal rather than approximate the blend. Documented in
  the use-case KDoc.

### Why we don't fix it further

Compositing layers properly requires loading every layer's
bitmap, allocating an N×M ARGB buffer, running the blend ops in
order, and sampling luminance from the result. That's a real
mini-render pipeline. The cost-vs.-coverage trade is unfavourable
right now: the alpha-gate-on-`layers[0]` heuristic is correct for
the common shape (opaque bottom + detail overlays + the
single-layer case), and shipping a partially-correct compositor
would only paper over edge cases that the user can already work
around by picking LIGHT or DARK manually (the explicit overrides
exist for exactly this).

### Trigger for re-evaluation

Reopen this entry if any of the following changes:

- A user reports that AUTO consistently picks the wrong surface
  for a multi-layer wallpaper they actually use, *and* the
  manual LIGHT/DARK override is unsatisfactory (e.g., they
  alternate between wallpapers that legitimately need different
  surfaces).
- The wallpaper editor grows a "preview composite as bitmap"
  step that already runs the render pipeline — at that point the
  classifier could reuse the result for free.
- Multi-layer alpha conventions in the codebase shift (e.g.,
  `layers[0]` becomes the *top* layer instead of the bottom by
  some refactor) — the alpha-gate semantics would need to follow.

---

## 2. Clipboard read pulls a URI-backed clip in whole

- **Status:** 🟡 Intentional / Documented
- **Frequency:** Rare — needs a multi-megabyte, URI-backed `text/*` clip
- **Affected:** `MainActivity.readClipboard` (double-tap clipboard action)

### Explanation

`readClipboard` calls `ClipData.Item.coerceToText`, and
`ClipboardActionResolver.resolve` then discards everything past its
8192-character cap. For the overwhelmingly common case that is free:
`coerceToText` returns `item.text` immediately and no stream is ever
opened. But when the clip is URI-backed and the provider serves it as
`text/*`, the framework opens a typed asset FD and reads it to EOF into
an unbounded `StringBuilder` — so copying a 20 MB log file in a file
manager and then double-tapping allocates far more than the ~8 KB that
survives, in the HOME process of all places.

### Why it is accepted

The obvious fix does not work. Pre-checking the MIME type and taking
`item.text` for `text/plain` changes nothing, because that is already
the fast path *inside* `coerceToText`; the allocating branch is exactly
the fallback such a check would still route to. A real fix means opening
`openTypedAssetFileDescriptor` ourselves and reading a bounded number of
characters — i.e. re-implementing framework behaviour, including the
`htmlText` and Intent-item cases `coerceToText` also covers, in the one
process that must never crash.

Against that: the read already runs on `Dispatchers.IO`, so there is no
ANR exposure, only allocation. The trigger requires a clip that is
simultaneously URI-backed, several megabytes, and text-typed — which is
constructible but not something a launcher user stumbles into.

### Trigger for re-evaluation

Reopen this entry if any of the following changes:

- An OOM or a slow double-tap is actually observed via ACRA with a large
  clipboard item in the report.
- `ClipData.Item` gains a bounded read in a future Android release, at
  which point the fix becomes a one-liner.
- The clipboard feature grows a second consumer that needs the full text
  rather than a capped preview — the cap is what makes the whole read
  wasteful today.

---

## 3. RecoveryWatchdog does not re-arm after a loop-guard-suppressed trip

- **Status:** 🟡 Intentional / Documented
- **Frequency:** Very rare — needs the loop-guard threshold already reached
  *and* a later, separate recoverable stall on the surviving process
- **Affected:** `RecoveryWatchdog.run()` (crashreporting/resilience); AUDIT-11 V1

### Explanation

`run()` returns unconditionally after `onStallDetected()`. In the normal
*kill* path that is correct — the process is terminating (`killProcess` +
`exitProcess(10)`), and a fresh watchdog starts in the restarted process. The
`return` only actually executes in the *suppressed* path: once the loop-guard
has seen `maxKills` self-kills within `windowMs` (default 3 / 60 s), the next
trip captures the stall but does **not** kill (`shouldSuppressKill()` → true),
so the process survives — and `run()` then returns, ending the daemon thread.

Consequence: that surviving process (potentially the long-lived HOME process)
runs **watchdog-less** for the rest of its life. It never re-arms, even after
the 60 s kill window ages out. A genuine *later* main-thread hang in that
process gets no fast (~8 s) self-recovery and falls back to the system's slower
~10 s ANR path.

### Why it is accepted

- **The trigger is a narrow, already-pathological corner.** All must hold: the
  loop-guard is engaged (≥3 self-kills in 60 s — the device is already in a
  restart storm), *then* the surviving process hits a *separate* stall, *and*
  that stall would have self-recovered. And there is a fallback: post-mortem
  `AnrReporter` still catches hard ANRs on the next launch, and the system ANR
  path still works. The user-visible loss is "slightly slower recovery in an
  already-degraded state," not a lost crash.
- **The naive fix is worse than the gap.** Simply looping instead of returning
  would, on a *deterministic* wedge (exactly what tripped the loop-guard),
  re-capture the same stall every `timeoutMs` → an on-device report/queue flood
  (disk writes + `:acra` sender wakeups + POST attempts + backlog, per the
  G3-C probe) on a device that is already struggling. A *correct* fix must
  distinguish transient stall from persistent wedge and dedup per episode.
- **The correct fix adds state to the component that must have the least.** The
  watchdog is a raw daemon `Thread` *by design* (its KDoc: "the recovery path
  must have fewer moving parts than what it watches"; "don't modernise"). A
  recovery-gated re-arm loop with episode-dedup is exactly the kind of state
  that design deliberately avoids — added to the most safety-critical,
  least-testable path, where a mistake can reopen the kill-restart loop the
  loop-guard (C.3/G2) exists to close. Cost-vs-coverage is unfavourable.
- **It also leans the right way on flood philosophy (B3).** The rewrite deleted
  the client-side throttle and put flood control server-side (§S). "Capture
  once on suppression, then stop" is itself a mild client-side flood-suppression
  — conservative, and it keeps the on-device cost bounded without a client
  throttle to maintain.

### Trigger for re-evaluation

Reopen this entry if any of the following changes:

- Telemetry/ACRA shows real devices actually reaching loop-guard suppression
  *and* then surviving long enough to hit a second recoverable stall — i.e. the
  corner happens in the field, not just on paper.
- `RecoveryWatchdog.run()` gains a test harness (today only `onStallDetected()`
  is unit-tested), at which point a recovery-gated re-arm becomes safely
  testable and the risk half of the trade shrinks.
- A flood-hardened server (fingerprint dedup + ingestion rate-limit, §S) is in
  place **and** the on-device capture cost of a re-arm is separately bounded.
  Server hardening defuses the *server-facing* half of the flood, but not the
  on-device half (writes, `:acra` wakeups, battery, queue backlog) — so it
  lowers, but does not by itself remove, the objection to a re-arm.

---

## 4. Post-mortem ANR watermark advances while ACRA is transiently disabled

- **Status:** 🟡 Intentional / Documented
- **Frequency:** Very rare — needs a transient consent-read failure at cold
  start coinciding with a pending AEI ANR, for a consent-*granted* user
- **Affected:** `CrashReportingBootstrap.onCreate` ANR drain + `AnrReporter`
  watermark; AUDIT-11 U1 — a named trigger for the AN4 accepted-loss family

### Explanation

`onCreate`'s post-mortem ANR drain runs unconditionally, regardless of ACRA's
current enabled state. For each pending ANR it advances the watermark
(`AnrReporter` `KEY_WATERMARK`), and the delivery handler
(`Timber.e → AcraTree → handleSilentException`) is a silent no-op while ACRA is
disabled (Rule 7/C1: the delivery path swallows and never reports back).

Scenario: stored decision = Granted, but the bootstrap consent read (R1, the
`attachBaseContext` `runBlocking`) hits a *transient* DataStore `IOException` →
fail-closed to `NeverAsked` → ACRA stays disabled at `onCreate`. The drain walks
a pending post-mortem ANR, the handler swallows (ACRA off), and the watermark
advances past it. A later successful read in `MainActivity` (RC1) reaffirms
Granted and enables ACRA — but that ANR is already past the watermark and is
never sent. RC1 heals the ACRA flag; it cannot rewind the watermark.

### Why it is accepted

- **It is a specific trigger for an already-accepted outcome.** AN4
  (ACRA_FLOW.md §4.5, `AnrReporter` KDoc) already states generically: "the
  watermark advances even when the report is not persisted → that ANR is
  dropped. Accepted." U1 only names one path (transient read failure) into that
  priced-in outcome. The loss is a single post-mortem ANR — telemetry only, no
  user-facing effect, bounded by the AEI record count and server-side dedup.
- **The naive fix regresses a privacy property.** Gating the drain on
  `ACRA.isEnabled()` looks obvious, but the same "consume-while-disabled"
  mechanism is exactly what stops a *Denied → later-Granted* user's pre-consent
  ANRs from being sent after they consent: the drain advances the watermark past
  them while ACRA is off. Gate the drain and those old ANRs stay above the
  watermark, to be sent on the first post-consent launch. Losing one report
  (U1) is preferable to sending pre-consent data.
- **A correct fix is surgical and costly.** It would have to defer the drain
  *only* when the consent read was `Unavailable` — not when it was a definitive
  `Denied`/`NeverAsked` — which means un-collapsing the fail-closed bootstrap
  read (threading a `ConsentReadResult`-style tri-state into the `onCreate`
  drain decision) and coordinating with the intentional delivery-path swallow.
  That is real coupling added to the §12-ordered bootstrap for one rare item.

### Trigger for re-evaluation

Reopen this entry if any of the following changes:

- Telemetry shows this actually happening: consent-*granted* devices losing
  post-mortem ANRs to a transient cold-start read failure (distinct from the
  generic AN4 "ACRA broken" loss).
- The bootstrap consent read gains a tri-state (`Unavailable` vs `NeverAsked`)
  on the `runBlocking` path — at which point the surgical "defer only on
  `Unavailable`" fix becomes cheap and consistent with the existing
  `ConsentReadResult` pattern (Rule 11).
- The ANR drain is moved to run only under a reconciled, known-good consent
  decision (e.g. after RC1 rather than unconditionally in `onCreate`), which
  would make the ordering race moot.
