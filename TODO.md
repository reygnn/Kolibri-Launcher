# Kolibri Launcher — TODO & Roadmap

Lebendes Dokument. Stand: 2026-04-30, basierend auf einer vollständigen Source-Analyse
des Repos auf Branch `main` (Version `0.99.61` / versionCode `79`).

Was hier steht, ist aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## 1. `Timber.e` vs. `silentError`: Severity-Audit — abgeschlossen

`TimberWrapper.silentError(...)` ist als „fail fast in DEBUG, fail safe in
RELEASE" gebaut:

- **DEBUG:** wirft `RuntimeException` → Bug sofort sichtbar, Senior fixt
- **RELEASE:** Logcat-Eintrag mit `SILENT_ERROR`-Tag → App läuft weiter,
  User merkt nichts

Direktes `Timber.e(...)` hat dieses Verhalten **nicht** — es loggt in beiden
Build-Typen still. Bug-Symptome im Debug-Build landen damit in der
Logcat-Mülltonne, statt dem Senior aufzufallen.

Nach allen Migrationen verbleiben 8 `Timber.e`-Aufrufe im Main-Source —
alle in Gruppe A (bleibt bewusst `Timber.e`). Diese Sektion bleibt im
TODO als Referenz, damit ein neuer Reviewer beim Auftauchen weiterer
`Timber.e`-Aufrufe sofort den richtigen Mental-Frame hat.

### Gruppe A — bleibt `Timber.e` (8 Aufrufe, bewusst, kein Handlungsbedarf)

Direkt im Crash-Handler-Code oder im Fail-Safe-Pfad. `silentError` würde
hier rekursiv die eigene Crash-Behandlung auslösen oder eine bewusst
schluckende Logik kippen.

| Stelle | Kontext |
|---|---|
| `KolibriLauncherApp.kt:76` | `applicationExceptionHandler` selbst |
| `:180`, `:264` | Catch um `setupGlobalExceptionHandler` (zwei Pfade) |
| `:189` | StrictMode-Setup-Fallback |
| `:218` | DEBUG-only Backup-Restore-Fehler — Catch ist „Continue anyway, not critical" by design; ein `silentError`-Throw würde die anschließende Migration killen UND in den outer catch fallen, der dann fälschlich „Error during migration" loggt. Inline-Kommentar im Code erklärt es. |
| `:277` | `handleUncaughtException` selbst |
| `:302` | Catch innerhalb des Crash-Handlers |
| `:376` | `onTerminate` (Process wird gekillt) |

---

## 2. XXL-Files: `HomeFragment` und `ZoomableImageView` zerlegen

Code-Audit am 2026-04-30 hat zwei UI-Klassen identifiziert, deren
Größe Review- und Merge-Surface unangemessen aufbläht:

- `ui/home/HomeFragment.kt` — **2.694 Zeilen**
- `ui/home/ZoomableImageView.kt` — **1.472 Zeilen**

`HomeFragment` enthält bereits einen ~200-Zeilen-Selbstkommentar
(`:75-267`), der das Problem benennt und einen Split-Plan skizziert:
zuerst Throwable-Catch-Audit (§3), dann Delegate-Extraktion analog
zu `ui/main/delegate/`. Der Plan steht; die Umsetzung fehlt.

- [ ] **`HomeFragment.kt` aufspalten** entlang der im File-KDoc
  beschriebenen Grenzen (Wallpaper-Edit-Logik, Favorites-Rendering,
  Layout-Customization-Hooks). Ziel: jede neue Klasse < 600 Zeilen.
  Existierende Helfer-Pattern als Vorlage: `LayerButtonsState`,
  `WallpaperSaveAction`, `LayoutCalculator`. Erst nach §3, sonst
  zerstreut man die Throwable-Catches über mehr Files.
- [ ] **`ZoomableImageView.kt` aufspalten** in einen Touch/Gesture-
  Handler und einen Matrix/Transform-Calculator. Tests entkoppeln,
  damit der reine Transform-Pfad JVM-testbar wird (Rule 10).
