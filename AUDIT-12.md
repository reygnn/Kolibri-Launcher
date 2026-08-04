# AUDIT-12 — Cancellation-Swallower im gesamten Produktionscode (quellenverifiziert)

> **Erzeugt** 2026-08-04 gegen `main` @ `30aef063` (Version 0.99.144 / code 164),
> ausgelöst durch einen ACRA-Report aus dem Feld:
> `[E/SILENT_ERROR] Error loading layer layer_… — CancellationException improperly
> caught and logged as an error`.
> **Fokus:** eine einzige Defektklasse über alle drei Module — breite Catches
> (`Throwable` / `Exception`), die eine `CancellationException` schlucken statt
> sie durchzureichen.
> **Methode:** aggressiver Linter-Lauf über den gesamten Produktionscode
> (`tools/check-cancellation-rethrow.awk`, ohne Whitelist), danach Triage per
> Wegwerf-Skript (alle 127 `suspend fun`-Namen des Projekts einsammeln,
> try-Block per Klammer-Balance rekonstruieren, Body dagegen scannen), zuletzt
> **jeder verbleibende Kandidat von Hand am Quelltext nachgelesen**.

---

## Warum diese Klasse gefährlich ist

Der Defekt ist **im Diff unsichtbar**. Ein `catch (e: Throwable)` ist harmlos,
solange sein try-Block keinen Suspension Point enthält — es kann schlicht keine
`CancellationException` ankommen. In dem Moment, in dem ein Aufruf in diesem
Block `suspend` wird, kippt derselbe, unveränderte Catch zum Cancellation-
Schlucker: normaler Coroutine-Kontrollfluss wird als Crash geloggt, und der
bereits abgebrochene Job arbeitet weiter. **Die Catch-Zeile ändert sich nie**,
also sieht ein Review nichts.

