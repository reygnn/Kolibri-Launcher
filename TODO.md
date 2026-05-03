# Kolibri Launcher — TODO & Roadmap

Lebendes Dokument. Stand: 2026-05-03 (post-audit-Sweep-Session), basierend auf
einer vollständigen Source-Analyse des Repos auf Branch `main` (Version
`0.99.61` / versionCode `79`).

Was hier steht, ist aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Aktueller Status

| § | Titel | Status | Größe |
|---|---|---|---|
| 1 | `Timber.e` vs. `silentError` Severity-Audit | erledigt, Memo bleibt | — |
| 2 | Throwable-Catch-Audit | 229 von 735 Catches weg (über 25 Sweeps in 2 Wellen + Folge-Sweeps), Sweep an seinem natürlichen Ende | klein-mittel pro Sweep |
| 3 | `BackupRepositoryImpl` zerlegen | verworfen, aber siehe §9.1 (Reopen-Vorschlag) | groß |
| 5 | `MainDispatcherRule`-Audit über Tests | erledigt, Memo bleibt | — |
| 6 | Robolectric-Test-Application-Leak | erledigt 2026-05-02, Memo bleibt | — |
| 7 | Selbst-Linter für die 13 Rules | offen | mittel (1-2 Tage) |
| 8 | Time-basierte Test-Konvention | offen | klein-mittel (~5 h) |
| 9 | Architektur-Schritte für 9+ Score | offen | groß (mehrere Tage) |
| 10 | Lib-Pinning regelmäßig revisit | offen, prozessual | klein, periodisch |

**Empfohlene Reihenfolge bei freier Wahl:** §7 (Linter) zuerst — verhindert
zukünftigen Drift, der die häufigste Defekt-Klasse in diesem Repo ist (post-
audit-Session 2026-05-03 hat sieben Doku-vs-Realität-Diskrepanzen aufgedeckt;
ein dummer grep-Hook hätte sie nie entstehen lassen). Danach §8 (Tests-für-
time-Code) als komplementäres Schließen einer Test-Lücke. §9 (Architektur)
ist der größte Brocken und die einzige Möglichkeit, das Repo über 7.5/10
Reife zu bringen — sequenziell, nicht parallel anfangen.

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

Insgesamt **229 Catches in 25 Sweep-PRs** entfernt.

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

Base-Klassen + UI-Glue-Fragment + Nachklapp auf SettingsFragment +
data-layer-CANT_THROW-Reste + ui/-Bündel-Sweeps gruppiert nach
gemeinsamem Pattern:

| PR | Catches | File(s) |
|---|---:|---|
| BaseViewModel | 9 | 1 |
| BaseActivity | 1 | 1 |
| FavoritesSortFragment | 12 | 1 |
| SettingsFragment (Nachsweep) | 4 | 1 |
| data-layer Layer-A Nachsweep | 7 | 4 |
| ZoomableImageView (View-Math) | 4 | 1 |
| UsageExportFragment (UI-Glue) | 1 | 1 |
| ViewModels-Bündel (HiddenApps + CustomNames + Settings) | 4 | 3 |
| Delegates-Bündel (Clock + Gesture) | 4 | 2 |
| Utilities-Bündel (ShortcutLaunchExtensions) | 1 | 1 |
| ScrollViewBorderDecorator (dead-code) | 2 | 1 |
| **Subtotal** | **49** | **17** |

Verifiziert clean (ohne Sweep — entweder schon gesweept oder
Defensive-Religion nicht vorhanden):

- `core/` (1 catch in TextColorCalculator) — Bitmap/Drawable-API,
  legitim.
- `domain/usecase/` (19 catches in 14 Files) — alle DataStore I/O,
  System-API, Flow-Boundary, oder graceful-degradation Fallbacks
  (sortBy mit alphabetical fallback).
- `data/` Repository-Files (Backup, Reset, Wallpaper, TimeBased,
  Favorites, FavoritesOrder, AppUsage, HiddenApps, SwipeActions,
  Settings, CustomNames, Shortcut, ShortcutLauncherService,
  WallpaperFileManager, DataMigrationManager) — alle EXTERNAL I/O,
  per-Item-Recovery in ResetRepository, oder `silentDeath`-Lifecycle-
  Critical-Pfade in DataMigrationManager.