- [ ] **`HomeFragment.kt:2535/2545` Alias-Smell entfernen** —
  `updateLayerButtonsVisibility()` und `updateLayerButtonStates()`
  sind beide One-Liner, die `applyLayerButtonsState()` rufen.
  Aliase „für Call-Site-Klarheit" sind hier zu klever; einer
  davon (oder beide) verschwinden zugunsten eines direkten
  Aufrufs. Bei Review erster Stolperer.

---

## 3. Throwable-Catch-Religion: Failure-Maskerade auditieren

Code-Audit hat **71 `catch (e: Throwable)`-Blöcke im Main-Sourceset**
gezählt. Die Crash-Safety-Kultur ist Designziel (CLAUDE.md Rule 7
für `KolibriLauncherApp`), aber sie ist über die Whitelist
hinausgewuchert. Das eigentliche Problem hat der Autor selbst in
`HomeFragment.kt:157-208` formuliert:

> achieves *continuation*, which is a different thing from
> recovery — invisible failures don't get fixed.

Konkrete Belegstelle: `domain/usecase/GetDrawerAppsUseCase.kt:49-115`
hat **drei verschachtelte try/catch um eine `sortedBy { lowercase() }`-
Operation**, mit drei Fallback-Pfaden. Das maskiert Programmierfehler
statt sie sichtbar zu machen.

- [ ] **Catch-Audit ausführen.** Pro Catch entscheiden:
  - **Behalten:** echter externer Fehlerfall (System-API, IO,
    Reflection auf fragile Plattform-Klassen, OOM-Recovery in
    `KolibriLauncherApp` per Rule 7-Whitelist).
  - **Auf `silentError` migrieren:** Application-Logik, wo ein
    Programmierfehler in DEBUG laut werden soll (siehe Rule 9).
  - **Catch entfernen:** triviale Pfade (`sortedBy`, Listen-Map,
    String-Vergleich) — der Compiler garantiert hier schon, dass
    der Pfad nicht Throwable wirft.
- [ ] **`GetDrawerAppsUseCase.kt:49-115` als Pilot.** Drei
  verschachtelte Catches abbauen: die innere `sortedBy` braucht
  keinen Catch, die mittlere kann auf `silentError` heruntergehen,
  die äußere bleibt nur falls echte Cancellation-/IO-Pfade berührt
  werden.
- [ ] **Vor §2.** Wenn `HomeFragment` zerlegt wird, bevor diese
  Catches sortiert sind, multipliziert sich die Maskerade über
  mehrere Files.

---

## 4. `BackupRepositoryImpl` zerlegen

`data/BackupRepositoryImpl.kt` ist **1.356 Zeilen** mit **9 separaten
Test-Files** (Doomsday, Security, Wallpaper, Isolation, etc.). Die
Test-Disziplin ist exzellent — und genau das ist auch das Symptom:
eine Klasse, die so viele orthogonale Test-Themen anzieht, ist eine
God-Class.

Konstruktor-Smell: **9 Repository-Dependencies** — der erste Hinweis,
dass die Klasse drei Verantwortlichkeiten in einer trägt.

- [ ] **In drei Klassen splitten:**
  - `BackupExporter` — schreibt User-State in das Backup-Format.
  - `BackupImporter` — liest und applied ein Backup auf die Repos.
  - `BackupZipFormat` — Versionierung, Magic-Bytes, Zip-Layout.
- [ ] **Test-Files mit-splitten** — die 9 existierenden Tests
  alignen auf die neuen Grenzen, nicht alles in einen
  „BackupOrchestratorTest" verschmieren.
- [ ] **`BackupRepository`-Interface bleibt eine Facade**, falls
  Aufrufer das vereinheitlichte API brauchen — die Implementierung
  delegiert dann an die drei Klassen.

---

## 5. Naming-Konsistenz: `Manager` → `RepositoryImpl` zu Ende führen

