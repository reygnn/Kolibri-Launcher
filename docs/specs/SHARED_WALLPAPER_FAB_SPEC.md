# SHARED_WALLPAPER_FAB_SPEC

**Status:** v1.1 (2026-09-15) — **Phase 1 + 1b UMGESETZT; Phase 2/3 ZURÜCKGESTELLT** (siehe §9 Ergebnis).
**Rolle:** Extraktion des Wallpaper-FAB/Edit-Clusters aus `kolibri` + `nyx` nach `common-ui`.
**Motivation:** Drift-Reduktion. Folge-Arbeit im Sinne des `MONOREPO_MERGE_SPEC`-Leitprinzips
(„Architektur erben, produktneutrale Infra teilen"), analog zur bereits erfolgten
`DrawerOverlayController`-Extraktion.

---

## 1. Ausgangslage (gemessen)

`jscpd` über beide App-Trees (`src/main`, exakte Token-Klone, min. 8 Zeilen / 50 Tokens,
Stand 2026-09-15) findet **978 App-übergreifend duplizierte Kotlin-Zeilen** (kolibri ↔ nyx).
**~90 % davon** liegen in genau einem Feature: dem Wallpaper-Speed-Dial-FAB + Wallpaper-Edit.

Datei-Paare (kolibri ↔ nyx), Divergenz nach Package-/Namens-Normalisierung:

| Datei | kolibri | nyx | Diff | Einordnung |
|---|---|---|---|---|
| `SpeedDialFabCluster.kt` | 329 | 329 | ~0 | byte-identisch (nur Package) |
| `CommandsPanel.kt` | 227 | 227 | ~0 | byte-identisch |
| `FabPositionMath.kt` | 86 | 86 | ~0 | byte-identisch, pure Logik |
| `FabDragHandler.kt` | 59 | 59 | ~0 | byte-identisch, pure Logik |
| `WallpaperFabConstants.kt` | 15 | 15 | ~0 | byte-identisch, pure Logik |
| `SnapIconResolver.kt` | 60 | 60 | ~0 | byte-identisch (R.drawable) |
| `LayerButtonsState.kt` | 52 | 52 | ~0 | byte-identisch, pure Logik |
| `FabPosition.kt` (Model) | :domain | app/home/model | ~0 | pures Model |
| `WallpaperEditController` | 561 | 400 | 257 | **stark divergent** — bleibt pro App |

Zusätzlich (von jscpd nicht erfasst, da nur `.kt`): die Layout-XMLs
`view_speed_dial_fab_cluster.xml`, `view_commands_panel.xml`,
`view_wallpaper_edit_overlay.xml` sind ebenfalls in beiden Apps dupliziert.

## 2. Zielort

`common-ui`, Paket `com.github.reygnn.launcher.common.ui.wallpaperfab` + `common-ui/src/main/res/`.
Präzedenz: `common-ui` beherbergt bereits das geteilte Wallpaper-Paket
(`WallpaperViewBinder`, `ZoomableImageView`, `WallpaperEditTransition`, `WallpaperFlattener`, …)
und liefert eigene `res/`.

Muster wie `DrawerOverlayController`: die geteilte View trägt Callback-Vars
(`SpeedDialFabCluster.onPositionChanged` etc.), der app-spezifische Edit-Controller bleibt
der Glue.

## 3. `SnapMode`-Konsolidierung (Vorbedingung)

`SnapMode { EDGE, CENTER }` ist **dreifach** definiert: `kolibri … SnapIconResolver`,
`nyx … SnapIconResolver` und kanonisch `common.ui.wallpaper.ZoomableImageView.SnapMode`.
Beim Verschieben von `SnapIconResolver` die lokalen Enums fallen lassen und auf die
common-ui-Variante (oder ein hochgezogenes `common.ui.wallpaper.SnapMode`, auf das
`ZoomableImageView` umstellt) verweisen.

## 4. Phasen

### Phase 0 — Verifikation (rein lesend)
- Byte-Identität der 7 Cluster-Dateien modulo Package bestätigen.
- `SnapMode`-Konsolidierungspfad festlegen (§3).

### Phase 1 — Pure Logik (trivial, ~0 Risiko, ~200 Zeilen)
Kein `R`-Bezug → verbatim nach common-ui:
- `FabPositionMath`, `FabDragHandler`, `WallpaperFabConstants`, `LayerButtonsState`
  → `common.ui.wallpaperfab`.
- `FabPosition` (Model) → `:core` (pure Kotlin; kolibri heute in `:domain`, nyx in
  `app/home/model`).
- Beide Apps: Importe umbiegen, Duplikate löschen. JVM-Tests der Mathe nach common-ui
  migrieren.

### Phase 2 — Ressourcen-Migration (der eigentliche Aufwand)
Insgesamt **33 distinct `R.*`-Referenzen** lösen sich auf common-uis R auf. Nach
`common-ui/res` verschieben:
- **2 Layouts:** `view_speed_dial_fab_cluster.xml`, `view_commands_panel.xml`
  (die ~17 `R.id.*` kommen mit den Layouts).
- **~13 Drawables:** `ic_magnet_on/off`, `ic_rectangle_on`, `ic_center_on`,
  `ic_horizontal_edge_on/off`, `ic_horizontal_center_on/off`,
  `ic_vertical_edge_on/off`, `ic_vertical_center_on/off`, `bg_wallpaper_edit_toolbar`.
- **1 Dimen:** `wallpaper_panel_slide_distance`.
- Namenskollisionen prüfen (beide Apps nutzen identische Namen → in common-ui nur
  einmal). Die einbindenden App-Layouts (`view_wallpaper_edit_overlay.xml`) auf die
  common-ui-Layouts/Views umstellen.

### Phase 3 — Ressourcen-gekoppelte Klassen
- `SnapIconResolver` → common-ui (nutzt common-ui `R.drawable` + shared `SnapMode`).
- `SpeedDialFabCluster`, `CommandsPanel` → common-ui (custom Views; verdrahten schon
  über Callback-Vars, kein ViewModel-Dep).
- App-Controller (`WallpaperEditController` / `NyxWallpaperEditController`) referenzieren
  die shared Views/Resolver. Da die View-Dateien heute byte-identisch sind, nutzen beide
  Controller bereits dieselbe View-API → sicher.

### Phase 4 — divergenter Edit-Controller (optional/zuletzt)
`WallpaperEditController` 561 vs `NyxWallpaperEditController` 400 Zeilen, nur ~100 geteilt.
**Nicht** zwangsweise zusammenführen — der Controller ist der app-spezifische Glue (analog
`MainActivity` beim Drawer). Höchstens den echt-gemeinsamen Entscheidungs-/Geometrie-Kern in
einen kleinen shared Helper ziehen; sonst pro App belassen.

## 5. Tests
- kolibri `SnapIconResolverTest`, `WallpaperEditTransitionTest` migrieren bzw. grün halten.
- common-ui JVM-Tests für die verschobene Mathe (`FabPositionMath`, `FabDragHandler`).
- On-Device-Test Wallpaper-Edit auf **beiden** Geräten (Pixel 9a + A17) — View/Gesten/
  Ressourcen-schwer; Robolectric nur wo nötig.

## 6. Risiken
- **Ressourcen-Namespace/Kollisionen** (Phase 2) ist der Knackpunkt; common-ui liefert aber
  bereits `res`, also gangbar.
- Die shared Views müssen beide Controller bedienen — durch die heutige View-Datei-Identität
  ist die API-Kompatibilität gegeben.
- Multi-Modul-Change über beide Apps → **eigener Review + On-Device-Test**, wie beim Drawer.

## 7. Erwarteter Gewinn
Eliminiert praktisch die gesamten ~978 cross-app Kotlin-Duplikate (minus der ~100
Controller-Zeilen, die bewusst app-spezifisch bleiben) **plus** die duplizierten Layout-XMLs.

## 8. Sequenzierung
**Nach** den zwei offenen Merges (`feature/drawer-overlay-drag-dismiss` → `main`, dann
`refactor/shared-drawer-overlay-controller` → `main`), auf eigenem Branch von `main`
(Vorschlag `refactor/shared-wallpaper-fab`) — sonst stapeln sich zu viele ungemergte
Branches auf demselben Code. Phase 1 ist risikofrei und liefert sofort sichtbaren Gewinn.

## 9. Ergebnis (2026-09-15)

**Phase 1 + 1b umgesetzt** (Branch `refactor/shared-wallpaper-fab`):
- Phase 1 (`d796d75`): `FabPositionMath`, `FabDragHandler`, `WallpaperFabConstants`,
  `LayerButtonsState` → `common.ui.wallpaperfab` (public); 8 Quell- + 6 Test-Kopien
  entfernt; Tests nach common-ui konsolidiert.
- Phase 1b (`dfefa0b`): `FabPosition` → `:core` (`core.wallpaper`); beide `:domain`/
  `:data`/`:app` umgestellt; 2 Kopien entfernt.
- Netto ~580 Zeilen dedupliziert, alle Tests + kolibri-Linter grün.

**Phase 2/3 bewusst ZURÜCKGESTELLT.** Die Ressourcen-Analyse zeigte, dass §4 die Kopplung
unterschätzt hat:
- Die Layouts referenzieren **~26 Drawables** (nicht 13), **~18 lokalisierte Strings**
  (× `values`/`values-de`), eine Farbe (`wallpaper_edit_foreground`), einen Style und
  `spacing_medium` (dimen ist **app-generisch** → müsste in common-ui dupliziert werden).
- **Blocker: Material.** Die Layouts nutzen `FloatingActionButton`/`MaterialButton`, aber
  common-ui hat **bewusst keine Material-Dependency** (material-before-appcompat-force-Regel
  lebt pro `:app`). Die Views zu teilen erzwingt Material in common-ui.
- Nicht sauber teilbar: die Drawables hängen gleichzeitig am Layout (`@drawable/...`) und an
  `SnapIconResolver` → alles-oder-nichts.

Entscheidung des Maintainers: bei Phase 1/1b stoppen. Das Aufwand/Risiko der View-Extraktion
(Material + großer Ressourcen-Schwanz in common-ui, gegen die dokumentierte Konvention)
rechtfertigt die Deduplizierung von ~550 Zeilen View-Code nicht. Die Views bleiben pro App.

**Falls Phase 2/3 je doch gewünscht:** entweder Material in common-ui aufnehmen (Force-Regel
handhaben) **oder** ein neues Modul `:common-ui-wallpaper` mit Material anlegen, das common-ui
Material-frei hält. `SnapMode`-Konsolidierung (§3) und der volle Ressourcen-Umzug (inkl.
`values-de`-Parität in common-ui) gehören dann dazu.
