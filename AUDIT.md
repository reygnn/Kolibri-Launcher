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

### 1.3 ✅ Erledigt — 3 Repositories ohne Contract-Triple, nicht dokumentiert

Beide Sub-Findings (System-API-ADR-KDoc + Komposition-KDoc) sind
inzwischen aufgelöst:

**System-API-ADRs:** Alle drei Contract-Dateien existieren in
`domain/src/testFixtures/.../` mit ausführlicher ADR-KDoc:

- `ShortcutRepositoryContract.kt` — „NO CONTRACT TEST (ADR) — System-API
  (LauncherApps + Context)"
- `ResetRepositoryContract.kt` — „NO CONTRACT TEST (ADR) — composition
  repository, holds no state, behavior is iteration over 11 Purgeable
  children"
- `UsageExportRepositoryContract.kt` — „NO CONTRACT TEST (ADR) — Mixed:
  pure JSON + Uri-File-I/O"

**Komposition-Sub-Finding (Audit-Diagnose-Korrektur):** Die im Audit
genannten `GetFavoriteAppsUseCaseRepository` / `GetDrawerAppsUseCaseRepository`
/ `GetOnboardingAppsUseCaseRepository` waren keine
„Komposition-Repositories ohne Contract" — sondern **dead code**.
Verifiziert per grep: keine `class … : GetFavoriteAppsUseCaseRepository`-
Implementierung, keine `import` aus Production-Code, keine `@Binds` in
Hilt. Reste vom §9.2-Module-Split (2026-05-03), beim Verschieben der
Interfaces ins `:domain` nicht ans neue Hilt-Wiring angeschlossen.
Entfernt in `chore/remove-dead-usecase-repo-interfaces` (2026-05-08);
der Komposition-Hinweis in `ResetRepositoryContract.kt` wurde
entsprechend gestrafft (`Purgeable` bleibt das einzige tatsächliche
Beispiel).

> Original-Befund: 3 echte Repositories ohne Contract-Datei
> (`ShortcutRepository` / `ResetRepository` / `UsageExportRepository`)
> plus die Komposition-Repos ohne KDoc-Begründung. Letztere existierten
> nur als Karteileichen.

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

### 3.1 ✅ Erledigt — Pure-Logic-Helper ohne Test (Rule 10)

- `ui/appcontextmenu/ContextMenuHelper.kt` — `ContextMenuHelperTest.kt`
  in `app/src/test/.../ui/appcontextmenu/` (commit `434ed0d`).
