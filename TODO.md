# Kolibri Launcher — TODO & Roadmap

Lebendes Dokument. Stand: 2026-05-03 (post-Brocken-C-Sweep), basierend auf
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
| 3 | `BackupRepositoryImpl` zerlegen | umgesetzt 2026-05-03 via §9.1 mit anderem Argument als ursprünglich angesetzt — Memo bleibt | — |
| 5 | `MainDispatcherRule`-Audit über Tests | erledigt, Memo bleibt | — |
| 6 | Robolectric-Test-Application-Leak | erledigt 2026-05-02, Memo bleibt | — |
| 7 | Selbst-Linter für die 13 Rules | teilumgesetzt 2026-05-03 (3 von 6 Rules), Memo bleibt | — |
| 8 | Time-basierte Test-Konvention | erledigt 2026-05-03 (Konvention dokumentiert + 1 Pin), Memo bleibt | — |
| 9 | Architektur-Schritte für 9+ Score | erledigt 2026-05-03 (alle Subteile), Memo bleibt | — |
| 10 | Lib-Pinning regelmäßig revisit | Format etabliert 2026-05-03, nächster Recheck 2026-Q3 | klein, periodisch |
| 11 | Brocken C — `:domain` Source Pure-Kotlin | Source-Refactor erledigt 2026-05-03 (alle 16 Files), Modul-Type-Switch versucht aber durch Timber-AAR blockiert — siehe §12 | — |
| 12 | `:domain` Modul-Type-Switch (§11-Followup) | erledigt 2026-05-03 — Plugin-Switch zu `kotlin("jvm")` durch `KolibriLog`-Indirektion (Timber-AAR-Blocker aufgelöst), Memo unten | — |
| 13 | Brocken B — Test-Isolation pro Modul | weitgehend erledigt 2026-05-03 — `:domain:test` hat 310 Tests in 45 Files (alle pure-JVM, ~5s gesamt), testFixtures in `:domain` etabliert (TimberRule, MainDispatcherRule, 14 Fake*Repositories, 14 abstract Contracts). `:data:test` blockiert durch AGP-android-library testFixtures-Kotlin-Bug — Memo unten | erledigt-für-:domain, blockiert-für-:data |

**Empfohlene Reihenfolge bei freier Wahl:** Brocken A (HomeFragment-
Restructure) als verbleibender großer Brocken aus dem Audit-Snapshot.
§13: Brocken B ist für `:domain` weitgehend durch (310 Tests gewandert);
für `:data` durch einen AGP-Kotlin-testFixtures-Bug blockiert — Move
folgt sobald der Bug fixed ist oder eine Alternative gefunden wird.
§10 ist als wiederkehrender Quartals-Termin etabliert und triggert
sich von selbst — nächster Recheck 2026-Q3.

---

## Wenn du als neue Session hier ankommst

Reihenfolge zum Einlesen, damit du nicht alles selbst neu herleitest:

1. **`CLAUDE.md` (Projekt-Root)** — die 13 harten Regeln, der aktuelle
   Modul-Layout-Snapshot (post-§9.2), und die Workflow-Konventionen.
   Pflicht vor irgendeinem Code-Touch.
2. **`app/src/test/CLAUDE.md`** — Test-Reference. Was existiert,
   warum, was bewusst weggelassen ist, wo anzufangen.
3. **`app/src/test/java/com/github/reygnn/kolibri_launcher/TESTING_CONVENTIONS.kt`** —
   technische Test-Konventionen (Coroutines, MockK, Time-PIN, Robolectric).
4. **Diesen Audit-Snapshot unten** — aktueller Score 8.9, Baseline-
   Vergleich, ein verbleibender großer Brocken zu 9+.

### Ein kalter „leg los"-Auftrag braucht eine Richtungs-Entscheidung

Der TODO.md-Status sagt „keine kleinen Open-Items mehr". Wenn der
User trotzdem Arbeit will, ist das praktisch immer Brocken A
(HomeFragment-Restructure) — frag aber einmal nach. Brocken C ist
durch (§11/§12), Brocken B für `:domain` durch (§13), für `:data`
blockiert durch einen AGP-Bug.

### Workflow-Defaults

- **Branch vor non-trivialer Arbeit.** CLAUDE.md „Git workflow"-
  Sektion. Solo-Workflow per User-Memory: Branch → commit → ff-merge
  → push → branch löschen.
- **Spike vor DI-/Build-riskanten Refactorings.** §9.2-Memo unten.
  Throw-Away-Worktree, validiere die Risiko-Punkte, dann erst der
  echte Branch. Hat 6-11h-Risiko in 4-6h-Plan verwandelt.
