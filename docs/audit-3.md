# Audit 3 — `nyx` launcher (second pass)

**Date:** 2026-09-28
**Base:** `main` @ `cd391a3` (after all audit-2 fixes + the span-scaffolding removal)
**Lens (deliberately different from audit-1/2):**

1. **Regression on the audit-2 fixes** — the N1–N16 work + span removal introduced a
   lot of new code (`dockDropAt`, `selectAppShortcuts`, `startShortcutSafe`, the
   `styleReady` gate, `drainIconRepaints`, `CappedInputStream`,
   `DrawerFoldersTransition.sanitize`, `runResetStep`, import key-validation, the
   regridder `pages` term, the conservation net). Did any fix introduce a new bug?
2. **Surfaces audit-2 covered only lightly** — the wallpaper edit engine, the drag
   engine / touch dispatch, the drawer (search / auto-launch / folder overlay),
   settings / SAF / app-usage, and cross-cutting lifecycle / leaks / DI.

## Method

Multi-agent, all Opus 4.8. **6 review agents** by the lenses above, then **3 adversarial
verifiers** on the actionable findings (each instructed to *refute*).

**Excluded** (already settled): audit-1 shared installed-apps in `:core`/`:common-*`;
the audit-2 findings themselves (only a *broken fix* counts here); `ACCEPTED_LIMITATIONS.md`
/ `KNOWN_ISSUES.md` / `KNOWN_QUIRKS.md` items.

## Verdict summary

**Headline: two of the audit-2 fixes carried a regression, and the wallpaper edit engine
has one real interleaving bug.** Everything else is low / latent / cosmetic or verified
sound. No high-severity defect; no data loss anywhere.