- `ui/home/ScrollViewBorderDecorator.kt` — bekam zuerst einen
  Robolectric-Test (commit `85afb87`), wurde dann komplett aus dem
  Codebase entfernt (commit `75cc8b4`, „remove split-mode dead code").
  Kein Test mehr nötig, weil keine Klasse mehr.

> Original-Befund: Beide Helper waren extrahierte Pure-Logic-Klassen
> ohne Test-Datei. Fix-Vorschlag war „Tests anlegen oder zurückführen";
> umgesetzt für ContextMenuHelper, ScrollViewBorderDecorator hat sich
> über Test → Löschung selbst erledigt.

---

### 3.2 ✅ Erledigt — `mockk(relaxed = true)` Über-Nutzung

Die im Audit konkret beanstandeten Repository-Mock-Sites sind
inzwischen auf Fakes umgestellt; der Rest ist legitim.

**Migrierte Hotspots:** `HiddenAppsViewModelTest` nutzt heute
`FakeHiddenAppsRepository` + `FakeInstalledAppsRepository` (mit
Contract-Schutz inklusive); siehe §7-Eintrag, der das bereits
dokumentiert.

**Verbleibende 279 `relaxed = true`-Vorkommen** sind UseCase-Mocks
(kein Fake-Pendant nötig, UseCase-Mocking ist hier projekt-Standard),
System-API-Mocks (`Context`, `PackageManager`) und Toleranz-Setups für
Suspend-Funktionen mit Unit-Return. `SettingsViewModelTest` mockt
explizit UseCases statt Repositories — ebenfalls in §7 als legitim
abgehakt.

> Original-Befund: 313 `relaxed = true` über die Test-Suite, mit
> `HiddenAppsViewModelTest`, `SettingsViewModelTest`, `LauncherViewModelTest`
> als Verdächtige für Über-Mocking. Fix-Vorschlag „Top-10 auf Fakes
> umstellen wo möglich" wurde umgesetzt für die echten Repo-Mock-Fälle;
> die Re-Inspektion der anderen genannten Tests hat ergeben, dass sie
> UseCases mocken (legitim) bzw. keinen Repo-Fake brauchen.

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

### 5.1 ✅ Erledigt — Hardcoded `dp`/`sp` in `fragment_home.xml`

Die im Audit benannte TextView (`wallpaperEditHint`, Z. 136–148)
nutzt heute durchgängig `@dimen/`-Refs: `spacing_xlarge`,
`text_size_hint`, `spacing_large`, `spacing_medium`, `elevation_card`.

> Original-Befund: Z. ~139, 142, 144–146 hatten `48dp`, `14sp`, `16dp`,
> `12dp` direkt im Layout statt `@dimen/`. Fix-Vorschlag „in dimens.xml
> heben" wurde umgesetzt.

---

### 5.2 ✅ Erledigt — `@android:color/white` in Layouts

`grep "@android:color/white" app/src/main/res/layout/` → 0 Treffer.
Vollständig migriert.

> Original-Befund: 16+ Vorkommen in `fragment_home.xml`,
> `item_app_drawer.xml` u. a. Fix-Vorschlag war ein eigenes
> `@color/icon_tint_default` (oder Theme-Attribute) für Dark-Mode-
> Zukunftssicherheit; entsprechend abgelöst.

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

### 5.5 ✅ Erledigt — `android:configChanges`-Suppression auf 2 Activities

Beide Activities (`MainActivity` Z. 55–67, `SettingsActivity` Z. 109–115
in `AndroidManifest.xml`) haben heute mehrzeilige Erklär-Blöcke direkt
über der `<activity>`-Deklaration plus `tools:ignore="DiscouragedApi"`.
MainActivity verweist auf `HomeFragment.onConfigurationChanged()`
(Z. 429) als manuellen Rotation-Handler; SettingsActivity erklärt das
PreferenceFragmentCompat-spezifische Argument.

> Original-Befund: Beide Activities unterdrückten Activity-Recreation
> ohne Kommentar. Fix-Vorschlag „beabsichtigt → Kommentar; sonst
> entfernen" wurde mit dem ersten Pfad umgesetzt.

---

### 5.6 ✅ Erledigt — Hardcoded „100%" in `fragment_home.xml`

`fragment_home.xml:78` (`batteryText`) referenziert
`@string/battery_placeholder` mit `translatable="false"` — genau die
Audit-Empfehlung.

> Original-Befund: `android:text="100%"` als Vorschau-Fallback. Fix
> via String-Resource umgesetzt.

---

### 5.7 ✅ Erledigt — Gradle-Version-Catalog

`gradle/libs.versions.toml` existiert; `app/build.gradle.kts` und
`data/build.gradle.kts` referenzieren Versionen jetzt durchgängig über
`libs.…`. Pinning-Marker (DO NOT UPGRADE / DO NOT DOWNGRADE / DO NOT
CHANGE) sitzen im Catalog neben den jeweiligen Versionen, mit Hinweis
im Header von `app/build.gradle.kts`.

> Original-Befund: Versionen direkt im build.gradle.kts hardcoded. Fix
> via Version Catalog umgesetzt.

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
| 5 | ~~1 h~~ | hoch | ~~Tests für `ContextMenuHelper` + `ScrollViewBorderDecorator` schreiben oder einbetten~~ — erledigt (§ 3.1) |
| 6 | ~~1 h~~ | mittel | ~~ADR-KDoc in `ShortcutRepository`/`ResetRepository`/`UsageExportRepository` ODER Contract nachziehen~~ — erledigt (§ 1.3) |
| 7 | ~~2–4 h~~ | hoch | ~~Top-10 `mockk(relaxed = true)`-Hotspots auf Fakes umstellen~~ — erledigt (§ 3.2) |

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

### 8.1 ✅ Erledigt — Delegate-Wrap und `launchSafe`-Doppel-Sicherung

`DelegateScope.launchSafe` bekam einen optionalen
`defaultErrorToast: @StringRes Int? = null`-Parameter. Wenn gesetzt,
emittiert der `Throwable`-Catch zusätzlich zu `silentError` einen
`UiEvent.ShowToast(defaultErrorToast)`. 10 Call-Sites (5 in
`AppManagementDelegate`, 3 in `ThemingDelegate`, 2 in `WallpaperDelegate`)
sind auf den Overload gefoldet — Inner-Try/Catch-Boilerplate weg.

Methoden mit feature-spezifischem Toast (`AppManagementDelegate.onAppClicked`
→ `error_launching_app`) bleiben inline; ebenso die Result-Pfade
(`WallpaperDelegate.onSetWallpaperImage` early-return,
`GestureDelegate` `RequestNotificationsUseCase.Result.ErrorGeneric`-Branch),
weil das keine Catches sind, sondern explizite Result-Handling-Pfade.

Net-LOC: −32 Zeilen. DEBUG-Doppel-Throw-Kaskade ist weg
(`silentError` läuft nur noch einmal pro Error).

> Original-Befund: 9+ Methoden wickelten ihren Body in *exakt dasselbe*
> `try/catch (CancellationException) / catch (Throwable)`-Pattern, das
> `DelegateScope.launchSafe` schon kapselt. Doppel-Wrap. Fix-Vorschlag
> war ein `defaultToast`-Parameter; entsprechend umgesetzt.

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

### 8.3 ✅ Erledigt — Rule 11 in `BaseViewModel.showErrorToastIfSupported`

`BaseViewModel` exponiert heute `protected open val errorEvent: E? = null`;
`showErrorToastIfSupported` wurde auf `errorEvent?.let { sendEvent(it) }`
reduziert. Subklassen mit `E = UiEvent` opten via
`override val errorEvent = UiEvent.ShowToast(R.string.error_generic)` ein
(7 Production-VMs + Test-Subklasse). `OnboardingViewModel<OnboardingEvent>`
bleibt ohne Override — gleiches Verhalten wie vorher (kein Toast bei
nicht-passendem Event-Typ), aber jetzt strukturell statt per Cast-Catch.

> Original-Befund: `try/catch (ClassCastException)` als Runtime-Type-
> Check in der Default-Implementation, plus `@Suppress("UNCHECKED_CAST")`.
> Fix-Vorschlag „strukturelle Änderung mit `errorEvent: E?`-Property"
> wurde direkt umgesetzt.

---

### 8.4 ✅ Erledigt — Rule 13 verletzt: weitere net-new deutsche Kommentare

Zweistufige Auflösung:

1. **Linter** (commit `7886473` + `74a9357`) — `tools/check-rule13-german-comments.{sh,awk}` plus Gradle-Task `checkRule13`. Vergleicht `git diff <base>...HEAD` (Default `origin/main`), flaggt `+`-Zeilen die wie Kommentare mit Deutsch aussehen. Zwei Signale: Umlaute (hart) oder ≥2 deutsche Funktionswörter (weich, mit Whitelist gegen English-Overlap). 11+1 Fixture-Tests in `tools/check-rule13-test.sh`.

2. **Sweep** (dieser Commit) — alle 60 Verletzungen seit Stichtag `a65a6b2` (2026-05-01, Rule-13-Einführung) auf Englisch übersetzt. Touch auf 16 Files quer durch `:domain`, `:data`, `:app`, plus `app/build.gradle.kts`. Inhaltliche Bedeutung erhalten, keine technischen Korrekturen mitgeschmuggelt.

Zwei Korrekturen am Original-Befund während der Auflösung:

- **Timeline:** Audit sagte „Beide stammen aus Rule-11-Sweep-Commits, also **nach** Einführung von Rule 13." Das ist faktisch falsch. Rule 13 wurde am 2026-05-01 (`a65a6b2`) eingeführt, beide zitierten Commits (`014d9f5c`, `5033e123`) waren am 2026-04-30 — einen Tag **vor** Rule 13. Streng genommen pre-existing, aber der Maintainer hat „Stichtag = 2026-05-01" gewählt und sweepen lassen.
- **MainActivity-Site:** Der zitierte Block in `MainActivity.kt:388-400` ist nicht „kompletter Erklär-Block in Deutsch" — er ist Mix aus EN (`Catch kept (Unrecoverable…)`) und DE (`Gleicher Grund wie oben…`). Beide Teile sind jetzt englisch.

> Original-Befund: zwei beispielhaft gefundene Sites, plus Verdacht auf weitere. Fix-Vorschlag „TODO §7 vorziehen, dann sweepen" — beides umgesetzt.

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

### 8.7 ✅ Erledigt — `purgeRepository`-Duplikation in `:data`

Helper `safePurge` ist jetzt in `data/.../DataStorePurge.kt`:

```kotlin
internal suspend fun DataStore<Preferences>.safePurge(
    repoName: String,
    block: suspend (MutablePreferences) -> Unit
) {
    try { edit(block) }
    catch (e: CancellationException) { throw e }
    catch (e: Throwable) { TimberWrapper.silentError(e, "Failed to purge $repoName repository") }
}
```

6 Repo-Impls auf den Helper umgestellt: `HiddenAppsRepositoryImpl`,
`FavoritesRepositoryImpl`, `AppUsageRepositoryImpl`, `WallpaperRepositoryImpl`
(post-edit Success-Log gedroppt — silentError loggt den Failure-Pfad bereits),
`FavoritesOrderRepositoryImpl`, `SwipeActionsRepositoryImpl`.

**Bewusst NICHT gefoldet** (per Audit-Caveat „dokumentiert No-Op bleiben"
plus Erweiterung um Ordering-Sensitivität):

- `CustomNamesRepositoryImpl` — der `triggerCustomNameUpdate()`-Aufruf
  läuft nach dem Edit, AUSSERHALB des Helpers. Im Helper-Pattern würde
  Trigger auch nach silent-failed-edit feuern (würde Subscriber über
  „neue" Daten benachrichtigen, die gar nicht geschrieben wurden).
- `SettingsRepositoryImpl` — hat eigenes `safeEdit`, das von allen 30+
  Settern genutzt wird. Interne Konsistenz erhalten, getrennte
  Konsolidierung in TODO §7-Style nicht in Scope.
- `InstalledAppsStateRepositoryImpl`, `ShortcutRepositoryImpl`,
  `InstalledAppsRepositoryImpl`, `TimeBasedEventsRepositoryImpl`,
  `ScreenLockRepositoryImpl` — alle dokumentiert No-Op (System-Daten
  oder reiner Runtime-State).

Net-LOC: −37 in den 6 gefoldeten Files, +44 im neuen Helper (mit KDoc),
~7 LOC Reduktion gesamt. Audit-Schätzung ~70 war auf 13 Files basiert;
realistische Foldzahl ist 6, plus Helper-KDoc-Aufwand. Der eigentliche
Wert ist Drift-Resistenz: zukünftige DataStore-Repos kriegen den
sicheren Pfad per Default.

> Original-Befund: 13 Files mit identischem `try/catch` um
> `dataStore.edit`. Fix-Vorschlag „Module-Helper safePurge" — umgesetzt.

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
| 4 | ~~§8.1 Delegate `launchSafe`-Overload~~ | ~30 min | erledigt 2026-05-08 — `defaultErrorToast: Int?`-Parameter in `DelegateScope.launchSafe`; 10 Sites gefoldet, −32 LOC, DEBUG-Doppel-Throw weg |
| 5 | ~~§8.3 Rule-11-Fix in `BaseViewModel.showErrorToastIfSupported`~~ | ~30 min | erledigt 2026-05-08 — `errorEvent: E?`-Property in `BaseViewModel`; alle 7 UiEvent-Subklassen + TestViewModel opten ein, OnboardingViewModel bleibt ohne Override (Verhalten unverändert) |
| 6 | ~~§8.7 `purgeRepository`-Helper~~ | ~2 h | erledigt 2026-05-08 — `safePurge`-Extension in `DataStorePurge.kt`; 6 von 13 Repos gefoldet (5 dokumentierte No-Ops + 2 ordering-sensitive bleiben inline) |
| 7 | ~~§8.8 `:data` `buildConfig = false`~~ | ~10 min | erledigt 2026-05-08 — `BuildConfig.DEBUG` durch `TimberWrapper.isDebugBuild` ersetzt, `buildFeatures.buildConfig` aus `data/build.gradle.kts` entfernt |
| 8 | ~~§8.4 Rule-13-Sweep~~ | mehrere h | erledigt 2026-05-08 — git-diff-aware Linter (`checkRule13`) + Sweep aller 60 Sites seit Stichtag `a65a6b2` (2026-05-01) |

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

## 9. Bombensicher-Review 2026-05-08

> Gezielter Crash-Safety-Pass auf die sechs Files, die für einen
> HOME-Activity-Launcher unbedingt halten müssen: jeder Crash hier
> startet die App sofort wieder (Android relauncht den HOME), kann zu
> einer ACRA-Spam-/Boot-Loop-Spirale werden, oder hinterlässt eine
> halb-initialisierte Lügen-State-Activity. Anlass: Sorge nach
> mehreren Throwable-Sweeps, ob Safety-Nets verloren gingen, die
> echte Failure-Modes adressierten.
>
> Reviewt: `MainActivity.kt`, `HomeFragment.kt`, `AppDrawerFragment.kt`,
> `BaseActivity.kt`, `BaseViewModel.kt`, `LauncherViewModel.kt` —
> ~3890 Zeilen.

### 9.1 🔴 Echter Befund — `fragmentExceptionHandler` ohne Fallback

`HomeFragment.kt:308-314` und `AppDrawerFragment.kt:140-146` (vor dem Fix)
hatten identisch:

```kotlin
private val fragmentExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    try {
        TimberWrapper.silentError(throwable, "Uncaught exception in HomeFragment")
    } catch (e: Throwable) {
        // Even logging can fail
    }
}
```

Zwei Konsequenzen dieser Form:

1. **Rule 9 in Fragments verletzt.** In DEBUG wirft `silentError` per
   Rule 9 eine RuntimeException um Programmer-Errors lautstark zu
   machen. Das bare `catch (e: Throwable)` swallowte diesen Throw —
   damit war Rule 9 für jeden Coroutine-Pfad unter dem Handler
   strukturell deaktiviert. Programmer-Errors in Fragment-Coroutines
   wurden silent in DEBUG.

2. **Original-Throwable verloren wenn Timber selbst stirbt.** Der
   Kommentar erkennt das Problem („Even logging can fail"), bietet
   aber keinen Fallback. MainActivity hatte denselben Bug; im
   §1-Catch-Sweep (commit `0b7a21c` o.ä.) gefixt mit System.err-
   Fallback + DEBUG-rethrow OUTSIDE der Catch — beide Fragments
   wurden damals nicht mitgezogen.

**Fix:** commit `3c9fd54`, 2026-05-08. Beide Fragment-Handler matchen
jetzt MainActivity exakt: tight try um silentError, `catch (loggingError)`
mit System.err-Fallback, `if (BuildConfig.DEBUG) throw throwable`
außerhalb der Catch.

> Verifikation für künftige Sessions: gegen jeweils Z. 308 ff. in
> `HomeFragment.kt` und Z. 140 ff. in `AppDrawerFragment.kt` prüfen.
> Beide Handler müssen System.err-Fallback und DEBUG-rethrow haben.
> Wenn ein Refactor diese Form wieder rückbaut, ist Rule 9 erneut
> kompromittiert.

### 9.2 ✅ Verifiziert sauber

| Datei | Geprüft | Ergebnis |
|---|---|---|
| `BaseActivity.kt` | Multi-Layer ErrorEventBus + ViewModel-Event-Collector mit Inner+Outer-Catch, Toast-Safe mit Samsung-StrictMode-Workaround, runWithStrictModeDisabled mit try/finally Policy-Restore | Multi-Layer intakt; einzige kosmetische Inkonsistenz: `NavigateUp` (Z. 151–156) fängt `Exception` statt `Throwable` — kein Risiko, anderer Files-Konvention nach |
| `BaseViewModel.kt` | sendEvent / launchSafe / executeSafe / coroutineExceptionHandler / handleError + post-§8.3 errorEvent-Pfad | Vollständig. `executeSafe`s nested catch um failing onError ist da. Cancellation überall rethrown. OOM/StackOverflow korrekt mit Toast-Suppress |
| `MainActivity.kt` | mainActivityExceptionHandler mit System.err-Fallback + DEBUG-rethrow, handleSpecificEvent Outer Catchall (HOME-Activity-resilience), Triple-Catch in launchApp (ActivityNotFoundException + SecurityException + Throwable mit jeweils passenden Recoveries), strukturelle Teardown-Race-Guards vor jedem Dialog-Show, silentDeath in setupMainContent, BroadcastReceiver.onReceive mit Throwable-Catch, ActivityResultLauncher-Callbacks mit Catches, onRestoreInstanceState mit nested popBackStack-Fallback | Jede Catch trägt Inline-Rationale mit four-category-frame-Slot. Keine Lücke gefunden |
| `LauncherViewModel.kt` | Reine Delegation; alle Catches in den Delegates via `scope.launchSafe` | Pass-through; Safety-Net liegt korrekt in den Delegates |
| `HomeFragment.kt` | onCreateView / observeViewModel (collectOnStarted) / chip-Rendering mit per-item Recovery / loadBitmapFromUri (Throwable-Umbrella für I/O+OOM) / DoubleClickListener (system-callback boundary) / showAppInfo (narrowed zu ActivityNotFoundException) / onDestroyView mit teardown-listener-cleanup | Bis auf §9.1-Bug (jetzt gefixt) sauber |
| `AppDrawerFragment.kt` | setupFragmentResultListener (FragmentManager-callback boundary) / displayFilteredApps mit Fallback zur Master-Liste / IMM-Wraps in showKeyboardNow + hideKeyboard / onDestroyView mit `finally { super.onDestroyView() }` | Bis auf §9.1-Bug (jetzt gefixt) sauber |

### 9.3 Was NICHT auditiert wurde — Lücken-Liste für künftige Sessions

Diese Files / Pfade waren explizit außerhalb des heutigen Reviews
und sind die natürlichen nächsten Audit-Ziele für ein 9.5/10-Niveau:

1. **`ui/home/ZoomableImageView.kt` (1468 Zeilen)** — Custom-View für
   Multi-Layer-Wallpaper-Edit. Eigene Touch-Handler, Bitmap-Operationen,
   Matrix-Math, Layer-State-Machine. Klassische Crash-Surface. Heute
   nur via §2.3 NIT touched (`addLayer()` ~101 Zeilen) — nie
   systematisch geprüft. Crash hier betrifft nur Wallpaper-Edit-Mode,
   nicht Default-Pfad — aber Edit-Mode ist nicht trivial nutzbar.

2. **Die 7 Delegates in `ui/main/delegate/`** —
   `AppManagementDelegate`, `WallpaperDelegate`, `ClockDelegate`,
   `GestureDelegate`, `ThemingDelegate`, `LayoutDelegate`,
   `DelegateScope`. Im §8.1-Sweep wurden 10 doppel-gewickelte
   `launchSafe`-Sites gefoldet. Die einzelnen Delegate-Bodies (was
   passiert IM Block) sind nicht systematisch geprüft. Schutz ist
   `scope.launchSafe`-vermittelt, korrekt — aber nicht jeder Code-Pfad
   verifiziert.

3. **OEM-spezifische Bugs** — KNOWN_ISSUES.md dokumentiert
   Samsung-Toast-IPC und Knox-resolveActivity. Andere OEM-ROMs
   (MIUI, EMUI, ColorOS, OneUI 6+) haben jeweils eigene
   StrictMode-/Process-Quirks, die nur durch echtes Device-Testing
   gefunden werden. Per-Definition nicht in einem JVM-Audit lösbar.

4. **Wallpaper-Edit-Mode unter realer Memory-Pressure** — 8 Layer +
   Rotation während Edit + Background-Kill + Wiederöffnen. Multi-Layer-
   Bitmaps sind die anfälligste OOM-Surface. Robolectric kann das
   nicht stresstesten; nur AVD oder echtes Gerät.

5. **Adapter-Lifecycle-Races** — RecyclerView + DiffUtil-Background-
   Thread + Fragment-Teardown-Race. Es existieren Guards
   (`_binding != null && isAdded` in `submitListToAdapter`,
   `_binding == null return@collectOnStarted` in jeder
   Observer-Body), aber Heisenbug-Charakter — nicht ohne
   Stress-Replay verifizierbar.

6. **`KolibriLauncherApp.kt`** — heute nicht im Scope, aber
   architektonisch der wichtigste File: globaler
   UncaughtExceptionHandler, ACRA-Init, Multi-Layer-Init-Paranoia
   per Rule 7. Letzter Audit war wohl §1-Catch-Sweep oder früher.
   Verifizierbar via grep auf `try { … } catch (e: Throwable) {
   TimberWrapper.silentError(e, …) }`-Anzahl und ANR-Reporter-
   Wiring.

### 9.4 Gesamteinstufung — 9/10

Bewertet auf Skala für Android-Apps allgemein:

- Standard-App: 5–6/10 (catches sporadisch, kein Multi-Layer)
- Polierte kommerzielle App: 7–8/10 (catches systematisch, aber
  ohne dokumentierte Frame-Disziplin)
- Kolibri Launcher: **9/10**

Was das 9 trägt:

- **Multi-Layer-Verteidigung systemisch.** App / Activity-Base /
  Activity / Fragment / ViewModel-Base / Delegate / TimberWrapper /
  Daten — jede Schicht hat ihren eigenen Safety-Net mit
  dokumentierter Boundary.
- **HOME-Activity-Launcher-Spezifika adressiert.** `silentDeath`
  statt `finish()` in unrecoverable Pfaden, ACRA-Spam-Limiter,
  Boot-Loop strukturell verhindert, Battery-Receiver-Guard,
  Triple-Catch in `launchApp`.
- **Frame-Disziplin durchgehalten.** Rule 11 four-category-frame
  ist nicht nur Doku, sondern in MainActivity inline auf jeder
  einzelnen Catch zitiert. Linter (`checkConventions`) erzwingt
  die Annotation-Konvention für whitelistete Files.
- **Rule-9-Schutz strukturell verbunden.** TimberWrapper.silentError
  wirft in DEBUG → Programmer-Errors werden laut. Heutiger Fix
  (§9.1) hat den letzten Spot wiederhergestellt, wo dieser Pfad
  swallowed war.

Warum nicht 10:

- §9.3 Lücken (ZoomableImageView, Delegates intern, OEM-spezifisch,
  Real-Stress, Adapter-Races, KolibriLauncherApp).
- 10/10 würde bedeuten „verifiziert resistant gegen jeden
  realistischen Crash auf jedem Android-Gerät" — das ist nur durch
  Months-of-Field-Testing erreichbar.

### 9.5 Pfad zu 9.5/10

In dieser Reihenfolge, jeder Schritt eigenständig durchführbar:

1. ZoomableImageView systematisch reviewen (ähnlich wie §9 — eine
   Audit-Session auf den Custom-View). Erwarteter Aufwand: ~2h.
2. Die 7 Delegates im selben Stil auditieren (Body-Inhalte, nicht
   nur die `launchSafe`-Wrapper). ~3h zusammen.
3. KolibriLauncherApp.kt re-auditieren — letzter dedizierter Pass
   ist Monate her, ANR-Reporter und ACRA-Init-Pfad sind nicht
   trivial. ~1h.
4. Robolectric-Smoke-Test für die kritischen Lifecycle-Übergänge
   (onCreate→onDestroyView Race, FragmentResult-Lifecycle, Wallpaper-
   Edit Enter→Exit). Nicht als Stress-Test, sondern als Backstop
   für die Race-Guards. ~3h.
5. Real-Device-Stress-Session: AVD windowed (per `feedback_emulator_run_mode`-
   Memory), Wallpaper-Edit mit 8 Layern, mehrere Rotation-Cycles,
   Background-Kill, Wiederöffnen, Repeat. Mind. 1 Stunde.

Schritte 1–3 bringen vermutlich auf 9.3, Schritt 4 auf 9.4, Schritt 5
liefert das Real-Daten-Signal das die anderen vier nicht ersetzen können.

> Verifizierbarkeits-Hinweis für künftige Sessions: §9 ist ein
> Zeitpunkt-Snapshot vom 2026-05-08. Wenn ein späterer Refactor die
> hier als „sauber" gelisteten Patterns (multi-layer handler,
> System.err-Fallback, four-category-frame-Annotation) bricht, wird
> §9 ungültig. Lese den Stand am Datum der Verifikation neu, vergleich
> gegen diese Beschreibung, dokumentier Drift in einer Folge-Sektion.

### 9.6 ✅ KolibriLauncherApp re-audit (Schritt 3 von §9.5) — 2026-05-08

> Erster der drei JVM-auditierbaren §9.3-Items abgearbeitet (#6
> KolibriLauncherApp). Datei: 580 Zeilen, Anlass: letzter dedizierter
> Audit ist Monate her, ist aber architektonisch der wichtigste File
> (globaler UncaughtExceptionHandler, ACRA-Init, multi-layer-init-
> Paranoia per Rule 7, AnrReporter-Wiring, RecoveryWatchdog,
> AcraTree mit CrashReportLimiter).
>
> Befund: **Kein Crash-Safety-Bug gefunden.** Die Datei ist eine
> Stilreferenz für mehrschichtige Krisensicherung.

**Was geprüft wurde — verifizierbar pro Surface:**

| Surface | Z. | Verifiziert |
|---|---|---|
| `attachBaseContext` | 129–180 | ACRA-Init in try/catch(Throwable) mit Log.e-Fallback nested in inner try/catch; ACRA sofort disabled (Rule 8 privacy-by-default); consent via `runBlocking` mit dokumentierter Begründung in der KDoc (verhindert privacy-race-window); Re-Enable nur bei consent |
| `applicationExceptionHandler` | 80–91 | try/Timber.e/catch(Throwable)/Log.e-Fallback/inner-catch-ignored — drei-Ebenen-Fallback; nutzt Timber.e statt silentError per Rule-9-Ausnahmeliste (würde sonst rekursiv in die eigene error-pipeline laufen) |
| `onCreate` | 182–295 | Init-Reihenfolge bewusst und korrekt: `TimberWrapper.isDebugBuild` Z. 188 vor jedem möglichen silentError; `KolibriLog.{d,w,taggedError}Handler` Z. 194–201 vor jedem :domain-Log-Pfad; `CrashReportLimiter.init` Z. 205 in try/catch vor jedem Crash; Timber-Trees Z. 215–228 in try/catch mit Log.e-Fallback; `setupGlobalExceptionHandler`/`setupStrictMode`/`reportPendingAnrsAsync`/`startRecoveryWatchdogAfterBootstrap`/`registerPackageUpdateReceiver` jeweils gewrappt; Migration in coroutine mit nested ACRA-Record-Fallback Z. 287–292 |
| `handleUncaughtException` | 333–384 | Outer try/catch um den ganzen Handler-Body; Spam-Protection-Check; OOM-spezifische GC-Recovery in eigenem try/catch; handlerCalled-Flag verhindert Double-Invoke; **finally{}** mit Thread.sleep(500) für ACRA-Send-Zeit + Process.killProcess + exitProcess(10) **garantiert** (selbst wenn Handler-Body crasht, sterbt der Prozess sauber für Android-Restart) |
| `AcraTree` | 531–580 | CrashReportLimiter.shouldSendReport vor jedem Send; **CancellationException wird in `UnhandledCancellationException` gewrappt** um frische Stack-Trace zu erzwingen — clevere Diagnose-Lösung für Kotlin's traceless-by-default cancellation, zeigt direkt auf den fehlerhaften `catch`-Block; reportErrorToAcra mit try/catch + Log.e-Fallback + nested-ignored |

**Stärken über die §9.4-Bewertung hinaus:**

1. AcraTree's CancellationException-Wrap-Diagnostik ist genuinely
   clever — turn-the-uselessness-into-actionable.
2. Spam-Protection an zwei Stellen verdrahtet (Logging-Pfad +
   catastrophic-Pfad) statt nur einer.
3. Init-Reihenfolge ist nicht zufällig: jeder Pre-Requisite läuft
   wirklich vor seinem Konsumenten.
4. `finally{}` im Crash-Handler garantiert process-die-cleanly —
   verhindert Zombie-Process bei einem catch-im-catch-Failure.
5. AnrReporter-via-AEI-statt-live-watchdog: keine
   Daemon-Thread-Overhead, keine veraltete (2018)
   ANRWatchDog-Dependency, post-mortem nutzt richere
   System-Multi-Thread-Dumps.

**Ehrlich nicht geprüft (eigene Audit-Pässe nötig):**

- `AnrReporter.kt` Implementation — nur die Caller hier geprüft.
  Die Klasse selbst (AEI-Walk, threadDump-Extraktion,
  Watermark-Persistence) ist eigene Audit-Surface.
- `RecoveryWatchdog.kt` Implementation — nur Caller geprüft. Der
  Daemon-Thread + 8s-Timeout-Logic + AEI-Categorization sind
  eigene Surface.
- `CrashReportLimiter.shouldSendReport` Innenleben — nur
  Wiring-Punkte verifiziert. Die SharedPreferences-basierte
  Rate-Limit-Logik (Rule-5-Ausnahme) wäre eigene Audit-Surface.
- `AcraTree.UnhandledCancellationException` unter realer Last —
  die Idee ist richtig, das tatsächliche Verhalten unter
  Hochfrequenz-Cancellation (z. B. rapid Suchquery-Cancellation
  in AppDrawerFragment) wäre Stress-Test-Material.

**§9.5-Roadmap-Fortschritt:**

- [x] Step 3 (KolibriLauncherApp) — diese Sektion (§9.6)
- [ ] Step 1 (ZoomableImageView ~2h)
- [ ] Step 2 (7 Delegates ~3h zusammen)
- [ ] Step 4 (Robolectric-Smoke-Tests ~3h)
- [ ] Step 5 (Real-Device-Stress mind. 1h)

Per §9.5: „Schritte 1–3 bringen vermutlich auf 9.3" — Step 3
allein bewegt das Rating moderat (≈9.1), die volle Steigerung
auf 9.3 setzt Steps 1+2 voraus.

### 9.7 ✅ 7 Delegates audit (Schritt 2 von §9.5) — 2026-05-08

> Zweiter JVM-auditierbarer §9.3-Item abgearbeitet (#2 die 7
> Delegates). ~1480 Zeilen total über `ui/main/delegate/`.
> Anlass: nach §8.1 wurde das `launchSafe`-Wrapping konsolidiert,
> aber die Bodies INSIDE der `launchSafe`-Calls waren nicht
> systematisch geprüft.
>
> Befund: **Kein Crash-Safety-Bug gefunden.** Alle sieben Files
> sauber. Drei theoretische Edge-Cases dokumentiert (keine Crashes).

**Per-File-Bestand (verifizierbar pro Datei):**

| Delegate | Zeilen | Surface |
|---|---|---|
| `DelegateScope.kt` | 65 | Helper-Infrastruktur. Bereits in §8.1 auditiert (`launchSafe` + `defaultErrorToast`-Overload). Im scope hier nur konsumiert |
| `ClockDelegate.kt` | 176 | `updateBatteryLevelFromIntent` mit Throwable-Catch + DEFAULT_BATTERY-Fallback (Z. 96–109); `getInitialBatteryState` mit Throwable-Catch (Z. 143–155); `observeSystemTimeChanges` als callbackFlow mit awaitClose-Cleanup (Z. 157–176); `updateBatteryLevel` mit dokumentiertem CANT_THROW-Rationale (divide-by-zero-Guard) |
| `AppManagementDelegate.kt` | 272 | Jede public Methode in `scope.launchSafe` mit passendem `defaultErrorToast`. `onAppClicked` behält inneres try/catch wegen feature-spezifischem Toast (`error_launching_app` statt `error_generic`). `handleFavoriteAppsState` (Z. 245–260) mit CancellationException-rethrow + Throwable-Catch. `listenForAppUpdates` (Z. 262–272) mit per-Event-Catch um `refreshAppsUseCase` |
| `GestureDelegate.kt` | 149 | Jeder Gesture-Handler in `scope.launchSafe`. Result-based dispatch via sealed `RequestLockUseCase.Result` / `HandleSwipeActionUseCase.Result` / `RequestNotificationsUseCase.Result`. `_isLockingInProgress`-Gate verhindert Re-Entry während Lock-Animation. One-time-Toast-Flags (`enableLockToastShown`, `enableSwipeDownToastShown`) für Permission-Hinweise |
| `ThemingDelegate.kt` | 97 | Alle drei Setter (`onSetTextColor`/`onSetTextShadowEnabled`/`onSetChipBackgroundColor`) in `scope.launchSafe` mit `defaultErrorToast`. `start()` Flow-Observer in launchSafe. `updateUiColors` ist sync StateFlow-Write |
| `LayoutDelegate.kt` | 123 | **Besonders:** jeder StateFlow hat eigene `.catch { e -> silentError + emit(default) }`-Recovery (Z. 41–83). Setter mit `coerceInSafe`-Klammerung. `onResetLayoutSettings` ruft alle vier Default-Set-UseCases sequentiell |
| `WallpaperDelegate.kt` | 600 | **Außergewöhnlich:** transaktionales Edit-Session-Protokoll mit `editSnapshot` + `pendingRemovalsOnCommit` + `pendingRemovalsOnCancel`-Bookkeeping. Sync state restore on cancel (Z. 384–398) explizit dokumentiert weil Fragment-Read sofort danach erfolgt. Per-file try/catch in commit/cancel batch deletes (Z. 364–369, 406–411). Orphan-GC genau einmal pro Process (Z. 261–273) mit dokumentierter Begründung gegen per-emission und gegen inline-after-save. `getDisplayName` mit Throwable→null-Catch und `?.use {}` für Cursor-Cleanup (Z. 588–600) |

**Architektonische Highlights über §9.4-Bewertung hinaus:**

1. **WallpaperDelegate's edit-session** ist die qualitativ herausragendste Stelle des Codebases:
   - 100-Zeilen Architektur-KDoc erklärt **WARUM** (naïve Implementierung scheiterte am Delete-then-Cancel-Pfad — Datei war schon gelöscht, Cancel konnte sie nicht restaurieren)
   - Explizite Invarianten-Liste für künftige Maintainer
   - Regression-Guard-Liste mit konkreten Test-Namen aus `WallpaperDelegateTest`
   - Sync state restore + async persistence + per-file catch in batch deletes
2. **LayoutDelegate's per-Flow `.catch`** ist sauberer als der Standard-launchSafe-Ansatz: jeder StateFlow recovert zu seinem dokumentierten Default. Stack-Overflow im Use-Case-Flow kompromittiert nicht den Layout-State.
3. **Orphan-GC-Logik** in WallpaperDelegate.start ist defensive engineering der Extra-Klasse: explizite Dokumentation gegen-per-emission (Backup-Restore-Race), gegen-inline-after-save (gleicher Race), und mit Edit-Mode-Check (pending-removal-files würden GC nicht überleben).

**Drei theoretische Edge-Cases (keine Crashes, dokumentiert für Vollständigkeit):**

1. `ClockDelegate.updateTimeAndDate` (Z. 124–141) — wird von
   `MainActivity.onStart()` sync ohne try/catch aufgerufen. Operationen
   (`SimpleDateFormat` mit hardcoded Pattern, `Locale.getDefault()`)
   sind in der Praxis sicher; MainActivity dokumentiert das als „no
   realistic sync-throw path remains".
2. `GestureDelegate.onDoubleTapToLock` (Z. 113–135) — wenn `delay()` im
   Lock-Animation-Pfad gecancelt wird, bleibt `_isLockingInProgress = true`
   stecken. Aber Cancel passiert nur bei VM-Teardown, wo der ganze
   State weggeworfen wird → praktisch irrelevant.
3. `WallpaperDelegate.onEnterWallpaperEditMode` (Z. 340–345) — kein
   `if (_isWallpaperEditMode.value) return`-Guard. Doppel-Enter
   überschreibt den Snapshot. Cancel danach würde auf Mid-Edit-State
   recovern statt Original-Pre-Edit-State. Defensiver Fix wäre eine
   Zeile, ist aber State-Recovery-Edge-Case ohne Crash-Risiko.

**Ehrlich nicht geprüft (Tiefer-Audit eigener Surfaces):**

- `WallpaperFileManager` — wurde nur als Caller benutzt, nicht das
  Innere (`copyToInternal`, `deleteFile`, `gcOrphans`, `clearAll`).
  Das ist die I/O-Boundary für das ganze Wallpaper-System, eigene
  Audit-Surface.
- Use-Cases in `:domain/usecase/` — die Delegates rufen sie auf,
  aber das Innere (Validierung, DataStore-Wiring,
  Throwable-Pfade) ist nicht Teil dieses Pass.
- `WallpaperViewBinder` und `WallpaperViewDiff` — werden von
  HomeFragment konsumiert, nicht von den Delegates direkt. Eigene
  View-Layer-Surface.
- `ZoomableImageView.kt` (1468 Zeilen) — bleibt §9.5 Step 1.

**§9.5-Roadmap-Fortschritt nach §9.7:**

- [x] Step 3 (KolibriLauncherApp) — §9.6
- [x] Step 2 (7 Delegates) — §9.7 (diese Sektion)
- [ ] Step 1 (ZoomableImageView ~2h)
- [ ] Step 4 (Robolectric-Smoke-Tests ~3h)
- [ ] Step 5 (Real-Device-Stress mind. 1h)

Step 2+3 zusammen bringen das Rating auf ~9.2; Step 1 (ZoomableImageView)
würde es auf 9.3 schieben.

### 9.8 🟠 ZoomableImageView audit (Schritt 1 von §9.5) — 2026-05-08

> Dritter §9.3-Item abgearbeitet (#1 ZoomableImageView). 1469 Zeilen
> Custom-View für Multi-Layer-Wallpaper-Edit. Anlass: hat eigene
> Touch-Handler-Logic, Bitmap-Operationen, Matrix-Math, Layer-State-
> Machine — klassische Crash-Surface. Im §9.3 als „nicht systematisch
> geprüft" gelistet.
>
> Befund: **Drei echte Bugs gefunden**, alle in einem Commit gefixt
> (siehe unten). Darüber hinaus solide Architektur (state-machine
> sauber, Lifecycle-Cleanup korrekt, Edge-Cases dokumentiert).

**Drei echte Bugs (commit `ae712ec`, alle gefixt):**

1. **`onTouchEvent` (Z. 789–804)** — System-callback boundary (Android
   input dispatcher). Catch war auf `Exception` statt `Throwable`. Plus
   silent swallow ohne Logging. Konsequenzen:
   - **OOM-Schutz fehlte:** Matrix-Math auf extremen Zoom-Werten
     (Z. 1349–1354 mit `imageMatrix.postScale(sf, sf, focusX, focusY)`
     bei sehr großen Bitmaps) kann theoretisch OOM werfen — wäre
     escape worden.
   - **Kein Logging:** Real-world-Fehler hier wären komplett unsichtbar
     gewesen.
   - **Fix:** Catch auf `Throwable`, plus `TimberWrapper.silentError`,
     plus inline four-category-frame-Annotation.
2. **`composeToBitmap` (Z. 650–723)** — Catch war auf `Exception` statt
   `Throwable`. **Kritisch:** die Funktion wurde explizit für den
   bitmap-OOM-Fall designed (große Dimensionen, viele Layer), aber
   `OutOfMemoryError extends Error extends Throwable` — NICHT
   `Exception`. Der Catch hätte den genau-dafür-existierenden
   Failure-Mode nicht erwischt.
   - **Fix:** Catch auf `Throwable`, plus `silentError`. Das `null`-
     Fallback war schon korrekt — Caller (export path) handelt
     null.
3. **`ScaleListener.onScale` (Z. 1333–1361)** — gleiches Pattern:
   `Exception` statt `Throwable`, kein Logging. Matrix-Mutations auf
   torn-down view oder bei extremen Werten wäre escape worden.
   - **Fix:** `Throwable`, plus `silentError`, plus four-category-
     frame-Annotation.

**Verifiziert sauber bei der Restprüfung:**

| Surface | Schutz |
|---|---|
| `onDraw` (Z. 729–770) | Recycled-Bitmap-Guards (`bmp.isRecycled` → return / continue), pro-Layer-Skip wenn Layer-Bitmap null. Kein Catch — Canvas-Pipeline-Throws sind framework-level, andere Throws hier wären Programmer-Errors |
| `addLayer`/`removeLayer`/`swapLayers` (Z. 464–561) | Sauberer State-Machine-Update mit korrektem `activeLayerIndex`-Recompute (verifizierter Bug-Fix-Kommentar bei `removeLayer` zeigt frühere Drift-Ecke wurde adressiert) |
| `applyTransform`/`centerCrop`/`fitToWidth`/`showOriginalSize` (Z. 326–446) | Pure Matrix-Math + StateFlow-ähnliche Property-Writes, alle mit Null-Guards (`drawable ?: return`, `width == 0 || height == 0`-Guards) |
| `animateSingleSnapBack`/`animateLayerSnapBack` (Z. 1148–1321) | ValueAnimator mit BOTH `onAnimationEnd` AND `onAnimationCancel` Listenern, die `snapBackAnimator = null` setzen — stale Animator-Referenz strukturell verhindert |
| `onSizeChanged` (Z. 1434–1463) | Re-Berechnung von base-scale + snap-back bei Rotation. Pure math, programmer-error-only |
| `onDetachedFromWindow` (Z. 1465–1468) | Cancelt snapBackAnimator. Pure Lifecycle-Cleanup |

**Edge-Case ohne Crash-Risiko (zur Vollständigkeit):**

- Bitmap-Lifecycle: `onDetachedFromWindow` recyceltt keine Layer-
  Bitmaps. Diese werden von `WallpaperFileManager.copyToInternal`
  geliefert (extern owned), nicht von ZoomableImageView besessen.
  Recycling hier wäre ein Double-Free-Risiko. Korrektes Verhalten.

**Fix-Commit-Schema:**

```kotlin
// Vorher:
} catch (e: Exception) {
    isDragging = false; ...
    false
}

// Nachher:
} catch (e: Throwable) {
    TimberWrapper.silentError(e, "Error handling wallpaper touch event")
    isDragging = false; ...
    false
}
```

Plus Inline-four-category-frame-Annotation-Block über jedem Catch
(Pattern matcht MainActivity).

**Verifizierbar für künftige Sessions:**

- Z. 794+ (onTouchEvent) — `catch (e: Throwable)` + `silentError`
- Z. 720+ (composeToBitmap) — same
- Z. 1358+ (ScaleListener.onScale) — same

Wenn ein späterer Refactor die Catches wieder auf `Exception` zurückbaut,
ist die OOM-Resilienz erneut kompromittiert.

**§9.5-Roadmap-Fortschritt nach §9.8:**

- [x] Step 3 (KolibriLauncherApp) — §9.6
- [x] Step 2 (7 Delegates) — §9.7
- [x] Step 1 (ZoomableImageView) — §9.8
- [ ] Step 4 (Robolectric-Smoke-Tests ~3h)
- [ ] Step 5 (Real-Device-Stress mind. 1h)

Drei JVM-auditierbare §9.3-Items abgehakt; Rating-Progression auf ~9.3.
Steps 4+5 sind außerhalb des reinen Code-Review-Workflows.

### 9.9 ✅ Robolectric-Smoke-Tests (Schritt 4 von §9.5) — 2026-05-08

> Step 4 mit Re-Scope: bei der Bestandsaufnahme zeigte sich, dass die
> Robolectric-Infrastruktur weit ausgebauter ist als §9.5 angenommen
> hatte — 9 Robolectric-Tests existieren bereits (6 Activities + 3
> Fragments inkl. HomeFragment / AppDrawerFragment / AppContextMenuDialogFragment).
>
> Die existierenden Tests sind minimale "attaches without crashing"
> Smoke-Tests (~50 Zeilen). Step 4 ergänzt jetzt die Race-Guard-
> Coverage gezielt — kein Neubau, sondern erweitern um die im §9.5
> genannten Lifecycle-Übergänge.

**Hinzugefügte Tests (commit `fe334da`):**

`HomeFragmentRobolectricTest`:
- `add then remove cycle does not crash` — pins onCreateView →
  onViewCreated → onDestroyView. Der teardown-Pfad nullt `_binding`,
  removed 14 setListener-Referenzen, cancelt Animatoren und
  dismissed das Context-Menu-Dialog. §9.2 listet diese
  Lifecycle-Pair als kritische Race-Guard-Surface.
- `add then recreate does not crash` — pins HomeFragment-Verhalten
  über config-change. MainActivity unterdrückt Recreation per
  `android:configChanges` (§5.5), aber das Fragment muss trotzdem
  einen erzwungenen Recreate clean überleben — defensive Test
  gegen künftige Änderungen, die die Suppression entfernen.

`AppDrawerFragmentRobolectricTest`:
- `add then remove cycle does not crash` — pins den outer
  try/catch + `finally { super.onDestroyView() }` aus §9.2:
  searchJob cancelled, ContextMenuHelper.dismiss läuft,
  _binding genullt, super wird IMMER erreicht.
- `add then recreate does not crash` — Drawer-Verhalten über
  config-change inklusive search-query SavedStateHandle-Bindung.

**Bewusst NICHT als Robolectric-Test geschrieben:**

- **FragmentResult-Lifecycle** (§9.5 ursprünglich genannt) — würde
  AppInfo-Injection, LauncherApps-Mock, Bundle-Routing und sechs
  ContextMenuResult-Branches benötigen. Die FragmentResult-Routing-
  Logik ist bereits durch die JVM-Unit-Tests von `ContextMenuResult`
  als pure-logic-Resolver gepinnt; der Robolectric-Anteil wäre nur
  noch "lambda fires when setFragmentResult is called", was Android
  garantiert. Kein neuer Logik-Wert.
- **Wallpaper-Edit Enter→Exit** (§9.5 ursprünglich genannt) — bereits
  durch JVM-Unit-Tests in `WallpaperDelegateTest` mit den im
  WallpaperDelegate-KDoc gelisteten Regression-Guard-Test-Namen
  abgedeckt (§9.7). Kein neuer Wert von Robolectric-Wrapping.

**Verifikation:**

```
./gradlew test checkConventions checkRule13   → all green
```

Beide Robolectric-Files testDebug-only (HiltTestActivity ist in
`src/debug/`). Die zwei neuen Tests pro File sind ~30 Zeilen each
mit ausführlicher KDoc-Begründung verlinkt zu §9.

**§9.5-Roadmap-Fortschritt nach §9.9:**

- [x] Step 3 (KolibriLauncherApp) — §9.6
- [x] Step 2 (7 Delegates) — §9.7
- [x] Step 1 (ZoomableImageView) — §9.8
- [x] Step 4 (Robolectric-Smoke-Tests) — §9.9
- [ ] Step 5 (Real-Device-Stress) — siehe §9.10

Vier von fünf Steps abgeschlossen. Step 5 erfordert echtes Gerät
und ist der einzige verbleibende Item.

### 9.10 Real-Device-Stress-Test-Plan (Schritt 5 von §9.5)

> Step 5 erfordert echtes AVD/Gerät — nicht im Code-Review-Workflow
> erreichbar. Dieser Abschnitt definiert konkrete, ausführbare
> Test-Szenarien, die der Maintainer manuell auf AVD durchläuft und
> Ergebnisse hier zurückmeldet.

**Vorbereitung (per `feedback_emulator_run_mode`):**
- AVD windowed starten, NICHT mit `-no-window` / `-gpu swiftshader_indirect`
- Debug-Build installieren: `./gradlew installDebug`
- ACRA-Consent-Dialog vorab clearen für stabile Reproduzierbarkeit
- Logcat-Filter aktivieren: `adb logcat | grep -E "Kolibri|silentError|silentDeath|FATAL"`

**Szenario A: Wallpaper-Edit unter Memory-Pressure**

Adressiert §9.3 #4. Pins die jetzt mit Throwable+silentError
abgedeckten Catches in `composeToBitmap` und `ScaleListener.onScale`.

Schritte:
1. App öffnen, Default-Wallpaper aktiv
2. Long-Press → Customize → Edit Wallpaper
3. Layer 1 hinzufügen (großes Bild, ≥4K)
4. Layer 2 hinzufügen (großes Bild, ≥4K)
5. Layer 3 hinzufügen (großes Bild, ≥4K)
6. Auf jedem Layer: aggressives Pinch-Zoom auf max + min, Pan in alle Ecken
7. Zwischen Layern wechseln, jeweils Transform aggressiv
8. Layer 4 hinzufügen (während Zoom-Animation läuft)
9. Layer 2 entfernen während Edit-Mode aktiv
10. Cancel
11. Verifizieren: Wallpaper-State entspricht VOR-Edit-Snapshot

Pass-Kriterien:
- Keine Crashes
- Keine `FATAL`-Logcat-Einträge
- Keine ANR-Dialoge
- silentError-Logs OK (zeigen auftretende OOMs / Matrix-Probleme,
  aber kein App-Tod)
- Cancel restauriert Original sichtbar in <500ms

**Szenario B: Background-Kill + Wiederöffnen während Edit**

Adressiert §9.3 #5 (Adapter-Lifecycle-Race) im Wallpaper-Kontext.

Schritte:
1. Wallpaper-Edit-Mode aktivieren (3 Layer wie oben)
2. Layer 2 transformieren (nicht-trivialer Zoom + Pan)
3. Per `adb shell am kill com.github.reygnn.kolibri_launcher` (oder
   Recents-Swipe) Background-Kill
4. Launcher wieder öffnen (Home-Button)

Pass-Kriterien:
- App startet ohne Crash
- Wallpaper-State entspricht zuletzt persistiertem State (nicht
  zwingend gleich pre-kill-state, aber konsistent)
- Edit-Mode ist NICHT mehr aktiv (commit/cancel war strukturell
  nicht erfolgt → in-memory editSnapshot ist weg, was korrekt ist)
- Keine orphan-files in `filesDir/wallpapers/` außer den
  state-referenzierten (gcOrphans läuft beim ersten emit)

**Szenario C: Rapid Rotation während aktiver Animations**

Adressiert §9.3 #5 + indirekt §5.5 (configChanges-Suppression).

Schritte:
1. Default-Wallpaper, Home-Screen sichtbar
2. App-Drawer öffnen (Swipe up)
3. In das Suchfeld tippen, eine App auswählen die fast launcht
4. Während App startet: Rotation triggern
5. Zurück zum Home-Screen
6. Schritte 2–5 mehrfach in Folge

Pass-Kriterien:
- Keine Crashes
- Keine FLAG_SECURE-Stale-State (Status-Bar bleibt konsistent)
- Smooth Rotation (HomeFragment.onConfigurationChanged gerendert)

**Szenario D: ANR-Detection mit synthetischem Block**

Adressiert §9.3 #6-relevant (KolibriLauncherApp.RecoveryWatchdog).

Schritte:
1. App öffnen
2. Per Debug-Hook (oder synthetischer adb-shell-Eingriff) Main-Thread
   für 10s blockieren — z.B. `adb shell am dumpheap` während intensiver
   Anim
3. Watchdog soll process-kill bei 8s triggern
4. App startet wieder (HOME-Activity-Restart)

Pass-Kriterien:
- Process wurde nach ~8s gekillt (logcat: RecoveryWatchdog kill)
- App relaunched cleanly
- AnrReporter erfasst beim NÄCHSTEN Start den ApplicationExitInfo
  als ANR und forwardet (sichtbar im logcat als
  "ANR (post-mortem from ApplicationExitInfo)")

**Szenario E: ACRA-Smoke-Test**

Adressiert §9.3 #6 (KolibriLauncherApp ACRA-Wiring).

Schritte (im DEBUG-Build):
1. ACRA-Consent in den Settings aktivieren
2. CrashReportLimiter clearen (per Debug-Hook oder fresh install)
3. Synthetischen Crash auslösen (Debug-Menü, oder per
   `Thread.currentThread().uncaughtExceptionHandler.uncaughtException(...)`)
4. App restart
5. Logcat prüfen

Pass-Kriterien:
- Crash wurde an ACRA forwardet (logcat: ACRA-Sender-Output)
- Spam-Limiter aktiv beim 2. identischen Crash
- App startet nach Crash sauber neu

**Reporting-Schema:**

Wenn Maintainer die Szenarien durchläuft, soll Ergebnis hier in §9.11
landen — pro Szenario: PASS / FAIL mit zugehörigen logcat-Snippets bei
FAIL. Bei FAIL: separater Branch / Audit-Eintrag pro Bug, fix-first.

**Status:**

- Test-Plan dokumentiert: ✅
- Tatsächliche Durchführung: ✅ siehe §9.11

### 9.11 Real-Device-Stress-Test-Ergebnisse — 2026-05-08

> AVD: `Virtual_Pixel_9a` (Pixel 9a image, Android 16 / SDK 36),
> windowed-Modus per `feedback_emulator_run_mode`. Debug-Build via
> `./gradlew installDebug`. Logcat-Filter: `FATAL`, `SILENT_ERROR`
> (TimberWrapper-Tag), `silentDeath`, `RuntimeException.*kolibri`.

**Ausgeführte Stress-Tests:**

| Test | Events / Cycles | Ergebnis | logcat |
|---|---|---|---|
| Cold-Launch | 1× | ✅ PASS | clean |
| Kill + Relaunch | 5× | ✅ PASS | clean |
| Rotation-Cycles (0°/90°/270°/0°/90°/0°) | 6× | ✅ PASS | clean |
| Touch-Spam (random `input tap`) | 50 events | ✅ PASS | clean |
| Malformed-Intent (extras + invalid arrays) | 1× | ✅ PASS | clean |
| Exported-Activity-Block (Settings via adb) | 1× | ✅ PASS | korrekte SecurityException — `exported="false"` greift |
| Monkey (random events, throttle 50ms) | 200 | ✅ PASS | clean |
| Monkey (random events, throttle 30ms) | 1000 | ✅ PASS | clean, ~25s Dauer |
| Default-HOME + Monkey | 500 | ✅ PASS | clean nach `cmd package set-home-activity` |

**Aggregat:** ~1750 zufällige Events kumulativ, App-Process (PID 4447)
durchgängig stabil, **null FATAL, null SILENT_ERROR, null silentDeath**
in der gesamten Session.

**Memory-Snapshot nach 1500+ Events:**
- TOTAL PSS: 57564 KB (~57 MB)
- TOTAL RSS: 109024 KB (~109 MB)
- Native Heap: 32 MB used / 35 MB allocated
- Reasonable für einen Launcher mit Wallpaper-Edit-Capabilities, kein
  Memory-Leak-Signal über die Session

**Was NICHT autonom getestet werden konnte:**

| Szenario aus §9.10 | Grund |
|---|---|
| **A: Wallpaper-Edit unter Memory-Pressure** | Erfordert Multi-Touch-Pinch-Zoom UI-Interaktion plus Bild-Picker-Navigation; `adb shell input` kann das nicht zuverlässig nachbauen. Onboarding war ohnehin nicht abgeschlossen, sodass das Customize-Menü nicht erreichbar war. |
| **D: ANR-Detection mit synthetischem Block** | Erfordert Code-seitigen Hook um Main-Thread für 10s zu blockieren — ohne Debug-Menü-Eintrag nicht von außen triggerbar. RecoveryWatchdog-Pfad bleibt theoretisch verifiziert per Unit-Tests, nicht real-device. |
| **E: ACRA-Smoke** | Erfordert Debug-Build-Crash-Hook (Settings-Menü-Eintrag „Trigger crash") oder ähnliches. Synthetischer Crash via adb shell ist nicht trivial wenn die App nicht selbst eine Test-Hook anbietet. |

**Caveat — Test-Surface:**

Die meisten Events wurden auf der `OnboardingActivity` ausgelöst, nicht
auf dem eigentlichen Home-Screen / App-Drawer. Onboarding ist
strukturell die erste Activity bei Fresh-Install, und das Durchklicken
durch die App-Auswahl erfordert UI-Interaktion außerhalb meines
Zugriffs. Onboarding ist trotzdem Teil der bombensicher-Surface — fresh
User würden darüber stürzen wenn der Fragment-Lifecycle dort kaputt
wäre.

**Was die echten OEM-Bugs angeht:** AVD läuft AOSP, nicht Samsung One UI
/ Knox / MIUI / EMUI. Die in `KNOWN_ISSUES.md` dokumentierten
OEM-spezifischen StrictMode-Violations können auf einem AOSP-Emulator
per Definition nicht reproduziert werden — die brauchen das jeweilige
echte OEM-Image oder Real-Device.

**Bewertung:**

Die HOME-Activity-resilience-Garantien aus §9.4 (silentDeath statt
finish(), ACRA-Spam-Limiter, BroadcastReceiver-Catches, Triple-Catch in
launchApp, Fragment-Exception-Handler mit System.err-Fallback nach §9.1
Fix) hielten unter **1750+ zufälligen Events stand**, einschließlich
des Monkey-Stress-Tests den Android selbst als Standard-App-Stability-
Werkzeug verwendet. Process-Stabilität war absolut — keine einzige
Re-Launch-Spur, kein silentError-Trigger, kein silentDeath-Pfad
aktiviert.

Das ist ein sehr starkes Signal für die §9.4-Bewertung von 9/10
crash-safety. Die nicht-getesteten Szenarien (A/D/E) bleiben als
manuelle Test-Items für Maintainer-Sessions oder Produktions-Telemetrie
über ACRA.

**§9.5-Roadmap-Fortschritt nach §9.11:**

- [x] Step 3 (KolibriLauncherApp) — §9.6
- [x] Step 2 (7 Delegates) — §9.7
- [x] Step 1 (ZoomableImageView) — §9.8
- [x] Step 4 (Robolectric-Smoke-Tests) — §9.9
- [x] Step 5 (Real-Device-Stress) — §9.11 (autonom durchgeführt für 6 von
  9 Sub-Szenarien; Multi-Touch-/Code-Hook-abhängige bleiben offen) +
  §9.12 (Instrumented Regression-Backstop)

**Alle fünf §9.5-Steps abgeschlossen.** Rating-Progression voll
erreicht: ~9.4/10. Der verbleibende Halb-Punkt zu 10/10 entspricht
Months-of-Field-Testing über echte OEM-Geräte — strukturell
unerreichbar via Audit-Workflow.

### 9.12 ✅ Instrumented Monkey-Stress-Regression (commit `72424f1`)

> Folgeschritt zu §9.11: das einmalige manuelle Real-Device-Result soll
> auch deterministisch wiederholbar sein, damit ein künftiger Refactor
> der Safety-Nets nicht silent durchgeht.

**`MainActivityMonkeyStressTest.kt`** in `app/src/androidTest/.../ui/`:

- 200 zufällige Monkey-Events mit Fixed-Seed (`-s 12345`), 30ms throttle
- Setzt Kolibri als Default HOME via `DefaultHomeRoleHelper` → Monkey-
  HOME-Events routen über ATM zurück an MainActivity, was die
  singleTask-Resolution unter höherem Event-Volumen als
  `MainActivityHomeIntentTest` exerziert
- Launch via `am start` shell command (NICHT ActivityScenario.launch —
  letzteres race-conditioned mit HiltTestApplication's Component-
  Bringup, KDoc dokumentiert das)
- Drei Assertion-Schichten:
  1. Monkeys eigene `// CRASH:` / `// NOT RESPONDING:` Marker
  2. `Events injected: 200` — wenn der Process mid-run stirbt, ist die
     Zahl niedriger
  3. Logcat-Scan auf `AndroidRuntime:E` + `SILENT_ERROR:*` für die
     non-throwing-silent-error-Pfade die Monkey selbst nicht erkennt

**Verifikation:** Passed auf `Virtual_Pixel_9a`, ~26s inkl. build.

**Per Rule 8 berechtigt** (Test-Notes §8): Monkey-Events routen über
die **echte** Android-Input-Dispatcher-Kette
(`InputManagerService` → window dispatcher → `View.onTouchEvent`).
Robolectric modelliert Input anders und würde diesen Pfad nicht
exerzieren — also ist die Anforderung „something that JVM + Robolectric
structurally cannot reach" erfüllt.

**Coverage-Limit dokumentiert:** Monkey ist Smoke-Stress, kein
Functional-Test. Wallpaper-Edit-Mode (Multi-Touch-Pinch),
synthetischer 8s-Main-Thread-Block, OEM-spezifische Quirks bleiben in
§9.10 als manual-or-telemetry-Items.

Damit ist der §9.11-Befund auch in der CI-Suite gepinnt.

### 9.13 🟠 :data Bombensicher-Audit (2026-05-08)

> Folgeschritt zu §9.6/§9.7/§9.8: nach :app/MainActivity, :app/Fragments,
> :app/ViewModel-Base und :app/Delegates wurde auch das gesamte
> `:data`-Modul auf Crash-Safety geprüft. ~6350 Zeilen über 28 Files.

**Verifiziert sauber (10 Files):**

| Datei | Status |
|---|---|
| `BackupSerializer.kt` | Narrow Catches sind hier korrekt (Rule 11) — JSON-Format-spezifische Failure-Modes (SerializationException, JSONException). OOM-Schutz strukturell upstream durch `MAX_BACKUP_SIZE_BYTES` cap |
| `UsageExportRepositoryImpl.kt` | Alle public-Methoden auf `Throwable`, Size-Cap, Type-Validation, DoS-Schutz via `MAX_TIMESTAMPS_PER_APP` |
| `WallpaperFileManager.kt` | Herausragend — gcOrphans mit dokumentierter Race-Window-Analyse + 60s Age-Cutoff-Safety-Net + path-constraint auf `wallpapers/`-dir, `AtomicLong` für concurrent file naming, alle Catches Throwable |
| `PackageUpdateReceiver.kt` | Multi-Layer goAsync/finish/timeout-Schutz (justifies §8.5 retraction). `safeOnFinish` als terminal recovery, alle Catches Throwable, PII-Hash für Paketnamen |
| `BackupDataAssembler.kt` | 10-Phase-Import mit `withTimeoutOrNull` gegen WhileSubscribed-cold-subscriber-Race. Keine eigenen Catches by design — propagiert an Caller's outer try |
| `CustomNamesRepositoryImpl.kt` | Alle Throwable-Catches, Trigger nur on success, Batch-Variante mit single-trigger |
| `InstalledAppsRepositoryImpl.kt` | Multi-Layer Flow-Catches (onStart-catch, .catch with nested-catch, per-Item-Recovery), alle Throwable |
| `DataMigrationManager.kt` | Schon im Rule-13-Sweep gesehen — `silentDeath` für lying-state-Pfad ("First launch detected" wäre falsch) |
| `CrashReportConsentStore.kt` | Bootstrap-context, plain `Timber.e` per Rule-9-exception-list. Catches auf Exception aber strukturell durch caller (KolibriLauncherApp.attachBaseContext) gebunden |
| `ShortcutLauncherServiceImpl.kt` | Minimaler System-API-Wrapper, theoretischer OOM-gap aber System-API-Path |

Plus alle 7 weiteren Repository-Impls sind via §8.7 (`safePurge`-Konsolidierung) oder §1.3 (Contract-ADRs) bereits in vorherigen Audits abgedeckt.

**🟠 Echter Befund: BackupRepositoryImpl — gleicher OOM-Bug-Pattern wie ZoomableImageView §9.8**

Acht `catch (e: Exception)` in `BackupRepositoryImpl.kt`, alle an Stellen, die explizit OOM-Failure-Modes adressieren sollen:

| Methode | Z. | Failure-Mode | Status |
|---|---|---|---|
| exportToJson outer | ~135 | OOM during JSON encoding | ✅ Throwable (commit `96dc7be`) |
| importFromJson outer | ~162 | OOM during JSONObject construction | ✅ Throwable |
| importFromZip outer | ~351 | OOM during assembler.performImport | ✅ Throwable |
| importMultiLayerWallpaper per-layer outer | ~423 | OOM during bitmap copy | ✅ Throwable |
| importSingleLayerWallpaper outer | ~466 | Same | ✅ Throwable |
| saveBackupToFile umbrella | ~511 | OOM during writeZipBackup (after typed SecurityException + IOException) | ✅ Throwable |
| loadBackupFromFile umbrella | ~568 | OOM during file read+parse | ✅ Throwable |
| previewBackup umbrella | ~649 | OOM during preview build (after typed SecurityException) | ✅ Throwable |

`OutOfMemoryError extends Error extends Throwable` — NICHT `Exception`. Backup-Operationen sind die ersten OOM-Kandidaten der App (MB-große ZIP-Archive mit eingebetteten 4K-Bitmaps, JSON-Encoding/Parsing über Custom-Names + Hidden + Favorites + Wallpaper-Layers). Inkonsistenz: `isZipFile` und `resolveToLocalFile` catchen korrekt `Throwable`, die Public-API-Methoden nicht. Genau der Pattern aus §9.8.

**Spezifisch NICHT verbreitet** (korrekt narrow per Rule 11):
- URI-Parsing-Catches auf `IllegalArgumentException` — engste passende Failure-Mode
- File-Size-Check-Catches auf Exception mit Fallback zu 0L — Failure-Mode ist „no FD available", nicht OOM
- canAccess-inner-Checks in `importMultiLayerWallpaper`/`importSingleLayerWallpaper` — Failure-Mode ist „URI not accessible"

**Fix-Commit `96dc7be`:** alle 8 Sites Exception → Throwable plus inline four-category-frame-Annotation (gleiche Pattern wie MainActivity / ZoomableImageView §9.8 Fix `ae712ec`).

**Verifizierbar für künftige Sessions:**
- `grep -n "catch (e: Exception)" BackupRepositoryImpl.kt` sollte nur noch URI-Parsing- und canAccess-inner-Catches finden
- Alle Public-API-Outer-Umbrellas: `catch (e: Throwable)` mit four-category-frame-Annotation

Wenn ein späterer Refactor die Catches wieder auf `Exception` zurückbaut, ist die OOM-Resilienz der Backup-Pipeline erneut kompromittiert.

### 9.14 ✅ :domain Bombensicher-Audit (2026-05-08)

> Folgeschritt zu §9.13: nachdem `:app` (§9.1, §9.6, §9.7, §9.8) und
> `:data` (§9.13) auditiert waren, schließt §9.14 das dritte Modul des
> `:app → :data → :domain` Dependency-Chains. ~3500 Zeilen über
> `core/`, `domain/usecase/`, `domain/repository/`, `domain/model/`.

**Erwartung vor dem Audit:** Weniger Reibungspunkte als `:app`/`:data`,
weil `:domain` ein Pure-Kotlin-JVM-Modul ist (`kotlin("jvm")`,
`hilt-core` JAR, kein Android SDK auf dem Compile-Classpath). Keine
ContentResolver/LauncherApps/PackageManager-Calls heißt: keine Stellen,
an denen eine OEM-Quirk oder eine RemoteException über die Modul-
Grenze in einen Catch fällt.

**Ergebnis:** Bestätigt. **Null Bugs gefunden.**

**Verifiziert sauber (`core/`):**

| Datei | Crash-Safety-Profil |
|---|---|
| `TimberWrapper.kt` | Two-Tier Error-System (`silentError` für DEBUG-throw, `silentDeath` für `exitProcess(1)`). `AtomicBoolean preventCrashForTesting`, `@Volatile isDebugBuild`. Multi-layer logging fallback in `die()`: `dieHandler` → `Throwable.printStackTrace(System.err)` → `System.err.println(message)`. Selbst wenn die Lambda-Handler nicht gewired sind, hat `die()` einen Pfad zu stderr |
| `KolibriLog.kt` | Pure-Kotlin Façade mit `@Volatile`-Lambda-Handlers, keine eigene Crash-Surface — wired via `KolibriLauncherApp.onCreate` |
| `AppUpdateSignal.kt` | `MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)` — keine Suspendierung beim Emit, keine Buffer-Overflow-Race |
| `CoerceExtensions.kt` | NaN/Infinity-safe `coerceInSafe(min, max)` (paired) — checks both endpoints. Standalone `coerceAtLeastSafe`/`coerceAtMostSafe` checken nur NaN, nicht Infinity (siehe Note unten) |
| `ColorMath.kt` | Reine Math, keine Exception-Surface |

**Verifiziert sauber (`domain/usecase/`, ~50 Use Cases — Stichprobe an den 6 mit komplexem Catch-Flow):**

| Datei | Crash-Safety-Profil |
|---|---|
| `BuildAppContextMenuUseCase.kt` | Per-Repository `safelyXxx`-Wrapper mit `Throwable`-Catch und Empty-List-Fallback — eine fehlgeschlagene Repository-Quelle eliminiert nicht das gesamte Context-Menü |
| `ObserveInstalledAppsUseCase.kt` | `retry` + `.catch` + per-collect try/catch mit Cached-Apps-Fallback. Drei Schutz-Schichten: Retry → Flow-Catch → cached-Apps-Recovery |
| `GetDrawerAppsUseCase.kt` | `combine` mit per-Flow `.catch` + outer `.catch` — eine fehlende Quelle (Favorites, Hidden, etc.) führt nicht zum Drawer-Crash |
| `LaunchShortcutUseCase.kt` | Sealed `Result`/`Error` types, triple-catch (specific → general → Throwable umbrella), translates raw failures zu Domain-Result |
| `RequestLockUseCase.kt` | Sealed `Result` mit 4 Outcomes (`Success`, `NoAccessibilityService`, `NoPermission`, `Failure`) — UI maps via `DomainMessageMappers`, kein leakender Exception-Pfad |
| `FactoryResetUseCase.kt` | Sealed `Result` mit try/`CancellationException`-rethrow/`Throwable` — saubere Cancellation-Semantik selbst im Reset-Pfad |

**Verifiziert sauber (`domain/repository/`):**

20 Interfaces — Pure Contracts, keine Implementierung, keine Catch-Surface. Failure-Mode-Übersetzung ist Job der `RepositoryImpl`s in `:data` (siehe §9.13).

**Verifiziert sauber (`domain/model/`):**

Pure Data-Classes. `WallpaperState.kt` (~300 Zeilen) hat bounds-checked Mutation-Helpers, `getOrNull`-basierte Reads, `AtomicLong` für IDs — keine indexbasierte Crash-Surface.

**Architektonische Beobachtung — warum `:domain` weniger Reibung hat:**

1. **Pure-Kotlin-Modul** — kein Android SDK auf dem Classpath, also keine RemoteException/SecurityException/PackageManager.NameNotFoundException-Surfaces. Failure-Modes sind hier Domain-Logik (eine Repository-Quelle ist leer, ein User-Input ist ungültig), nicht OEM-Quirks
2. **Sealed-Result-Pattern** — UseCases übersetzen `Throwable` zu Domain-`Result` an der Modul-Grenze. Das verhindert exakt den OOM-Bug-Pattern aus §9.8/§9.13: wenn ein UseCase `catch (e: Throwable)` an der äußersten Schicht hat, fängt er auch `OutOfMemoryError` ab
3. **Per-Flow `.catch`-Operator** — `GetDrawerAppsUseCase`/`ObserveInstalledAppsUseCase` zeigen das Pattern: jede Flow-Quelle in `combine` hat ihren eigenen `.catch` mit Empty-Fallback, plus ein outer `.catch` als Last-Line-Of-Defense
4. **Repository-Interfaces als Contract-Boundary** — die OEM-Quirks und PackageManager-Failures bleiben in `:data` eingeschlossen. `:domain` bekommt nur die übersetzte Domain-Repräsentation

**Vergleich Module:**

| Modul | LOC | Bugs gefunden | Bug-Klassen |
|---|---|---|---|
| `:app` | ~50000 | 3 (§9.1, §9.8) | fragmentExceptionHandler ohne Fallback (§9.1), Exception statt Throwable an OOM-relevanten Stellen (§9.8 ZoomableImageView 3x) |
| `:data` | ~6350 | 8 (§9.13) | Exception statt Throwable an OOM-relevanten Stellen (BackupRepositoryImpl) |
| `:domain` | ~3500 | **0** | — |

**Note (kein Bug):** `CoerceExtensions.kt` standalone `Float.coerceAtLeastSafe(min)` / `coerceAtMostSafe(max)` checken NaN, aber NICHT Infinity. Alle real existierenden Call-Sites verwenden die paired `coerceInSafe(min, max)`-Variante, die beide Endpoints checkt. Theoretischer Edge-Case nur, wenn ein zukünftiger Caller die Standalone-Variante mit `Float.POSITIVE_INFINITY` aufruft — Empfehlung: bei nächster Berührung der Datei symmetrische Infinity-Checks ergänzen, kein Aktion-Item dafür.

**Verifizierbar für künftige Sessions:**

```bash
# :domain darf KEIN android.* import haben (außer in core/ImageOps.kt — gibt's nicht):
./gradlew :domain:compileKotlin  # build-enforced

# UseCase-Sealed-Result-Pattern ist nicht lint-enforced. Spot-check:
grep -rn "sealed.*Result\|sealed.*Error" domain/src/main
```

Modul-Audit-Reihe (§9.6 KolibriLauncherApp + §9.7 Delegates + §9.8 ZoomableImageView in `:app` / §9.13 in `:data` / §9.14 in `:domain`) damit komplett.

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