Genau so entstand der auslösende Report: `4c09c30b` machte
`WallpaperViewBinder.BitmapLoader.load` zu `suspend` und ließ den vorhandenen
Catch stehen — obwohl dieselbe Commit-Message Cancellation zum *gewollten*
Steuerfluss erklärte („neuer State cancelt den vorigen → latest wins").
Vorgeschichte derselben Klasse: `e1ef671d`, `fb62b88d`.

---

## Zahlen

| | |
|---|---|
| Breite Catches im Produktionscode gesamt | 339 |
| davon durch einen Rethrow-Arm bereits erfüllt | 146 |
| vom aggressiven Linter geflaggt | 193 |
| davon mit potenziellem Suspension Point (Heuristik) | 31 |
| davon nach Handprüfung **echte Funde** | **8** (in 4 Dateien) |

Die 185 nicht bestätigten Treffer sind kein Rauschen im Sinne von „Linter
kaputt" — die aggressive Regel *will* eine Deklaration, keine Diagnose. Sie
sind der Grund, warum die Whitelist datei-weise wächst statt global zu gelten.

---

## Bestätigte Funde

### #1 — `medium` · Cancellation im Recovery-Pfad beendet den Prozess

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/MainActivity.kt:477`
(`initializeMainApp()` ist `suspend`, deklariert in derselben Datei `:509`)

Der **äußere** Catch von `handleInitialSetup` rethrowt Cancellation korrekt
(`:464`). Der **innere** Recovery-Catch um das suspendierende
`initializeMainApp()` tut es nicht — und sein Handler ist
`TimberWrapper.silentDeath(…)`, also `exitProcess(1)` in jedem Build-Typ.

Failure-Szenario: ein Nicht-Cancellation-Throwable erreicht den äußeren
Catch → Recovery ruft `initializeMainApp()` → die Activity stirbt während
dieses Aufrufs (Rotation, Home-Wechsel, Prozess-Druck) → `CancellationException`
→ innerer Catch → **Prozess-Exit statt sauberem Teardown**.

Erreichbarkeit ist niedrig (setzt einen vorherigen Fehler voraus), die Wirkung
ist die härteste im ganzen Audit. Hier steckt zusätzlich eine
**Design-Entscheidung**: ob der `silentDeath`-Vertrag auf diesem Pfad auch für
Cancellation gelten soll, ist nicht aus dem Code ableitbar — siehe „Offene
Entscheidungen".

### #2 — `medium` · ACRA-Report bei jedem Drawer-Schließen während des Settings-Reads

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/AppDrawerFragment.kt:598`
(`viewModel.isAutoShowKeyboardEnabled()` ist `suspend`, `LauncherViewModel.kt:334`)

Der direkteste Zwilling des auslösenden Wallpaper-Bugs: suspend-Read in einem
`viewLifecycleOwner.lifecycleScope.launch`, breiter Catch, `silentError` im
Handler. Wird der Drawer geschlossen, während der DataStore-Read läuft, cancelt
der Scope → Report. Kein Nutzerschaden, aber Dauerrauschen im ACRA-Backend,
das echte Fehler verdeckt.

### #3 — `low` · Abgebrochene Coroutine läuft weiter (Auto-Launch-Pfad)

**Datei:** `.../AppDrawerFragment.kt:485`
(`viewModel.isAutoLaunchEnabled()` ist `suspend`, `LauncherViewModel.kt:333`)

Die Cancellation wird still zu `false` verrechnet (kein `silentError`), die
Coroutine setzt danach ihre Arbeit im toten Scope fort. Kein Report, dafür die
andere Hälfte des Defekts: Arbeit im Namen eines abgebrochenen Jobs.

### #4 — `low` · dito im Kontextmenü-Pfad

**Datei:** `.../AppDrawerFragment.kt:548`
(`viewModel.hasUsageData(…)` ist `suspend`, `LauncherViewModel.kt:335`)

Gleiche Form wie #3. Anschließend wird noch `ContextMenuHelper.show(…)`
aufgerufen — der `isAdded`/`isDetached`-Check auf `:552` fängt den sichtbaren
Teil ab, nicht aber die verschluckte Cancellation selbst.

### #5 — `low` · Wallpaper-Layer-Import, pro Layer ein Report

**Datei:** `data/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupRepositoryImpl.kt:431`
(`wallpaperFileManager.copyToInternal(…)` ist `suspend`, `WallpaperFileManager.kt:66`)

Der Catch trägt bereits einen Rule-11-Marker („Catch kept … can OOM"), der
sachlich stimmt — die OOM-Begründung ist echt. Sie deckt aber die
Cancellation-Frage nicht ab: der Catch sitzt in einer Schleife, ein Abbruch
mitten im Import erzeugt einen Report **pro verbleibendem Layer** und kopiert
weiter.

### #6 — `low` · Single-Layer-Variante desselben Pfads

**Datei:** `.../BackupRepositoryImpl.kt:487`
(`copyToInternal(…)` + `assembler.saveWallpaperStateForRestore(…)`, letzteres
`suspend` in `BackupDataAssembler.kt:419`)

Wie #5, ohne Schleife.

### #7 / #8 — `medium` · „CRITICAL"-Reports aus dem Flow-`.catch{}`

**Datei:** `data/src/main/java/com/github/reygnn/kolibri_launcher/data/InstalledAppsRepositoryImpl.kt:97` und `:169`

Beide Male steht innerhalb eines Flow-`.catch { }` ein handgeschriebenes
`try { … emit(…) } catch (catchError: Throwable)`. `emit` ist ein Suspension
Point; bricht der Collector ab, landet die Cancellation im Catch und wird als
`CRITICAL: Error in catch block` bzw. `CRITICAL: Error in flow catch block`
gemeldet — die höchste Dringlichkeitsstufe im Log, ausgelöst durch Normalbetrieb.

Lehrreich: **`:158` derselben Datei hat den Rethrow korrekt**, die beiden
`.catch{}`-Blöcke direkt darunter nicht. Genau diese lokale Inkonsistenz sieht
ein Linter besser als ein Mensch.

---

## Abgeschirmt, aber fragil (kein Fund, bewusst notiert)

**`.../AppDrawerFragment.kt:560`** — der äußere Catch desselben `launch`-Blocks
wie #4. Er ist heute *nicht* betroffen, weil der einzige Suspension Point im
Block (`hasUsageData`) schon vom inneren Catch auf `:548` geschluckt wird und
`ContextMenuHelper.show` nicht `suspend` ist (`ContextMenuHelper.kt:33`).

Die Abschirmung ist ein Zufall der Aufrufkette, keine Eigenschaft dieses Catches:
sobald `ContextMenuHelper.show` suspendiert, ist die Stelle live. Das ist
exakt die Mechanik, wegen der die Linter-Regel aggressiv ist statt diagnostisch.

---

## Geprüft & verworfen (Nachvollziehbarkeit)

Alle folgenden Stellen wurden von der Heuristik als verdächtig markiert und
nach Quelltextlesung **verworfen**. Dokumentiert, damit sie nicht bei jedem
künftigen Lauf erneut untersucht werden.

| Stelle | Warum kein Fund |
|---|---|
| `SettingsFragment.kt` (10 Treffer: `:182 :196 :210 :224 :238 :253 :314 :496 :915 :959`) | Das try umschließt nur den **Aufruf** von `lifecycleScope.launch { }`. Der Builder suspendiert selbst nicht; der suspend-Call liegt im Lambda darin und hat dort einen eigenen, korrekten Rethrow (z. B. `:304`, `:489`). |
| `PackageUpdateReceiver.kt:122` | Gleiche Form — äußerer Catch um `scope.launch { }`; der innere Catch auf `:113` rethrowt korrekt. |
| `CustomNamesActivity.kt:258` | Ebenso; der Kommentar auf `:241-244` beschreibt die Konstruktion bereits richtig. |
| `BackupFragment.kt:92` | `viewModel.previewBackup(…)` ist **nicht** `suspend` (`BackupViewModel.kt:98`) — der Callback ist gar keine Coroutine. |
| `KolibriLauncherApp.kt:209`, `:223` | Umschließende Funktionen sind nicht `suspend`; zusätzlich greift Rule 7 (bewusste Mehrschicht-Paranoia). |
| `AppContextMenuDialogFragment.kt:426` | try enthält reinen Dialog-Aufbau; die Heuristik traf Namen aus verschachtelten Button-Lambdas. |
| `ContextMenuHelper.kt:53`, `ToastSafe.kt:30`, `HomeFavoritesAdapter.kt:158` | Heuristik-Fehlalarm über gleichnamige Symbole (`show`, `setTextColor`) — nichts davon `suspend`. |

**Falsch-Positiv-Muster, das die Triage präzisiert hat:** relevant ist nicht
„suspend-Call irgendwo im try", sondern **im selben Coroutine-Frame**. Ein
verschachteltes `launch { }` / `async { }` unterbricht die Kette — der Builder
kehrt sofort zurück, die Cancellation des inneren Jobs erreicht den äußeren
Catch nie.

---

## Linter-Status

`tools/check-cancellation-rethrow.awk` (eingeführt in `30aef063`) wurde im Zuge
dieses Audits um zwei empirisch belegte Lücken erweitert:

- **`runCatching`** fängt `Throwable` inklusive `CancellationException` und
  enthält gar kein `catch`-Keyword — der eine Schlucker, den ein
  catch-förmiger Linter prinzipiell nicht sehen kann. Das Projekt hat die
  Policy bereits in Prosa (`TimeBasedEventsRepositoryImpl.kt:29`), bisher ohne
  Durchsetzung. Im Produktionscode: **null Vorkommen** — reine Drift-Sperre.
- **In-Body-Guard** `if (e is CancellationException …) throw e` (das Idiom aus
  `SettingsRepositoryImpl.kt:85`) gilt jetzt als erfüllt. Korrekter Code darf
  keinen Marker-Kommentar erzwungen bekommen.

Beide ändern an den Bestandszahlen **nichts** (193 vor wie nach): das
Guard-Idiom steht dort in einem Flow-`.catch { }`, das die Catch-Regex ohnehin
nie traf, und `runCatching` kommt schlicht nicht vor. Sie sind Zukunftsgarantien,
keine Aufräumarbeit.

**Whitelist nach der Umsetzung:** `WallpaperViewBinder.kt`, `MainActivity.kt`,
`AppDrawerFragment.kt`, `InstalledAppsRepositoryImpl.kt`,
`BackupRepositoryImpl.kt` — alle vier empfohlenen Dateien aufgenommen. Die
non-suspend-Catches tragen den `no suspension point`-Marker, die Fixes sind
strukturelle Rethrow-Arme.

**awk-Härtung während der Umsetzung:** Das Whitelisten von `BackupRepositoryImpl`
hat eine Lücke im case-(a)-Scan offengelegt: `saveBackupToFile` (:550) ist
korrekt (Cancellation-Arm vor den typisierten Armen), wurde aber fälschlich
geflaggt, weil der Aufwärts-Scan an der ersten typisierten `throw Wrapped(...)`-
Zeile stoppte. Der Scan ist jetzt einrückungs-basiert und erkennt einen
cancellation-first-Arm auch hinter typisierten Geschwister-Armen (Stacked-catch-
Muster); ein unrelated früherer try erfüllt weiterhin nicht (dessen `try {`-Zeile
bricht den Walk). Regression-Fixtures CASE 14/15, 15/15 grün.

**Ausdrücklich nicht empfohlen:** die aggressive Regel global scharf zu
schalten. Das kostet ~185 Marker-Kommentare an Stellen ohne jedes Risiko und
verwässert genau die Aussage, die der Marker tragen soll.

---

## Entschieden & umgesetzt

1. **#1 (`MainActivity`)** — **Rethrow.** Cancellation geht dort in den
   Durchreich-Arm, nicht in `silentDeath`. Eine abgebrochene Activity ist kein
   lügender Zustand; der Prozess-Exit hätte zudem das dokumentierte
   `isInitialized`-Retry beim nächsten `onCreate` sabotiert. `silentDeath` bleibt
   für einen echten Recovery-Fehlschlag reserviert.
2. **Reihenfolge der Fixes** — umgesetzt in zwei Branches: `:app` (#1–#4) auf
   `chore/cancellation-audit`, `:data` (#5–#8) auf `chore/cancellation-audit-data`
   (auf ersterem aufgesetzt).
3. **Testabdeckung** — für #7/#8 **kein Unit-Test**, sondern Linter-Guard +
   KDoc. Nach dem Codelesen war der natürliche Ort *nicht* ein Contract-Test:
   der Suspension Point ist das Framework-`emit` in einem `Flow.catch`-Recovery-
   Arm, den die inneren Catches fast unerreichbar machen — keine injizierbare
   Naht wie beim `WallpaperViewBinderCancellationTest` (dort ist der
   `BitmapLoader` injiziert). Ein Test wäre Timing-Theater; die
   `cancel_files`-Whitelist ist der ehrliche, deterministische Guard, die
   Begründung steht im Impl-KDoc (Prinzip „ehrliches KDoc > zu viele Tests").

Zusätzlich mit erledigt: die „Abgeschirmt, aber fragil"-Stelle
`AppDrawerFragment:560` — der #4-Fix gibt dem äußeren Catch einen eigenen
Rethrow-Arm, die Stelle ist nicht mehr zufällig durch die Aufrufkette
abgeschirmt, sondern strukturell korrekt.