| ID | Sev (verified) | Finding | Verdict |
|----|----------------|---------|---------|
| A3-01 | **low-med** | `DrawerFoldersTransition.sanitize` (audit-2 N10) claims members before the <2-drop, so a dropped sub-2 folder strips a shared member from a later valid folder → destroys a folder the read projection would keep. **Regression vs pre-N10.** | CONFIRMED |
| A3-02 | **med (→med-low)** | Wallpaper **commit** during an in-flight async layer-add appends an unpreviewed layer *after* the session ends and persists it (`onCommitEditMode` doesn't bump `editRollbackGeneration`, unlike cancel). | CONFIRMED |
| A3-03 | **low** | `IconLoaderImpl.styleReady` (audit-2 N4) has no fallback completion, so a non-`IOException` flow death before first emission hangs ALL icon loading forever (soft→hard). Trigger largely theoretical (`CorruptionException ⊂ IOException` is caught). | PARTIALLY-CONFIRMED |
| A3-04 | **low-med** | Open **drawer** folder overlay doesn't live-reconcile members: a member uninstalled while the overlay is open stays as a phantom tile; tap → "launch failed" toast (home folders DO live-reconcile — deliberate drawer opt-out). | CONFIRMED |
| A3-05 | **low** | Wallpaper blobs extracted during import before the manifest is validated → orphaned on an aborted/partial import. **Mitigated:** the startup `reclaimOrphans`/`gcOrphans` (60 s cutoff) reclaims them; transient/self-healing. | CONFIRMED (mitigated) |
| A3-06 | **low** | Failed export leaves a partial/corrupt `.zip` at the SAF destination (no truncate/delete on failure). | CONFIRMED |
| A3-07 | **low** | Calendar toggle keeps showing ON after `READ_CALENDAR` is revoked externally (no re-validate on resume, unlike notification-dots). Event rendering is fail-closed in shared code → cosmetic. | CONFIRMED |
| A3-08 | **low** | First keystroke can't auto-launch after a no-search drawer use (`SearchQueryChangeTracker` reset without re-seed on reopen). Direction-safe (only suppresses, never spurious). | CONFIRMED |
| A3-09 | **low/latent** | Drag: `gesturesEnabled=false` mid-drag strands the engine (only settable via edit mode, unreachable during a drag); `ACTION_UP` commits at pointer index 0 (correct by invariant, fragile); DOWN-less hand-off of a residual non-active pointer (root in `:common-ui`); drag ImageView bitmap not recycled (GC'd). | CONFIRMED (latent/minor) |
| A3-10 | **low** | Wallpaper `onEnterEditMode` has no re-entry guard (second entry corrupts the rollback snapshot). Practically blocked (sheet unreachable while overlay up). | CONFIRMED (blocked) |
| A3-11 | **info** | Usage-sort tie-break nondeterministic for identically-named apps; clock minute-tick + notification-dot recompute run backgrounded (cheap). | CONFIRMED (not bugs) |

## Resolution log (2026-09-29)

Every finding is addressed: A3-01..A3-10 fixed, A3-11 no action (info, not a bug). A
post-merge regression pass over the A3-01..03 fixes (all callers read, full
`:nyx:domain`/`:nyx:data`/`:nyx:app` suites + `checkConventions`/`checkRule13` green) found
no regression.

| ID | Disposition | Commit(s) |
|----|-------------|-----------|
| A3-01 | **Fixed** — `sanitize` commits a folder's claim only once it survives (≥ 2 members); a dropped folder claims nothing. Sole caller: backup restore. | `192aafa` |
| A3-02 | **Fixed** — `onCommitEditMode` bumps `editRollbackGeneration`, so an add whose copy is still in flight at Save discards its copied file. Deliberate semantic: a Save pressed before a large pick finishes copying drops that layer (commit persists exactly the previewed state). | `efe06b0` |
| A3-03 | **Fixed** — `.onCompletion { styleReady.complete(Unit) }` on the style collector: a dead collector degrades to the COLOR seed instead of hanging `bitmap()`. Normal path unchanged. | `a8717d2` |
| A3-04 | **Fixed (live-reconcile chosen over accept)** — an open drawer-folder overlay reconciles against `installedKeys` with the projection's rule (`drawerFolderLiveMembers`): uninstalled members drop out (not greyed), reinstall restores them, < 2 live closes the overlay; a tap on a stale, already-dissolved drawer tile refreshes the drawer instead of opening. Persisted membership untouched. | `ee4d37a` |
| A3-05 | **Fixed** — import deletes every extracted blob the saved wallpaper state does not reference, on all paths (`finally`); blobs of a wallpaper already saved survive a later failure. The startup orphan sweep stays as the backstop. Follow-up: the blobs are claimed BEFORE the wallpaper save (a save interrupted after DataStore committed must not delete referenced files). | `97cec3a`, `de91eca` |
| A3-06 | **Fixed** — `writeOrDiscard`: a failed/thrown/cancelled export deletes its `CreateDocument` target (`DocumentsContract.deleteDocument`); a discard failure is logged, never masks the result. | `ec005de` |
| A3-07 | **Fixed (summary, not auto-off)** — mirrors notification dots: the calendar toggle's summary shows "access not granted" when READ_CALENDAR is missing (re-checked on resume + pref change). The pref is kept, so a re-grant restores events without re-toggling. | `7905739` |
| A3-08 | **Fixed** — the drawer no longer resets `SearchQueryChangeTracker` on hide (Kolibri parity; reset only in `onDestroyView`), so the first keystroke after reopen may auto-launch. No new test: fragment-level (overlay lifecycle); the tracker contract is covered in `:common-ui`. | `ced6510` |
| A3-09 | **Fixed (structural) / no action** — disabling `gesturesEnabled` now cancels a live drag and disarms a pending long-press; the `ACTION_UP` index-0 invariant is commented. No action: the DOWN-less residual-pointer hand-off (`:common-ui`) and the unrecycled drag bitmap (GC'd). | `a0d9436` |
| A3-10 | **Fixed** — `onEnterEditMode` re-entry guard (no-op while a session is live). | `237d750` |
| A3-11 | **No action** — info only (nondeterministic usage tie-break for identical names; cheap backgrounded recompute). | — |

## Actionable findings (detail)

### A3-01 — `DrawerFoldersTransition.sanitize` claims before dropping · CONFIRMED (low-med)

**File:** `nyx/domain/.../home/transition/DrawerFoldersTransition.kt` (`sanitize`).
`val members = folder.members.filter { claimed.add(it) }` runs the shared-set claim for
**every** folder, then `if (members.size < 2) null` drops it — but `mapNotNull` can't
un-claim. Trace `[f1=["a"], f2=["a","b"]]`: f1 claims "a" then is dropped (size 1); f2 has
"a" stripped → ["b"] → also dropped → **zero folders**. The read projection
(`GetDrawerContentUseCase.projectDrawerContent`) does per-folder installed/hidden filter
then `size>=2` with **no cross-folder dedup**, so it would keep `f2=["a","b"]`. So sanitize
is strictly more destructive than the projection it claims to mirror, and worse than
pre-N10 — for exactly the crafted/cross-device blobs it exists to repair. No app loss
(apps remain as loose drawer entries); order-dependent (invalid folder must precede a
valid one sharing a member). **Fix:** claim only among surviving (≥2-member) folders —
partition/filter `size>=2` before populating `claimed`. Untested (both existing tests use
a *surviving* claiming folder).

### A3-02 — Wallpaper commit mid-async-add persists an unpreviewed layer · CONFIRMED (med→med-low)

**File:** `nyx/app/.../home/wallpaper/NyxWallpaperEditCoordinator.kt` (`onAddLayer` guard vs
`onCommitEditMode`). Only `onCancelEditMode` bumps `editRollbackGeneration`; `onCommitEditMode`
does not, and the resuming-add guard is keyed **only** on the generation. So: enter edit →
Add → pick image → `copyToInternal` (suspending content:// copy) starts → user taps Save →
`onCommitEditMode` ends the session → copy finishes → guard sees unchanged generation → no
discard → appends `newLayer` to the committed state and calls `persist` → a layer the user
never saw at Save is persisted. Not data loss, user-recoverable (remove the layer), narrow
race, but violates "commit persists exactly what the preview showed". Untested (tests
`advanceUntilIdle` right after `onAddLayer`). **Fix:** bump the generation in
`onCommitEditMode` too (or abort the resuming add when `!isEditMode`).

### A3-03 — `styleReady` gate has no fallback completion · PARTIALLY-CONFIRMED (low)

**File:** `nyx/data/.../data/icon/IconLoaderImpl.kt`. `styleReady` is completed only inside
the `iconStyle()` collector's `onEach`; there is no `.catch`/`.onCompletion`/timeout. If the
flow dies before its first emission with a non-`IOException`, `styleReady` never completes and
every `bitmap()` awaits forever (icons blank until process restart) — the N4 gate turns the
pre-N4 "degraded but working under COLOR" into a hard outage. **But** `readFlowFailOpen`
catches `IOException`, and `CorruptionException ⊂ IOException`, so the realistic corruption/IO
cases recover; only a deterministic wiring misconfig yields a non-`IOException` (would fire in
dev/test). Low. **Fix (cheap hardening):** `.onCompletion { styleReady.complete(Unit) }` on the
collector — idempotent, restores degraded-but-working, keeps N4's normal-path guarantee.

### A3-04 — Open drawer folder overlay doesn't live-reconcile · CONFIRMED (low-med)

**File:** `MainActivity.kt` (folder-open wiring) + `FolderMemberAdapter`. Home folders get
`updateInstalled(...)` live; drawer folders are opened with a static member snapshot and
`installed = emptySet()` and are deliberately excluded from the live-reconcile collector
(`openFolderId != null`). A member uninstalled while the overlay is open stays as a phantom
tile (no greyed affordance since `installed` is empty); tap → `ComponentGone` → toast. No
crash, persistence untouched. **Decision needed:** live-reconcile drawer folders too (feed
reconciled members / pass the installed set), or accept as a documented delta.

### Lower / judgment-call (A3-05..A3-08)

- **A3-05** (import orphan blobs): already mitigated by the startup `gcOrphans` sweep — treat
  as known/self-healing, or extract-after-validate / clean on the failure path.
- **A3-06** (partial export zip on failure): delete/truncate the SAF target on failure, or accept.
- **A3-07** (calendar toggle stale after external revoke): re-validate against the permission on
  resume (mirror the notification-dots pattern), or accept (cosmetic).
- **A3-08** (first-keystroke auto-launch suppression): re-seed `SearchQueryChangeTracker` on
  drawer reopen, or accept (direction-safe, narrow symptom).

## Verified sound / robust (checked, cleared — recorded so they aren't re-hunted)

**The audit-2 fixes that held up:** `drainIconRepaints` (take-and-clear cannot lose a
repaint), `runResetStep` (independent steps, only clears more on partial failure),
the regridder `pages` guard (no persist storm — `expectedPages` == the relocation path's
`newPages`, idempotent), N15 invalid-key drop (never drops a valid key; reconcile heals the
gaps), the span removal (zero dangling refs; footprint→single-cell arithmetic identical for
1×1), and the `dockDropAt`/`selectAppShortcuts`/`startShortcutSafe` extractions (behaviour
identical to the inline originals).

**Drag engine:** pointer-id tracking across `POINTER_UP` (id-vs-index correct), CANCEL /
lifted-non-active-pointer never leaves `isDragging` stuck (except the unreachable A3-09
case), DropZone priority + no-gap tiling, edge-advance gated on `isDragging`/home-only and
clamped, `gesturesEnabled` restored on both edit exits, no `dispatchTouchEvent` re-entrancy.
Strong test coverage (DragController/DragLayer/EdgeAdvance + 12 TAPL androidTests).

**Wallpaper (beyond A3-02/10):** cancel-mid-add discards the copied file (generation bump);
add-then-remove-same-layer deletes correctly on both commit and cancel; mirror-collector
gating prevents stale-repo clobber; bitmap cache handles a view-recycled bitmap and the
decode-vs-clear race (generation/`putIfCurrent`); FAB position has no feedback loop; ViewStub
single-inflate + listeners replaced/cleared per session; orphan reclaim respects a 60 s cutoff.

**Drawer:** auto-launch replay guard correct (replay returns false; only genuine changes
launch), debounce doesn't drop the final query, `flatMapLatest` doesn't surface stale content,
hidden-filter has no shown/hidden inversion, adapter null-out clean, vendor grouping enforces
≥2 for creation.

**Cross-cutting:** no view/context leaks (all adapters/listeners/observers/dialogs torn down;
every `@Singleton` holds only app-context/DataStore/other singletons/StateFlows — none hold an
Activity/View); DI scoping correct (stateful bindings `@Singleton`, `@UsageDataStore` isolated,
`AppsUpdateTrigger` buffered); all streams `use{}`-wrapped; no `GlobalScope`/raw
`Handler`/`Timer`. Settings/usage: SAF stream lifecycle, `registerForActivityResult` placement,
usage cap/validity-filter/scoring, factory-reset UI refresh, dev-command/ACRA gating — all sound.

## Recommended priority

1. **A3-01** — fix the `sanitize` claim-before-drop (a real regression in restore-repair) + a
   test with a dropped sub-2 folder preceding a valid folder that shares a member.
2. **A3-02** — bump the generation on commit (+ a mid-copy-then-commit test).
3. **A3-03** — the one-line `.onCompletion` hardening on the styleReady collector.
4. **A3-04** — decide: live-reconcile drawer folders, or accept the delta (document in TODO).
5. **A3-05..A3-08** — batch opportunistically or accept with a TODO note.
6. **A3-09/10/11** — latent/blocked/info; no action (A3-09's `ACTION_UP` fragility is worth a
   one-line comment at most).

_No code was changed by this audit itself; fixes are tracked in the Resolution log above._
