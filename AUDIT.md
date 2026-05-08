# AUDIT.md

> Erstellt am 2026-05-03. Externe-Reviewer-Perspektive (Senior Android-Engineer
> mit Kenntnis der 13 CLAUDE.md-Regeln). Funde sind mit `file:line` belegt;
> Behauptungen wurden gegen das tatsächliche Repo verifiziert (mehrere initial
> gemeldete „BLOCKER" haben sich als Fehlannahmen herausgestellt und wurden
> entfernt).
>
> **Aktualisiert 2026-05-04:** HomeFragment- und MainActivity-Zeilenzahlen
> auf den Stand nach Brocken-A-Sweep (TODO „Pfad zu 9+") und MainActivity-
> Sweep (commits `d5c5ce3 → 0b7a21c → 986d478 → 78903ec`) nachgezogen —
> das Original-Audit hatte mit 2657 / 797 Zeilen gearbeitet, inzwischen
> sind HomeFragment bei 1989 Zeilen / 19 Catches und MainActivity bei
> 991 Zeilen / 25 Catches (21 Throwable). Plus Rule-9-DEBUG-Throw-Bug
> in `mainActivityExceptionHandler` gefixt. Die Bewertungs-Logik der
> einzelnen Befunde bleibt unverändert; nur die belegten Zahlen wurden
> gesynced. Score-Update siehe TODO.md → Audit-Snapshot.
>
> **Ergänzt 2026-05-07:** Zweiter Audit-Pass aus frischer Session, fokussiert
> auf Polish-Opportunitäten (Code-Duplikation, Konvention-Drift, tote
> Scaffolding). Neue Befunde stehen in §8; zwei stale Stellen im Original
> (§3.4 `advanceUntilIdle`-Claim, §5.8 String-Parität-Zahlen) und ein
> bereits behobenes Issue (§4.1 PackageName-Log) in §8.11 korrigiert.

**Skala:**
- 🔴 **BLOCKER** — bricht Architektur-Regel oder Korrektheit
- 🟠 **MAJOR** — Risiko/Aufwand bei nächster Änderung
- 🟡 **MINOR** — Stil, Hygiene, Konsistenz
- 🟢 **NIT** — kosmetisch, optional

**Gesamteindruck:** Reifer Code mit ungewöhnlich strenger Disziplin
(Repository-Pattern, Contract-Tests, dokumentierte Throwable-Sweeps,
Robolectric-Zwei-Schichten-Strategie). Keine echten Korrektheits-BLOCKER
gefunden. Funde konzentrieren sich auf **Konsistenz** (Doku ↔ Code-Drift,
Test-Pattern-Konsistenz) und **Hygiene** (Ressourcen, Magic-Werte).

---

## 1. Architektur- & Regelverletzungen (CLAUDE.md Rules 1–13)

### 1.1 🟠 MAJOR — CLAUDE.md ↔ Code-Drift bei Implementierungs-Naming

**Rule 1** (CLAUDE.md:74): „Production implementations live in `data/` as
**`XyzManager`**". **Rule 2** (CLAUDE.md:80): Contract-Triple nennt
„**`XyzManagerContractTest`**".

**Realität:** Alle 17 Production-Implementierungen heißen `*RepositoryImpl.kt`.
Belegt durch Commit `0f9e7be Rename data/ Manager classes to RepositoryImpl` —
der Rename ist erfolgt, **CLAUDE.md wurde nicht nachgezogen**. Auch
`app/src/test/CLAUDE.md` verwendet vereinzelt noch „Manager".

**Auswirkung:** Neue Contributors (oder Claude in einer frischen Session)
suchen nach `FavoritesManager`, finden nichts, und sind verwirrt.
Die einzige Ausnahme `WallpaperFileManager.kt` ist tatsächlich ein Helper,
kein Repository — und stützt die Verwirrung zusätzlich.

**Fix:** Rule 1 + Rule 2 in CLAUDE.md auf `*RepositoryImpl` aktualisieren.

---

### 1.2 🟠 MAJOR — Rule 13 frisch verletzt: deutscher Kommentar in neuem Code

`app/src/main/java/com/github/reygnn/kolibri_launcher/data/AppUsageRepositoryImpl.kt:171-173`

```kotlin
// Pure Long/Double-Arithmetik plus `kotlin.math.exp` (gibt NaN
// statt zu werfen) und die safe-coerce-Extensions. Die früheren
// Throwable-Catches hier konnten nichts fangen.
```

Hinzugefügt in Commit `460ce36 Layer-A throwable sweep: drop 9 cant-throw / dead catches`.
Rule 13 (CLAUDE.md:280–290): „Source-code comments and KDoc that you add or
rewrite must be in English." Der gesamte Kommentar-Block wurde im Sweep neu
geschrieben — also unter Rule 13 fallend.

**Fix:** Block ins Englische übersetzen.

---

### 1.3 🟠 MAJOR — 3 Repositories ohne Contract-Triple, nicht dokumentiert

Rule 2 verlangt das Triple `XyzRepositoryContract` + `Fake…ContractTest` +
`Impl…ContractTest`. Ausnahmen müssen in der Contract-KDoc begründet sein.

**13 vollständige/dokumentierte Triples** (✓): AppUsage, Backup (Fake-only,
dokumentiert), CustomNames, FavoritesOrder, Favorites, HiddenApps,
InstalledApps (Fake-only, dokumentiert), InstalledAppsState, ScreenLock,
Settings, SwipeActions, TimeBasedEvents (ADR-only, dokumentiert), Wallpaper.

**3 echte Repositories ohne Contract-Datei** (Impl-Tests existieren, aber
keine Drift-Protection zwischen Fake und Impl):

| Repository | Impl-Test vorhanden? | Contract |
|---|---|---|
| `ShortcutRepository` | ✓ `ShortcutRepositoryImplTest.kt` | ✗ fehlt |
| `ResetRepository` | ✓ `ResetRepositoryImplTest.kt` | ✗ fehlt |
| `UsageExportRepository` | ✓ `UsageExportRepositoryImplTest.kt` (+ 4 Spec-Files) | ✗ fehlt |

**Bewertung:** Kein BLOCKER, weil Tests existieren — aber die zentrale
Drift-Schutz-Disziplin fehlt. Entweder Contract nachziehen oder ADR-KDoc
schreiben („System-API, kein Fake möglich").

**Komposition-Repositories** (`GetFavoriteAppsUseCaseRepository`,
`GetDrawerAppsUseCaseRepository`, `GetOnboardingAppsUseCaseRepository`,
`Purgeable`) sind keine echten Daten-Repositories; für sie sind Contracts
nicht nötig — sollte aber explizit in deren KDoc stehen.

---

### 1.4 ✅ Keine Funde

Sauber durch:
- **Rule 1** — keine `*RepositoryImpl`/Manager-Imports in UseCases/ViewModels (nur Interfaces).
- **Rule 4** — kein Service-Locator/Manual-DI neben Hilt.
- **Rule 5** — `getSharedPreferences(` taucht nur in den zwei dokumentierten Files auf.
- **Rule 6** — alle `DO NOT…`-Pinning-Kommentare an der richtigen Deklaration.
- **Rule 7** — `KolibriLauncherApp` Mehrschicht-Paranoia ist intakt.
- **Rule 8** — ACRA wird sofort nach Init deaktiviert; Reaktivierung nur bei Consent.
- **Rule 9** — kein nacktes `Timber.e(` außerhalb `KolibriLauncherApp.kt` und `TimberWrapper.kt`.
- **Rule 10** — keine Activity/Fragment/Receiver mit nennenswerter Business-Logik außerhalb Helpern. (`HomeFragment` 1989 Zeilen sind View-Glue + Wallpaper-Edit, dokumentiert.)
- **Rule 11** — keine Catches um can't-throw-Operationen mehr; TODO §2 Sweep hat 229 entfernt, Brocken-A-Audit weitere 40 Catches in HomeFragment (sieben Region-Sweeps, 59→19), Reste sind legitim.
- **Rule 12** — keine `Timber.Forest.`-Aufrufe.

---

## 2. Code-Smells, Duplikation, Komplexitäts-Hotspots

### 2.1 (erledigt 2026-05-04) — Sweep `repeatOnLifecycle` → `collectOnStarted`

Audit-Ausgang: „34 Vorkommen" — bei genauerer Verifikation 12 echte
Call-Sites (Rest waren Imports + Doc-Kommentare). Davon waren 8
konvertierbar; die übrigen 4 sind multi-launch / nested-coroutine-Shapes,
die per `FlowCollection.kt`-KDoc bewusst aus dem Extension-Scope
ausgenommen sind.

**Umgesetzt (commit nach 2026-05-04):**

- Neue `ComponentActivity.collectOnStarted`-Extension parallel zur
  bestehenden Fragment-Variante in `ui/flow/FlowCollection.kt`
  (Activities haben kein `viewLifecycleOwner`, brauchen daher eigenen
  Receiver).
- 8 Activity-Sites konvertiert: `CustomNamesActivity` (×2),
  `HiddenAppsActivity` (×2), `OnboardingActivity` (×2 — bei einer fiel
  ein unnötiger Inner-`launch` weg), `SwipeActionsActivity` (×2).

**Bewusst NICHT konvertiert (4 Sites):**

- `AppDrawerFragment:220` — Body enthält `searchJob = launch { delay; ... }`
  (debounced search). KDoc-explicit „Not covered".
- `BaseActivity:61` — multi-launch (Job 1 + Job 2 + …) inside
  `repeatOnLifecycle`. KDoc-excluded.
- `UsageExportFragment:105` — multi-launch.
- `SettingsFragment:644` — multi-launch.

Net-LOC: ~+30 (wegen der neuen ComponentActivity-Extension; reine
Call-Sites etwa gleich groß). Audit-Schätzung „250–300 Zeilen
Reduktion" beruhte auf dem unfilterten 34-er-Count und überschätzte
die Pro-Site-Ersparnis — realer Win liegt nicht im LOC, sondern in:

- zentralisierter Error-Handling-Schicht (Catch-Logik nur einmal in
  FlowCollection.kt, nicht 8× kopiert)
- drift-resistenter Konvention (wenn die Inner-Catch-Policy ändert,
  ändert sie sich an einer Stelle)
- symmetrischer Coverage von Fragment + Activity (Extension parallel
  zur Fragment-Variante)
- weniger kognitives Boilerplate-Geräusch — `collectOnStarted` ist
  benannt für seinen Zweck.

---

### 2.2 🟡 MINOR — Datei-Größen-Hotspots (>500 Zeilen)

| Datei | Zeilen | Bewertung |
|---|---:|---|
| `ui/home/HomeFragment.kt` | 1989 | Bewusst (CLAUDE.md erwähnt explizit); post-Brocken-A 2026-05-03 von 2657 reduziert |
| `ui/home/ZoomableImageView.kt` | 1468 | Custom-View für Multi-Layer-Wallpaper-Edit; vertretbar |
| `data/BackupRepositoryImpl.kt` | 1444 | TODO.md §3 Split-Verwerfung dokumentiert |
| `ui/settings/SettingsFragment.kt` | 1074 | Klassisches PreferenceFragment-Smell — Aufteilbar |
| `ui/main/MainActivity.kt` | 991 | Launcher + HOME-Activity, Plattform-Glue; post-Sweep 2026-05-03 (37 → 21 Throwable Catches), gewachsen durch neuen KDoc-Header-Block, reine Code-Größe gesunken |
| `ui/main/delegate/WallpaperDelegate.kt` | 609 | Edit-Session-Protokoll, vertretbar |
| `ui/appdrawer/AppDrawerFragment.kt` | 607 | Aufteilbar in Adapter/Coordinator-Helfer |

**Empfehlung:** `SettingsFragment` und `AppDrawerFragment` würden von einer
Aufteilung profitieren (z. B. `SettingsBackupSection`, `SettingsResetSection`,
`AppDrawerKeyboardCoordinator` ist bereits extrahiert — Muster fortsetzen).

---

### 2.3 🟢 NIT — 2 Funktionen ~100 Zeilen

- `ui/home/ZoomableImageView.kt:464` — `addLayer()` ~101 Zeilen (Layer-Init mit 5-Wege-Logik)
- `data/BackupRepositoryImpl.kt:509` — `resolveZipImages()` ~101 Zeilen (ZIP-Deserialisierung)

Beide sitzen an I/O- bzw. Initialisierungs-Grenzen, wo Aufteilung Klarheit
nicht erhöht. Akzeptabel.

---

### 2.4 🟢 NIT — 18 `@Suppress` / `@SuppressLint` insgesamt

Alle inline begründet (7× `unused` auf `ZoomableImageView`-Public-API,
4× `ClickableViewAccessibility` auf Touch-Handlern, 2× `UNCHECKED_CAST` in
generischem Kontext, 2× `DEPRECATION` für Plattform-Fallback,
2× `UNREACHABLE_CODE` für Feature-Flag in `ScrollViewBorderDecorator`).
Keine ungerechtfertigten Suppresses.

---

### 2.5 ✅ Keine Funde

- **Magic Numbers** — `AppConstants.kt` ist diszipliniert genutzt.
- **TODO/FIXME/HACK** — null in `app/src/main/`. Sauber.
- **Mixed-Language-Comments** — historisches Deutsch ist isoliert in legacy
  Constants/Repos; Rule 13 wird sonst eingehalten.

---

## 3. Test-Coverage-Lücken & Test-Qualität

### 3.1 🟠 MAJOR — Pure-Logic-Helper ohne Test (Rule 10)

Rule 10 verlangt: alles Reine („state transitions, decisions, parsing,
side-effect orchestration") gehört in testbare Helper. Diese zwei sind
extrahiert, aber **untested**:

- `ui/appcontextmenu/ContextMenuHelper.kt` — keine Test-Datei
- `ui/home/ScrollViewBorderDecorator.kt` — keine Test-Datei

**Fix:** Tests anlegen oder, falls die Klassen wirklich nur View-Glue sind,
in das jeweilige Fragment zurückführen.

---

### 3.2 🟠 MAJOR — `mockk(relaxed = true)` Über-Nutzung: 313 Vorkommen

```bash
$ grep -rc "relaxed = true" app/src/test | awk -F: '{sum+=$2} END {print sum}'
313
```

`TESTING_CONVENTIONS.kt` empfiehlt chirurgische Relaxation (einzelne
Suspend-Funktionen), nicht flächendeckend. 313 Treffer deuten auf:
- Über-Mocking statt Fakes (besonders `HiddenAppsViewModelTest`,
  `SettingsViewModelTest`, `LauncherViewModelTest`)
- Versteckte Spezifikations-Lücken (relaxed → keine Verifikation der
  tatsächlichen Aufruf-Argumente)

**Fix:** Stichproben-Audit der Top-10 ViewModelTests; wo Fake-Repos
existieren, lieber diese verwenden (Contract-Schutz inklusive).

---

### 3.3 🟡 MINOR — Inkonsistente ViewModel-Test-Pattern

Spot-Check 4 ViewModelTests:
- `BackupViewModelTest` ✓ Fakes + echte UseCases
- `CustomNamesViewModelTest` ✓ Fakes + echte UseCases
- `HiddenAppsViewModelTest` ✗ relaxed Mocks
- `SettingsViewModelTest` ✗ relaxed Mocks

Zwei inkompatible Schulen koexistieren. Eine `BaseViewModelTest`-Klasse mit
`MainDispatcherRule` + `TimberRule` als gemeinsamem Setup würde Boilerplate
ziehen und die Fake-Schule als Default etablieren.

---

### 3.4 ✅ Keine Funde

- Alle 10 ViewModels haben Test-Dateien.
- Alle 7 Delegates in `ui/main/delegate/` haben Tests.
- Null `runTest {` ohne `mainDispatcherRule.dispatcher`.
- ~~Null `advanceUntilIdle()`.~~ Falsch — siehe §8.11 (640 Vorkommen über
  21 Files; gemeint war wahrscheinlich „kein `advanceUntilIdle` gegen
  `WhileSubscribed`-Flows").
- Null Mockito-Imports — Migration vollständig.
- Null `@Ignore`, null `TODO()` in Test-Funktionen.
- `androidTest/` ist leer (historisch, dokumentiert).
- Robolectric-Zwei-Schichten-Strategie korrekt (`robolectric.properties`
  setzt `application=android.app.Application`).

---

## 4. Error-Handling, Security, Privacy

### 4.1 ✅ Erledigt — PII-Log: Paketnamen in Debug

`PackageUpdateReceiver.kt:66-67` hasht den Paketnamen via
`packageName.hashCode().toString(16)`, statt ihn im Klartext zu loggen.
Begründung steht im Kommentar darüber. Siehe §8.11.

> Original-Befund: `KolibriLauncherApp.kt` Bereich `PackageUpdateReceiver` —
> Zeile ~59 loggte `"package: $packageName"` bei `PACKAGE_ADDED/REMOVED`.
> Nur `.d()`-Level, aber Paketnamen aller User-Apps sind potenziell PII
> (z. B. `com.gambling.bigwin`, `com.affair.app`). Fix-Vorschlag war
> `BuildConfig.DEBUG`-Guard oder Hash statt Klartext — Hash wurde umgesetzt.

---

### 4.2 🟢 NIT — Breite ProGuard-Keep-Regel

`app/proguard-rules.pro:24`:
```
-keep class com.github.reygnn.kolibri_launcher.** { *; }
```

Defeat-iert die Obfuskierung der eigenen Klassen — bewusst gewählt für
Crash-Reporting-Treue (ACRA-Mappings sind aussagekräftiger). Akzeptabel,
aber granulare Regeln pro Komponente wären eleganter.

---

### 4.3 🟢 NIT — `runBlocking` in `attachBaseContext`

`KolibriLauncherApp.kt:155` —
`runBlocking { CrashReportConsent.hasConsent(base) }`. Bewusst (Rule 8 +
KNOWN_ISSUES.md): ACRA muss vor jedem möglichen Crash konsent-geprüft sein,
der Read ist klein, passiert einmal kalt-startend. Dokumentiert.

---

### 4.4 ✅ Keine Funde

- **Catch-Hygiene** — alle Catches spezifisch oder mit `silentError`.
- **CancellationException** — überall korrekt rethrow-t.
- **Non-Null `!!`** — null Vorkommen im Hauptcode.
- **Hardcoded Secrets** — ACRA-Credentials kommen aus `secrets.properties`
  via BuildConfig. `cleartextTrafficPermitted="false"`.
- **Manifest-Exports** — nur `MainActivity` exported (Launcher-Pflicht).
- **Receiver-Registrierung** — `RECEIVER_EXPORTED` korrekt für System-Broadcasts.
- **Crash-Handler-Sicherheit** — Mehrschicht-Catches im UncaughtExceptionHandler.
- **Backup/Restore** — Hardened-Parser mit Type-Validation, Size-Caps,
  Inf/NaN-Rejection, Array-Length-Caps.
- **Wallpaper-File-Handling** — Pfad ist hardcoded sandboxed
  (`filesDir/wallpapers/`); Filenamen via Timestamp+AtomicLong, keine
  User-Inputs.

---

## 5. Ressourcen, Localization, Build, Dependencies

### 5.1 🟡 MINOR — Hardcoded `dp`/`sp` in `fragment_home.xml`

Zeilen ~139, 142, 144–146: `48dp`, `14sp`, `16dp`, `12dp` direkt im Layout
statt `@dimen/`. Erschwert Theme-Anpassung und Layout-Customization
(die App hat dafür sogar einen eigenen Customization-Dialog!).

**Fix:** In `app/src/main/res/values/dimens.xml` heben.

---

### 5.2 🟡 MINOR — `@android:color/white` 16+ Mal in Layouts

`fragment_home.xml`, `item_app_drawer.xml` u. a. nutzen
`@android:color/white` direkt. Eine eigene `@color/icon_tint_default` (oder
über Theme-Attribute) wäre Dark-Mode-zukunftssicherer. (Aktuell ist Dark Mode
kein Feature, aber das Material-3-Theme nutzt `DynamicColors.DayNight` —
Halbweg-Konsistenz wäre sauber.)

---

### 5.3 🟡 MINOR — Unbenutztes Drawable

`app/src/main/res/drawable/ic_center_focus.xml` — keine Source-Referenz.
Wahrscheinlich Überbleibsel.

**Fix:** Löschen.

---

### 5.4 🟡 MINOR — `kotlin-kapt` ist Legacy

`app/build.gradle.kts:27` aktiviert `id("kotlin-kapt")`. Hilt 2.57.2
unterstützt seit Längerem KSP, das deutlich schneller baut. Kein dringender
Bug, aber Build-Performance-Win bei nächster Hilt-Major-Version.

**Caveat:** `Hilt 2.57.2` ist mit `DO NOT UPGRADE` gepinnt — KSP-Migration
müsste mit dem Pinning kompatibel sein, vorher prüfen.

---

### 5.5 🟡 MINOR — `android:configChanges`-Suppression auf 2 Activities

`AndroidManifest.xml` Zeilen 54 & 92: `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode"`
unterdrückt Activity-Recreation. Wenn beabsichtigt (z. B. eigenes Rotation-
Handling im Wallpaper-Edit-Modus), Kommentar hinzufügen; sonst entfernen
und Lifecycle-States nutzen.

---

### 5.6 🟢 NIT — Hardcoded „100%" in `fragment_home.xml:61`

`android:text="100%"` als Vorschau-Fallback auf `batteryText`. Wird
runtime überschrieben — aber Layout-Editor-Preview leidet weniger, wenn
`@string/battery_placeholder` mit `translatable="false"`.

---

### 5.7 🟢 NIT — Kein Gradle-Version-Catalog

Versionen sind direkt in `app/build.gradle.kts` hardcoded. Bei einem
Single-Module-Projekt akzeptabel; ein `gradle/libs.versions.toml` würde die
vielen `DO NOT…`-Pinnings übersichtlicher machen.

---

### 5.8 ✅ Keine Funde

- **String-Parität EN/DE** — ~~237/236 Keys, einziger Unterschied
  `time_placeholder`~~. Aktualisiert per §8.11: 246 EN / 232 DE; alle 14
  Differenzen sind `translatable="false"` (`battery_placeholder` + 13
  `blend_mode_*`). Vollständig lokalisiert.
- **Hardcoded UI-Strings in Code** — null gefunden.
- **Material-3-Compliance** — `Theme.Material3.DynamicColors.DayNight`,
  Splash-Screen-API, Edge-to-Edge alles korrekt.
- **R8/Shrinking** — `isMinifyEnabled = true`, `isShrinkResources = true`
  im Release-Build.
- **Adaptive Icon** — `mipmap-anydpi-v26/ic_launcher.xml` + Round-Variante.
- **Backup-Rules** — `xml/backup_rules.xml` und `xml/data_extraction_rules.xml`
  vorhanden (Android-12+-Pflicht).
- **`.gitignore`** — `local.properties`, `secrets.properties`,
  `keystore.properties`, `*.jks` alle ignoriert.
- **Layouts** — alle scheinbar unbenutzten Layouts (z. B.
  `dialog_color_customization.xml`) werden via ViewBinding-generierte
  Klassen referenziert; **kein** Cleanup-Bedarf.

---

## 6. Quick-Wins (geringer Aufwand, hoher Wert)

| # | Aufwand | Wert | Aktion |
|---|---|---|---|
| 1 | ~~5 min~~ | hoch | ~~CLAUDE.md Rule 1 + Rule 2 von „XyzManager" auf „XyzRepositoryImpl" aktualisieren~~ — erledigt vor 2026-05-07 (§ 1.1) |
| 2 | ~~2 min~~ | mittel | ~~`AppUsageRepositoryImpl.kt:171-173` Kommentar ins Englische~~ — erledigt vor 2026-05-07 (§ 1.2) |
| 3 | ~~1 min~~ | klein | ~~`app/src/main/res/drawable/ic_center_focus.xml` löschen~~ — erledigt vor 2026-05-07 (§ 5.3) |
| 4 | ~~30 min~~ | mittel | ~~Mechanischer Sweep `repeatOnLifecycle` → `collectOnStarted`~~ — erledigt 2026-05-04 (§ 2.1) |
| 5 | 1 h | hoch | Tests für `ContextMenuHelper` + `ScrollViewBorderDecorator` schreiben oder einbetten (§ 3.1) |
| 6 | 1 h | mittel | ADR-KDoc in `ShortcutRepository`/`ResetRepository`/`UsageExportRepository` ODER Contract nachziehen (§ 1.3) |
| 7 | 2–4 h | hoch | Top-10 `mockk(relaxed = true)`-Hotspots auf Fakes umstellen (§ 3.2) |

---

## 7. Bewusst akzeptierte Schulden (NICHT zu beanstanden)

Diese Punkte würden in einem oberflächlichen Audit als „Issue" markiert, sind
aber **bewusste Architektur-Entscheidungen** und korrekt dokumentiert:

| Punkt | Begründung | Quelle |
|---|---|---|
| `KolibriLauncherApp` Mehrschicht-Catches | Crash-Festung; jede Schicht muss überleben | Rule 7 |
| 8 nackte `Timber.e(`-Aufrufe im Crash-Handler | `silentError` würde dort rekursieren | Rule 9 |
| 2× SharedPreferences (`DataMigrationManager`, `CrashReportLimiter`) | Bootstrap-Henne-Ei + sync ACRA-Aufruf | Rule 5 |
| `runBlocking` in `attachBaseContext` | Privacy-Race-Window vermeiden | KNOWN_ISSUES.md |
| ProGuard `keep class …** { *; }` | Stack-Trace-Treue für Crash-Reports | Projekt-Philosophie (GPLv3) |
| `HomeFragment` 1989 Zeilen (post-Brocken-A 2026-05-03) | Pure Logik bereits extrahiert; UI-Glue bleibt; verbleibende Catches sind echte Boundaries | CLAUDE.md, TODO „Pfad zu 9+" |
| `BackupRepositoryImpl` 1444 Zeilen | Split würde 9 Repo-Deps duplizieren, nicht reduzieren | TODO.md §3 |
| `SettingsFragment` 1074 Zeilen (§2.2) | PreferenceFragmentCompat-Pattern; weiteres Aufteilen wäre rein kosmetisch — keine echte Komplexität | akzeptiert 2026-05-03 |
| `AppDrawerFragment` 607 Zeilen (§2.2) | KeyboardShowCoordinator + Adapter sind bereits extrahiert; weiteres Aufteilen wäre rein kosmetisch | akzeptiert 2026-05-03 |
| `androidTest/` leer | Historische Flakiness, JVM-First per Rule 10 | CLAUDE.md |
| StrictMode-Violations (OneUI/Knox) | Plattform-Bug, nicht im App-Code behebbar | KNOWN_ISSUES.md |
| 7× `@Suppress("unused")` in `ZoomableImageView` | Public-API-Stubs für externe Konsumenten | inline kommentiert |
| `kotlin-kapt` statt KSP (§5.4) | Toolchain-Wechsel nur bei Major-Rewrite — Hilt-Pinning (2.57.2 DO NOT UPGRADE) plus kotlin-parcelize-Kompatibilität müssten geprüft werden, Nutzen ist rein Build-Performance | aufgeschoben 2026-05-03 |
| Inkonsistente VM-Test-Patterns (§3.3) | Audit-Befund hält Re-Inspektion nicht stand: SettingsViewModelTest mockt UseCases (kein Fake-Pendant existiert, UseCase-Mocking ist hier legitim), nicht Repositories. Die Repo-Mock-Fälle (HiddenAppsViewModelTest) wurden in §3.2 auf Fakes umgestellt. Alle VM-Tests teilen heute `MainDispatcherRule` + `TimberRule`. Audit-Vorschlag „BaseViewModelTest"-Basisklasse wäre ~6 Zeilen Boilerplate-Reduktion × 12 Tests — separater QoL-Refactor, kein Audit-Fix | abgehakt 2026-05-03 |

---

## 8. Nachtrag-Audit 2026-05-07 — Fresh-Eyes-Polish-Sweep

> Zweiter Audit-Pass aus frischer Session. Fokus: Polish-Opportunitäten mit
> struktureller Substanz, die nicht in TODO.md oder oben stehendem Audit
> aufgeführt sind. Befunde wurden cross-checked gegen TODO.md / Original-
> Audit; alle hier gelisteten Sites sind durch direkten Datei-Read
> verifiziert. Stichproben-Verifikation der konkreten Site-Zahlen ist im
> Konversations-Log dokumentiert.

### 8.1 🟡 MINOR — Delegate-Wrap und `launchSafe`-Doppel-Sicherung

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/delegate/AppManagementDelegate.kt:129-223` (6 Methoden), `ThemingDelegate.kt:74-105` (3 Methoden), analog in `WallpaperDelegate.kt` und `ClockDelegate.kt`.

`DelegateScope.launchSafe` (`DelegateScope.kt:41-54`) kapselt bereits
```kotlin
try { block() }
catch (e: CancellationException) { throw e }
catch (e: Throwable) { TimberWrapper.silentError(e, errorMessage) }
```
Jede Delegate-Methode wickelt ihren Body in **exakt dasselbe** try/catch und
sendet zusätzlich einen Toast-Event. Tatsächliches Verhalten:

- **Production:** Innerer `Throwable`-Catch swallowed alles; der äußere
  `launchSafe`-Catch sieht nichts, ein einziger `silentError` pro Error.
  Der `launchSafe`-Catch ist hier reine ungenutzte Sicherheits-Schicht.
- **DEBUG:** Innerer `silentError` wirft → äußerer `launchSafe`-Catch
  fängt → zweiter `silentError` wirft erneut. Doppel-Throw-Kaskade.

Der ursprüngliche Befund („zwei `silentError`-Aufrufe in Production") war
falsch und ist hier korrigiert.

**Reduktions-Potenzial:** Methoden mit `R.string.error_generic` als
Toast (4 von 5 in `AppManagementDelegate`, alle 3 in `ThemingDelegate`)
könnten auf eine `launchSafe(errorMessage, defaultToast = R.string.error_generic)`-Variante
kondensieren. Methoden mit feature-spezifischem Toast (`onAppClicked` →
`error_launching_app`) bleiben inline. Realistische Schätzung: ~20 Zeilen
Reduktion, nicht die initial behaupteten 40.

**Bewertung MINOR statt MAJOR:** kein Korrektheits-Bug (das Verhalten ist
in Production identisch zur idealen Form), nur Boilerplate-Konsolidierung
plus DEBUG-Doppel-Throw vermeiden.

---

### 8.2 🟠 MAJOR — `MutableSharedFlow()` ohne Buffer in 6 Tests

Der dokumentierte Anti-Pattern aus `app/src/test/CLAUDE.md` §2 (Erste
Emission ohne Subscriber suspendiert) ist in 6 Setup-Blöcken aktiv:

- `app/src/test/.../ui/main/MonolithicLauncherViewModelTest.kt:208`
- `app/src/test/.../ui/main/LauncherViewModelTest.kt:193`
- `app/src/test/.../ui/main/LauncherViewModelSecurityTest.kt:140`
- `app/src/test/.../ui/main/LauncherViewModelDoomsdayTest.kt:122`
- `app/src/test/.../ui/main/LauncherViewModelContractTest.kt:210`
- `app/src/test/.../ui/main/delegate/AppManagementDelegateTest.kt:129`

Production wired das in `di/AppUpdateModule.kt:18` als
`MutableSharedFlow(replay = 0, extraBufferCapacity = 1)`. Tests stubben
mit bare `MutableSharedFlow()`. Latente Flake-Quelle bei Race zwischen
Test-Subscribe-Timing und ersten Emits.

**Fix:** Production-Wiring nachbauen (`extraBufferCapacity = 1`) oder
`onBufferOverflow = BufferOverflow.DROP_OLDEST`. Keine separate Test-
Helper-Funktion nötig, ein Konstruktor-Argument reicht.

---

### 8.3 🟠 MAJOR — Rule 11 in `BaseViewModel.showErrorToastIfSupported`

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/base/BaseViewModel.kt:152-166`

```kotlin
protected open fun showErrorToastIfSupported() {
    viewModelScope.launch(coroutineExceptionHandler) {
        try {
            @Suppress("UNCHECKED_CAST")
            sendEvent(UiEvent.ShowToast(R.string.error_generic) as E)
        } catch (e: CancellationException) { throw e }
        catch (e: ClassCastException) {
            Timber.d("This ViewModel does not support UiEvent error toasts")
        }
    }
}
```

`try/catch ClassCastException` als Runtime-Type-Check ist genau der
Anti-Pattern aus Rule 11 — Catch um eine Operation, deren einzige Failure-
Mode Programmer-Error ist. Fire-Pfad: jeder ViewModel mit `E != UiEvent`
landet bei jedem Error in dieser Catch-Branch.

**Fix:** Strukturell — `protected open val errorEvent: E? = null` (oder
Factory-Lambda im Constructor) und `errorEvent?.let { sendEvent(it) }`.
Der Type-Test wird zu einem Null-Check, kein Catch nötig.

---

### 8.4 🟠 MAJOR — Rule 13 verletzt: weitere net-new deutsche Kommentare

Zwei zusätzliche Sites zur Existing-Finding §1.2:

- `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/MainActivity.kt:388-400` (Commit `014d9f5c`, 2026-04-30) — kompletter Erklär-Block in Deutsch zu `silentDeath`-Strategie und HOME-Activity-Restart-Loop.
- `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/AppDrawerAdapter.kt:102-104` (Commit `5033e123`, 2026-04-30) — „when auf String-Payloads + holder.updateX-Aufrufe sind reine TextView-Setter…".

Beide stammen aus Rule-11-Sweep-Commits, also nach Einführung von Rule 13.
`tools/check-conventions.sh` fängt Rule 13 nicht (per TODO §7 wäre git-diff-
Integration nötig), daher silent drift. Weitere Sites wahrscheinlich.

**Fix:** Komment-Sweep über alle 2026er Sweep-Commits, oder TODO §7 vorziehen
(git-diff-aware Lint). Sweep-only-Lösung ist Sisyphos ohne den Lint.

---

### 8.5 ✗ Zurückgezogen — `PackageUpdateReceiver`-Catch-Verschachtelung

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/PackageUpdateReceiver.kt:30-52`

Initialer Befund: 4 verschachtelte Catches in `onReceive` (`goAsync`,
`handleReceive`, zwei `pendingResult.finish()`-Wrapper) plus
`safeOnFinish` als drittes Wrapper.

**Zurückgezogen:** Die TODO §2-Sweep-Kampagne hat diesen Receiver explizit
geprüft und das Layering bewusst akzeptiert. Begründung steht im Code-
Kontext: `BroadcastReceiver.onReceive` darf nicht werfen lassen
(System-API-Kontrakt), `PendingResult.finish()` muss garantiert laufen
(sonst lebt der Receiver weiter und blockiert den Process), Hilt-Cold-
Start-Race ist real auf dem Package-Broadcast-Pfad. Die einzelnen Catches
adressieren unterschiedliche Failure-Modes — Konsolidierung würde Coverage
reduzieren.

Hier nur dokumentiert, damit künftige fresh-eyes-Audits diesen Befund
nicht erneut ziehen. Der korrekte Ort für die Begründung wäre §7
(„Bewusst akzeptierte Schulden"); bislang dort nicht eingetragen, weil
die Sweep-Akzeptanz nur im Commit-Verlauf sitzt.

---

### 8.6 🟡 MINOR — `domain/model/UsageExport.kt` ist tot

`/home/user/AndroidStudio_Projects/Kolibri_Launcher/domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/model/UsageExport.kt`

Datei mit 22 Zeilen, davon Z. 3-20 alle `//`-prefixed (data-class
`UsageExportData` mit `kotlinx.serialization`-Annotationen). Nur das
`package`-Statement ist live; die Datei produziert keine Symbole.
`UsageExportRepositoryImpl.kt:130` referenziert den toten Typ noch in
einem Kommentar („…da UsageExportData jetzt List<String> statt List<Long>
hat"). Half-state aus dem JSON-Serialization-Refactor.

**Fix:** Datei löschen oder reanimieren. Mittelweg ist die schlechteste
aller Welten.

---

### 8.7 🟡 MINOR — `purgeRepository`-Duplikation in `:data`

13 Repository-Impls in `:data` haben `purgeRepository`-Methoden mit
identischem Body-Schema:

```kotlin
override suspend fun purgeRepository() {
    try {
        dataStore.edit { preferences -> /* remove keys */ }
    } catch (e: CancellationException) { throw e }
    catch (e: Throwable) {
        TimberWrapper.silentError(e, "Failed to purge X repository")
    }
}
```

Alle 13 Files: `AppUsageRepositoryImpl`, `CustomNamesRepositoryImpl`,
`FavoritesRepositoryImpl`, `FavoritesOrderRepositoryImpl`, `HiddenAppsRepositoryImpl`,
`InstalledAppsRepositoryImpl`, `InstalledAppsStateRepositoryImpl`,
`ScreenLockRepositoryImpl`, `SettingsRepositoryImpl`, `ShortcutRepositoryImpl`,
`SwipeActionsRepositoryImpl`, `TimeBasedEventsRepositoryImpl`, `WallpaperRepositoryImpl`.

`SettingsRepositoryImpl` hat schon eine `safeEdit`-Helper-Methode, die
nirgends sonst genutzt wird.

**Fix:** Module-Helper `suspend fun DataStore<Preferences>.safePurge(repoName: String, edit: MutablePreferences.() -> Unit)`. Schätzung ~70 Zeilen Reduktion.
Caveat: Einige `purgeRepository`-Bodies sind absichtlich No-Op
(`InstalledAppsStateRepository` ist in der Drift-Doku) — die müssen
dokumentiert No-Op bleiben, nicht in den Helper hineinrefactort.

---

### 8.8 🟡 MINOR — `:data` hält `buildConfig = true` für eine einzige Zeile

`data/src/main/java/com/github/reygnn/kolibri_launcher/data/FavoritesRepositoryImpl.kt:289` ist die **einzige** `BuildConfig.DEBUG`-Referenz im gesamten `:data`-Modul (verifiziert via grep).

Die ganze BuildConfig-Generation existiert für eine `Timber.d`-Branch.
`TimberWrapper.isDebugBuild` ist bereits aus `:app`/`KolibriLauncherApp.onCreate`
durchgereicht (per §11/§12-Migration). `:data` könnte `buildConfig = true`
aus seiner `build.gradle.kts` entfernen — kleineres AAR, eine Indirektion
weniger.

---

### 8.9 ✗ Zurückgezogen — `BaseViewModel.handleError` Cancellation-Branches

Initialer Befund: `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/base/BaseViewModel.kt:122` (`is CancellationException -> Timber.d(...)`) und Z. 141 (`is CancellationException -> true` in `shouldSuppressErrorToast`) seien dead code, weil alle drei Aufrufpfade (`launchSafe`, `executeSafe`, `coroutineExceptionHandler`) CancellationException herausfiltern.

**Zurückgezogen:** Die Pfad-Analyse stimmt für `launchSafe`/`executeSafe`/`coroutineExceptionHandler`, übersieht aber die **`protected open` API-Surface**. `BaseViewModelTest.kt:425-438` exerziert `handleError` direkt mit `CancellationException`:

```kotlin
@Test
fun `handleError processes CancellationException without toast`() = runTest {
    val vm = createViewModel()
    val events = mutableListOf<UiEvent>()
    val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }
    vm.testHandleError(CancellationException("Cancelled"), "test-context")
    advanceUntilIdle()
    assertFalse(events.any { it is UiEvent.ShowToast })
    job.cancel()
}
```

Entfernt man die `is CancellationException -> true` aus `shouldSuppressErrorToast`, wird der Test rot — Cancellation rutscht in den Toast-Pfad. Die Branches sind kein dead code, sondern enforcen den Vertrag der `protected open` API.

Außerdem: Rule 11 betrifft `try/catch` um can't-throw Operationen, nicht `when`-Pattern-Matching auf einer Throwable-Hierarchie. Defensives Type-Dispatching in einer offenen Vertrags-Methode ist legitim und nicht von Rule 11 erfasst.

Hier dokumentiert, damit künftige Audits den Befund nicht erneut ziehen. Korrekter Ort wäre §7 („Bewusst akzeptierte Schulden"); bislang dort nicht eingetragen, weil die Test-Verbindung im Audit nicht sichtbar war.

---

### 8.10 🟢 NIT — Auskommentierte Klassen-Deklaration in `AppDrawerFragment`

`app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/AppDrawerFragment.kt:69`

```kotlin
@AndroidEntryPoint
//class AppDrawerFragment : Fragment(R.layout.fragment_app_drawer) {
class AppDrawerFragment : Fragment() {
```

Leftover aus dem Zeitpunkt, als der Fragment via Layout-ID-Konstruktor
aufgesetzt wurde. Die lebende Klasse inflated jetzt manuell in
`onCreateView`. Entweder Comment löschen oder zur Layout-ID-Form
zurückkehren (kürzer).

---

### 8.11 Korrekturen am bestehenden Audit

Bei der Verifikation der eigenen Findings sind drei Drifts im Original-
Audit aufgefallen. Alle drei sind inzwischen in den Original-Sektionen
inline markiert (§3.4 strikethrough mit Korrektur, §4.1 als „Erledigt"
umgeschrieben, §5.8 mit aktualisierter Zahl).

**§3.4 „Null `advanceUntilIdle()`":** Faktisch falsch. Aktueller Stand:
**640 Vorkommen über 21 Files** in `app/src/test/` allein
(`MonolithicLauncherViewModelTest`: 153, `OnboardingViewModelTest`: 82,
`HiddenAppsViewModelTest`: 37, `LauncherViewModelTest`: 56). Wahrscheinlich
gemeint: „Null `advanceUntilIdle` gegen `WhileSubscribed`-Flows" (das wäre
die Konvention aus `app/src/test/CLAUDE.md` §1). Sollte präzisiert oder
gestrichen werden.

**§5.8 String-Parität:** Faktisch falsch. Behauptet „237/236 Keys, einziger
Unterschied `time_placeholder`". Aktuell (verifiziert via `grep -c '<string '`):
**246 EN / 232 DE = 14 Differenzen**. Alle 14 sind `translatable="false"`
(`battery_placeholder` + 13 `blend_mode_*`), Verhalten korrekt — aber
das Framing „einzige Differenz" ist veraltet.

**§4.1 „PII-Log: Paketnamen in Debug":** Bereits behoben. `PackageUpdateReceiver.kt:66-67` hasht den Paketnamen über `packageName.hashCode().toString(16)` und dokumentiert die Begründung im Kommentar darüber. Der Befund kann gestrichen werden.

---

### 8.12 Empfohlene Reihenfolge

| # | Schritt | Aufwand | Begründung |
|---|---|---|---|
| 1 | ~~§8.2 Test-Fix `MutableSharedFlow`~~ | ~15 min | erledigt 2026-05-08 — alle 6 Sites auf `extraBufferCapacity = 1` (matcht Production-Wiring `di/AppUpdateModule.kt:18`) |
| 2 | ~~§8.11 Korrekturen am Original-Audit (§3.4, §5.8, §4.1)~~ | ~5 min | erledigt 2026-05-08 — Inline-Strikethrough/Erledigt-Marker in den jeweiligen Original-Sektionen |
| 3 | ~~§8.6 + §8.10~~ + §8.9 Cleanup-Sammel-Branch | ~10 min | erledigt 2026-05-08 für §8.6 (`UsageExport.kt` weg) und §8.10 (auskommentierte Klassendeklaration weg). §8.9 zurückgezogen — siehe Sektion. |
| 4 | §8.1 Delegate `launchSafe`-Overload | ~30 min | Boilerplate-Konsolidierung ~20 Zeilen; nur die `error_generic`-Sites fold-bar, feature-spezifische Toasts bleiben inline |
| 5 | §8.3 Rule-11-Fix in `BaseViewModel.showErrorToastIfSupported` | ~30 min | Strukturelle Änderung (`errorEvent: E?` statt Cast-Catch); Touch auf alle Subklassen |
| 6 | §8.7 `purgeRepository`-Helper | ~2 h | 13 Files; ~70 Zeilen Reduktion; sorgfältig gegen die dokumentierten No-Op-Drifts checken |
| 7 | ~~§8.8 `:data` `buildConfig = false`~~ | ~10 min | erledigt 2026-05-08 — `BuildConfig.DEBUG` durch `TimberWrapper.isDebugBuild` ersetzt, `buildFeatures.buildConfig` aus `data/build.gradle.kts` entfernt |
| 8 | §8.4 Rule-13-Sweep | offen | Nur sinnvoll, wenn `checkConventions` git-diff-aware wird (TODO §7); sonst Sisyphos |

§8.5 zurückgezogen (siehe Sektion). Reihenfolge optimiert für Risiko-Profil
(klein/isoliert zuerst), nicht strikt nach Schweregrad. §8.4 bleibt
blockiert auf TODO §7.

---

### 8.13 Bewusst nicht ergänzt

- **Falscher Rule-13-Treffer in der initialen Pass-Liste:**
  `data/src/main/java/com/github/reygnn/kolibri_launcher/data/FavoritesRepositoryImpl.kt:300`
  („// Nicht crashen, einfach den aktuellen Zustand behalten") — git blame
  zeigt 2025-11-03 (Commit `aca08435`), also **vor** Einführung von Rule 13.
  Legacy, exempt. Hier dokumentiert, damit künftige Audits nicht denselben
  False-Positive ziehen.

---

## Zusammenfassung

Original-Audit (2026-05-03/04):

| Kategorie | Anzahl |
|---|---|
| 🔴 BLOCKER | **0** |
| 🟠 MAJOR | **5** (1.1, 1.2, 1.3, 3.1, 3.2) |
| 🟡 MINOR | **9** (2.1, 2.2, 4.1, 5.1, 5.2, 5.3, 5.4, 5.5, plus 3.3) |
| 🟢 NIT | **6** (2.3, 2.4, 4.2, 4.3, 5.6, 5.7) |

Nachtrag (2026-05-07):

| Kategorie | Anzahl |
|---|---|
| 🔴 BLOCKER | **0** |
| 🟠 MAJOR | **3** (8.2, 8.3, 8.4) |
| 🟡 MINOR | **4** (8.1 [von MAJOR herabgestuft], 8.6, 8.7, 8.8) |
| 🟢 NIT | **1** (8.10) |
| Zurückgezogen | **2** (8.5 — bereits in TODO §2-Sweep akzeptiert; 8.9 — `BaseViewModelTest:425-438` exerziert die Branches direkt) |
| Korrekturen am Original | **3** (3.4, 5.8, 4.1) |

**Reifegrad:** Hoch. Die Architektur-Disziplin ist außergewöhnlich, und die
größte Schwachstelle ist nicht der Code, sondern die **Doku-zu-Code-Drift**
in CLAUDE.md (`Manager` → `RepositoryImpl`-Rename nicht nachgezogen). Das
ist ein 5-Minuten-Fix mit hohem Wert für Onboarding und LLM-Sessions.

**Reifegrad-Update 2026-05-07:** Der Nachtrag-Pass hat keine BLOCKER und
keine architekturkritischen MAJORs gefunden — die vier MAJORs sind alle
Konsistenz-/Boilerplate-Probleme, kein Korrektheits-Risiko. Die größte
strukturelle Bedrohung bleibt die Drift zwischen Lint-Coverage
(`checkConventions`) und Konvention-Spread (Rule 13, §8.4) — wenn die
Lint-Lücke nicht geschlossen wird, sammelt sich das Problem selbst-
verstärkend an.