Geprüft, aber kein Sweep nötig (alle Catches sind echte UX-/
Lifecycle-/Per-Item-Recovery, kein CANT_THROW):

- `AppManagementDelegate` (8) — UX-Toast-Recovery + Per-Item-Recovery
  in Flow-Collection.
- `WallpaperViewBinder` (4) — `view.post {}` + User-Callback Boundaries.
- `ThemingDelegate` (3) — UX-Toast-Recovery um DataStore-UseCases.
- `DelegateScope` (1) — die `launchSafe`-Methode selbst, Existenz-
  berechtigung der Abstraktion.
- `AppDrawerAdapter` (5) — RecyclerView-Race / getItem-Race / User-
  Callback-Boundaries (Inline-dokumentiert).
- `BackupFragment` (3) — Activity-Result-Callbacks + Dialog-Loading
  mit User-facing Snackbar-Recovery.
- `ContextMenuHelper` (2) — Fragment-Manager-Lifecycle-Race.
- `FlowCollection` (4) — bewusste Two-Layer-Defense-Konstruktion mit
  Inline-KDoc-Schutz.
- `OnboardingViewModel` / `BackupViewModel` / `SwipeActionsViewModel` /
  `FavoritesSortViewModel` (3+3+2+1) — sealed-Result-State-Machine-
  Recovery + sendEvent-Toast.
- `DoubleClickDetector` (1 grep-Treffer) — Catch ist im Klassen-
  KDoc-Code-Beispiel, nicht im Code.

`MainActivity` (~37 Catches): konservativ geprüft 2026-05-02. Nach
dem früheren MainActivity-Mini-Sweep (-3) sind die verbleibenden
Catches alle echte Boundaries — System-API (`registerReceiver`,
`startActivity`, `LauncherApps`, `WallpaperManager`), View-Inflation
(`setContentView`, `installSplashScreen`), DataStore-I/O,
Dialog-Lifecycle-Race (`MaterialAlertDialogBuilder.show`,
`DialogFragment.show`), BroadcastReceiver-Callback,
`silentDeath`-Pfade als HOME-Activity-Recovery, plus UX-Toasts
in `handleSpecificEvent`. Keine eindeutigen CANT_THROW-Catches.
Hinweis: `mainActivityExceptionHandler` (L82-93) hat einen nested
Catch um `silentError + if(DEBUG) throw`, der die Rule-9-DEBUG-
throw-Semantik lokal aufhebt — das ist ein Bug-Hint, kein Sweep-
Material; wenn er gefixt wird, dann als bewusste Verhaltens-
änderung im DEBUG-Build.

In jeder bearbeiteten Klasse sind die behaltenen Catches per Inline-
Kommentar als „echte EXTERNAL/Lifecycle"-Pfade markiert, damit ein
Reviewer den entfernten Mustercode nicht reflexartig wieder einfügt.

### Reststand: 506 Catches (Stand 2026-05-02)

- **HomeFragment (~104)** — eigenes Lifecycle-Restructure-Projekt,
  KDoc im File-Header beschreibt den Pfad. NICHT als Sweep angehen,
  wartet auf strukturelles Refactoring (`_binding?.let { }` +
  `viewLifecycleOwner.lifecycleScope`).
- **MainActivity (~37)** — geprüft 2026-05-02, nichts mehr zu sweepen.
- **Repository-Files** — `BackupRepositoryImpl` (16),
  `ResetRepositoryImpl` (13), `UsageExportRepositoryImpl` (8),
  `InstalledAppsRepositoryImpl` (8), `FavoritesRepositoryImpl` (7) etc. —
  überwiegend echte EXTERNAL I/O. Verifiziert 2026-05-02.
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
  2026-05-02, Reststand (3 + 6) sind echte Coroutine-/Flow-/Toast-
  Boundaries.
