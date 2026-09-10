# Completed TODO sections (archive)

Roadmap sections from `TODO.md` that are **done** (abgeschlossen / erledigt /
umgesetzt), moved here to keep the active TODO focused on open work. Provenance
only — the code and its enforcement are the live truth. Section numbers are the
original `TODO.md` numbers (gaps are original).

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

**Update 2026-05-06:** der konkret-namentlich genannte Auslöser
(`com.github.anrwatchdog:1.4.0`) ist weg — durch
[`AnrReporter`](app/src/main/java/com/github/reygnn/kolibri_launcher/ui/util/AnrReporter.kt)
ersetzt. Der Fix selbst (Properties-Default auf `android.app.Application`)
bleibt load-bearing, weil ACRA + Timber + Receiver + CoroutineScope
auch ohne ANRWatchDog cross-test-bleed-through verursachen würden.

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

## 23. (erledigt 2026-08-20) Tote ACRA-Consent-Keys im `settingsDataStore` bei Bestands-Installs

**Aufgedeckt 2026-07-22** im Review des AUDIT-4-#2-Fixes. Mit dem
Backup-Fix wurde der ACRA-Consent bewusst in einen eigenen, vom
Auto-Backup ausgenommenen DataStore (`acra_consent.preferences_pb`,
`AppConstants.CONSENT_DATASTORE_NAME`) ausgelagert — **ohne** Migration
(Entscheidung: einmaliger Consent-Reset statt Wieder-Einführung der
gelöschten Migrations-Maschinerie auf dem Bootstrap-Pfad).

**Was übrig bleibt:** Installs, die vor dem Split schon einen Consent
gesetzt hatten, behalten die alten Keys `acra_has_consent` /
`acra_has_asked` als **tote Keys** im `settingsDataStore`
(`kolibri_settings.preferences_pb`). Da genau diese Datei jetzt gesichert
wird, wandern die toten Keys beim Cloud-Restore / Gerätewechsel *innerhalb*
von `kolibri_settings` mit.

**Warum das heute harmlos ist:** `CrashReportConsentStore` liest den
Consent ausschließlich aus dem `consentDataStore` (nicht gesichert,
startet leer → `hasConsent` = false). Die toten Keys im Settings-Store
hat kein Leser mehr; die Zustimmung wandert effektiv nicht mit. Reine
Daten-Hygiene, kein user-sichtbarer Effekt.

**Einzige theoretische Falle:** Würde künftig versehentlich wieder
`HAS_CONSENT_KEY` aus dem `settingsDataStore` gelesen (z.B. ein
zurückgedrehter Import-Pfad), könnte der mitgesicherte tote Key die
Zustimmung „wiederbeleben".

**Mögliche Lösungen, in aufsteigendem Aufwand:**

1. Nichts tun — bewusster Trade-off, dokumentiert (dieser Eintrag + KDoc
   in `CrashReportConsentStore` / `DataStoreModule`). Ein aktives Cleanup
   wäre genau die vermiedene Migration.
2. Idempotenter Cleanup-`remove` der beiden Keys aus `settingsDataStore`
   beim nächsten `saveConsent`-Schreibvorgang — kein Bootstrap-Read-Pfad,
   kein `runBlocking`, ~2 Zeilen. Räumt die Keys über die Zeit organisch
   weg, ohne Migrations-Klasse.
3. `purgeRepository()` entfernt sie zusätzlich (greift aber nur bei Reset).

**Empfehlung:** Variante 1 (belassen), solange die toten Keys nicht
konkret stören. Variante 2 ist der günstigste echte Cleanup, falls
gewünscht.