- **Vor dem Refactor: lesen.** HomeFragment-Header-KDoc beschreibt
  selbst, was der Pfad ist (siehe „Pfad zu 9+" → HomeFragment unten).
  Den Header lesen, bevor du HomeFragment anfasst.

---

## Audit-Snapshot 2026-05-03 (post-§13)

Brutaler-ehrlicher-Auditor-Hut, ungeschönt. Snapshot nach dem §11/§12/§13-
Sweep gemacht, damit beim nächsten Audit ein Vergleichspunkt da ist.

### Score: **8.9/10** (post-§9.2: 8.2/10, vor §9.2: 7.5/10)

### Was die +0.7 ggü. 8.2 gebracht hat

- **`:domain` ist pure-Kotlin JVM Modul.** Plugin `kotlin("jvm")`,
  keine Android-/AndroidX-/Timber-Imports im Source, kein
  `BuildConfig`, kein `R`. Die ursprünglich im §9.2-Audit „bewusst
  abgegebenen 0.5 Punkte" sind komplett zurückgewonnen.
- **§11 (Source-Sweep):** alle 16 Files aus der 9.2-Variante-1-
  Schuldenliste sind Android-frei (siehe §11-Memo).
- **§12 (Modul-Type-Switch):** TimberWrapper auf `KolibriLog`-
  Indirektion umgestellt (lambda-backed handlers, wired by
  `KolibriLauncherApp.onCreate`). Strings nach `:app/res/`,
  Sealed-Identifier statt `@StringRes Int` in drei Use Cases. Plugin
  von `com.android.library` auf `kotlin("jvm")`, `hilt-core` (JAR)
  statt `hilt-android` (AAR), `kotlinx-coroutines-core` statt
  `kotlinx-coroutines-android`.
- **§13 (Brocken B für `:domain`):** 310 Tests in 45 Files nach
  `:domain/src/test/` gewandert — pure JVM, ~5s gesamt vs. ~90s im
  alten `:app/src/test/`-Run mit Robolectric-Bootstraps. testFixtures
  in `:domain` als Shared-Helper-Mechanismus etabliert (TimberRule,
  MainDispatcherRule, 14 Fake*Repositories, 14 abstract Contract-
  Klassen). Test-Time-Win, der ursprünglich §9.2 versprochen hatte,
  ist jetzt real. `:data:test` bleibt durch AGP-Kotlin-Bug pending
  (siehe §13-Memo).
- **Wert geliefert beim Refactor** (siehe §11-Memo): drei neue
  Domain-Modelle (`LauncherShortcut`, `DomainWallpaperColors`,
  `WallpaperBlendMode`), pure-Kotlin Color-Math (`core/ColorMath`),
  Bundle-Wrapper-Pattern (`AppInfoParcelable`,
  `LauncherShortcutParcelable`). String-Overloads im
  `WallpaperFileManager` als saubere Daten-/UI-Boundary.
- **Dead-Code-Fund:** `TextColorCalculator` (86 LOC + 291 Test-LOC)
  war seit dem Modul-Split unbenutzt — beim Pure-Kotlin-Sweep
  ausgemistet.

### Was die 0.1 unter 9.0 deckelt

1. **HomeFragment ist 1997 Zeilen.** Auch nach `WallpaperEditController`-
   Extraktion und Rule-11-Sweep. §2-Reststand nennt es als „eigenes
   Lifecycle-Restructure-Projekt" — ehrlich, ändert aber nicht den
   Smell. Bleibt der größte Einzel-Brocken zu 9+.

2. **`androidTest/` ist leer.** Aus historischen Gründen (Rule 10),
   gut begründet. Aber: Activity-Lifecycle, AccessibilityService,
   IPC mit System-Diensten sind End-to-End nie auf einem Gerät
   getestet. Robolectric ist guter Backstop, kein vollständiger
   Ersatz.

3. **`:data:test` ist leer.** Repository-Impl-Tests stecken weiterhin
   in `:app/src/test/` weil AGP's testFixtures-Kotlin-Compilation
   für Android-Library-Module nicht funktioniert (siehe §13). Sobald
   das aufgelöst ist, wandern ~12 Repository-Impl-Tests nach
   `:data/src/test/`. Mittelschwer.

### Folge-Schritt zu §11 (erledigt durch §12)

§12 hat den Modul-Type-Switch komplett geliefert. `:domain` ist jetzt
`kotlin("jvm")` mit `hilt-core` und `kotlinx-coroutines-core` als
JVM-only-Dependencies. Memo unten.

### Mittelschwere Deckler

- **MainActivity ~1000 Zeilen mit ~37 Catches.** 2026-05-02 als
  „nichts mehr zu sweepen" geprüft (alle Catches sind echte
  Boundaries). Catches sind Symptom, nicht Krankheit — die
  1000-Zeilen-Activity ist die Krankheit.
- **Test-Isolation pro Modul nicht realisiert.** Tests bleiben in
  `:app/src/test/`. `./gradlew :domain:test` läuft heute praktisch
  leer. §9.2 hat den Compile-Cache-Win für `:app`, aber nicht den
  Test-Time-Win den der ursprüngliche Plan beworben hat.
- **506 verbleibende `catch (Throwable)` im Main-Source.** Die
  meisten sind verifiziert legitim, aber Volumen ist im Branchen-
  vergleich hoch. Crash-Safety-Kultur gut; -Religion reduziert,
  nicht eliminiert.
- **`MainActivity.mainActivityExceptionHandler` Bug-Hint** (siehe
  §2-Reststand). Echter Defekt, dokumentiert aber nicht gefixt.

### Was *nicht* zählt für die Note

Der Doku-Apparat (CLAUDE.md, TODO.md, AUDIT.md, KNOWN_ISSUES.md,
TESTING_CONVENTIONS.kt, Memos) ist überdurchschnittlich gut für ein
Solo-GPLv3-Projekt — fließt aber nicht direkt in die Note ein.
Ein industrieller Reviewer wertet Doku als „nice but secondary";
Code- und Test-Qualität schlagen Doku-Qualität bei der Note. Die
+0.4 von Brocken C sind explizit aus Code-Refactoring gekommen,
nicht aus Doku-Pflege.

### Pfad zu 9+

Brauchst genau **einen** der zwei verbleibenden großen Brocken sauber,
um über 9 zu kommen. Beide halb wäre Score-neutral. Eins davon sauber
bringt 0.4-0.5 — plus der oben beschriebene §11-Modul-Type-Followup
für die letzten 0.1, dann 9.0+.

#### Brocken A — HomeFragment try/catch-Audit + Lifecycle-Restructure

- **Plan lebt bereits im Code.** `HomeFragment.kt` Datei-Header
  (Zeilen ~72-233) beschreibt den Pfad selbst. **Lies den Header
  zuerst** — er erklärt warum die Datei groß ist, was schon erledigt
  wurde, und warum der try/catch-Audit der nächste Schritt ist
  (~30-40% Zeilen-Reduktion).
- **Erster konkreter Schritt:** den `try/catch(Throwable)`-Audit
  laut der Vier-Kategorien-Frame im Header (Expected error,
  Teardown race, Programmer error, Unrecoverable). Pro Catch
  klassifizieren, dann die Programmer-Error-Catches entfernen, die
  Teardown-Race-Catches durch `_binding?.let { }` +
  `viewLifecycleOwner.lifecycleScope` ersetzen.
- **Baseline 2026-05-03:** 1997 Zeilen, 59 `catch (`-Anweisungen,
  davon 51 `catch (e: Throwable)`. Jede Sweep-Runde sollte diese
  Zahlen drücken; Backstop-Test als Regression-Indikator.
- **Aktueller Stand 2026-05-03 (Region 1: Init/Lifecycle):**
  1996 Zeilen, 54 catches (47 `Throwable`). Region 1 hat 4 Throwable-
  Catches und einen dead `CancellationException`-Catch entfernt
  (`onViewCreated`, `registerLayerImagePicker`, `onConfigurationChanged`,
  `checkAndEmitScrollState`). Klassifizierung: alle waren
  Programmer-Error-Catches im Sinn der Vier-Kategorien-Frage —
  `silentError`-swallow, kein realistischer Throw-Vektor (synchroner
  Lifecycle-Init, fire-and-forget-launchSafe-Bodies, oder strukturell
  via `_binding == null`-Guard schon abgesichert). Backstop-Test bleibt
  grün.
- **Aktueller Stand 2026-05-03 (Region 2: Observers — N/A):**
  Bereits in §9.3-Sweep abgehakt. Inline-Kommentare in `observeViewModel`
  / `observeLayoutChanges` dokumentieren das (Zeilen 522-527, 637-638,
  665-667). Region übersprungen.
