# Kolibri Launcher — TODO & Roadmap

Lebendes Dokument. Stand: 2026-05-01, basierend auf einer vollständigen Source-Analyse
des Repos auf Branch `main` (Version `0.99.61` / versionCode `79`).

Was hier steht, ist aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Aktueller Status

| § | Titel | Status | Größe |
|---|---|---|---|
| 1 | `Timber.e` vs. `silentError` Severity-Audit | erledigt, Memo bleibt | — |
| 2 | Throwable-Catch-Audit | 79 von 735 Catches weg, optionale Folge-Sweeps offen | klein-mittel pro Sweep |
| 3 | `BackupRepositoryImpl` zerlegen | offen | ~1 Tag, eigenes Projekt |
| 4 | `Manager` → `RepositoryImpl` Rename | offen, IDE-Job (Shift+F6) | ~5 min |
| 5 | `MainDispatcherRule`-Audit über Tests | offen | mittel |
| 6 | Code-Hygiene-Sweep (Smells) | offen | klein |

**Empfohlene Reihenfolge bei freier Wahl:** §6 (kleinste, schnelle Wins) → §4 (IDE-Job, Voraussetzung für sauberen §3) → §3 (Split, danach Test-Splitting) → §5 (Test-Konsistenz). §2 ist optional fortsetzbar, kein Blocker.

---

## 1. (Memo, abgeschlossen) `Timber.e` vs. `silentError`: Severity-Audit

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

## 2. Throwable-Catch-Religion — Trivial-Sweep abgeschlossen, Reststand

Survey hat insgesamt **735 `catch (Throwable)` / `catch (Exception)`-
Blöcke im Main-Sourceset** gezählt (das initiale Audit-Sample von 71
war zu eng gefiltert). Die Crash-Safety-Kultur ist Designziel
(CLAUDE.md Rule 7 für `KolibriLauncherApp`), aber sie war über die
Whitelist hinausgewuchert. Das eigentliche Problem hat der Autor
selbst in `HomeFragment.kt:157-208` formuliert:

> achieves *continuation*, which is a different thing from
> recovery — invisible failures don't get fixed.

### Erledigt

5 Sweep-PRs haben **79 nachweislich tote oder CANT_THROW-Catches**
entfernt:

| PR | Catches | Files |
|---|---:|---|
| Pilot `GetDrawerAppsUseCase` | 6 | 1 |
| Layer-A (`core`/`data`/`domain`) | 9 | 4 |
| Adapter-Sweep (App- + FavoritesAdapter) | 29 | 2 |
| SettingsFragment-Sweep | 32 | 1 |
| MainActivity-Mini | 3 | 1 |
| **Total** | **79** | **9** |

In jeder bearbeiteten Klasse sind die behaltenen Catches per Inline-
Kommentar als „echte EXTERNAL/Lifecycle"-Pfade markiert, damit ein
Reviewer den entfernten Mustercode nicht reflexartig wieder einfügt.

### Reststand: ~656 Catches

Verteilung laut Sub-Agent-Survey, abzüglich des Sweeps:
- **~250 EXTERNAL** — echte System-/IO-/ContentResolver-/PackageManager-
  Calls. Bleibt.
- **~100 LIFECYCLE** in `HomeFragment` & co — formal CANT_THROW (View-
  Setter), aber als Schutz gegen Teardown-Races platziert. **Gehört
  in ein strukturelles Refactoring** (`_binding?.let { }` +
  `viewLifecycleOwner.lifecycleScope`), nicht in einen Sweep. Das
  HomeFragment-eigene KDoc Z. 157-208 beschreibt diesen Pfad.
- **~120-150 weitere CANT_THROW/DEAD** in den UI-Files, die wir noch
  nicht angefasst haben (`AppDrawerFragment` ~36, `OnboardingActivity`
  ~19, `HiddenAppsActivity` ~15, etc.). Lohn-Surface aber kleinteilig
  pro File.
- **~30 in `KolibriLauncherApp`** — Rule-7-Whitelist, **bleibt
  unangetastet**.

### Optionale nächste Sweeps

- [ ] **`AppDrawerFragment` (~36 Catches)** — laut Survey ähnliches
  Profil wie die Adapter, etwa hälftiger Trivial-Anteil. Kandidat für
  einen weiteren kleinen PR.
- [ ] **`OnboardingActivity` / `HiddenAppsActivity` / `CustomNamesActivity`
  / weitere Activities** — Repetitive Doppelschalung um setOn*Listener.
  Pattern ist exakt der SettingsFragment-Sweep, vermutlich 30-50%
  Reduktion pro File.
- [ ] **HomeFragment LIFECYCLE-Restructure** — größeres eigenes Projekt,
  nicht als Sweep. Sollte erst angegangen werden, wenn die vier
  Pure-Logic-Extraktionen aus dem File-Header-KDoc finalisiert sind.

### Default-Disposition

Siehe **CLAUDE.md Rule 11** — die Default-Regel für neue Catches lebt
dort als projektweite Konvention. Im Review die Vier-Kategorien-Frage
stellen (siehe Rule 11 + `HomeFragment.kt:157-208`).

---

## 3. `BackupRepositoryImpl` zerlegen