**Erledigt 2026-08-20 — durch das Blacklist-Cleanup.** Die
Speicher-aufräumen-Funktion wurde von einer Whitelist-of-dead
(`RetiredDataStoreKeys`, gelöscht) auf eine **Keep-Liste / Blacklist**
umgestellt: `DataStoreMaintenanceRepository.removeOrphanKeys` löscht jeden
Settings-Store-Key, den kein lebender `OwnsSettingsStoreKeys`-Owner
beansprucht. Beide toten Keys (`acra_has_consent` / `acra_has_asked`)
gehören keinem Owner → sie werden jetzt vom user-getriggerten Cleanup
erfasst und entfernt (faktisch eine user-gesteuerte Variante 3, ohne
Migrations-Maschinerie und ohne Bootstrap-Read-Pfad). Der neue
**Dry-Run** listet sie vor dem Löschen auf, was diesen Eintrag on-device
verifiziert hat: sie liegen tatsächlich im Settings-Store, kein Owner
beansprucht sie. Der lebende Consent (`consent_decision` im separaten,
vom Cleanup nie berührten `consentDataStore`) bleibt unangetastet, und
das Entfernen schließt zugleich die oben genannte „einzige theoretische
Falle" (ein zurückgedrehter Import-Pfad, der den mitgesicherten toten Key
liest). Kein dedizierter §23-Code nötig — der generische Blacklist-Sweep
deckt es ab.

---

## 24. (erledigt 2026-07-23) Swipe-App-Cleanup lebt im Lese-/Launch-Pfad statt event-getrieben

**Umgesetzt 2026-07-23** auf Branch `refactor/swipe-cleanup-event-driven`,
in zwei Stufen. Endstand: **Load-Time-Orphan-Sweep** — der Cleanup läuft
nach jedem erfolgreichen App-Load, gebündelt mit dem bereits existierenden
Favoriten-Cleanup in `ObserveInstalledAppsUseCase`.

- `HandleSwipeActionUseCase` ist reines launch-or-no-op, mutiert nie mehr
  persistenten State. Das alte `else`-Clear ist weg, ebenso das
  `hasLoadedApps()`-Gate (danach tote API → aus Interface + Impl + Fake +
  4 Contract-Tests entfernt).
- Neue `cleanupX(installed…)`-Methoden auf `SwipeActionsRepository`,
  `HiddenAppsRepository`, `CustomNamesRepository` (Interface + Impl + Fake +
  je 4 Contract-Tests), exakt nach der `FavoritesRepository.cleanupFavorite\
  Components`-Blaupause. Custom-Names werden gegen **Paketnamen** abgeglichen
  (paket-basiert), die anderen gegen componentNames. Logs enthalten nur
  Counts/Slot, nie componentName/Paket (PII, s. Review #2).
- `ObserveInstalledAppsUseCase` ruft die drei neuen Cleanups neben dem
  Favoriten-Cleanup auf, **hinter** dem `realApps.isEmpty()`-Cold-Start-Guard
  (leerer Load → kein Sweep). Jeder Store eigenständig `try/catch`-guarded.

**Warum Load-Time statt event-getrieben (die entscheidende Einsicht):** Ein
Uninstall-bei-lebendem-Prozess triggert über `AppUpdateSignal → refreshApps
→ reload` genau diesen Load-Pfad, ein Uninstall-bei-totem-Prozess wird beim
nächsten Start abgedeckt. Der Sweep deckt also **beide** Fälle ab und ist
strikt allgemeiner als der zuerst gebaute event-getriebene Ansatz. Er
braucht **kein `EXTRA_REPLACING`-Gate** (bei einem Update ist die App
installiert → nicht verwaist → nicht gelöscht). Damit ist auch Review-#3
(untestbares Gate) gegenstandslos.

**Verworfene Zwischenstufe:** Ein event-getriebener
`ClearSwipeActionsForPackageUseCase`, den der `PackageUpdateReceiver` bei
`ACTION_PACKAGE_REMOVED && !EXTRA_REPLACING` aufrief. Deckte den
Prozess-tot-Uninstall (#1) nicht ab und war swipe-spezifisch (#4). Vom
Load-Time-Sweep vollständig subsumiert und wieder entfernt (Receiver +
EntryPoint zurück auf `main`-Stand).

Damit erledigt: **#1** (Prozess-tot-Uninstall wird beim nächsten Load
gereinigt), **#4** (generalisiert über Swipe/Hidden/CustomNames; Favoriten
hatten es schon), **#3** (Gate existiert nicht mehr). §25 siehe unten —
weitgehend entschärft.