- **`FavoritesSortFragment` (7)** — gesweept 2026-05-02, Reststand
  sind Bundle-Parsing, Flow-Collection-Wrapper, Per-Item-Recovery,
  setFragmentResult-Lifecycle-Race, Toast-IPC.
- **`SettingsFragment` (48)** — Nachsweep 2026-05-02, Reststand
  sind Listener-Bodies mit `viewLifecycleOwner`-Zugriff (Lifecycle-
  Race), direkte System-API (`startActivity`, `parentFragmentManager`,
  `RoleManager`, `setPreferencesFromResource` XML-I/O), 9 Flow-
  Collection-Wrapper in `observeSettings`, Dialog-/View-Inflation,
  plus die spezifische `NumberFormatException`-UX-Recovery für
  splitModeThreshold.
- **`ZoomableImageView` (3)** — gesweept 2026-05-02, Reststand sind
  `composeToBitmap` (OOM auf großen Bitmaps), `onTouchEvent`
  (MotionEvent + ScaleGestureDetector), `ScaleListener.onScale`
  (System-API).
- **`UsageExportFragment` (6)** — gesweept 2026-05-02, Reststand sind
  Activity-Result-Launcher, Dialog, Flow-Collection, Toast.
- **ViewModels** (HiddenApps 2, CustomNames 3, Settings 1) —
  gesweept 2026-05-02, Reststand sind UX-Toast / State-Machine
  Recovery um DataStore I/O.
- **`ClockDelegate` (2)** — gesweept 2026-05-02, Reststand sind
  BroadcastReceiver-Intent-Boundary + `registerReceiver`-System-API.
- **`ScrollViewBorderDecorator` (3)** — Dead-Code-Sweep 2026-05-02
  (Datei bleibt deaktiviert), Reststand sind die Resource-API-
  Catches um `getDimensionPixelSize` / `getDimension`.
- Use cases (~1-3 pro File) — verifiziert 2026-05-02 als alles
  echte EXTERNAL/Result-Wrapper, kein CANT_THROW.

### Optionale nächste Sweeps

Nach der Verifikations-Welle 2026-05-02 sind die einzigen offenen
Punkte strukturelle Projekte — keine Sweeps mehr im engeren Sinn:

- [ ] **HomeFragment LIFECYCLE-Restructure** — größeres eigenes Projekt,
  nicht als Sweep. Sollte erst angegangen werden, wenn die vier
  Pure-Logic-Extraktionen aus dem File-Header-KDoc finalisiert sind.
- [ ] **`ScreenLockAccessibilityService` (~16)** — Service-Test-
  Strategie unklar; wenn Pattern etabliert, ist hier Lohn drin.
- [ ] **`MainActivity.mainActivityExceptionHandler` Bug-Hint** — der
  nested Catch um `silentError + if(DEBUG) throw` hebt die Rule-9-
  DEBUG-throw-Semantik lokal auf. Fix wäre eine bewusste DEBUG-
  Verhaltensänderung, kein reiner Sweep.

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

## 7. Selbst-Linter für die 13 Rules

**Motivation:** Die post-audit-Sweep-Session 2026-05-03 hat sieben Stellen
aufgedeckt, an denen die Doku Wahrheiten behauptete, die der Code nicht
hielt — die mit Abstand häufigste Defekt-Klasse in diesem Repo. Konkrete
Funde dieser einen Session:

- **Rule 9** behauptete „kein nacktes `Timber.e` außerhalb `KolibriLauncherApp`
  und `TimberWrapper`" — 53 Verstöße gefunden, davon 29 echte App-Code-Drift.
- **CLAUDE.md Rule 1+2+4+5** redeten von „Manager"-Schreibweise — alle 17
  Production-Klassen sind seit Commit `0f9e7be` `*RepositoryImpl`.
- **AUDIT.md §4.4** behauptete „Non-Null `!!` — null Vorkommen im Hauptcode" —
  eines in `SettingsActivity.kt:108`.
- **AUDIT.md §2.5** behauptete „AppConstants ist diszipliniert genutzt" —
  Counterexample mit Magic-Numbers in `ObserveInstalledAppsUseCase`.
