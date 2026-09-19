# Retrofit-Priorisierung: nyx & kolibri

> Was aus der [Greenfield-Retrospektive](history/GREENFIELD_RETROSPECTIVE.md)
> lässt sich im **bestehenden** Code nachziehen — und in welcher Reihenfolge?
> Bewertet wird **Retrofit-Aufwand im Ist-Stand** (nicht Greenfield-Reinschrieb),
> festgehalten am 2026-09-19.
>
> Die Retrospektive ist die *Richtung* (was wäre ideal), dieses Dokument die
> *Umsetzungssicht* (was kostet es und was zuerst). Die Retro ist damit
> archiviert — ihr handlungsleitender Teil lebt hier weiter.

---

## Bereits umgesetzt (Retro hier überholt)

Zwei Empfehlungen der Retro sind seit dem 13.09. faktisch erledigt — im
Ist-Stand verifiziert:

- **Shared Error-/Concurrency-Netz.** `:common-ui` hat `BaseActivity` /
  `BaseViewModel` (launchSafe/executeSafe-Basis); nyx nutzt sie bereits
  (`MainActivity : BaseActivity<Nothing, HomeViewModel>`,
  `HomeViewModel : BaseViewModel`, plus `LaunchSafe.kt`). **Rest:** kolibris
  eigene `ui/base/BaseActivity.kt` noch auf die shared Version ziehen → siehe
  R1.
- **`:common-ui`-Schnitt + FAB-Dedup.** Der „byte-identisch kopierte"
  FAB-/Wallpaper-Edit-Code ist entkoppelt: FAB-Cluster in
  `common-ui/wallpaperfab/`, `FabPosition` in `:core`, `WallpaperEditTransition`
  in `common-ui`; nyx hält nur noch dünne `NyxWallpaperEditController/Coordinator`,
  kolibri testet denselben Cluster. Der WV5d-Dedup ist gelaufen.

---

## Bewertungsmatrix

Aufwand / Risiko / Nutzen je klein–mittel–groß (S/M/L). „Risiko" = Wahrscheinlichkeit
schwer sichtbarer Regressionen, nicht Sichtbarkeit der Änderung.

| # | Maßnahme | Aufwand | Risiko | Nutzen | Gate |
|---|---|:---:|:---:|:---:|---|
| R1 | kolibri `BaseActivity` → shared `:common-ui` | S | S | M | — |
| R2 | nyx: „minimal persistieren, maximal ableiten" (inkrementell) | S | S | M | — |
| R3 | kolibri-LOC weiter in geteilte Module ziehen | M | S | M | — |
| R4 | nyx: Layout-Pipeline → eine reine `normalize()` | M–L | **L** | **L** | — |
| R5 | nyx God-Activity (`MainActivity`, 1647 Z.) auflösen | **L** | **L** | **L** | nach R4 |
| R6 | nyx DataStore aufsplitten (layout/prefs/wallpaper) | M | **L** | M | **Migrations-Policy** |
| R7 | kolibri Serializer → versioniertes Schema + Migrationen | L | **L** | M | **Migrations-Policy** |
| R8 | Backup als transaktionales Cross-Store-Framework | **L** | **L** | L | **Migrations-Policy** |
| R9 | Compose-Hybrid (Chrome in Compose, Workspace als View) | **L** | M | M | Scope-Entscheid |

---

## Empfohlene Reihenfolge

### Tier 1 — billige Konsolidierung (jetzt, risikoarm)

- **R1 — kolibri `BaseActivity` auf shared ziehen.** Mechanisch: kolibri hat noch
  seine eigene `ui/base/BaseActivity.kt`, die shared Version existiert und ist von
  nyx erprobt. Vereinheitlicht das Fehlernetz über beide Apps. Quick win.
- **R2 — „minimal persistieren, maximal ableiten".** Kein Big-Bang, pro Fall:
  `renderedPageCount` (berechnet, nicht persistiert) ist das Vorbild; jeder weitere
  ableitbare Wert einzeln. Reduziert die Persistenz-Fläche und damit Ordering-Races
  an der Wurzel — inkrementell, geringes Risiko.