**Historischer Kontext (Aufdeckung).**
**Aufgedeckt 2026-07-23** im Review der AUDIT-5-Swipe-Härtung
(`a47760d → bffa25e → 27ce121`). Der Cold-Start-Datenverlust ist
gefixt und die aktuelle Fassung ist korrekt (die Monotonie von
`InstalledAppsStateRepositoryImpl.lastSuccessfulAppList` — nur in
`updateApps` bei nicht-leerer Liste gesetzt, nie zurückgesetzt,
`purgeRepository` macht „NICHTS TUN" — macht das loaded-first-Gate
wasserdicht). Das ist ein **Architektur-Cleanup, kein Bugfix.**

**Wo.** `domain/.../usecase/HandleSwipeActionUseCase.kt:64-69` — der
`else`-Zweig löscht die persistierte Swipe-Zuweisung
(`setSwipeAction(slot, null)`), wenn die zugewiesene App nicht in
`getCurrentApps()` gefunden wird. Diese *Reconciliation* („App weg →
Einstellung aufräumen") hängt faul im **Lese-/Launch-Pfad** und muss
deshalb überhaupt erst raten: „deinstalliert" vs. „noch nicht
geladen". Genau dieses Rate-Problem erzwang das `hasLoadedApps()`-Gate
und die drei Härtungs-Iterationen.

**Sauberere Struktur.** Es existiert bereits ein
`data/.../PackageUpdateReceiver.kt`, der auf `ACTION_PACKAGE_REMOVED`
feuert (aktuell nur `sendUpdateSignal()` → App-Reload). Würde die
Swipe-Bereinigung *von diesem Event* getrieben, verschwände das
Rate-Spiel komplett: man löscht präzise dann, wenn wirklich
deinstalliert wurde, und `HandleSwipeActionUseCase` würde nur noch
*launch-or-noop*, ohne je persistenten State zu mutieren. Betrifft
sinngemäß auch andere komponentenname-gebundene Settings (Favoriten,
Custom Names) — die könnten am selben Receiver-Pfad reconciliaten.

**Trigger.** Kein Handlungsdruck — die aktuelle Fassung ist korrekt.
Angehen, wenn (a) §25 (toten componentName launchen) real stört und
man beide zusammen lösen will, oder (b) ein weiteres
komponentenname-gebundenes Setting dieselbe „uninstalled vs. not
loaded"-Ratelogik bräuchte und die Duplikation den Umbau rechtfertigt.
Eigener `refactor/`-Branch, kein Review-Fix.

---

## 25. (erledigt 2026-07-23) Swipe auf frisch deinstallierte App startet toten componentName

**Umgesetzt 2026-07-23** auf Branch `fix/launch-failure-reconcile`.

**Befund bei der Umsetzung:** Der eigentliche *Crash* war nie einer — der
Launch-Pfad fängt `ActivityNotFoundException` längst ab
(`MainActivity.launchApp`, ~Zeile 837: silentError + Toast
`error_app_not_available`, kein Crash). §25 war also nie ein Absturz,
sondern eine fehlende *Selbstheilung*.

**Fix (Sketch 1):** Ein fehlgeschlagenes `startMainActivity` ist das
*definitive* „diese Komponente ist weg"-Signal (verlässlicher als jede
Cache-Inspektion) → ein Reload löst den §24-Load-Time-Sweep aus, der jede
verwaiste Zuweisung (Swipe, Favorit, Hidden, Custom Name) auf die tote App
räumt. **Nur** bei ActivityNotFound: `SecurityException`/Sonstiges
implizieren keinen Uninstall und dürfen keinen Reconcile triggern. Damit ist
die enge Race (Swipe zwischen Uninstall und Sweep-Abschluss, App noch im
Cache) geschlossen — der eine Fehlversuch heilt sich selbst.

