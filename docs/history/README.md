# History archive (monorepo-level)

One-off historical documents kept for provenance, not as live reference. The
per-app archives live under [`../../kolibri/docs/history/`](../../kolibri/docs/history/)
and [`../../nyx/docs/history/`](../../nyx/docs/history/); this one holds
cross-app documents rooted at the monorepo level.

- [`GREENFIELD_RETROSPECTIVE.md`](GREENFIELD_RETROSPECTIVE.md) — the 2026-09-13
  subjective architecture retrospective on nyx & kolibri (what a greenfield
  rewrite would do differently). Kept for its rationale, but archived on
  2026-09-19: its actionable half is superseded by the living
  [`../RETROFIT_PRIORITIZATION.md`](../RETROFIT_PRIORITIZATION.md), and two of
  its recommendations (shared error/concurrency net, `:common-ui` cut + FAB
  dedup) had already shipped by then, so the snapshot no longer reflects the
  live tree.