- **R3 — kolibri-LOC in geteilte Module.** Mechanische Extraktion (time-info /
  wallpaper / crash sind schon draußen). Pro Modul überschaubar, risikoarm, weil
  `:domain` rein und testbar ist. Breit, aber nicht tief.

### Tier 2 — die zustandsbehaftete Mitte (hoher Nutzen, sorgfältig)

- **R4 — Layout-Pipeline zu einer reinen `normalize(layout, grid, apps)`.**
  `HomeLayoutReconciler` + `HomeLayoutRegridder` + `seedInitialDock` verschmelzen.
  Korrektheitskritisch (Ownership-/Ordering-Races), aber tractabler als die
  God-Activity, weil der Code bereits in `:domain` mit Truth-Table-Tests lebt.
  **Zuerst R4, dann R5:** eine klare Layout-Wahrheit de-riskt die Activity-Auflösung.
- **R5 — God-Activity auflösen.** Der Flaggschiff-Umbau: nyx `MainActivity` ist
  1647 Zeilen zustandsbehafteter Orchestrierung (Overlay-State-Machines, Drag-Wiring,
  Seed, Consent, Gesten) — laut Retro saß „fast jeder echte Bug" hier. Höchster
  Nutzen, höchste Kosten, Henne-Ei-Charakter (man baut ohne Tests um, um überhaupt
  testbar zu werden). Orchestrierung in ViewModels/Use-Cases ziehen, Activity auf
  View-Binding reduzieren. In Scheiben schneiden (eine Overlay-/Drag-Domäne nach der
  anderen), nicht in einem Rutsch.

### Tier 3 — hinter Persistenz-Migration verriegelt (teuer, erst mit Policy-Entscheid)

Alle drei brechen bestehende Installs, wenn die Migration nicht geklärt ist. kolibris
Konvention ist explizit **keine In-Code-Migrationen** → der sanktionierte Pfad ist
Export → Reset → Restore. Diese Policy für nyx zu bestätigen (oder bewusst
abzuweichen) ist die **Voraussetzung** für R6–R8.

- **R6 — DataStore aufsplitten.** Teilweise begonnen (`usageDataStore` ist raus),
  aber Layout + Prefs + Wallpaper-Display + FAB + Hidden-Apps + Drawer-Folders teilen
  weiter einen `homeLayoutDataStore`. Der Code ist mittel; die **Migration** ist der
  teure Teil.
- **R7 — Serializer → versioniertes Schema.** Den „paranoiden Multi-Pass-Serializer"
  gegen ein Migrations-Framework tauschen heißt, Recovery-Heuristiken für
  hand-editierte Legacy-Backups aufzugeben — Rückwärtskompatibilität mit realen
  Alt-Backups ist das Minenfeld.
- **R8 — transaktionales Cross-Store-Backup.** DataStore kennt keine
  Store-übergreifende Transaktion; echte „apply in one transaction"-Atomizität ist
  ein architektonischer Eigenbau. Der „Layout zuletzt schreiben"-Fix ist das Pflaster,
  das ersetzt werden soll. Am tiefsten.

### Tier 4 — Scope-Entscheidung, kein Risiko-Problem

- **R9 — Compose-Hybrid.** Kein Regressions-, sondern Umfangsproblem und von der Retro
  bewusst *nicht* dogmatisch empfohlen: Launcher behalten echte View-Vorteile
  (Touch-/Window-Kontrolle, RecyclerView-Perf, Wallpaper-Surface). Falls überhaupt:
  Chrome/Settings/Drawer-Chrome in Compose, Workspace/Drag/Grid als gekapselte
  View-Surface — als Entscheidung, nicht als Erbe.

---

## In einem Satz

Zuerst die billige Konsolidierung (R1–R3), dann die zustandsbehaftete Mitte mit
R4 vor R5, und alles mit Persistenz-Migration (R6–R8) erst hinter einem bewussten
Migrations-Policy-Entscheid — R9 ist eine Scope-Frage für sich.
