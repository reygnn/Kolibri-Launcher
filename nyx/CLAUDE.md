# CLAUDE.md — Nyx Launcher

Project conventions for **Nyx** (grid/dock launcher with an app drawer, home
edit + drag engine, and a wallpaper edit session). Loaded automatically when
working under `nyx/`. The monorepo-wide rules come from the root `../CLAUDE.md`; this file carries
only Nyx specifics.

---

## Shared rules — one set for both apps

The product-neutral rules and their enforcement live once in the monorepo root
`../CLAUDE.md` (loaded automatically alongside this file). Do not copy a rule's text
or a detector into Nyx — extend the shared source.

- **Test conventions in depth**: `TESTING_CONVENTIONS.kt` in the `:core` test fixtures
  (`core/src/testFixtures/.../core/testing/`, next to `MainDispatcherRule` and
  `recordEmissions`) — valid for every module, Nyx included. The longer prose
  reference `kolibri/app/src/test/CLAUDE.md` still sits under Kolibri's path; its
  path is historical, not a scope.
- **Enforcement**: `./gradlew :nyx:app:checkConventions` and
  `:nyx:app:checkRule13`. There is **one orchestrator for both apps**,
  `tools/check-conventions.sh --app nyx` (SPEC_NYX_REWRITE A3); `nyx/tools/check-conventions.sh`
  is a one-line wrapper. Nyx owns only DATA in `tools/conventions/nyx.conf`: scan roots,
  positive lists, and one decision per registered check (RUN / TASK / "SKIP: reason").
  A parity gate fails the build (exit 2) when a detector is added without a decision
  for both apps, or a SKIP carries no reason.

### Checks Nyx runs (and the ones still skipped)

The single source of truth is `tools/conventions/nyx.conf`: one decision per
registered check — `RUN`, `TASK` (own Gradle task, rides along with
`checkConventions`) or `"SKIP: reason"`. Every SKIP names the spec step that turns
it into RUN; the parity gate fails the build if a check is left undecided.

- **RUN**: Rule 9, Rule 12, Toast routing, Flow.catch rethrow, unbuffered
  `MutableSharedFlow`, `registerForActivityResult` placement, adapter null-out,
  localization parity, contract triple (Rule 2), the positive lists (Rule 11,
  cancellation rethrow, Exception breadth, init-order), A7/A12 test conventions,
  A8 mirror comments (ratchet), A11 `WhileSubscribed` literals, A13 hard-coded
  dispatchers (ratchet; inject `@IoDispatcher` / `@DefaultDispatcher` / `@MainDispatcher`).
- **TASK**: Rule 13 (`checkRule13`), stale-replay gate (`checkStaleReplayRead`,
  dormant: Nyx's repositories are cold flows; the UI StateFlows read via `.value`
  are collected under `repeatOnLifecycle(STARTED)` in the same component).
- **RUN since 2b-4c**: `Manager`-naming in `data/` (`NyxBackupManager` became
  `BackupRepositoryImpl`, `NyxResetManager` became `ResetRepositoryImpl`; do not add new
  `*Manager` classes) and `purgeRepository()` completeness. The purge gate scans
  `*RepositoryImpl.kt` only: `NyxWallpaperDisplaySettings` and `NyxFabPositionStore` are no
  repositories and are covered by the `ResetCompletenessContract` alone — a new store of that
  kind is not protected by the gate.
- **SKIP until the named phase** (SPEC_NYX_REWRITE):
  - Settings-store keep-list — Nyx gets storage cleanup in Phase 4b (E5b).

---

## Architecture (Nyx-specific)

- **Views + RecyclerView / ViewPager2**, Hilt, DataStore, coroutines/Flows.
  Three Gradle modules: `:nyx:app` / `:nyx:domain` (pure-Kotlin) / `:nyx:data`.
- Shares `:core`, `:common-ui`, `:common-data`, `:feature-crashreporting` with
  Kolibri (e.g. `showToastSafe`, `ClockDelegate`, `WallpaperViewBinder`,
  `DrawerOverlayController`, `TimberWrapper`, the gesture stack, ACRA consent).
- Repositories follow Rule 1/2: interface in `:nyx:domain/.../repository/`,
  `FakeXyz` in `testFixtures`, `XyzRepositoryImpl` in `:nyx:data`; contract
  triple (or an ADR marker) per repo. `InstalledAppsRepository` carries a
  `NO CONTRACT TEST (ADR)` marker (system-API wrapper).
- `MainActivity` is the HOME activity (grid pager + dock + drawer overlay +
  drag engine + wallpaper edit). Keep testable logic in use cases / the
  `HomeViewModel`, per the shared Rule 10.
- **Instrumented device tests go through the TAPL-lite facade** — shared
  `:common-testing-android` (`BasePage`/`awaitUntil`/`longPressDrag`/`tap`/
  `probeFloat`) + Nyx's own `androidTest/.../tapl/` pages (`NyxLauncher`,
  `NyxHome`, `NyxDrawer`, `NyxFolder`). Grid reorder / cross-page / folders /
  dock / drawer→home are already covered; see `docs/specs/TAPL_LITE.md` for the
  pattern, coverage and the Nyx drag-engine lessons.

---

## What this file is NOT

Not the stack baseline, git workflow, release/signing, or test philosophy —
those are the global `~/.claude/CLAUDE.md`. Not a re-statement of Kolibri's
rules — those are referenced above, not duplicated (duplication drifts). Not the
place for intentional UX/behavioural limitations — those live in
`ACCEPTED_LIMITATIONS.md` (sibling of Kolibri's; check it before "fixing" a
perceived UX bug in an area it covers, e.g. drag-only home/drawer organisation
not being TalkBack-operable).
