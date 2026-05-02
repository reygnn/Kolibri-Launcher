# Kolibri Launcher — TODO & Roadmap

Lebendes Dokument. Stand: 2026-05-02, basierend auf einer vollständigen Source-Analyse
des Repos auf Branch `main` (Version `0.99.61` / versionCode `79`).

Was hier steht, ist aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Aktueller Status

| § | Titel | Status | Größe |
|---|---|---|---|
| 1 | `Timber.e` vs. `silentError` Severity-Audit | erledigt, Memo bleibt | — |
| 2 | Throwable-Catch-Audit | 202 von 735 Catches weg (über 17 Sweeps in 2 Wellen + Folge-Sweeps), optionale Folge-Sweeps offen | klein-mittel pro Sweep |
| 3 | `BackupRepositoryImpl` zerlegen | verworfen, Memo bleibt | — |
| 5 | `MainDispatcherRule`-Audit über Tests | erledigt, Memo bleibt | — |
| 6 | Robolectric-Test-Application-Leak | erledigt 2026-05-02, Memo bleibt | — |

**Empfohlene Reihenfolge bei freier Wahl:** Aktuell keine offene Hauptaufgabe. §2 ist optional fortsetzbar, kein Blocker. Nächster Sweep-Kandidat: `MainActivity` (37 Catches, **konservativ** angehen wegen HOME-Activity-Sensibilität).

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

Insgesamt **202 Catches in 17 Sweep-PRs** entfernt.

**Erste Welle (vor Robolectric-Pattern):**

| PR | Catches | Files |
|---|---:|---|
| Pilot `GetDrawerAppsUseCase` | 6 | 1 |
| Layer-A (`core`/`data`/`domain`) | 9 | 4 |
| Adapter-Sweep (App- + FavoritesAdapter) | 29 | 2 |
| SettingsFragment-Sweep | 32 | 1 |
| MainActivity-Mini | 3 | 1 |
| **Subtotal** | **79** | **9** |

**Zweite Welle (Marathon 2026-05-01, mit Robolectric-Backstop):**

Nach dem Robolectric+Hilt-Test-Pilot (siehe [TESTING_CONVENTIONS.kt
→ ROBOLECTRIC + HILT — ACTIVITY / FRAGMENT TESTS]
(app/src/test/java/com/github/reygnn/kolibri_launcher/TESTING_CONVENTIONS.kt))
war für UI-Files endlich ein Test-Backstop möglich. Damit konnte die
Activity/Fragment-Surface gesweept werden:

| PR | Catches | File |
|---|---:|---|
| AppDrawerFragment | 25 | 1 |
| OnboardingActivity | 12 | 1 |
| CustomNamesActivity | 14 | 1 |
| HiddenAppsActivity | 12 | 1 |
| SwipeActionsActivity | 11 | 1 |
| SettingsActivity | 4 | 1 |
| AppContextMenuDialogFragment | 8 | 1 |
| WallpaperDelegate (JVM-test backstop) | 12 | 1 |
| PackageUpdateReceiver | 3 | 1 |
| **Subtotal** | **101** | **9** |

Plus **6 neue Robolectric-Tests** (`MainActivityRobolectricTest`,
`AppDrawerFragmentRobolectricTest`, `CustomNamesActivityRobolectricTest`,
`HiddenAppsActivityRobolectricTest`, `SwipeActionsActivityRobolectricTest`,
`SettingsActivityRobolectricTest`, `AppContextMenuDialogFragmentRobolectricTest`)
zusätzlich zum Pilot `OnboardingActivityRobolectricTest`.

**Folge-Sweeps (2026-05-02 ff.):**

Base-Klassen-Pass + ein UI-Glue-Fragment ohne neuen Robolectric-Backstop
(Logic ist schon im ViewModel-Test gepinnt):

| PR | Catches | File |
|---|---:|---|
| BaseViewModel | 9 | 1 |
| BaseActivity | 1 | 1 |
| FavoritesSortFragment | 12 | 1 |
| **Subtotal** | **22** | **3** |

Geprüft, aber kein Sweep nötig (alle Catches sind echte UX- oder
Per-Item-Recovery, kein CANT_THROW): `AppManagementDelegate` (8 Catches).

In jeder bearbeiteten Klasse sind die behaltenen Catches per Inline-
Kommentar als „echte EXTERNAL/Lifecycle"-Pfade markiert, damit ein
Reviewer den entfernten Mustercode nicht reflexartig wieder einfügt.

### Reststand: 533 Catches (Stand 2026-05-02)

- **HomeFragment (~104)** — eigenes Lifecycle-Restructure-Projekt,
  KDoc im File-Header beschreibt den Pfad. NICHT als Sweep angehen,
  wartet auf strukturelles Refactoring (`_binding?.let { }` +
  `viewLifecycleOwner.lifecycleScope`).
- **MainActivity (~37)** — nächster geplanter Sweep, **konservativ**
  wegen HOME-Activity-Sensibilität.
- **Repository-Files** — `BackupRepositoryImpl` (16),
  `ResetRepositoryImpl` (13), `UsageExportRepositoryImpl` (9),
  `FavoritesRepositoryImpl` (7) etc. — überwiegend echte EXTERNAL-
  I/O-Catches. Lassen.
