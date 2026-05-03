# AUDIT.md

> Erstellt am 2026-05-03. Externe-Reviewer-Perspektive (Senior Android-Engineer
> mit Kenntnis der 13 CLAUDE.md-Regeln). Funde sind mit `file:line` belegt;
> Behauptungen wurden gegen das tatsächliche Repo verifiziert (mehrere initial
> gemeldete „BLOCKER" haben sich als Fehlannahmen herausgestellt und wurden
> entfernt).

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
- **Rule 10** — keine Activity/Fragment/Receiver mit nennenswerter Business-Logik außerhalb Helpern. (`HomeFragment` 2657 Zeilen sind View-Glue + Wallpaper-Edit, dokumentiert.)
- **Rule 11** — keine Catches um can't-throw-Operationen mehr; TODO §2 Sweep hat 229 entfernt, Reste sind legitim.
- **Rule 12** — keine `Timber.Forest.`-Aufrufe.

---

## 2. Code-Smells, Duplikation, Komplexitäts-Hotspots

### 2.1 🟡 MINOR — 34 rohe `repeatOnLifecycle`-Blöcke statt `collectOnStarted`-Extension

`ui/flow/FlowCollection.kt` definiert die Extension `Fragment.collectOnStarted`,
exakt um diese 8-Zeilen-Boilerplate zu kollabieren. Aktuell stehen
**34 Vorkommen** roher `repeatOnLifecycle(Lifecycle.State.STARTED)` über das
Repo verteilt.

```bash
$ grep -rn "repeatOnLifecycle" app/src/main/java | wc -l
34
```

**Fix:** Mechanischer Sweep — `viewLifecycleOwner.lifecycleScope.launch { repeatOnLifecycle(STARTED) { … } }`
→ `collectOnStarted { … }`. ~250–300 Zeilen Reduktion zu erwarten.

---

### 2.2 🟡 MINOR — Datei-Größen-Hotspots (>500 Zeilen)

| Datei | Zeilen | Bewertung |
|---|---:|---|
| `ui/home/HomeFragment.kt` | 2657 | Bewusst (CLAUDE.md erwähnt explizit) |
| `ui/home/ZoomableImageView.kt` | 1468 | Custom-View für Multi-Layer-Wallpaper-Edit; vertretbar |
| `data/BackupRepositoryImpl.kt` | 1444 | TODO.md §3 Split-Verwerfung dokumentiert |
| `ui/settings/SettingsFragment.kt` | 1074 | Klassisches PreferenceFragment-Smell — Aufteilbar |
| `ui/main/MainActivity.kt` | 797 | Launcher + HOME-Activity, Plattform-Glue |
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
- Null `advanceUntilIdle()`.
- Null Mockito-Imports — Migration vollständig.
- Null `@Ignore`, null `TODO()` in Test-Funktionen.
- `androidTest/` ist leer (historisch, dokumentiert).
- Robolectric-Zwei-Schichten-Strategie korrekt (`robolectric.properties`
  setzt `application=android.app.Application`).

---

## 4. Error-Handling, Security, Privacy

### 4.1 🟡 MINOR — PII-Log: Paketnamen in Debug

`KolibriLauncherApp.kt` Bereich `PackageUpdateReceiver` — Zeile ~59 loggt
`"package: $packageName"` bei `PACKAGE_ADDED/REMOVED`. Nur `.d()`-Level,
also nur bei aktivem Debug-Logging sichtbar. Aber Paketnamen aller User-Apps
sind potenziell PII (z. B. `com.gambling.bigwin`, `com.affair.app`).

**Fix:** Tag mit `BuildConfig.DEBUG`-Guard oder Hash statt Klartext.

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

- **String-Parität EN/DE** — 237/236 Keys, einziger Unterschied
  `time_placeholder` (`translatable="false"`). Vollständig lokalisiert.
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
| 1 | 5 min | hoch | CLAUDE.md Rule 1 + Rule 2 von „XyzManager" auf „XyzRepositoryImpl" aktualisieren (§ 1.1) |
| 2 | 2 min | mittel | `AppUsageRepositoryImpl.kt:171-173` Kommentar ins Englische (§ 1.2) |
| 3 | 1 min | klein | `app/src/main/res/drawable/ic_center_focus.xml` löschen (§ 5.3) |
| 4 | 30 min | mittel | Mechanischer Sweep `repeatOnLifecycle` → `collectOnStarted` (§ 2.1) |
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
| `HomeFragment` 2657 Zeilen | Pure Logik bereits extrahiert; UI-Glue bleibt | CLAUDE.md |
| `BackupRepositoryImpl` 1444 Zeilen | Split würde 9 Repo-Deps duplizieren, nicht reduzieren | TODO.md §3 |
| `SettingsFragment` 1074 Zeilen (§2.2) | PreferenceFragmentCompat-Pattern; weiteres Aufteilen wäre rein kosmetisch — keine echte Komplexität | akzeptiert 2026-05-03 |
| `AppDrawerFragment` 607 Zeilen (§2.2) | KeyboardShowCoordinator + Adapter sind bereits extrahiert; weiteres Aufteilen wäre rein kosmetisch | akzeptiert 2026-05-03 |
| `androidTest/` leer | Historische Flakiness, JVM-First per Rule 10 | CLAUDE.md |
| StrictMode-Violations (OneUI/Knox) | Plattform-Bug, nicht im App-Code behebbar | KNOWN_ISSUES.md |
| 7× `@Suppress("unused")` in `ZoomableImageView` | Public-API-Stubs für externe Konsumenten | inline kommentiert |
| `kotlin-kapt` statt KSP (§5.4) | Toolchain-Wechsel nur bei Major-Rewrite — Hilt-Pinning (2.57.2 DO NOT UPGRADE) plus kotlin-parcelize-Kompatibilität müssten geprüft werden, Nutzen ist rein Build-Performance | aufgeschoben 2026-05-03 |
| Inkonsistente VM-Test-Patterns (§3.3) | Audit-Befund hält Re-Inspektion nicht stand: SettingsViewModelTest mockt UseCases (kein Fake-Pendant existiert, UseCase-Mocking ist hier legitim), nicht Repositories. Die Repo-Mock-Fälle (HiddenAppsViewModelTest) wurden in §3.2 auf Fakes umgestellt. Alle VM-Tests teilen heute `MainDispatcherRule` + `TimberRule`. Audit-Vorschlag „BaseViewModelTest"-Basisklasse wäre ~6 Zeilen Boilerplate-Reduktion × 12 Tests — separater QoL-Refactor, kein Audit-Fix | abgehakt 2026-05-03 |

---

## Zusammenfassung

| Kategorie | Anzahl |
|---|---|
| 🔴 BLOCKER | **0** |
| 🟠 MAJOR | **5** (1.1, 1.2, 1.3, 3.1, 3.2) |
| 🟡 MINOR | **9** (2.1, 2.2, 4.1, 5.1, 5.2, 5.3, 5.4, 5.5, plus 3.3) |
| 🟢 NIT | **6** (2.3, 2.4, 4.2, 4.3, 5.6, 5.7) |

**Reifegrad:** Hoch. Die Architektur-Disziplin ist außergewöhnlich, und die
größte Schwachstelle ist nicht der Code, sondern die **Doku-zu-Code-Drift**
in CLAUDE.md (`Manager` → `RepositoryImpl`-Rename nicht nachgezogen). Das
ist ein 5-Minuten-Fix mit hohem Wert für Onboarding und LLM-Sessions.
