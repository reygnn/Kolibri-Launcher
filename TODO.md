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
| 5 | `MainDispatcherRule`-Audit über Tests | offen | mittel |
| 7 | `CrashReportConsent` von SharedPreferences nach DataStore migrieren (Rule-5-Verstoß) | offen | mittel |
| 8 | `@MockK`-Variablen-Naming vereinheitlichen (mock-Präfix oder nicht) | offen | klein |

**Empfohlene Reihenfolge bei freier Wahl:** §3 (Split, danach Test-Splitting) → §5 (Test-Konsistenz). §7 und §8 sind orthogonal zum Rest und können jederzeit dazwischen. §2 ist optional fortsetzbar, kein Blocker.

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

## 7. `CrashReportConsent` von SharedPreferences nach DataStore migrieren

Aufgedeckt während der §6(g)-Analyse: `ui/util/CrashReportConsent.kt`
liest und schreibt den ACRA-Consent-Flag direkt über
`context.getSharedPreferences(...)`. Das ist der einzige verbliebene
SharedPreferences-Pfad im App-Code — und damit ein **Verstoß gegen
Rule 5** („DataStore Preferences is the only app storage").

Die `runBlocking`-Frage selbst (§6(g)) ist erledigt — KDoc auf
`KolibriLauncherApp.attachBaseContext` erklärt, warum der Sync-Read
dort intentional ist. Die Wahl der Storage-Schicht ist davon
unabhängig.

### Was zu tun ist

- [ ] DataStore-Preferences-Schema für den Consent-Flag in
  `di/DataStoreModule.kt` ergänzen (eigener `Preferences.Key`).
- [ ] `CrashReportConsent` von `SharedPreferences` auf das DataStore-
  API umstellen. `hasConsent` / `setConsent` / `resetConsent` /
  `hasAsked` (private) sind die Aufrufstellen.
- [ ] **Migration:** bestehende Installationen müssen den alten
  Wert übernehmen. Pfad: `DataMigrationManager` bekommt einen
  Migrations-Schritt, der die Legacy-`SharedPreferences` ausliest
  und in DataStore schreibt, dann die XML-Datei löscht.
- [ ] In `attachBaseContext` weiter `runBlocking { … }` benutzen —
  DataStore-Read ist genauso async, der KDoc-Block bleibt korrekt.
  Nur die Aufruf-Form ändert sich (`dataStore.data.first()` statt
  `prefs.getBoolean(...)`).
- [ ] Den Hinweis im `attachBaseContext`-KDoc auf "TODO §7"
  entfernen, sobald die Migration im Code steht.
- [ ] KNOWN_ISSUES.md §3 textlich anpassen, falls die DataStore-
  Implementation einen anderen StrictMode-Pfad triggert (vermutlich
  ähnlicher DiskRead, aber mit anderem Stack).

### Warum mittel und nicht klein

Ein Migrations-Schritt im `DataMigrationManager` braucht: Read der
alten XML, Write in DataStore, Delete der XML, Idempotenz für
mehrfache Aufrufe, Test in `DataMigrationManagerTest`. Dazu der
Refactor in `CrashReportConsent` (alle vier Methoden) plus der
Test-Update — `CrashReportConsent` hat aktuell wenig direkten
Test, müsste evtl. ergänzt werden. Eine Stunde, vielleicht zwei.

---

## 8. `@MockK`-Variablen-Naming vereinheitlichen

Während des §4-Sweeps aufgefallen: in den Test-Files ist nicht
konsistent, ob MockK-Variablen einen `mock`-Präfix bekommen oder
nicht. Pro File ist es meist konsistent, zwischen Files inkonsistent
— klassischer Drift-by-Author-Daytime.

### Beispiele

**Mit `mock`-Präfix:**
- `mockContext`, `mockPackageManager`, `mockAlarmManager`,
  `mockContentResolver`, `mockIntent` (Android-System-Mocks)
- `mockSettingsRepository`, `mockCustomNamesRepository`,
  `mockLauncherService`, `mockShortcut` (eigene Klassen)

**Ohne Präfix:**
- `context`, `sharedPreferences`, `launcherApps`, `packageManager`
  (Android-System-Mocks)
- `repository`, `timeBasedEventsRepository` (eigene Klassen)

Ganze Files sind durchgängig in einem Stil, aber nebeneinander
existieren z. B. `TimeBasedEventsRepositoryImplTest.kt` (alle
mit `mock`-Präfix) und `ShortcutRepositoryImplTest.kt` (alle ohne).

### Was zu entscheiden ist

- [ ] **Konvention wählen:** `mock`-Präfix oder nicht.
  - **Pro Präfix:** explizit, sofort sichtbar dass es ein Mock ist;
    de-facto-Standard in vielen MockK-Codebases.
  - **Contra Präfix:** Type-Annotation (`: HiddenAppsRepository`)
    sagt schon was es ist; `@MockK` über der Variable auch;
    Präfix ist redundant.
- [ ] **Sweep:** alle Test-Files auf die gewählte Konvention
  bringen. Per-File Shift+F6 in Android Studio. Klein, aber breit
  verteilt — alle ~60 Test-Files mit MockK-Variablen.
- [ ] **Konvention in `app/src/test/CLAUDE.md` festhalten** unter
  „Test conventions", damit zukünftige Tests nicht wieder driften.

### Bonus-Fund (gleicher Sweep, Rule-1-Verstoß)

`GetFavoriteAppsUseCaseTest.kt:42` deklariert
`favoritesOrderManager: FavoritesOrderRepositoryImpl` — also den
**konkreten Impl**-Type, nicht das Interface. Verstößt gegen Rule 1
(„ViewModels und use cases depend only on the interface, never on
the concrete manager"). Sollte beim Sweep auf `: FavoritesOrderRepository`
korrigiert werden.

---

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/test/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
