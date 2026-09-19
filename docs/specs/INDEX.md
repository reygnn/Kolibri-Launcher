# Nyx × Kolibri — Spec-Bundle

Stand-Übersicht der Zusammenführungs-Specs (Nyx erbt Kolibris battle-tested Infra).

| Datei | Rolle | Version |
|---|---|---|
| `MONOREPO_MERGE_SPEC.md` | Dach: Modulschnitt, Klassifizierung, Migrationspfad, Cross-Cutting-Konventionen | ENTWURF v1.7 |
| `HOME_INFO_ELEMENTS_SPEC.md` | Uhr/Datum/Akku/Event-Indikator (Klasse A) | ENTWURF v1.2 |
| `APP_USAGE_SPEC.md` | Tracking/Score/Sort/Export der App-Nutzung | ENTWURF v1.2 |
| `BACKUP_SCHEMA_PORT_SPEC.md` | Geteilte Backup-Engine + pro-App-Schema/Assembler | ENTWURF v1.2 |
| `WALLPAPER_RESTORE_SPEC.md` | Wallpaper-Persistenz + Blob-Gegenseite des Backups | ENTWURF v1.1 |
| `HOME_CURATION_SPEC.md` | „Ist auf dem Home" — Lese-Port + Schreib-Callback + Kontextmenü | ENTWURF v1.4 |
| `NYX_SINGLE_USER_CLEANUP.md` | Einmalige Aufgabe: `userSerial`/Multi-User aus Nyx entfernen | Patch-Liste |

## Leitprinzip
„Architektur erben, nicht die Produkt-Philosophie" — geteilt wird die produktneutrale,
battle-tested Infra; die Produkt-Domäne (Grid-Home vs. Favoriten-Home) bleibt pro App.

## Kern-Entscheidungen
- Single-User: Nyx entfernt `userSerial`; Work-Profiles out-of-scope (MRG-INV-9).
- Geteiltes `AppInfo` in `:core`, ohne `isFavorite` (Home-Kuration ist Pro-App-Overlay).
- Identität strukturiert (`ComponentKey`), flach nur als Persistenz-Projektion (MRG-INV-10);
  Store-Keying: Hidden = `key.flat` (component-granular), CustomNames/Usage = `key.packageName`.
- Neutraler Namespace-Stamm: `com.github.reygnn.launcher`.
- Ein `:core`; `crashreporting` als eigenes Feature-Modul ab Start; ein Baseline-/
  Macrobench-Modul mit Per-App-Flavors.
- Home-Grid: gepagt in ViewPager2 (bereits so); Overflow → neue Seite (implementiert);
  Trailing-Seiten-GC = genau eine leere Landing-Seite.
- Backup: Blob-Präfix `blobs/` schreiben (alt `wallpapers/` lesbar); `BackupPreview`
  generische Hülle; blob-loses Schema ⇒ `.json` statt `.zip`; `recover` geteilter
  opt-in lenient-Helfer (Feld-Recovery pro Schema); `ImportOptions` pro App.
- Info-Elemente: `TimeTickSource`-Port, geteiltes `HomeInfoUiState`, `Purgeable` nach `:core`.
- Usage: `BackupSchema<UsageData>` auf geteilter Engine; package-granular; `rankByUsage`
  mit injizierbarem Tie-Break; Settings-Keys nur im Settings-Backup.

## Offene Punkte
Keine mehr — alle Entscheidungen getroffen.

## Folge-Arbeit (kein offener Punkt, eigene künftige Specs)
- `WALLPAPER_SHARE_SPEC` (Rendering/Compositing-Extraktion).
- `SHARED_WALLPAPER_FAB_SPEC` (v1.1) — Wallpaper-FAB/Edit-Cluster: **Phase 1+1b umgesetzt**
  (pure Logik → `common.ui.wallpaperfab`, `FabPosition` → `:core`; ~580 Zeilen dedupliziert).
  **Phase 2/3 (Views/Ressourcen) zurückgestellt** — erzwingt Material + großen Ressourcen-Schwanz
  in common-ui gegen die no-Material-Konvention; Aufwand/Risiko nicht gerechtfertigt (siehe §9).
- `MOVE_ITEM_SPEC`-Ergänzung: Trailing-Seiten-GC + `normalizePages()` als Transition-Schritt.
- `DRAWER_FOLDERS_SPEC` (ENTWURF v1.0) — Folders im App-Drawer als abgeleitete
  Projektion; `FolderMembership` aus `HomeLayoutTransition` extrahiert und mit Home
  geteilt. Nyx-eigenes Feature, nicht Teil des Merge-Bundles.
- `TAPL_LITE.md` — TAPL-lite Instrumented-Test-Fassade: geteiltes
  `:common-testing-android` (BasePage/awaitUntil/dragRecyclerItem/probeFloat/
  longPressDrag) + Page-Objekte pro App. **Kolibri Phase 1+2** (AppDrawer-Swipe,
  FavoritesSort-Drag, Layout-Slider-Preview) und **nyx Phase 1+2** (Grid-Reorder,
  Cross-Page-Drag, Folder-create/add, Drag-to-Remove, Drawer→Home) — alle
  device-grün auf A17. Offen: Kolibri Gap 3 (UsageExport-SAF), nyx Drawer→Home
  per Drag (Weg 2) + Folder-Öffnen/Dock.

## Empfohlene Reihenfolge (Auszug MONOREPO_MERGE_SPEC §6/§3.5)
`:core` → ACRA (`:feature-crashreporting`) → Info-Elemente → App-Usage →
Backup-Engine → Swipe-Analyzer → Wallpaper (mit Backup-Schema zusammen) → Drawer-Helfer.
