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
| 3 | `BackupRepositoryImpl` zerlegen | verworfen, Memo bleibt | — |
| 5 | `MainDispatcherRule`-Audit über Tests | erledigt, Memo bleibt | — |

**Empfohlene Reihenfolge bei freier Wahl:** Aktuell keine offene Hauptaufgabe. §2 ist optional fortsetzbar, kein Blocker.

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

## 3. (Memo, verworfen) `BackupRepositoryImpl` zerlegen

Ursprüngliche Annahme: 1.356-Zeilen-Klasse mit 9 Repo-Dependencies +
9 separaten Test-Files = klassischer God-Class-Smell, Split in
`BackupExporter` / `BackupImporter` / `BackupZipFormat` empfohlen.

**Bei näherer Betrachtung verworfen.** Der Split würde den
Konstruktor-Smell **nicht beheben, sondern duplizieren**:

- `BackupExporter` bräuchte alle 8 Repositories zum *Lesen*.
- `BackupImporter` bräuchte alle 8 Repositories zum *Schreiben*.
- `BackupZipFormat` bräuchte 0 Repositories (reine File-IO).

Statt einer Klasse mit 9 Deps hätten wir zwei Klassen mit je 8 Deps
plus eine ohne. Per-Klasse-Konstruktor kürzer, Gesamt-Injection-
Komplexität verdoppelt — pure Kosmetik mit negativer Engineering-
Bilanz. Backup/Restore ist inhärent das Dual jedes persistierten
Repositories: jede Klasse die das tut, *muss* alle Repos injecten.

Auch das „9 Test-Files = God-Class"-Argument kippt: die Tests sind
9 *orthogonale Concerns* (Security/Doomsday/Wallpaper/...) die
zufällig dieselbe Production-Klasse exercieren. Splitting der
Production-Klasse, um Test-File-Namen zu spiegeln, wäre Cargo-Cult.
Die Tests beweisen schon, dass die Concerns unabhängig *testbar*
sind — ohne dass die Production-Klasse split sein müsste.

**Einzige potenziell sinnvolle Sub-Extraktion:** `BackupZipFormat`
(ZIP-Layout, Magic-Bytes, Versionierung) — 0 Repo-Deps, klare
Single-Responsibility. Aber lohnt sich erst wenn der Format-Code
substantiell wächst (neue Versionen, Migrations-Pfade zwischen
Format-Versionen). Aktueller Stand: zu klein, lebt neben seinem
einzigen Aufrufer.

**Trigger die das Verdikt umstoßen würden:**

- Format-Code wächst auf >250 Zeilen oder bekommt eine eigene
  Versionierungs-State-Machine → `BackupZipFormat` extrahieren.
- Export- und Import-Pfade brauchen genuinely verschiedene Sets
  von Repositories (heute: beide alle 8).
- Multiple Entwickler treffen regelmäßig auf Merge-Konflikte in
  der Datei (Solo-Projekt — irrelevant).

Begründung dauerhaft im File-Header von `data/BackupRepositoryImpl.kt`
(„Size & Refactoring Notes") verewigt, damit ein neuer Reviewer nicht
beim ersten Blick reflexartig den Split nochmal vorschlägt. Das
Pattern ist analog zum HomeFragment-Header.

---

## 5. (Memo, abgeschlossen) `MainDispatcherRule`-Audit über Tests

Audit am 2026-05-01 systematisch über alle ~130 Test-Files
durchgeführt (per Subagent-Klassifizierung, danach Cross-Check via
Grep). **Ergebnis: 0 Lücken.**

### Zahlen

- 42 Files haben `@get:Rule val mainDispatcherRule = MainDispatcherRule()`
- Davon nutzen 40 ihn legitim (alle ViewModel-Tests, alle Delegate-Tests,
  `PackageUpdateReceiverTest`, `FavoritesRepositoryImplShareInTest`)
  plus 2 in abstract Contract-Klassen für Vererbung
- Die übrigen ~88 Test-Files brauchen ihn nicht: pure-logic
  (Calculator/Formatter/State/Resolver), use-case-Wrapper um
  einzelne `suspend fun`s die direkt mit `runTest` getrieben werden,
  Repository-Impl-Tests die `externalScope = null` setzen oder
  `backgroundScope` aus `runTest` nutzen.

### Warum die Konvention sich von selbst hält

Der Audit hat ein Pattern aufgedeckt: jedes ViewModel nimmt seinen
`mainDispatcher` per Konstruktor (typed `CoroutineDispatcher`,
qualifiziert mit `@MainDispatcher`). Wer einen Test für ein neues
ViewModel schreibt, MUSS einen Dispatcher übergeben — und die
naheliegende Antwort ist `mainDispatcherRule.testDispatcher`,
was sofort den Rule mit ergänzt. Der Compiler trichtert in die
Konvention. `BaseViewModel(mainDispatcher: ...)` macht das für alle
Subklassen verpflichtend.

Das Self-Enforcement-Pattern ist jetzt explizit in
`TESTING_CONVENTIONS.kt` unter „SELF-ENFORCEMENT — WHY THE
CONVENTION HOLDS" verewigt, plus Warnung: wenn ein zukünftiges
ViewModel den Konstruktor-Dispatcher überspringt (z. B. `Dispatchers.Main`
hardgecodet im Body), bricht die Self-Enforcement und die
Konvention driftet still. In Reviews ablehnen.

### Memo-Zweck

Sektion bleibt im TODO als Referenz, damit (a) ein neuer Reviewer
beim Auftauchen einer flaky Test-Vermutung sofort weiß dass das
Audit gelaufen ist und das Pattern intakt ist, und (b) der
Self-Enforcement-Insight nicht beim nächsten Refactor verloren
geht.

---

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/test/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