Die Hilt-Migration hat Production-Klassen von `XyzManager` auf
`XyzRepositoryImpl` umbenannt (siehe `app/src/CLAUDE.md` →
„Namens-Historie"). An manchen Stellen ist die alte Schreibweise
in Field-Namen aber stehengeblieben:

`data/BackupRepositoryImpl.kt`-Konstruktor injiziert u. a.:
- `favoritesManager: FavoritesRepository`
- `appVisibilityManager: HiddenAppsRepository`
- `appNamesManager: CustomNamesRepository`

Cosmetic, aber „Tagesform der Migration" ist genau die Art Detail,
die einen frischen Reviewer beim ersten Vorbeigang stolpern lässt.

- [ ] **Field-Renames in `BackupRepositoryImpl`** — `favoritesManager`
  → `favoritesRepository`, etc. Andere Repos parallel checken
  (Grep nach `Manager:` in Field-Position).
- [ ] **Bewusste Ausnahmen erhalten:** `WallpaperFileManager`
  (kein Repo, reines File-Helper-Konstrukt) und
  `DataMigrationManager` (Migrations-Bootstrap, kein Repo) —
  per `app/src/CLAUDE.md` explizit dokumentiert. Nicht anfassen.

---

## 6. Test-Konsistenz: `MainDispatcherRule` flächendeckend prüfen

Die Test-Konvention ist klar (CLAUDE.md → „The dispatcher rule
(non-negotiable)"): Tests, die Code auf `Dispatchers.Main` treiben,
**MÜSSEN** den Rule + `runTest(mainDispatcherRule.testDispatcher)`
benutzen. Sonst werden sie flaky, ohne dass es im Output sichtbar
wäre.

Aktuelle Verteilung: `MainDispatcherRule` taucht in **26 Treffern
in 10 Files** auf — bei ~70 Test-Files insgesamt. Die Konvention
ist „MUSS, wenn Main berührt", nicht „immer", also ist die niedrige
Quote nicht per se falsch — aber pro Test vom Reviewer zu prüfen,
und das ist ein Risiko, das man nicht bei jedem PR neu durchgehen
will.

- [ ] **Audit pro Test-File:** ViewModels, Coroutine-driven Repos
  (`shareIn`/`stateIn` mit `mainDispatcher`), und alles was
  `viewModelScope` oder `lifecycleScope` mockt — diese MÜSSEN den
  Rule haben.
- [ ] **Bei fehlendem Rule:** entweder ergänzen oder einen
  Ein-Zeilen-Kommentar setzen, warum der Test ihn explizit nicht
  braucht (z. B. reine `Repository.saveOrder`-Pfade, die nicht auf
  Main laufen).

---

## 7. Code-Hygiene-Sweep (Smells, sammelweise)

Kleinere Auffälligkeiten aus dem Audit. Keine einzelne ist
gravierend; in Summe drücken sie die Code-Note. Eine Sweep-PR
nach §3 ist effizienter als sechs Einzel-Edits.

- [ ] **Toter Lehr-Kommentar:** `MainActivity.kt:116` —
  `// private var leaker: Context? = null` raus.
- [ ] **Auskommentierter Code:**
  - `MainActivity.kt:167-172` — Block raus oder erklären.
  - `KolibriLauncherApp.kt:105` — `ReportField.ANR_TRACE`
    auskommentiert; entweder reinziehen oder rausnehmen.
  - Zwei `// .penaltyDeath()` im StrictMode-Setup — Kontext geht
    aus dem Kommentar verloren; entweder Begründung
    nachdokumentieren oder löschen.
- [ ] **Logging-Stil-Konsistenz:** `Timber.Forest.tag(...)` vs.
  schlichtes `Timber.d` — eine der beiden Varianten als Regel
  setzen (oder begründen, warum beide koexistieren) und in
  CLAUDE.md festhalten.
- [ ] **Kommentar-Sprache:** Mischung DE/EN im selben File ist
  Tagesform der bisherigen Edits. Pro File **eine** Sprache
  fixieren (Default Deutsch, da CLAUDE.md / TODO.md / KDoc-
  Selbstkommentare bereits deutsch).
- [ ] **`runBlocking` in `attachBaseContext`** —
  `KolibriLauncherApp.kt:120`. Defensible (DataStore-Read vor
  Hilt verfügbar, ACRA-Init braucht das Result), aber StrictMode-
  relevant. Prüfen, ob der Pfad sich auf `runBlocking(Dispatchers.IO)`
  verbessern lässt, oder den Grund im KDoc verewigen.

---

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