- **`KolibriLauncherApp` (~16)** — Rule-7-Whitelist, **bleibt
  unangetastet**.
- **`ScreenLockAccessibilityService` (16)** — AccessibilityService,
  schwer zu testen. Mögliche zukünftige Welle.
- **`CrashReportLimiter` (8)** — als 2. Rule-5-Exception dokumentiert
  (sync API constraint von ACRA-Handler), bleibt.
- **`CrashReportConsent` (5)** — Coroutine-Boundary + EXTERNAL.
- **`LayoutCustomizationDialogFragment` (8)** — bereits clean
  strukturiert mit eigenen `safeRun`/`launchSafe` Helpers.
- **`AppManagementDelegate` (8)** — geprüft 2026-05-02, alle
  Catches sind echte UX-/Per-Item-Recovery, kein CANT_THROW.
- **`PackageUpdateReceiver` (9)** — gesweept, Reststand sind
  legitime Receiver-Lifecycle/Hilt-init Catches.
- **Base-Klassen** (`BaseViewModel`, `BaseActivity`) — gesweept
  2026-05-02, Reststand (4 + 6) sind echte Coroutine-/Flow-/Toast-
  Boundaries.
- **`FavoritesSortFragment` (7)** — gesweept 2026-05-02, Reststand
  sind Bundle-Parsing, Flow-Collection-Wrapper, Per-Item-Recovery,
  setFragmentResult-Lifecycle-Race, Toast-IPC.
- Use cases (~1-3 pro File) — meistens defensive, klein.

### Optionale nächste Sweeps

- [ ] **MainActivity (~37)** — konservativ (HOME-Activity, alles
  rund um App-Start, Splash, Fragment-Wiring; Catches die Lifecycle-
  Races schützen sind im Zweifel zu behalten).
- [ ] **HomeFragment LIFECYCLE-Restructure** — größeres eigenes Projekt,
  nicht als Sweep. Sollte erst angegangen werden, wenn die vier
  Pure-Logic-Extraktionen aus dem File-Header-KDoc finalisiert sind.
- [ ] **`ScreenLockAccessibilityService` (~16)** — Service-Test-
  Strategie unklar; wenn Pattern etabliert, ist hier Lohn drin.
- [ ] Restliche Repository-Files können vereinzelt CANT_THROW-Catches
  haben — File-für-File-Audit lohnt sich kaum.

### Default-Disposition

Siehe **CLAUDE.md Rule 11** — die Default-Regel für neue Catches lebt
dort als projektweite Konvention. Im Review die Vier-Kategorien-Frage
stellen (siehe Rule 11 + `HomeFragment.kt:157-208`). Robolectric-
Pattern für UI-Tests siehe TESTING_CONVENTIONS.kt.

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

## 6. (Memo, abgeschlossen) Robolectric-Test-Application-Leak

Erkannt und behoben am 2026-05-02. **Symptom:** GitHub-CI grün vor
2026-05-01, danach rot. Lokal hängende Test-Suite mit
`OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
"|ANR-WatchDog|"` (mehrfach gleichzeitig), plus
`java.lang.instrument ASSERTION FAILED ... can't create name string`.
User-Beobachtung am Build-Server: 100% CPU, mehrere hängende
Java-Prozesse.

**Ursache:** `AndroidManifest.xml` deklariert
`android:name=".KolibriLauncherApp"`. Robolectric-Tests ohne
explizites `@Config(application = ...)`-Override luden die echte
`KolibriLauncherApp`, deren `onCreate()` startete einen
`ANRWatchDog`-Thread (von `com.github.anrwatchdog`, `extends Thread`,
non-daemon) plus ACRA's globalen `UncaughtExceptionHandler`, Timber-
Trees, `BroadcastReceiver`-Registration und einen
`CoroutineScope`/`SupervisorJob`. Nichts davon wird zwischen
Robolectric-Tests aufgeräumt — jede Test-Klasse leakte je einen.

Bei Test-Executor `-Xmx512m` und ~10+ Robolectric-Test-Klassen
(Wallpaper, Backup, plus die 6 Activity-Robolectric-Tests aus dem
2026-05-01-Marathon) sammelten sich genug ANRWatchDog-Threads (jeder
sampelt alle 5 s den Main-Thread) um den Heap zu sprengen.

**Fix:** `app/src/test/resources/robolectric.properties` mit
`application=android.app.Application` als globaler Default. Hilt-
Tests setzen weiterhin per-test `@Config(application =
HiltTestApplication::class)` — per-test schlägt Properties-Default.

**Verifikation:** Volle Test-Suite davor: hing 4 min+ und brach mit
OOM ab. Nach Fix: 35 s, 157 Klassen, 2202 Tests, 0 Failures.

Memo bleibt damit ein neuer Reviewer beim Auftauchen ähnlicher
„hängende Tests / OOM in Watchdog-Thread"-Symptome sofort den
zentralen Fix-Punkt findet (`robolectric.properties`) und nicht ad
hoc per-Test `@Config`-Overrides streut.

---

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/test/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