- **Aktueller Stand 2026-05-03 (Region 3: Layout-Calc + Rendering):**
  2000 Zeilen, 52 catches (44 `Throwable`). Zwei Throwable-Catches in
  `adjustScrollViewWidth` entfernt (outer + inner um `scrollTo`); ein
  Throwable in `recalculateLayoutCache` zu `Resources.NotFoundException`
  narrowed (legitime ProGuard-Throw-Site, sinnvolle UX-Fallbacks). Drei
  Catches in der Region als legit-pattern verifiziert: `applyTopMargin`/
  `applyLayoutToExistingViews` (tight-around-`getDimensionPixelSize` mit
  Fallback) und `renderFavorites`-Per-Item-Recovery-Loop. LOC stieg um
  4 wegen Erklärungs-Kommentaren — Audit-Klarheit ist der Win.
- **Aktueller Stand 2026-05-03 (Region 4: Color/Button-Render, kleine Runde):**
  2005 Zeilen, 52 catches (43 `Throwable`). Ein Throwable in
  `createAppButton` (innerer `getDimensionPixelSize`-Catch) zu
  `Resources.NotFoundException` narrowed; silentError rausgeworfen damit
  der Pattern zu 723/819 matcht. Zwei outer-Per-Item-Recovery-Catches in
  `createAppButton` (Button-Construction + Wrapper) als legit-pattern
  verifiziert + erhalten — sind explizit als preserved-pattern in der
  Funktions-KDoc dokumentiert. Sub-sweep-Findung: die Resources-Catches
  in 723/819 catchen weiter `Exception` (nicht `Resources.NotFoundException`)
  — kleine Inkonsistenz, bewusst aufgehoben für künftigen Consistency-
  Sweep statt Region-Scope-Creep.
- **Aktueller Stand 2026-05-03 (Region 5: Time-Based Chips):**
  ~2010 Zeilen, 50 catches (40 `Throwable`). Ein
  Throwable→Resources.NotFoundException narrowed in
  `updateTimeBasedChips` (innerer Resources-lookup). Zwei innere
  Throwable-Catches um `timeFormatter.formatAlarmTime` /
  `formatCalendarTime` entfernt — pure JVM Kotlin durch
  TimeEventFormatter-Tests abgedeckt, der "fallback to title-only" war
  ein Programmer-Error-Swallow der durch silentError-DEBUG-throw eh
  unerreichbar war. Drei Catches als legit pattern erhalten:
  per-item-Recovery-Loop in `updateTimeBasedChips`, outer
  Chip-Construction-Catches in `createAlarmChip` / `createCalendarChip`.
- **Aktueller Stand 2026-05-03 (Region 6: Gestures + Listeners):**
  1993 Zeilen (-17), 39 catches (31 `Throwable`). Größter Single-Region-
  Schnitt bisher: 11 Catches und 9 Throwable raus. `setupGestures` outer
  entfernt (synchroner Init in `onViewCreated`), drei innere Catches in
  `createGestureListener`-Overrides (`onLongPress` / `onDoubleTap` /
  `onFling`) entfernt — Programmer-Error-Swallows um fire-and-forget
  viewModel-Calls + pure-Kotlin `swipeAnalyzer.analyze`. Throws funnel
  jetzt durch den **erhaltenen** outer-Catch in `setOnTouchListener`
  (system-callback boundary; Android-Input-Dispatcher invoked us — HOME-
  Activity-Resilience-Trade per Header-Frame). Alle 6 Catches in
  `setupDoubleTapActions` entfernt (3 outer Registrations + 3 innere
  onDoubleClick-Bodies); Throws funneln durch den **erhaltenen**
  outer-Catch in `DoubleClickListener.onClick` (gleiche
  system-callback-Boundary-Logik). `setupBackPressHandler`-Catch
  entfernt — Body ist nur addCallback + Timber.d in handleOnBackPressed.
  Backstop-Test grün, Full-Test-Suite grün.
- **Größenordnung:** ~30-40% von 1997 Zeilen — das ist der einzelne
  größte Hebel im Repo. Nicht ein Tag, eher eine Woche, mit Sweep-
  pro-Region statt Big Bang.
- **Risiko:** mittel-hoch. Jeder Catch-Wegfall ist ein potentielles
  Crash-Risiko in der HOME-Activity. Robolectric-Test-Backstop für
  HomeFragment ist Pflicht vor dem Sweep — **Spike erledigt
  2026-05-03**: `app/src/testDebug/java/.../ui/home/HomeFragmentRobolectricTest`
  attached die Fragment in HiltTestActivity ohne Crash, smoke-test
  läuft in 8s. Pattern parallel zu AppDrawerFragmentRobolectricTest.
  Ab jetzt kann pro-Region-Sweep starten (jeweils mit
  Backstop-Run vor und nach jedem Sweep).
- **Was nicht zu machen ist:** den im Header dokumentierten
  „Fragment-delegate split" (FavoritesRenderer, TimeChipsRenderer
  etc.). Das ist der Cargo-Cult-Move — der Header begründet bewusst
  warum es deferred ist.

#### Brocken B — Tests pro Modul redistribuieren

**Begonnen 2026-05-03** — Shared-Helper-Strategie entschieden
(Gradle `java-test-fixtures`-Plugin), Pattern etabliert, 4 pure-
domain Tests migriert. Memo in §13. Restliche Migration ist
inkrementell — siehe §13 für die Liste der Folge-Schritte und das
noch nicht-triggierte `:data`-Setup.

#### Brocken C — `:domain` als pure-Kotlin Modul

**Source-Sweep erledigt 2026-05-03** über alle 16 ursprünglich
gelisteten Files. Memo unten in §11. Modul-Type-Switch (Android
Library → pure-Kotlin) ist der noch ausstehende Folge-Schritt —
beschrieben oben unter „Folge-Schritt zu §11".

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

## 3. (Memo, umgesetzt 2026-05-03) `BackupRepositoryImpl` zerlegen

**Erste Runde — verworfen.** Ursprünglicher Vorschlag war ein
`BackupExporter` / `BackupImporter` / `BackupZipFormat`-Split. Der
würde den Konstruktor-Smell **nicht beheben, sondern duplizieren**:

- `BackupExporter` bräuchte alle 8 Repositories zum *Lesen*.
- `BackupImporter` bräuchte alle 8 Repositories zum *Schreiben*.
- `BackupZipFormat` bräuchte 0 Repositories.

Statt einer Klasse mit 9 Deps hätten wir zwei Klassen mit je 8 Deps
plus eine ohne — pure Kosmetik mit negativer Engineering-Bilanz.

**Zweite Runde — umgesetzt mit anderem Argument.** Aufgesplittet
entlang einer *anderen* Achse: nicht
„export-vs-import-vs-format", sondern
„pure-data-vs-repo-composition-vs-android-runtime":

