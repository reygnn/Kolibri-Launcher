# Correctness & Edge-Case Verification (v1.0 RC gate)

**Purpose.** After weeks of optimization the codebase is at the tuning floor
(see the multi-agent sweep + clean `/code-review high` on 2026-08-29): further
micro-optimization yields sub-millisecond, sweep-noise findings. The remaining
pre-`v1.0-rc1` leverage is **correctness and edge-cases**, not performance.

This document is the standing method for that check. It is deliberately *not* a
"write more tests" list — in this codebase correctness is largely
machine-enforced already, so the job is to (1) run what exists, (2) lean on the
contract harness, (3) actively hunt the bug-classes that have historically
slipped through, and (4) pin each find as a regression test at the right
altitude.

Treat the checkboxes below as an RC gate: an unchecked box is a reason not to
tag `v1.0-rc1`.

---

## 1. Run the machinery that already exists

Correctness in Kolibri is guarded by a large set of gates and report-only
scanners. Start here — this catches the known bug-classes for free.

```bash
./gradlew test checkConventions checkRule13   # full suite + all convention gates
./gradlew scanCancelCandidates                # after any coroutine-adding refactor
./gradlew scanOomCandidates                   # after new bitmap / inflate / JSON / ZIP code
./gradlew scanStaleReplayRead                 # stale hot-flow point-reads
```

- [ ] `./gradlew test` green across `:domain`, `:data`, `:app`.
- [ ] `./gradlew checkConventions` green (contract-triple, purge completeness,
      settings-store keep-list, intent-gate/Timber routing, cancellation &
      Flow.catch rethrow, unbuffered SharedFlow, OOM breadth, ActivityResult
      placement, adapter null-out, localization parity, …).
- [ ] `./gradlew checkRule13` green (no German in new comments).
- [ ] The three `scan*` report-only tasks triaged — every flagged broad catch is
      either genuinely non-suspend (leave it) or a real suspend-frame swallower
      (fix + whitelist). These find the defects that are **invisible in a diff**:
      a call that silently became `suspend` turns an untouched `catch (Throwable)`
      into a cancellation swallower.

> Why this is first: the gates encode the bugs that already bit this project
> (AUDIT-3…20). Re-deriving them by eye is wasted effort — let the linters assert
> them.

---

## 2. Lean on the contract-test triple

Every repository behaviour is pinned by its `XyzRepositoryContract` +
`FakeXyz…ContractTest` + `Xyz…ImplContractTest`. Fake-vs-impl drift goes red.

- [ ] Any repository touched this cycle still has a green triple (or a justified
      `NO CONTRACT TEST (ADR)` / `NO IMPL CONTRACT TEST (ADR)` marker).
- [ ] Impl-only failure branches (I/O error shapes not in the fake) are covered
      in the `…ImplContractTest` / `…ImplTest` — e.g. the
      `CrashReportConsentRepositoryImplTest` failure-result cases.

When a contract goes red: **the impl wins** (production truth); align the fake,
or document an intentional drift in the contract KDoc under
"NOT IN CONTRACT — intentional drifts".

---

## 3. Actively hunt the historical edge-case classes

This is the judgment part — best done **scoped to one surface at a time**, not as
a blind sweep. For each surface, walk these lenses:

### 3a. "A default that *acts*" (the AUDIT-10 lesson)
Trace every fallback / default value and ask **what it causes downstream**, not
whether it "looks safe". A default indistinguishable from a real answer *acts*:
an unreadable consent store collapsed to "not asked yet" overwrites the decision
the user already made; a write returning `Unit` on failure lets the caller assume
a save that never happened.

- [ ] Every caught-and-defaulted value: is the default distinguishable from a
      real answer where it matters? If not, return the outcome as a value
      (`ConsentReadResult` / `ConsentWriteResult` pattern), don't collapse it.

### 3b. Save-over-empty / lying-state
Every "Done"/"Save" path: **can it fire before its data has loaded?** This is the
wipe class the save-gates exist for (`OnboardingViewModel.PreselectState`,
`HiddenAppsViewModel` diff-against-initial, `SwipeActionsViewModel.isLoaded`).