**Umgesetzt als injectable Seam (Code-Review-Guard):** Der Launch liegt jetzt
hinter `AppLauncher` (`ui/main/AppLauncher` + `AppLauncherImpl`, Hilt-`@Provides`
in `AppModule`), der die `LauncherApps`/`ActivityOptions`-Runtime kapselt und
das Ergebnis als typisiertes `AppLaunchResult` zurückgibt
(`Launched` / `ComponentGone` / `PermissionDenied` / `Failed`). `MainActivity.
launchApp` reagiert nur noch auf das Result (Toast + `if (result.shouldReconcile)
refreshInstalledApps()`). Die Reconcile-Entscheidung sitzt als
`AppLaunchResult.shouldReconcile` (nur `ComponentGone` == true) und ist per
reinem JVM-Test `AppLaunchResultTest` gepinnt — Robolectric konnte den Pfad
nicht testen, weil dessen `ShadowLauncherApps` kein `startMainActivity`
implementiert (echter Launch wirft dort nie `ActivityNotFound`). Nebeneffekt:
MainActivitys inline Triple-Catch ist weg (in `AppLauncherImpl` gewandert),
ein Broad-Catch weniger im whitelisted File.

Sketch 2 (live `PackageManager`-Revalidierung vor jedem Start) wurde
verworfen: teurer, und der Fehlschlag liefert dasselbe Signal ohnehin
zuverlässig.

**Historischer Kontext (Aufdeckung).**
**Aufgedeckt 2026-07-23**, dieselbe Review wie §24. Die *Umkehrung*
der in AUDIT-5 behobenen Kante, und mit ihr strukturell verwandt.

**Wo.** `HandleSwipeActionUseCase.kt:56-63` sucht die Swipe-App in
`getCurrentApps()`. Weil `InstalledAppsStateRepositoryImpl.getCurrentApps()`
(`data/.../InstalledAppsStateRepositoryImpl.kt:80-85`) bei leerem
Live-Flow auf den `lastSuccessfulAppList`-Cache zurückfällt, kann eine
*tatsächlich* deinstallierte App kurz nach dem Uninstall (vor dem
Reload) noch im Cache stehen → sie wird „gefunden" → `Result.LaunchApp`
mit totem componentName → `ActivityNotFoundException` beim Start,
statt dass der `else`-Cleanup greift.

**Warum minor.** Kein Datenverlust; enges Zeitfenster (Uninstall →
Swipe vor dem Reload). Bewusst der Preis des „Resilience Buffer"-
Designs von `getCurrentApps()`. Zeigt aber, dass der Clear-Zweig
sowieso nur best-effort ist — was §24s Argument stützt.

**Sketch.** Root ist der **Launch-Pfad**, nicht der Cleanup-Ort, also
unabhängig von §24 lösbar:

1. `ActivityNotFoundException` an der Start-Stelle (Consumer von
   `Result.LaunchApp`) abfangen und *dann* reconciliaten (clear +
   Reload triggern). Der Fehlschlag *ist* das verlässlichste
   „deinstalliert"-Signal — verlässlicher als jede Cache-Inspektion.
   Kleinste, ehrlichste Lösung.
2. Vor dem Start gegen den live `PackageManager` re-validieren statt
   gegen den Cache.

**Vor dem Angehen prüfen:** wo `Result.LaunchApp` konsumiert wird
(GestureDelegate / HomeFragment) und ob dort heute schon ein
`ActivityNotFound` graceful abgefangen wird — dann ist §25 evtl. schon
teilweise gedeckt.

**Update 2026-07-23:** §24 ist erledigt (Load-Time-Orphan-Sweep, siehe
oben). Das hat §25s Fenster stark geschrumpft: der Sweep läuft nach jedem
Load — inklusive des Reloads, den der Uninstall-Broadcast selbst auslöst —
und cleart die Swipe-Zuweisung, bevor der Nutzer erneut swipen kann. Der
tote-componentName-Launch bleibt nur noch für die enge Race möglich, in
der der Swipe zwischen Uninstall und Sweep-Abschluss fällt UND der
`getCurrentApps()`-Cache die App noch listet. Kein Datenverlust, sehr
enges Fenster. Die saubere Restlösung wäre weiterhin, den
`ActivityNotFoundException` an der Start-Stelle abzufangen (Sketch 1) —
das ist unabhängig vom Sweep und die einzige vollständige Absicherung.

---

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/test/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