- [`BackupSerializer`](app/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupSerializer.kt) — pure JSON, 0 Deps
- [`BackupDataAssembler`](app/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupDataAssembler.kt) — 8 Repos (read+write in EINER Klasse, keine Duplikation)
- [`BackupRepositoryImpl`](app/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupRepositoryImpl.kt) — Context + WallpaperFileManager + ZIP-File-I/O
- [`WallpaperRestorer`](app/src/main/java/com/github/reygnn/kolibri_launcher/data/WallpaperRestorer.kt) (Interface) — Bridge für Wallpaper-File-Restoration während Phase 7 Import

Jede Schicht hat genau eine Art von Dependency. Kein Layer braucht
die Deps eines anderen. Die 8 Repos leben einmal (Assembler), Context
+ WallpaperFileManager leben einmal (RepositoryImpl). Das
Duplikations-Argument der ersten Runde greift nicht.

Tests: die 9 BackupRepositoryImpl-Spec-Files
(Doomsday/Io/Isolation/Logic/Malformed/NamingConvention/Security/
Strict/Wallpaper) blieben unverändert — eine Zeile pro File:
`BackupRepositoryImpl(...)` → `BackupRepositoryImplTestFactory.create(...)`.
Die Factory baut die neue Layer-Struktur intern.

**Folge-Arbeit (nicht blockierend):** Migration der Spec-Tests auf
die neuen Klassen direkt — ein neuer `BackupSerializerTest` für die
Strict/Malformed/Doomsday-Parser-Edge-Cases (pure-JVM, kein
Robolectric mehr nötig), ein `BackupDataAssemblerTest` für
Round-Trip-Tests gegen Fakes. Die existierende Coverage über die
Test-Factory hält die Tests grün; die direkte Migration ist
Optimierung, nicht Bugfix.

Begründung dauerhaft im File-Header von `data/BackupRepositoryImpl.kt`
verewigt — der Header erklärt jetzt die neue Drei-Schicht-Struktur
und warum die alte Ablehnungs-Argumentation nicht mehr greift. Memo
bleibt damit ein Reviewer beide Runden sieht: warum der naive Split
(Exporter/Importer) richtig abgelehnt wurde, und warum der andere
Split (Serializer/Assembler/IO) doch funktioniert.

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

## 7. (Memo, teilumgesetzt 2026-05-03) Selbst-Linter für die 13 Rules

Doc-vs-Realität-Drift war die häufigste Defekt-Klasse im Audit-Sweep
2026-05-03. Sieben separate positive Behauptungen in CLAUDE.md /
AUDIT.md kollabierten auf grep — am schlimmsten Rule 9 mit 53 Verstößen
in 9 Files. Linter wurde gebaut um diese Klasse dauerhaft zu beenden.

**Implementiert:**

- `tools/check-conventions.sh` — Bash-Script mit Sektion pro Rule.
- Gradle-Task `./gradlew checkConventions` — wrapt das Script via Exec.
- CLAUDE.md Rule 9 verweist auf den Linter und nennt das Erweitern als
  Nebenpflicht beim Hinzufügen neuer Crash-Infra-Files.

**Geprüfte Rules (3 von 6 ursprünglich gelisteten):**

- **Rule 9** — bare `Timber.e(` außerhalb der dokumentierten
  Crash-Infra-Files. Match-by-Message für die BackupFragment-Carve-Out
  (statt Line-Number-Pin).
- **Rule 12** — kein `Timber.Forest.*`.
- **Naming** — `*Manager`-Klassen in `data/` außer `WallpaperFileManager`
  und `DataMigrationManager`.

**Bewusst nicht geprüft (jeweils mehr-als-Grep nötig):**

- **Rule 11** — try/catch um can't-throw-Operations. Bräuchte
  semantische Analyse des catch-Body (welche Funktion wird gewrappt,
  ist die pure?).
- **Rule 13** — deutsche Kommentar-Zeilen in neu hinzugefügten Lines.
  Bräuchte git-diff-Integration.
- **Hardcoded UI-Strings** im Kotlin. Bräuchte String-Literal-
  Klassifikation.

Wenn diese drei je ihren Cost-of-Catch durch Reviews überschreiten,
in das Script einbauen — die Sektionen-Struktur ist dafür offen. Bis
dahin: catch-by-Review.

Memo bleibt damit ein Reviewer den Linter findet, weiß was er prüft
und was er nicht prüft, und das Pattern für neue Sektionen sieht
(Sektion = Heading-Comment + grep + report-on-non-empty).

---

## 8. (Memo, erledigt 2026-05-03) Time-basierte Test-Konvention

**Motivation:** Der Retry-Counter-Bug in `ObserveInstalledAppsUseCase` (siehe
Commit `f8a578b`) — ein Class-Field-State der über Flow-Invocations hinweg
persistierte und Backoff-Delays falsch skaliert hat — war Production-Code seit
File-Erstellung. Bestehende Tests haben ihn nicht gepinnt: alle 7 Tests testen
„passt das Resultat?", keiner testet „passt die Zeit-Differenz?".

**Umgesetzt 2026-05-03:**

- Neue Sektion „TIME-BASED ASSERTIONS" in `TESTING_CONVENTIONS.kt` —
  dokumentiert zwei Patterns: `testScheduler.currentTime`-Snapshot
  (relativ, gut für Retry-Backoff-Sequenzen) und
  `advanceTimeBy(BOUNDARY ± 1)` (absolut, gut für single-shot Delays).
  Plus Survey-Tabelle aller 12 time-basierten Sites im Repo mit
  Pin-Status (`✓` = explizit gepinnt, `✗` = nur kontraktuelles
  Vertrauen ohne Drift-Backstop).
- `GestureDelegateTest` neuer Test `TIME-PIN` für
  `LOCK_GESTURE_BLOCK_DURATION_MS` — der pre-existing-Test hat nur
  `advanceUntilIdle()` gemacht, was bei Konstanten-Drift still grün
  geblieben wäre. Der neue Test asserted Boundary - 1 ms (Flag noch
  gesetzt) und Boundary + 1 ms (Flag gecleart).
- Reference-Test `ObserveInstalledAppsUseCaseTest` →
  „retry counter resets between invocations on IOException backoff"
  bleibt der Lehr-Test für das `currentTime`-Snapshot-Pattern.

**Was bewusst nicht gepinnt wurde:** Die Survey-Tabelle in
`TESTING_CONVENTIONS.kt` listet ~7 weitere Sites (AppDrawer-Search-
Debounce, HomeFragment Spacing-Cache, etc.) als „kontraktuelles
Vertrauen" — diese werden nicht ad-hoc nachgepinnt, sondern im Touch
(neue Tests / Refactor) auf die Pin-Konvention gehoben. Die Konvention
ist jetzt Reviewer-fassbar; Drift wird beim nächsten Touch gefixt
statt in einem Big-Bang-Sweep.