- **AUDIT.md §5.8** behauptete „Hardcoded UI-Strings in Code — null
  gefunden" — `WallpaperLayer.AVAILABLE_BLEND_MODES` mit 12 EN-Labels.

**Pattern:** Negativ-Findings im Audit (313 `relaxed = true` etc.) waren
empirisch belegt. Nur die positiven „alles in Ordnung"-Aussagen kollabierten
auf grep. Die Doku wurde nicht stichprobenartig verifiziert.

**Was der Linter prüfen soll** (in Reihenfolge nach Wert / Drift-Risiko):

- Rule 9 — kein nacktes `Timber.e(` außerhalb der erweiterten Exception-Liste
  (`KolibriLauncherApp`, `TimberWrapper`, `BaseActivity`, `BaseViewModel`,
  `CrashReportLimiter`, `CrashReportConsent`, `BackupFragment.kt:50`).
  Pures Grep mit Negativ-Filter. Highest ROI.
- Rule 12 — kein `Timber.Forest.`. Trivial mit Grep.
- Naming-Konvention — keine `Manager`-Schreibweise außer den dokumentierten
  Ausnahmen (`WallpaperFileManager`, `DataMigrationManager`).
- Rule 13 — keine deutschen Kommentar-Zeilen in den letzten N Commits
  geänderter Files. Würde nur neue Zeilen prüfen, bestehendes Deutsch in
  alten Files ignorieren.
- Rule 11 — keine `try { … } catch (Throwable)` um Pure-Calls. Heuristisch:
  catch-Body ist nur silentError-Aufruf, try-Body enthält nur `.filter`/
  `.map`/`.sortedBy`/`String.lowercase()` etc. Nicht 100% präzise, aber
  fängt offensichtliche Verstöße.
- Hardcoded UI-Strings im Kotlin — String-Literals in `data class`-Defaults
  oder Konstanten, die in `res/values/strings.xml` gehören.

**Form:** Gradle-Task `./gradlew checkConventions` plus optional Pre-Commit-
Hook. Keine CI-Erzwingung anfangs — soll lokal lauffähig bleiben.
Output: Liste der Verstöße mit `file:line` und Rule-Referenz.

**Aufwand:** 1-2 Tage initial. Danach selbst-wartend. Jede neue Rule wird
beim Aufnehmen in CLAUDE.md gleich mit Linter-Check ergänzt — das ist die
Disziplin, die fehlt.

**Wert:** Beendet die häufigste Defekt-Klasse dauerhaft. Die nächste Audit-
Iteration findet keinen neuen Doku-Drift mehr. Den ROI sieht man bei jeder
nächsten Session.

---

## 8. Time-basierte Test-Konvention

**Motivation:** Der Retry-Counter-Bug in `ObserveInstalledAppsUseCase` (siehe
Commit `f8a578b`) — ein Class-Field-State der über Flow-Invocations hinweg
persistierte und Backoff-Delays falsch skaliert hat — war Production-Code seit
File-Erstellung. Bestehende Tests haben ihn nicht gepinnt: alle 7 Tests testen
„passt das Resultat?" aber keiner testet „passt die Zeit-Differenz?".

**Symptomatisch:** time-basierte Verhalten brauchen explizite zeit-bezogene
Asserts. Im Repo betrifft das:

- Retry-Backoffs (`ObserveInstalledAppsUseCase` — jetzt gepinnt)
- Debounces (`AppDrawerFragment` Search-Debounce, `HomeFragment` Spacing-Cache)
- Timeouts (`PackageUpdateReceiver` `withTimeout`, `BackupFragment` Preview-Timeout)
- Throttles und WhileSubscribed-Timeouts (`shareIn`-Repos in `data/`)

**Konvention:** Jeder neuer/touched Code-Pfad mit `delay()`, `withTimeout`,
`retry()` braucht einen Test, der:

1. `runTest { … }` mit `testScheduler.currentTime` snapshottet vor und nach
   dem Verhalten.
