# Design specs

Living design documents for the major subsystems (plus a few sibling reference
notes — the ACRA flow, on-device start-up measurements, a deferred optimisation
lever). Most were the blueprints for the big rewrites; now that those shipped,
they are the authoritative **rationale and invariants** the production code
delegates to — a code comment saying "`DATASTORE_READ_SPEC` Belang A" resolves
here.

Not blueprints-to-discard: they are actively referenced from `.kt` (up to 24
references for `DATASTORE_READ_SPEC`). Crucially, every reference is by **bare
name, never a path**, so this folder can be reorganised without touching code.
Rejected / superseded design alternatives live in [`../history/`](../history/),
not here.

- [`ACRA_FLOW`](ACRA_FLOW.md) — Ziel-Architektur des Crash-Reporting-Subsystems — konvergiert; alle vier Gabelungen entschieden.
- [`ACRA_SPEC`](ACRA_SPEC.md) — Implementierungs-Spec zum ACRA-Rewrite — die Signatur-/Test-/Konfig-Schicht unter ACRAFLOW.md.
- [`APPLIST_SORT_SPLIT_SPEC`](APPLIST_SORT_SPLIT_SPEC.md) — Sortierung dem Consumer geben, nicht dem Namens-Helfer (RAL-4)
- [`DATASTORE_READ_SPEC`](DATASTORE_READ_SPEC.md) — Ein autoritativer Lesepfad pro DataStore-State (Plan + Sigs)
- [`DEBOUNCE_SPEC`](DEBOUNCE_SPEC.md) — Status: ENTWURF (2026-08-09), Spec-Review-Runde 1 eingearbeitet — Verdikt SOUND
- [`INSTALLED_APPS_LOAD_SPEC`](INSTALLED_APPS_LOAD_SPEC.md) — Der App-Loader kollabiert Fehler zu leer (Plan + Sigs)
- [`PERF-BENCHMARK-SETUP`](PERF-BENCHMARK-SETUP.md) — App-start performance: benchmark & setup (A17-only, tooling, gates).
- [`PERF-RESULTS`](PERF-RESULTS.md) — App-start performance: on-device results (A17) — hop + cold-start TTID.
- [`REACTIVE_APPLIST_SPEC`](REACTIVE_APPLIST_SPEC.md) — Anzeige rein reaktiv, Enumeration nur bei echter Änderung
- [`RECONCILE_FIX_SPEC`](RECONCILE_FIX_SPEC.md) — Fix-Spec für das R-INV-Loch im Verifikations-Veto (RECONCILESPEC.md §3, as-built).
- [`RECONCILE_SPEC`](RECONCILE_SPEC.md) — Implementierungs-Spec für den App-Listen-Reconcile-Rewrite — grüne Wiese, noch kein Code.
- [`WALLPAPER_COMPOSITE_LIFECYCLE_SPEC`](WALLPAPER_COMPOSITE_LIFECYCLE_SPEC.md) — The cache was never the hard part. The read path (decode once, hold the bitmap, reuse it
- [`WALLPAPER_DRAWER_HOME_REBUILD_SPEC`](WALLPAPER_DRAWER_HOME_REBUILD_SPEC.md) — Status: IMPLEMENTED (Option D, Phases 1–3.5) on branch
- [`WALLPAPER_PARALLEL_DECODE_SPEC`](WALLPAPER_PARALLEL_DECODE_SPEC.md) — Status: ENTWURF v2 (2026-08-13), greenfield — noch nicht gebaut. Review-Runden 1+2
- [`WALLPAPER_RENDER_RES_SPEC`](WALLPAPER_RENDER_RES_SPEC.md) — Status: ENTWURF v3 (2026-08-12), Review-Runden 1+2 eingearbeitet. Fokus: den
- [`homescroll`](homescroll.md) — Handover document for migrating HomeFragment away from its split-screen
- [`project-wallpaper-parallel-decode-lever`](project-wallpaper-parallel-decode-lever.md) — Deferred optimization — parallelize the wallpaper layer decode to cut the