Memo bleibt damit ein Reviewer (a) das `TIME-PIN`-Suffix als
Konventions-Marker erkennt, (b) die zwei Patterns aus
`TESTING_CONVENTIONS.kt` direkt sieht, und (c) versteht, warum nicht
alle 12 Sites auf einmal gepinnt wurden.

---

## 9. (Memo, abgeschlossen 2026-05-03) Architektur-Schritte für 9+ Score

**Motivation:** Final-Review 2026-05-03 hat das Repo bei 7.5/10
eingeordnet. Stärken sind Architektur-Disziplin, Doku-Tiefe,
Test-Disziplin. Vier Architektur-Schritte aus dem Review — alle
umgesetzt 2026-05-03.

- **§9.1** — `BackupRepositoryImpl`-Split, siehe §3-Memo
- **§9.3** — `HomeFragment`-Edit-Submodul via `WallpaperEditController`,
  HomeFragment 2657 → 1997 Zeilen über zwei Sweeps
- **§9.4** — `ResetRepositoryImpl` 226 → 134 Zeilen via
  `purgeAll`-Helper
- **§9.2** — Modul-Split (siehe unten)

### 9.2 (Memo, umgesetzt 2026-05-03) Modul-Split

**Hintergrund:** Single-Module bei 16 Repositories + 50 UseCases +
10 ViewModels war groß genug, dass Build-Cache-Resilienz und
Compile-Zeit deutlich darunter litten.