2. Asserted, dass die virtuelle Zeit-Differenz dem erwarteten Backoff
   entspricht — exakter Wert oder symmetrische Eigenschaft (z. B. „beide
   Invocations kosten gleich viel").
3. Im Body-Comment dokumentiert, *was* die Test-Zeit pinnt — sonst sind
   solche Tests bei Änderungen schwer zu lesen.

**Reference-Implementation:** `ObserveInstalledAppsUseCaseTest` →
„retry counter resets between invocations on IOException backoff" zeigt das
Pattern. Verifiziert mit Bug-restore: Test wird rot, wenn man den
Class-Field-Counter wiederherstellt — also pinnt er das Verhalten korrekt.

**Aufwand:** ~30 min pro Test. Bei aktuell ~10 retry/delay/timeout-Sites in
`app/src/main/` ist das ~5 h Gesamtarbeit, gut parallelisierbar.

**In CLAUDE.md / TESTING_CONVENTIONS.kt aufnehmen:** Eine neue Section
„TIME-BASED ASSERTIONS" mit der obigen Konvention plus dem Reference-Test.

---

## 9. Architektur-Schritte für 9+ Score

**Motivation:** Final-Review 2026-05-03 hat das Repo bei 7.5/10 eingeordnet.
Stärken sind Architektur-Disziplin, Doku-Tiefe, Test-Disziplin. Was den
Score deckelt, sind vier konkrete Architektur-Brocken. Sequenziell, nicht
parallel anfangen.

### 9.1. `BackupRepositoryImpl` entkoppeln (TODO §3 reopen)

§3 hat den Split als „würde 9 Repo-Deps duplizieren" verworfen. Die
Begründung hält bei genauerem Hinsehen nicht: das Argument behandelt
die Frage „wie viele Constructor-Args braucht's?", aber das ist nicht
das Problem. Das Problem ist, dass eine 1444-Zeilen-Klasse drei
Verantwortlichkeiten mischt:

1. **Daten sammeln** (lese alle 9 Repos, baue `BackupData`)
2. **Daten serialisieren / deserialisieren** (JSON, ZIP, Validation)
3. **Datei-I/O** (URI, ContentResolver, Streams)

Sauberer Split:

- `BackupDataAssembler` (Composition-Schicht) — bekommt die 9 Repos,
  baut `BackupData`, ist gegen Fakes vollständig testbar ohne URI.
- `BackupSerializer` — Pure-Logic, JSON↔BackupData, keine Repos, keine
  I/O. Trivially testbar.
- `BackupRepositoryImpl` (verbleibend) — Datei-I/O, Uri-Validation,
  Permission-Handling, Größen-Caps. Der Robolectric-Smoke-Test reicht.

**Wert:** drei Klassen mit je einer Verantwortung, 1444 → ~3× 400-500
Zeilen. Tests werden pointierter: der Assembler testet „werden alle
Felder befüllt?" gegen Fakes, der Serializer testet „round-trip + alle
Edge-Cases" als Pure-Logic, der Repo-Rest testet nur die wenigen
URI/Stream-Sites mit Robolectric.

**Aufwand:** 2-3 Tage (inkl. Test-Migration der 5 Spec-Files).

### 9.2. Modul-Split

Single-Module bei 16 Repositories + 50 UseCases + 10 ViewModels ist groß
genug, dass Build-Cache-Resilienz und Compile-Zeit deutlich darunter
leiden.

**Vorgeschlagene Modul-Struktur:**

- `:domain` — `domain/repository/`, `domain/usecase/`, `domain/model/`,
  `core/`. Kein Android-SDK, nur Kotlin + Coroutines + Hilt-Annotations.
- `:data` — `data/`, `di/RepositoryModule`, `di/DataStoreModule`. Nur
  Datenzugriff.
- `:app` (resp. `:ui`) — alles unter `ui/`, `KolibriLauncherApp`, alle
  Activities + Fragments + ViewModels.

AGP 8.13 unterstützt das nahtlos. Hilt-Multi-Module-Setup ist gut
dokumentiert. `gradle/libs.versions.toml` ist schon da — Modul-Files
würden sie wiederverwenden.

**Wert:** inkrementelle Builds nach Domain-Änderungen werden ~3× schneller,
weil `:ui` nicht neu compiled wird wenn nur ein Repo ändert. Test-
Isolation: `:domain`-Tests laufen ohne `:data`/`:ui`-Compile.

**Aufwand:** 2-3 Tage (initial Modul-Aufteilung + Hilt-Multi-Module-
Wiring + ggf. einige zirkuläre Imports auflösen).

### 9.3. `HomeFragment`-Edit-Submodul

2657 Zeilen, davon ~600-800 für den Wallpaper-Edit-Modus (Layer-
Buttons, Snap-Controls, Save/Cancel-Toolbar, Touch-Interception,
Drag-State). Diese Section ist eine **echte Boundary**, nicht UI-Glue:

- Eigener State (`WallpaperEditState`, `WallpaperEditTransition` —
  bereits extrahiert).
- Eigene Lifecycle-Phasen (enter / commit / cancel).
- Eigene Touch-Interception (`wallpaperTouchInterceptor`).
- Eigene Toolbar mit eigener Logik.

**Vorschlag:** `WallpaperEditFragment` (oder `WallpaperEditController` als
Helper-Klasse) — bekommt `wallpaperContainer`, `wallpaperEditOverlay`,
ein Callback für Save/Cancel. `HomeFragment` wäre dann auf ~1900-2000
Zeilen reduziert und enthielte nur noch das Home-Render + die Wallpaper-
Edit-Aktivierung.

**Wert:** der Edit-Mode wird testbar (eigener Fragment-Test mit
Robolectric+Hilt-Pattern wäre möglich), Home-Render wird isoliert.
Reviewbar: heute ist „enter edit-mode → tap layer-up → cancel" ein
Pfad durch 5 verschiedene Sections in einer Datei.

**Aufwand:** 1-2 Tage. Mittleres Risiko (Lifecycle-States müssen
korrekt mit-migriert werden).

### 9.4. `ResetRepositoryImpl`: Schleife statt Copy-Paste

11 strukturidentische try/catch-Blöcke um `child.purgeRepository()`-
Calls. Wenn Repo Nr. 12 dazukommt, vergisst jemand den Block.

**Fix:** ein `Purgeable.purgeAllSafely(repos: List<Pair<String, Purgeable>>)`
als Extension oder ein lokaler `forEach`-Helper, der den `try/catch
(CancellationException) throw e catch (Throwable) silentError(...)`-
Block einmal kapselt.

**Aufwand:** 1 Stunde. Datei wird drittel-en. Drift-Resistenz.

---

## 10. Lib-Pinning regelmäßig revisit

**Motivation:** ANRWatchdog `1.4.0` ist von 2018 ohne aktive Maintenance.
Hilt `2.57.2` mit „DO NOT UPGRADE" — der ursprüngliche Grund (welche
Major-Breaking-Änderung?) ist im `libs.versions.toml`-Kommentar nicht
festgehalten. Pins werden zu Tech-Debt, je länger sie ohne Re-Check
stehen.

**Action:** Quartalsweiser Review-Termin (~30 min):

1. Für jedes „DO NOT UPGRADE"/„DO NOT DOWNGRADE" prüfen, ob die
   Begründung noch aktuell ist (z. B. Hilt 2.58+ released — Migration
   noch problematisch?).
2. Für jede außer-Maintenance-Lib (ANRWatchdog) prüfen, ob es eingebaute
   Alternativen gibt (z. B. Android's eigenes ANR-Tracing seit API 30+).
3. Findings im `libs.versions.toml`-Kommentar als Datum einarbeiten:
   `# DO NOT UPGRADE — letzter Recheck 2026-Q3, Grund: <kurz>`.

**Ohne diesen Prozess** ist die Pinning-Disziplin selbst ein Drift-Risiko:
heute ist „DO NOT UPGRADE" begründet, in 12 Monaten ist es Karawanen-
Aberglaube.

---

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/test/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
