# CLAUDE.md — Nyx Launcher

Project conventions for **Nyx** (grid/dock launcher with an app drawer, home
edit + drag engine, and a wallpaper edit session). Loaded automatically when
working under `nyx/`. Stack baseline / git / release / test *philosophy* come
from the global `~/.claude/CLAUDE.md`; this file carries only Nyx specifics and
the delta against the family rules.

---

## Shared rules — Nyx reuses Kolibri's, it does not fork them

The **battle-tested conventions and their enforcement live once in Kolibri** and
Nyx reuses them wherever they apply. Do not copy a rule's text or a detector
into Nyx — extend the shared source.

- **Rule reference**: `kolibri/CLAUDE.md` (project rules) and
  `kolibri/app/src/test/CLAUDE.md` + `kolibri/app/src/test/.../TESTING_CONVENTIONS.kt`
  (test conventions) are the canonical statements. They are Kolibri-path-scoped,
  so they are the *reference* for Nyx, not auto-loaded here — read them when a
  rule question comes up.
- **Enforcement**: `./gradlew :nyx:app:checkConventions` and
  `:nyx:app:checkRule13`. Nyx has its **own orchestrator**
  (`nyx/tools/check-conventions.sh`) that runs Kolibri's detectors
  (`kolibri/tools/*.awk` + the two generalized `*.sh`) against Nyx's sources,
  with Nyx's own scan roots / positive lists. When Kolibri adds or fixes a
  detector, Nyx inherits it automatically — only the roots and opt-in lists in
  Nyx's orchestrator are Nyx-owned.

### Checks Nyx runs (and the ones it deliberately skips)

Runs (product-neutral or Nyx already follows it): Rule 9 (Timber intent tag),
Rule 12 (`Timber.Forest`), Toast routing (`showToastSafe`), `Flow.catch`
rethrow, unbuffered `MutableSharedFlow`, `registerForActivityResult` placement,
RecyclerView adapter null-out, localization parity, contract-test triple
(Rule 2), plus the opt-in positive lists (Rule 11 annotation, cancellation
rethrow, Exception breadth — empty for now, grow as files are reviewed; the
`MainActivity` `runCatching` sites are the first review candidate).

Deliberately **skipped** (Kolibri-specific — would only mis-fire on Nyx), with
the reason encoded in the orchestrator header:

- **`Manager`-naming in `data/`** — Nyx uses `*Manager` names on purpose for its
  own `nyx/data/` classes (`NyxBackupManager`, `NyxResetManager`). This is the
  one naming delta from Kolibri; don't "fix" it to `*RepositoryImpl`.
  (`WallpaperFileManager` also carries the name but lives in the shared
  `:common-data`, not `nyx/data/`, and Kolibri already exempts it as a
  file-helper — so it is neither Nyx-owned nor in this detector's scope.)
- **`purgeRepository()` completeness** — Nyx has no `purgeRepository()`.
- **Settings-store keep-list (`OwnsSettingsStoreKeys` / `@IntoSet`)** — hangs on
  Kolibri's storage-cleanup feature, which Nyx does not have.
- **stale-replay hot-flow point-read** — deferred (no Nyx whitelist yet).

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