**Anlauf-Realität:** Der ursprüngliche Plan („`:domain` als reines
Kotlin-Modul ohne Android-SDK") war zu ambitioniert — die
Feasibility-Studie fand 13 Domain-Files mit Android-Imports
(`Parcelable`, `LiveData`, `WallpaperColors`, etc.) plus zwei
Cycles (`data → ui` und `domain → di`). Stattdessen wurde Variante 1
gewählt: `:domain` und `:data` als Android-Library-Module
(Compile-Cache-Win bleibt, „Pure Kotlin"-Strenge fällt weg).

**Vorbereitung in zwei Branches:**

- `refactor/cycle-elimination-data-to-ui` (Commits 5e0d9b3, 9f9b3d2,
  695dd52) — `SwipeSlot` → `domain/model/`, `AppUpdateSignal` →
  `core/`, `CrashReportConsent` aufgeteilt in
  `data/CrashReportConsentStore` (DataStore-I/O) +
  `ui/util/CrashReportConsent` (Dialog).
- `refactor/cycle-elimination-domain-to-di` (Commit a5db526) — Hilt-
  Qualifier-Annotationen (`@DefaultDispatcher` etc.) von `di/` nach
  `core/` verschoben; `@Provides`-Bindings bleiben in `di/`.

**Eigentlicher Modul-Split (Branch `refactor/module-split`, Commits
54eec88, 35eeda5):**

- **`:domain`** (Android Library) — `core/`, `domain/`,
  `DispatcherModule`. Eigene `R`-Klasse mit 12 Use-Case-Strings.
  `BuildConfig` aktiv für `TimberWrapper.DEBUG`.
- **`:data`** (Android Library) — `data/`, `RepositoryModule`,
  `DataStoreModule`. `BuildConfig` aktiv für `FavoritesRepositoryImpl`.
  `BackupDataAssembler` und `UsageExportRepositoryImpl` injizieren
  `versionName` per `@Named("appVersionName")` aus `:app` (Single
  Source of Truth statt Duplikat).
- **`:app`** — Application-Modul, hängt von `:domain` und `:data` ab.

**Spike-Vorlauf:** Ein Throw-Away-Worktree (`spike/module-split-feasibility`,
nach Verifikation gelöscht) hat zuerst die Hilt-2.57.2-Multi-Module-
Aggregation gegen die Realität geprüft, bevor der echte Branch begann.
Damit wurde aus dem 6-11h-Risiko ein 4-6h-Plan. Pattern empfohlen für
größere Refactorings mit Build-/DI-Risiko.

**Latente Leaks während der Move-Phase aufgedeckt + gefixt:**

- `ui.base.UiState` (generic Loading/Success/Error) → `domain/model/UiState`
- `ui.appcontextmenu.AppContextMenuAction` (laut KDoc selbst
  „rein datenbasiert") → `domain/model/AppContextMenuAction`

**Visibility-Anpassung:** Zwei Test-Entry-Points in `:data` mussten ihr
`internal`-Modifier verlieren (`PackageUpdateReceiver.handleReceive`,
`SwipeActionsRepositoryImpl.Companion.createForTesting`), weil Tests
weiterhin in `:app/src/test/` leben und `internal` keine
Modul-Grenzen überschreitet. `@VisibleForTesting` bleibt als
Lint-Schutz vor versehentlicher Produktions-Nutzung.

**Linter-Update:** `tools/check-conventions.sh` scannt jetzt alle
drei Modul-Source-Roots. Ohne diesen Fix wären Code-Stellen, die
in `:domain` oder `:data` gewandert sind, still aus den Rule-9-/
Rule-12-/Manager-Naming-Checks gefallen.

**Wert geliefert:** Inkrementelle Builds nach Domain-Änderungen
springen jetzt `:app`-Compilation, weil `:domain`/`:data` getrennt
gecached sind. Die Test-Isolation pro Modul (was die ursprüngliche
§9.2 erwähnt hatte) ist NICHT umgesetzt — alle Tests bleiben in
`:app/src/test/`. Wenn Test-Isolation zukünftig gewünscht ist,
ist das ein eigenes Folge-Projekt (Test-Helper-Modul oder
`testFixtures`-Feature).

Memo bleibt damit ein Reviewer (a) den Spike-vor-Implementierung als
Pattern erkennt für DI-/Build-riskante Refactorings, (b) die
versteckten Leaks dokumentiert sieht, (c) versteht warum
`:data` weiterhin Tests in `:app` hat (Pragmatismus, nicht
Versehen), und (d) den `BuildConfig.VERSION_NAME`-Hilt-Trick
parat hat falls neue `:data`-Features die Version brauchen.

---

## 10. (Memo, Format etabliert 2026-05-03) Lib-Pinning regelmäßig revisit

**Motivation:** ANRWatchdog `1.4.0` ist von 2018 ohne aktive Maintenance.
Hilt `2.57.2` mit „DO NOT UPGRADE" — der ursprüngliche Grund (welche
Major-Breaking-Änderung?) ist nicht festgehalten. Pins werden zu
Tech-Debt, je länger sie ohne Re-Check stehen.

**Etabliert 2026-05-03 (Inaugural-Pass):** Im Header von
`gradle/libs.versions.toml` lebt jetzt eine „QUARTERLY RECHECK
PROCESS"-Sektion, die den Recheck-Ablauf, das Annotations-Format
(`# DO NOT UPGRADE — Recheck YYYY-Qn: <kurz>`), das Time-Budget (~30
min/Quartal) und die Trennung „Recheck vs. Upgrade" (Recheck entscheidet
nur, Upgrade ist eigene Branch) definiert. Das letzte-Recheck-/
Nächste-Recheck-Datum steht ebenfalls im Header — wer den Catalog
öffnet, sieht sofort wann der nächste Termin fällig ist.

**Inaugural-Pass-Scope:** Format-Etablierung only. Die einzelnen Pins
wurden 2026-Q2 bewusst NICHT durchgegangen — der Recheck-Prozess
braucht ein web-fähiges Tool für die Upstream-Recherche
(Hilt-Changelog, ANRWatchdog-Alternativen, kotlinx-serialization 1.10
Status), das im Inaugural-Pass nicht verfügbar war. Erste echte
inhaltliche Durchlauf passiert 2026-Q3.

**Termin-Trigger:** Der Header-Kommentar nennt das nächste Recheck-
Datum explizit. Wer beim Routine-Touch des Catalogs (Lib hinzufügen,
Version bumpen) auf einen abgelaufenen Termin stößt, triggert den
Recheck — kein externer Kalender nötig.

**Ohne diesen Prozess** ist die Pinning-Disziplin selbst ein
Drift-Risiko: heute ist „DO NOT UPGRADE" begründet, in 12 Monaten ist
es Karawanen-Aberglaube. Memo bleibt damit (a) der Header-Kommentar
seinen Kontext behält und (b) ein neuer Reviewer direkt versteht,
warum der Inaugural-Pass nur das Format etabliert hat.

---

## 11. (Memo, Source-Sweep abgeschlossen 2026-05-03) Brocken C — `:domain` Source Pure-Kotlin

**Motivation:** §9.2 (Modul-Split) hatte zwei Varianten zur Auswahl —
`:domain` als Android Library (Variante 1, gewählt) oder als pure-
Kotlin Modul (Variante 2). Variante 2 wurde damals zurückgestellt,
weil 16 Domain-Files Android-Imports hatten (`Parcelable`,
`LiveData`, `WallpaperColors`, `Uri`, `Color`, `BlendMode`, etc.) —
zu viele Refactor-Frontiers für einen einzigen §9.2-Branch. Der
Audit-Snapshot direkt nach §9.2 hat das ehrlich als „~0.5 Punkte
bewusst abgegeben" markiert. Brocken C holt diese Schuld zurück.

**Vorgehen (2026-05-03):** Pro-File-pro-Branch, Reihenfolge nach
Risiko/Größe sortiert, ff-merge nach jedem grünem Build+Test+Linter.
Acht Branches insgesamt — einige Files ließen sich in einen Branch
clustern, weil sie strukturell verflochten waren:

| Branch | Files |
|---|---|
| `appinfo` | `domain/model/AppInfo.kt` |
| `menucontext` | `domain/model/MenuContext.kt` |
| `wallpaper` | `domain/model/WallpaperState.kt` + `BackupData.kt` (Uri-Aspekt) + `usecase/SetWallpaperImageUseCase.kt` |
| `livedata` | `repository/GetDrawerAppsUseCaseRepository.kt` + `usecase/GetDrawerAppsUseCase.kt` |
| `appconstants` | `core/AppConstants.kt` (Trivial: 4 unused DataStore-Imports) |
| `uicolorsstate` | `domain/model/UiColorsState.kt` |
| `shortcut` | `model/AppContextMenuAction.kt` + `repository/ShortcutRepository.kt` + `service/ShortcutLauncherService.kt` + `usecase/BuildAppContextMenuUseCase.kt` + `usecase/LaunchShortcutUseCase.kt` |
| `uicolors` | `usecase/ObserveUiColorsUseCase.kt` (mit neuem `core/ColorMath` und `model/DomainWallpaperColors`) |
| `textcolorcalculator` | `core/TextColorCalculator.kt` (Dead-Code-Fund — komplett gelöscht) |

**Verifikation des Endzustands:** `grep -rn "^import android\|^import androidx" domain/src/main/`
liefert leer. `:domain/src/main/` enthält nur noch reines Kotlin
plus `kotlinx.*`, `javax.inject.*`, `dagger.*`, `timber.log.Timber`.

**Eingeführte Domain-Patterns:**

- **Bundle-Wrapper-Pattern** für Parcelable-Transport: `:domain` hält
  pure Kotlin data classes, `:app/ui/util/` wrappt sie in
  `@Parcelize`-Mirror-Klassen mit `toX()`/`toParcelable()`-Mappern.
  Beispiele: `AppInfoParcelable`, `LauncherShortcutParcelable`.
- **String-Overloads an der Daten-Boundary** statt verteilter
  `Uri.parse`-Calls: `WallpaperFileManager` hat jetzt sowohl
  `deleteFile(Uri)` als auch `deleteFile(String)`, `:app`-Konsumenten
  rufen die String-Variante auf, intern parst sie der Manager. Bei
  `gcOrphans` mit `Set<Uri>` vs. `Set<String>` brauchte's `@JvmName`
  wegen Type-Erasure-Clash.
- **Domain-Mirror-Modelle** für System-API-Strukturen wo nur ein
  paar Felder gebraucht werden: `LauncherShortcut(id, packageName,
  shortLabel)` statt `ShortcutInfo`, `DomainWallpaperColors(supportsDarkText,
  secondaryColorArgb)` statt `WallpaperColors`. Mapping in `:data`/`:app`
  an der Boundary.
- **Pure-Kotlin Color-Math** (`core/ColorMath`) als Drop-In für
  `android.graphics.Color`-Konstanten und -Funktionen. Identische
  Bit-Patterns für `WHITE`/`BLACK`/`TRANSPARENT`, identische WCAG-
  Formel für `calculateLuminance` (matcht
  `androidx.core.graphics.ColorUtils.calculateLuminance` zur
  Floating-Point-Genauigkeit).

**Verhaltens-Drift-Wachen:** Das Risiko-Profil aus dem TODO-Eintrag
(„nicht Compile-Failure, sondern Verhaltens-Drift") hat sich
einmal manifestiert — `WallpaperRepositoryImpl`'s Scheme-Validierung
für `imageUri` hätte mit dem String-Switch verloren gehen können.
Lösung: lokales `uriString.toUri()` direkt vor dem Scheme-Check, der
Rest der Pipeline bleibt String. Sub-Step in `WallpaperRepositoryImpl`
explizit dokumentiert.

**Was noch fehlt:** Modul-Type-Switch von Android Library auf pure-
Kotlin (siehe Audit-Snapshot „Folge-Schritt zu §11"). Source ist
Android-frei, der Switch ist mechanisch — der einzige substantielle
Sub-Step ist der Move der 12 Use-Case-Strings aus `:domain/res/` nach
`:app/res/`, was einen kleinen Use-Case-API-Refactor braucht (heute
geben einige Use Cases `messageResId: Int` aus `domain.R.string.*`
zurück; nach dem Switch geht das nicht mehr — Use Cases müssen
Sealed-Type-Identifier exposen, UI mappt zu Strings).

Memo bleibt damit (a) ein neuer Reviewer beim Lesen des Audit-
Snapshots versteht, was Brocken C konkret bewegt hat, (b) die
Bundle-Wrapper- und String-Overload-Patterns als wiederverwendbare
Domain-Tools dokumentiert sind, und (c) der noch ausstehende
Modul-Type-Switch klar als kleiner Folge-Schritt umrissen ist
(nicht als „neuer großer Brocken").

---

## 12. (Memo, abgeschlossen 2026-05-03) `:domain` Modul-Type-Switch

**Motivation:** §11 hat `:domain/src/main/` Android-frei gemacht,
aber das Modul-Type blieb `com.android.library`. §12 bringt den
eigentlichen Switch auf `kotlin("jvm")` durch und holt damit die
verbleibenden ~0.1 Punkte aus dem Audit-Snapshot.

**Verlauf in zwei Branches:**

1. **`refactor/domain-jvm-module`** (preparatorisch): Strings nach
   `:app/res/`, Sealed-Identifier statt `@StringRes Int` in drei Use
   Cases, `TimberWrapper.isDebugBuild`-Flag. Plugin-Switch versucht
   und am **Timber 5.0.1 als `.aar`-Distribution** gescheitert —
   JVM-Module können keine AARs auf den Compile-Classpath ziehen.
   Build.gradle.kts zurückgedreht, Sub-Steps aber gemerged.

2. **`refactor/domain-jvm-module-final`** (Folge): Timber-AAR-
   Blocker durch `:domain/core/KolibriLog`-Indirektion aufgelöst.

**`KolibriLog` als Domain-Logging-Façade:**

```kotlin
object KolibriLog {
    @Volatile var dHandler: (message: String) -> Unit = { _ -> }
    @Volatile var wHandler: (throwable: Throwable?, message: String) -> Unit = { _, _ -> }
    @Volatile var taggedErrorHandler: (tag: String, throwable: Throwable?, message: String) -> Unit = { _, _, _ -> }

    fun d(message: String) = dHandler(message)
    fun w(message: String) = wHandler(null, message)
    fun w(throwable: Throwable, message: String) = wHandler(throwable, message)
    fun taggedError(tag: String, throwable: Throwable?, message: String) = taggedErrorHandler(tag, throwable, message)
}
```

Domain-Code (vier Use Cases plus TimberWrapper) ruft jetzt
`KolibriLog.d(...)` / `KolibriLog.w(...)` / `KolibriLog.taggedError(...)`
statt `Timber.d/w/tag().e(...)`. Pattern-Vorbild: das schon
etablierte `TimberWrapper.isDebugBuild`-Flag und
`preventCrashForTesting`-AtomicBoolean.

`KolibriLauncherApp.onCreate` wired die Handler beim App-Init zu
Timber durch — direkt nach `isDebugBuild`, vor allen anderen Init-
Schritten:

```kotlin
TimberWrapper.isDebugBuild = BuildConfig.DEBUG
KolibriLog.dHandler = { message -> Timber.d(message) }
KolibriLog.wHandler = { throwable, message ->
    if (throwable != null) Timber.w(throwable, message) else Timber.w(message)
}
KolibriLog.taggedErrorHandler = { tag, throwable, message ->
    val tree = Timber.tag(tag)
    if (throwable != null) tree.e(throwable, message) else tree.e(message)
}
```

Default-Handler sind no-op, sodass `silentError`-Aufrufe auf dem
Bootstrap-Pfad (vor `KolibriLauncherApp.onCreate`) sicher landen —
nichts geloggt, aber kein Crash. Akzeptabel weil das ACRA-Init-Flow
schon dieselbe Reihenfolge folgt.

**`TimberWrapper`-internal:** das Last-resort-Log in `silentDeath` (das
früher direkt `android.util.Log.e` aufrief) nutzt jetzt
`System.err.println` als JVM-portablen Fallback, falls KolibriLog-
Backend selbst werfen sollte.

**Build-Config (`domain/build.gradle.kts`):**

- Plugin: `kotlin("jvm")` statt `com.android.library`. Plus
  `kotlin-kapt` und `kotlin-serialization`. `hilt-android`-Plugin
  raus (Android-only); `kotlin-parcelize`-Plugin auch raus
  (kein @Parcelize-User mehr in `:domain`).
- Dependencies: `hilt-core` (JAR) statt `hilt-android` (AAR).
  `kotlinx-coroutines-core` statt `kotlinx-coroutines-android`.
  Timber-Dependency komplett raus aus `:domain`.
- `android { ... }` block weg, `namespace`/`compileSdk`/
  `buildFeatures`/`compileOptions`/`kotlin`-Block neu strukturiert
  als `java { toolchain }` und `kotlin { jvmToolchain }`.
- `:domain/src/main/AndroidManifest.xml` gelöscht.

**Tests:** `TimberWrapperTest` setzt im `@Before` zusätzlich
`KolibriLog.taggedErrorHandler` auf einen Lambda der zur Capture-Tree
forwardet (wie `KolibriLauncherApp` es zu Timber forwardet); im
`@After` wird der Handler zurück auf no-op. Reset-Pattern parallel zu
`isDebugBuild`/`preventCrashForTesting`.

**Verifikation:**

- `grep -rn "^import android\|^import androidx\|^import timber" domain/src/main/`
  liefert leer.
- `./gradlew :domain:compileKotlin` und `:domain:test` laufen ohne
  Android-Anteil. (`:domain:test` ist heute `NO-SOURCE` weil Tests
  noch in `:app/src/test/` leben — siehe Brocken B als Folge-Projekt.)
- `./gradlew assembleDebug` und voller `test`-Run grün.

**Was bewusst NICHT gemacht wurde:** Timber als JAR-only konsumieren
via Gradle-Attribute-Hacks (`org.gradle.libraryelements = jar` o.ä.).
AGP-AARs sind ZIP-Container mit `classes.jar` darin, kein direkter
Klassenpfad. Hacks würden zur Laufzeit instabile Resourcen-Auflösung
geben.

Memo bleibt damit (a) ein neuer Reviewer das Logging-Façade-Pattern
versteht, (b) der Wire-up-Punkt in `KolibriLauncherApp` als load-
bearing markiert ist (Bootstrap-Reihenfolge), und (c) zukünftige
domain-Files direkt `KolibriLog` benutzen statt versehentlich Timber
zu importieren (was den Build sofort brechen würde — sicheres
Self-Enforcement).

---

## 13. (Memo, weitgehend abgeschlossen 2026-05-03) Brocken B — Test-Isolation pro Modul

**Motivation:** §9.2 hat den Production-Code in drei Module gesplittet
(`:app → :data → :domain`), aber alle ~2200 Tests blieben in
`:app/src/test/`. `:domain:test` und `:data:test` waren `NO-SOURCE`.
Das kostet im Alltag: jede :domain-Code-Änderung lässt auch die
:app-Test-Compilation neu laufen, weil die Tests im selben Modul wie
die UI sind.

**Strategie-Entscheidung:** Gradle `java-test-fixtures`-Plugin auf
`:domain`. Shared Test-Helpers leben in `:domain/src/testFixtures/`;
Tests in `:domain/src/test/` und `:app/src/test/` konsumieren sie via
`testImplementation(testFixtures(project(":domain")))`. Vorteile
gegenüber einem eigenen `:test-fixtures`-Modul: kein zusätzliches
Modul, das `java-test-fixtures`-Plugin gehört zur Standard-Gradle-
Toolbox, und der Layering-Sinn ist klar (Helpers gehören semantisch
zur Domain-Test-Infrastruktur).

**Endstand `:domain` (2026-05-03):**

- **Plumbing:** `java-test-fixtures`-Plugin auf `:domain`.
  Fixtures-Source-Set hat eigene `testFixturesImplementation`-Deps
  (junit, kotlinx-coroutines-test, turbine, truth, mockk,
  kotlin-test-junit, hilt-core). `:app/build.gradle.kts` zieht
  `testImplementation(testFixtures(project(":domain")))`.
- **`:domain/src/testFixtures/`** enthält:
  - `rule/TimberRule`, `rule/MainDispatcherRule`
  - 14 Fake-Repositories (`FakeFavoritesRepository`, `FakeSettingsRepository`,
    …, `ReactiveFakeInstalledAppsRepository`)
  - 14 abstrakte Contract-Klassen (`FavoritesRepositoryContract`,
    `SettingsRepositoryContract`, …, `BackupRepositoryContract`)
- **`:domain/src/test/`** enthält:
  - 33 reine Use-Case-Tests
  - 12 Fake-Contract-Test-Implementations (`FakeFavoritesRepositoryContractTest`
    etc., die die abstrakten Contracts gegen die Fakes laufen lassen)
- **Performance:** 310 Tests in 45 Files, ~5s pure-JVM gesamt.
  Vergleich: dieselben Tests im alten `:app/src/test/` brauchten
  ~90s wegen Robolectric-Bootstraps.

**`:data:test` BLOCKIERT durch AGP-Bug:**

Der Plan war, FakeDataStore + Repository-Impl-Tests nach `:data` zu
verschieben. Vollständige Investigation in
[`data/TESTFIXTURES_KOTLIN_INVESTIGATION.md`](data/TESTFIXTURES_KOTLIN_INVESTIGATION.md)
(Reproduktion, geprüfte Workarounds, Re-Check-Anleitung beim nächsten
AGP/KGP-Bump). AGP unterstützt seit 8.0 testFixtures für Android-
Library-Module via `android { testFixtures { enable = true } }`,
ABER: für **Kotlin-Sourcen** in `src/testFixtures/` registriert AGP
keine Compile-Task. `./gradlew :data:tasks --all | grep TestFixturesKotlin`
liefert leer. Versuch mit `sourceSets.testFixtures.java.srcDir(
"src/testFixtures/kotlin")` ändert nichts. Symptom in Konsumenten:
"Cannot access 'class FakeDataStore': it is private in file" —
Kotlin sieht die Klasse aber kann sie nicht resolven.

Bekanntes Problem im Android-Studio-Issue-Tracker. Workarounds, die
NICHT funktionieren:
- AGP-`testFixtures`-Block zusammen mit `java-test-fixtures`-Plugin —
  Plugin-Konflikt.
- `kotlin.sourceSets.testFixtures` direkt konfigurieren — die Source-
  Set-Registry kennt den testFixtures-Variant nicht in kotlin-android.

Bleibt offen, bis AGP/KGP einen Fix liefern. Bis dahin:
- FakeDataStore stays in `:app/src/test/java/.../fakes/`
- Repository-Impl-Tests stays in `:app/src/test/java/.../data/`
  (~12 Files: `FavoritesRepositoryImpl*Test`, `WallpaperRepositoryImpl*Test`,
  `BackupRepositoryImpl*Test` (9 Files!), `CustomNamesRepositoryImpl*Test`,
  `AppUsageRepositoryImpl*Test` etc.)

**Was zukünftig passieren muss, wenn der AGP-Fix kommt:**

1. `:data/build.gradle.kts`: `android { testFixtures { enable = true } }`
2. FakeDataStore von `:app/src/test/java/.../fakes/` nach
   `:data/src/testFixtures/java/.../fakes/` (oder `kotlin/`)
3. `:app/build.gradle.kts`: zusätzlich `testImplementation(testFixtures(project(":data")))`
4. `app/src/test/resources/robolectric.properties` nach
   `data/src/test/resources/` duplizieren (siehe §6-Memo zum
   ANRWatchdog-Leak — ohne diesen Default lädt Robolectric
   `KolibriLauncherApp` und der Process leakt einen ANRWatchdog-
   Thread pro Test-Run).
5. ~12 Repository-Impl-Tests von `app/src/test/java/.../data/` nach
   `data/src/test/java/.../data/` schieben.

Erwarteter zusätzlicher Test-Time-Win: ~30s (Robolectric-Bootstraps
in :data:test isoliert von :app:test).

**Risiko-Punkte (relevant beim späteren :data-Move):**

- **Robolectric-Konfiguration:** robolectric.properties muss in
  `data/src/test/resources/` dupliziert werden — keine Magie aus
  `:app` greift dort.
- **`@TestInstallIn`/Hilt-Test-Module:** einige `:app`-Tests benutzen
  `HiltTestApplication` und Test-Module-Replacements. Bleiben
  naturgemäß in `:app/src/test/`, weil das Hilt-Plugin (`hilt-android`)
  in `:domain`/`:data` nicht aktiv ist.
- **`AndroidEntryPoint`/Activity-Tests:** alle in `:app/src/test/`
  (Activity-Lifecycle ist UI-Layer).
- **Test-Isolation ist nicht 100% erreichbar.** Tests, die
  Activity-Lifecycle, ContentResolver, oder System-API-Mocking
  brauchen, bleiben naturgemäß in :app oder :data. Das ist by design.

Memo bleibt damit (a) ein neuer Reviewer das testFixtures-Pattern und
seine Begründung versteht, (b) der `:data:test`-AGP-Blocker bekannt
ist und nicht versucht wird, ihn ad hoc zu reparieren, und (c) die
Folge-Schritte beim AGP-Fix als bereits konkret skizziert vorliegen.

---

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/test/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