> **Reihenfolge: §4 vor §3.** Das Rename der `Manager`-Felder (§4)
> sollte vor dem Split passieren. Sonst muss der Rename nicht in
> einer Klasse, sondern in drei neuen Klassen (Exporter / Importer /
> ZipFormat) wiederholt werden — verdreifachter Aufwand ohne Gewinn.

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

## 4. Naming-Konsistenz: `Manager` → `RepositoryImpl` zu Ende führen

Die Hilt-Migration hat Production-Klassen von `XyzManager` auf
`XyzRepositoryImpl` umbenannt (siehe `app/src/test/CLAUDE.md` →
„Namens-Historie"). An manchen Stellen ist die alte Schreibweise
in Field-Namen aber stehengeblieben:

`data/BackupRepositoryImpl.kt`-Konstruktor injiziert u. a.:
- `favoritesManager: FavoritesRepository`
- `appVisibilityManager: HiddenAppsRepository`
- `appNamesManager: CustomNamesRepository`

Cosmetic, aber „Tagesform der Migration" ist genau die Art Detail,
die einen frischen Reviewer beim ersten Vorbeigang stolpern lässt.

### Werkzeug: Android Studio, nicht Claude

**Diese Aufgabe gehört in die IDE, nicht in eine Edit-Session mit Claude.**
Android Studio's Refactor → Rename (Shift+F6 auf dem Field-Namen)
macht den Rename atomar projektweit:

- alle Aufrufstellen in derselben Klasse
- Imports und Hilt-Bindings
- KDoc-`[link]`-Referenzen
- String-Templates (`"Failed to query $favoritesManager"`)
- Named-Argument-Call-Sites in Tests

Aufwand pro Field: Cursor → Shift+F6 → neuer Name → Enter. Für die
~22 Fields gesamt **~5 Minuten**. Per Hand (oder via Claude-Edits)
wären das ~1-2h plus reales Risiko, eine Referenz zu übersehen
(KDoc, String-Template). Falsches Werkzeug.

Claude kann unterstützen: vor dem Rename den Plan validieren, nach
dem Rename Tests laufen lassen, Grep nach Reststand `Manager:` mit
`*Repository`-Typ, Commit-Message + Merge-Ablauf.

### Inventar (~22 Fields, alle mit Typ `*Repository`)

- [ ] **`data/BackupRepositoryImpl.kt`** (Z. 77-84) — 8 Fields:
  `favoritesManager`, `favoritesOrderManager`, `appVisibilityManager`,
  `appNamesManager`, `installedAppsManager`, `swipeActionsManager`,
  `settingsManager`, `wallpaperManager`. Größtes File, dichtest
  benutzt — eigener PR.
- [ ] **`ui/settings/SettingsFragment.kt`** (Z. 78-90) — 5 Fields:
  `appVisibilityManager`, `favoritesManager`, `favoritesOrderManager`,
  `settingsManager`, `screenLockManager`. Eigener PR.
- [ ] **Sammel-PR** für die übrigen 9 Fields:
  - `domain/usecase/GetDrawerAppsUseCase.kt` (3)
  - `domain/usecase/GetFavoriteAppsUseCase.kt` (3)
  - `data/InstalledAppsRepositoryImpl.kt` (1)
  - `data/TimeBasedEventsRepositoryImpl.kt` (1)
  - `ui/appcontextmenu/AppContextMenuDialogFragment.kt` (1)

### Bewusste Ausnahmen — nicht anfassen

Per `app/src/test/CLAUDE.md` explizit dokumentiert:
- **`WallpaperFileManager`** (kein Repo, reines File-Helper-Konstrukt)
- **`DataMigrationManager`** (Migrations-Bootstrap, kein Repo)

Auch nicht anfassen — Android-System-Klassen die *Manager* heißen,
weil Android sie so nennt: `PackageManager`, `RoleManager`,
`AlarmManager`, `WallpaperManager`, `FragmentManager`,
`LayoutManager`, `LauncherApps`. Das Distinktions-Kriterium ist
trivial: nur Felder umbenennen, deren **Typ** `*Repository` ist.

---

## 5. Test-Konsistenz: `MainDispatcherRule` flächendeckend prüfen

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

## 6. Code-Hygiene-Sweep (Smells, sammelweise)

Kleinere Auffälligkeiten aus dem Audit. Keine einzelne ist
gravierend; in Summe drücken sie die Code-Note. Eine Sweep-PR
nach §2 ist effizienter als sieben Einzel-Edits.

- [ ] **Logging-Stil-Konsistenz:** `Timber.Forest.tag(...)` vs.
  schlichtes `Timber.d` — eine der beiden Varianten als Regel
  setzen (oder begründen, warum beide koexistieren) und in
  CLAUDE.md festhalten.
- [ ] **Kommentar-Sprache:** Mischung DE/EN im selben File ist
  Tagesform der bisherigen Edits. Repo-Konvention ist
  **Englisch** für Source-Code, README.md und CLAUDE.md
  (TODO.md selbst darf DE oder EN sein). Pro File auf
  Englisch vereinheitlichen.
- [ ] **`runBlocking` in `attachBaseContext`** —
  `KolibriLauncherApp.kt:120`. Defensible (DataStore-Read vor
  Hilt verfügbar, ACRA-Init braucht das Result), aber StrictMode-
  relevant. Prüfen, ob der Pfad sich auf `runBlocking(Dispatchers.IO)`
  verbessern lässt, oder den Grund im KDoc verewigen.

---

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/test/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