- [ ] Each save path is gated so a tap in the pre-load window cannot persist an
      empty/default over real data.
- [ ] New save surfaces added this cycle carry the same gate.

### 3c. Parser / import boundaries (richest: backup + usage import)
Enumerate the adversarial inputs for every parser: empty, `null`, oversized
(> `MAX_BACKUP_SIZE_BYTES`), malformed syntax, **type-confusion** (String where a
Number/Boolean is expected), duplicate keys, snake_case↔camelCase, Unicode /
umlauts. Model to copy: `BackupRepositoryImplDoomsdayTest`,
`BackupRepositoryImplSecurityTest`, `UsageExportRepositoryImplTest` — extend
those, don't reinvent.

- [ ] Backup import: each malformed/oversized/type-confused input maps to a
      defined `ImportResult` (never a crash, never a silent partial).
- [ ] Usage import: same, mapping to `UsageImportResult`.
- [ ] OOM breadth holds: allocation boundaries catch `Throwable`, not `Exception`
      (an `OutOfMemoryError` is an `Error`).

### 3d. Coroutine lifecycle
- [ ] Cancellation **mid-operation** propagates (no `silentError`-as-crash from a
      teardown cancel); `CancellationException`-first arm on every broad catch in
      a suspend frame.
- [ ] View-teardown **during emit** does not surface as a logged error
      (`Flow.catch` arms guard the cancellation before logging).

### 3e. Lifecycle & cold-start races
- [ ] Config-change re-entry: `isInitialized` guards prevent a rotation from
      resetting in-memory selection to the persisted set (dropping uncommitted
      toggles).
- [ ] Cold entry directly into a sub-screen after process death: transient empty
      app lists are absorbed (`withTimeoutOrNull { … first { isNotEmpty() } }`),
      not rendered as a permanently-empty screen.

### 3f. Cross-check the "intentional" docs before "fixing" anything
Some odd-looking behaviour is a deliberate decision or an OEM workaround —
reverting it *is* the regression.

- [ ] `KNOWN_QUIRKS.md` (live workarounds, e.g. Samsung Calendar phantom 00:00
      alarm), `KNOWN_ISSUES.md` (framework/OEM StrictMode unfixables),
      `ACCEPTED_LIMITATIONS.md` (intentional UX limits) all cross-checked before
      touching wallpaper classification, the home/keyguard boundary, time-based
      events, or a package blocklist.

---

## 4. Pin every find as a regression test — at the right altitude

- [ ] Logic lives outside Android-runtime classes (Rule 10): the reproduction is
      a JVM test on a ViewModel / use case / helper, MockK + Turbine +
      `MainDispatcherRule` + `runTest(rule.dispatcher)`.
- [ ] A repository invariant becomes a **contract** test (both fake and impl), not
      a one-off impl test — unless it is an impl-only I/O shape.
- [ ] Robolectric only where `android.net.Uri` / `android.graphics.*` genuinely
      require it.
- [ ] Device-only truth (touch dispatch / gesture priority, real `Canvas`/`Bitmap`
      semantics, genuine OEM callbacks) goes to `androidTest/` under the value
      bar — never to duplicate what a JVM/Robolectric test already pins.

---

## 5. RC sign-off

- [ ] Sections 1–2 fully green.
- [ ] Section 3 lenses walked for every surface changed since the last tag, plus
      the four highest-risk standing surfaces: **backup import**, **usage
      import/export**, the **four save-gates**, and the **consent bootstrap
      chain**.
- [ ] Every find from section 3 is either fixed-with-regression-test or logged in
      `ACCEPTED_LIMITATIONS.md` with a re-evaluation trigger.
- [ ] `versionName` / `versionCode` bumped; baseline profile regenerated on a
      connected device **before** the RC `bundleRelease` (see
      `docs/specs/PERF-BENCHMARK-SETUP.md` — a fresh checkout otherwise ships
      library-default ART rules and a silently degraded cold-start).

---

### How this differs from an optimization sweep
An optimization sweep is broad and now low-yield. A correctness pass is
**adversarial and scoped**: "what input or timing breaks *this* surface?" Point
it at one subsystem, run the lenses, pin the finds. That is where the pre-1.0
value is.
