# AUDIT-7 — DRY- & SSOT-Komplexitäts-Audit

> **Erzeugt** 2026-07-24 durch einen Claude-Opus-4.8-Multi-Agent-Audit
> (Version 0.99.108 / code 128, Branch `main`).
> **Fokus:** Diesmal **nicht** Correctness (das war AUDIT-5/-6), sondern
> ausschließlich **Duplikation (DRY)** und **Single-Source-of-Truth
> (SSOT)** — also die Frage: „Lässt sich der Source-Code mit DRY/SSOT
> vereinfachen?"
> **Methode:** Zwei Bereichs-Agenten parallel — einer über `app/src/main`
> (UI: Fragments/Activities/Delegates/Dialoge), einer über `data/src/main`
> + `domain/src/main` (Repositories, Use-Cases, Backup-Pipeline, Konstanten).
> Ergänzt um direkte Struktur-Inspektion (`ZoomableImageView`,
> Repo-Kennzahlen). Beide Agenten waren angewiesen, **bewusst fein
> granulare Use-Cases und zufällige Ähnlichkeit NICHT** als Fund zu melden.
> **Reine Diagnose — es wurde kein Code geändert.**
>
> **Abgrenzung zu bestehenden Audits:** Findings, die AUDIT.md §7
> (bewusst akzeptierte Schulden) oder §8.7 (`purgeRepository`-Schreibpfad
> bereits zu `DataStorePurge.safePurge` gefaltet) schon abdecken, sind hier
> als solche markiert und **nicht** als offener Fund gezählt. Datei-Größen
> (`HomeFragment`, `BackupRepositoryImpl`, `SettingsFragment`,
> `AppDrawerFragment`) sind per §7 akzeptiert — Größe ≠ Duplikation, sie
> stehen hier bewusst nicht auf der Liste.

---

## Executive summary

Die Komplexität des Codebase ist **überwiegend essentiell, nicht
hausgemacht**: ~31 k Zeilen Produktivcode, sauber über `:domain`/`:data`/
`:app` verteilt, keine God-Class, und vieles ist bereits SSOT-diszipliniert
(`AppConstants`, Contract-Tests, `DataStorePurge.safePurge`,
`FlowCollection.collectOnStarted`, `BaseViewModel.launchSafe`,
`DomainMessageMappers`). Ein „Chaos-Refactor" ist **nicht** nötig.

Es bleiben aber **echte, klar abgrenzbare** Konsolidierungschancen — die
„letzte Meile", die bislang nicht gefaltet wurde. Roter Faden: der
**DataStore-Schreibpfad** wurde in §8.7 zentralisiert, der **Lesepfad
nie** — dieselbe Duplikation lebt dort in 8 Repos weiter. Analog gibt es
im **Backup-Pipeline** mehrere SSOT-Stellen, an denen dasselbe Wissen
(Feldnamen, Version) mehrfach gepflegt werden muss.

Gesamt-Einschätzung des Einsparpotenzials: realistisch **~400–500 LOC**
und mehrere stille Drift-Klassen, bei **niedrigem Verhaltensrisiko** für
die Top-Funde (#1–#3, #5). Der einzige riskante Fund ist #7
(`ZoomableImageView`).

Priorisierung:

| # | Fund | Typ | Impact | Risiko |
|---|---|---|---|---|
| 1 | DataStore „safe read flow" in 8 Repos dupliziert | DRY | 🔴 Hoch (~100 LOC) | Niedrig |
| 2 | Flow-Repo Konstruktor/Factory-Gerüst in 4 Repos | DRY | 🟠 (~70 LOC) | Niedrig |
| 3 | `SettingsRepositoryImpl` ~19 Getter/Setter-Paare | DRY | 🟠 (~120 LOC) | Niedrig |
| 4 | Backup-Feldnamen an 4 Stellen gelistet | SSOT | ✅ weitgehend abgedeckt (AUDIT-2/-3) — s. Note bei #4 | — |
| 5 | `BACKUP_VERSION "1.0.0"` an 3+ Stellen hardcodiert | SSOT | 🟡 | Niedrig |
| 6 | Vier „App-Auswahl"-Activities teilen Gerüst | DRY | 🟠 (~250–350 LOC) | Mittel |
| 7 | `ZoomableImageView` Single-/Multi-Layer-Pfad doppelt | DRY | 🟠 (groß) | **Hoch** |
| 8 | Wallpaper-Surface-Farbauflösung in 3 Dialogen | DRY | 🟡 | Niedrig |
| 9 | Rekursiver „tint TextViews"-Walk (2×) | DRY | 🟢 | Niedrig |
| 10 | Rename-Dialog-Builder (2×) | DRY | 🟢 | Niedrig |
| 11 | `Fragment.showToastSafe()` fehlt (Activities haben ihn) | DRY | 🟢 | Niedrig |
| 12 | Bottom-Sheet-Dialog-Window-Setup (2×) | DRY | 🟢 | Niedrig |

Kleinere SSOT-/DRY-Nits (Purge-Key-Listen, Enum-`valueOf`-Fallback,
`getThemeColor`-Wrapper, Orphan-Cleanup) siehe §„Nits".

---

## #1 — DataStore „safe read flow"-Boilerplate in 8 Repos dupliziert · 🔴 Hoch

### Befund
Der Schreibpfad wurde in AUDIT.md §8.7 zu `DataStorePurge.safePurge`
gefaltet — der **Lese**pfad nie. Der identische Block

```kotlin
dataStore.data
    .catch { e -> if (e is IOException) { silentError(...); emit(emptyPreferences()) } else throw e }
    .map { prefs -> ... }
```

steht wortgleich in:

- `FavoritesRepositoryImpl.kt:141-152`
- `FavoritesOrderRepositoryImpl.kt:178-187`
- `HiddenAppsRepositoryImpl.kt:103-114`
- `SwipeActionsRepositoryImpl.kt:103-116`
- `FabPositionRepositoryImpl.kt:74-90`
- `WallpaperRepositoryImpl.kt`, `AppUsageRepositoryImpl.kt` (grep-bestätigt `emit(emptyPreferences())`)

Dazu der `shareIn`/Null-Guard-Tail (`if (externalScope != null)
flow.shareIn(scope, started, replay = 1) else flow`) wortgleich in 4
davon (`FavoritesRepositoryImpl:153-163`, `FavoritesOrderRepositoryImpl:188-198`,
`SwipeActionsRepositoryImpl:117-128`, `FabPositionRepositoryImpl:91-99`).

**Drift bereits vorhanden:** `SettingsRepositoryImpl.kt:75-86` macht
dasselbe, fängt aber generisch `Exception` statt `IOException` (private
`safeData`-Extension) — eine unbeabsichtigte Policy-Abweichung.

### Warum Hoch
~8 Zeilen × 8 Dateien (~64 LOC) Catch-Block + ~10 Zeilen × 4 Dateien
(~40 LOC) Share-Tail. Direkt analog zu dem, was `safePurge` für den
Schreibpfad tat. Faltung macht den Lesepfad drift-fest **und** vereinheitlicht
die IOException-vs-Exception-Policy an einer Stelle.

### Konsolidierungs-Richtung (nicht umgesetzt)
Modul-interne Extension `DataStore<Preferences>.safeReadFlow(errorMsg)` +
`Flow<T>.shareInOrRaw(externalScope, strategy)`. Paart natürlich mit #2.

---

## #2 — Flow-Repo Konstruktor/Factory-Gerüst in 4 Repos dupliziert · 🟠

### Befund
`FavoritesRepositoryImpl`, `FavoritesOrderRepositoryImpl`,
`SwipeActionsRepositoryImpl`, `FabPositionRepositoryImpl` wiederholen alle
dieselbe dreiteilige Form: privater Primär-Konstruktor mit
`sharingStrategy`, `@Inject`-Sekundär-Konstruktor mit
`SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)`, und
`@VisibleForTesting createForTesting(...)`. (Kleine Inkonsistenz:
`FavoritesRepositoryImpl` nutzt statt der Factory einen
`@VisibleForTesting`-Sekundär-Konstruktor.) ~15–20 LOC DI-Plumberei je
Repo (~70 LOC gesamt). Die KDoc in `FavoritesOrderRepositoryImpl` sagt es
selbst: „Similar to `FavoritesRepositoryImpl`, this uses a sophisticated
dual-constructor pattern".

### Konsolidierungs-Richtung (nicht umgesetzt)
`abstract class SharedDataStoreFlowRepository(dataStore, externalScope,
sharingStrategy)`, die Konstruktoren + `safeReadFlow`/`shareInOrRaw` aus #1
besitzt. Größter struktureller Einzelgewinn; #1 + #2 gemeinsam umsetzen.

---

## #3 — `SettingsRepositoryImpl`: ~19 fast identische Getter/Setter-Paare · 🟠

### Befund
`SettingsRepositoryImpl.kt:104-311` enthält ~19 `Flow`-Getter + `set`-Paare,
jedes ~7–10 Zeilen `dataStore.data.safeData.map { prefs[KEY] ?: DEFAULT }`
+ `safeEdit { it[KEY] = value }`. Die 11 boolean-Settings (doubleTapToLock,
swipeDownToNotifications, textShadow, showCalendar, showAlarm,
autoShowKeyboard, autoLaunch, isFontBold, secureWindow, rotationLocked,
onboarding) unterscheiden sich **nur** in Key + Default → reines
Copy-Paste (~120 LOC). Die 3 Enum-Settings (sortOrder,
favoritesAlignment, wallpaperSurfaceMode) wiederholen zusätzlich denselben
`try { Enum.valueOf(name) } catch { default }` (`:110-115, :255-259,
:270-274`).

### Warum nur DRY (kein SSOT)
Defaults kommen bereits sauber aus `AppConstants` — hier ist nichts
doppelt *definiert*, nur die Mechanik ist copy-paste. Größte
Einzeldatei-Kompression. (SSOT-Nit: `onboardingCompletedFlow:144` nutzt
Literal `false`/`true` statt `AppConstants`-Default.)

### Konsolidierungs-Richtung (nicht umgesetzt)
Typisierte Helfer `boolFlow(key, default)` / `intFlow` / `floatFlow` +
`enumFlow(key, default, valueOf)`.

---

## #4 — SSOT: Backup-Feldnamen / snake_case-Aliasse an 4 Stellen · 🟠

> **⚠️ Cross-Reference / weitgehend Wiederentdeckung (nachgetragen 2026-07-24):**
> Dieser Fund überschneidet sich stark mit bereits abgearbeiteten Audits — die
> „Abgrenzung zu bestehenden Audits" oben hat ihn übersehen. Konkret:
>
> - **AUDIT-2** (A~19) hat die 3–4-fach-Duplikation des Strict-Parse-Pfads
>   geflaggt und die Faltung von `parseStrictly` + `mergeWithStrictValues` in
>   **ein** `extractStrictSettings(json, base)` empfohlen → **umgesetzt** (steht
>   heute so im Code). AUDIT-2 stufte `validateJsonTypes` dabei ausdrücklich als
>   *„type-grouped for a different purpose, not cleanly foldable"* ein — also
>   bewusst **nicht** Teil der Feldliste-Konsolidierung.
> - **AUDIT-3 #3** hat die camelCase/snake-Asymmetrie als **echten
>   Korrektheits-Bug** gefunden (Strict-Fallback las nur snake_case, App schreibt
>   camelCase → Skalar-Settings fielen still auf Default). **RESOLUTION
>   2026-07-21:** Strict-Path liest jetzt camelCase-first (+ snake als Alias),
>   Bug gefixt. **Explizite Scope-Entscheidung:** *„`validateJsonTypes` bleibt
>   unangetastet (recover-vs-reject ist eine eigene Frage)"*.
>
> Die im Befund unten als Beleg genannte Drift (`validateJsonTypes` prüft
> int/float/bool nur unter snake_case und ist damit gegen aktuelle camelCase-
> Backups ein No-Op) ist somit **kein neuer Fund**, sondern die bereits
> **bewusst akzeptierte** Disposition aus AUDIT-3 #3.
>
> **Verbleibend** ist nur die reine DRY-Idee (eine Feld-Deskriptor-Tabelle für
> `@JsonNames` + beide Validatoren) — und die hat AUDIT-2 schon als nicht sauber
> faltbar eingestuft. Der Backup-Pfad ist zudem mit Vorwärts-/Rückwärts-Unit-
> Tests (Round-Trip alt+neu, malformed-Rejection) abgedeckt, die eine echte
> Regression aus dieser Restduplikation fangen würden. **Disposition: nicht als
> eigener Refactor verfolgen** — Korrektheitskern gefixt (AUDIT-3), Haupt-DRY
> erledigt (AUDIT-2), Rest bewusst akzeptiert bzw. low-value.

### Befund
Das Wissen „Feld X heißt im JSON Y mit Legacy-Alias Y_snake" lebt in vier
unabhängigen Listen, die zusammen editiert werden müssen:

- `@JsonNames(...)`-Annotationen an `LauncherSettings`/`WallpaperLayerBackup`
  — `BackupData.kt:28-186`
- `BackupSerializer.extractStrictSettings` Kandidaten-Key-Paare —
  `BackupSerializer.kt:324-352` (z. B. `getStrictString("swipeLeftApp", "swipe_left_app")`)
- `BackupSerializer.validateJsonTypes` Feld-Listen (`intFields`/`floatFields`/
  `boolFields`/`stringFields`/`arrayFields`) — `BackupSerializer.kt:369-442`
- `BackupSerializer.validateWallpaperLayerTypes` — `BackupSerializer.kt:487-490`

~25 Settings × je 3–4-fach benannt (~90 String-Literale). Ein neues
Backup-Feld erfordert Änderungen an allen vier Stellen; stille Drift (Feld
validiert aber nicht gemergt, oder im Modell aliased aber nicht im
Strict-Parse) ist leicht.

### Konsolidierungs-Richtung (nicht umgesetzt)
Eine einzige Tabelle von Feld-Deskriptoren (name, alias, type-category),
über die sowohl Validator als auch Strict-Merge iterieren; `@JsonNames`
bleibt kotlinx-Quelle, die org.json-Pfade werden aus derselben Liste
getrieben.

---

## #5 — SSOT: Backup-Version `"1.0.0"` an 3+ Stellen hardcodiert · 🟡

### Befund
`AppConstants.BACKUP_VERSION = "1.0.0"` (`AppConstants.kt:187`) ist die
gewollte SSOT und wird von `BackupDataAssembler.kt:162` und
`BackupSerializer.isVersionSupported` (`:139`) korrekt genutzt. Aber das
Literal ist re-hardcodiert an:

- `BackupData.kt:9` — `val version: String = "1.0.0"` (Data-Class-Default)
- `BackupSerializer.kt:277` — Strict-Parse-Fallback `"1.0.0"`

Bei einem Version-Bump produzieren/akzeptieren diese zwei still die alte
Version. (`UsageExportRepositoryImpl.kt:58,184` `USAGE_EXPORT_VERSION =
"1.0.0"` ist ein **separater** Namespace, legitim unabhängig — nur
verwirrend derselbe Wert.)

### Konsolidierungs-Richtung (nicht umgesetzt)
`BackupSerializer:277` auf `AppConstants.BACKUP_VERSION` umstellen; der
Data-Class-Default kann die Konstante über die Annotation-Grenze nicht
sauber referenzieren → mindestens Pin-Kommentar, oder Default streichen
(da `BackupDataAssembler` ihn ohnehin immer explizit setzt).

---

## #6 — Vier „App-Auswahl"-Activities teilen ~250–350 LOC Gerüst · 🟠

### Befund
`OnboardingActivity`, `HiddenAppsActivity`, `SwipeActionsActivity`,
`CustomNamesActivity` (alle `BaseActivity`) wiederholen dasselbe Skelett:

- `handleWindowInsets()` — byte-genau identisch (HiddenApps:120-131,
  SwipeActions:101-112, Onboarding:148-159, CustomNames:122-133). ~12 LOC × 4.
- `onCreate`-Gerüst (`setDecorFitsSystemWindows(false)` → inflate →
  `setContentView` → setup* → Query-Restore → `catch (Throwable) { silentError; finish() }`).
  ~25 LOC × 4.
- `setupSearchListener()` + `searchQueryFlow`/`SEARCH_DEBOUNCE_MS = 300L`/
  `STATE_SEARCH_QUERY` + `doOnTextChanged` + `collectOnStarted(debounce)` —
  identisch. ~15 LOC × 4.
- `onSaveInstanceState` Query-Save + `_binding`-Guard-Idiom
  (`IllegalStateException("Binding accessed after onDestroy")`) — alle vier.
- Chip-Befüllung (`updateSelectionChips`/`updateCustomNameChips`) —
  gleicher `removeAllViews()` + `for(app){ Chip{ text; closeIcon; onClose } }`.
  ~13 LOC × 3.

### Konsolidierungs-Richtung (nicht umgesetzt)
`abstract class BaseAppSelectionActivity<E, VM>` (Adapter/XML sind zwischen
Onboarding und HiddenApps laut Header-Kommentaren teils schon geteilt):
`handleWindowInsets()`, debounced-Search-Wiring, `_binding`-Guard,
Query-Save/Restore hochziehen + `ViewGroup.bindChips(apps, onClose)`.

---

## #7 — `ZoomableImageView`: Single-/Multi-Layer-Pfad doppelt gepflegt · 🟠 · ⚠️ Risiko Hoch

### Befund
Ein „Single-Layer"- und ein „Multi-Layer"-Pfad existieren parallel — laut
Klassen-KDoc bewusst als „Backward Compatibility". Paarweise Duplikation:

- `handleSingleLayerTouch` (`:821`) / `handleMultiLayerTouch` (`:895`)
- `applySingleEdgeResistance` (`:1042`) / `applyLayerEdgeResistance` (`:1191`)
- `calculateSingleSnapBack` (`:1106`) / `calculateLayerSnapBack` (`:1255`)
- `animateSingleSnapBack` (`:1161`) / `animateLayerSnapBack` (`:1310`)
- `notifySingleTransformChanged` (`:1023`) / `notifyMultiTransformChanged` (`:1027`)

Single-Layer *ist* konzeptuell der Spezialfall „1 Layer".

### Warum riskant — vor Umsetzung §9.8 lesen
AUDIT.md **§9.8** ist ein eigener „ZoomableImageView audit" (🟠). Bevor
hier gefaltet wird, muss abgeglichen werden, ob der Doppelpfad dort bereits
als bewusst/notwendig eingestuft wurde. Der Code betrifft Touch-Matrix-,
Edge-Resistance- und Animations-Semantik — Verhaltensrisiko ist hoch,
Test-Netz dünn (kein androidTest). **Nur mit gutem Test-Netz angehen; im
Zweifel als bewusste Schuld nach §7 dokumentieren statt falten.**
(Der §7-Eintrag zu ZoomableImageView betrifft nur die 7× `@Suppress("unused")`-
Public-API-Stubs — **nicht** diese Single/Multi-Duplikation.)

---

## #8 — Wallpaper-Surface-Farbauflösung in 3 Dialog-Fragmenten kopiert · 🟡

`AppContextMenuDialogFragment.kt:434-443` (+ observe 216-235),
`ColorCustomizationDialogFragment.kt:102-127`,
`LayoutCustomizationDialogFragment.kt:307-337` re-implementieren jeweils
`LuminanceClassification.LIGHT/DARK → R.color.app_drawer_surface_light/_dark
→ ResolvedBackground.SolidColor(color).foregroundColor()`. Der Flow selbst
ist zentral (`ResolveWallpaperSurfaceUseCase`); nur das Farb-Mapping ist
geleakt. → Helfer `fun LuminanceClassification.toSurface(context):
ResolvedBackground.SolidColor`.

---

## #9 — Rekursiver „tint jede TextView im Baum"-Walk (2×) · 🟢

`ColorCustomizationDialogFragment.kt:138-146` (`applyForegroundColorToLabels`)
und `LayoutCustomizationDialogFragment.kt:350-357`
(`applyForegroundColorRecursive`): gleiche Rekursion, differiert nur im
Skip-Prädikat (`id ==` vs `is MaterialButtonToggleGroup`). →
`ViewGroup.tintTextViews(color, skip: (View) -> Boolean)`.

---

## #10 — Rename-Dialog-Builder (2×) · 🟢

`CustomNamesActivity.kt:202-262` und
`AppContextMenuDialogFragment.kt:343-432`: beide bauen denselben
`EditText`-seeded `MaterialAlertDialogBuilder` mit Routing über
`RenameDecision.decide(...)`. Die *Entscheidung* ist schon geteilt
(`RenameDecision`), die ~40-LOC-Dialog-Konstruktion nicht. → Factory
`buildRenameDialog(context, app, onResult): AlertDialog`.

---

## #11 — `Fragment.showToastSafe()` fehlt · 🟢

`BaseActivity.showToastSafe` existiert für Activities; Fragments
(`FavoritesSortFragment:233-246`, `CustomNamesActivity:273-281`,
`OnboardingActivity:251-265`, inline in `UsageExportFragment`,
`SettingsFragment`, `HomeFragment`, `AppDrawerFragment`) rollen jeweils
`try { Toast.makeText(...).show() } catch (Throwable) { silentError(...) }`
selbst (alle mit derselben Samsung-IPC-Begründung im Kommentar). →
Extension `Fragment.showToastSafe(msg, duration)` in `ui/util`.

---

## #12 — Bottom-Sheet-Dialog-Window-Setup (2×) · 🟢

`ColorCustomizationDialogFragment` (`onStart:62-77` + `setupDragListener:148-178`)
und `LayoutCustomizationDialogFragment` (`configureDialogWindow:120-134` +
`setupDragListener:418-460`): gleiche Window-Config (transparenter
Background, `clearFlags(FLAG_DIM_BEHIND)`, `gravity = BOTTOM`, Breite =
`widthPixels * fraction`, `params.y`) + gleicher Drag-Handle-`OnTouchListener`.
Werte differieren (0.90/y=200 vs 0.95/y=150; Layout animiert zusätzlich
Alpha). → `DialogFragment.configureBottomSheetWindow(widthFraction,
yOffset)` + `View.enableDialogDrag(window)`.

---

## Nits (geringer Wert, der Vollständigkeit halber)

- **Purge-Key-Listen re-enumerieren Getter-Keys.**
  `SettingsRepositoryImpl.purgeRepository:318-343` listet alle 17
  Preference-Keys erneut (der `APP_DRAWER_MODE`-Kommentar :337 zeigt, dass
  das schon mal gebissen hat); analog `SwipeActionsRepositoryImpl:194-199`,
  `FabPositionRepositoryImpl:116-121`. → Purge-Set aus `PreferenceKeys`
  ableiten / gemeinsame `resettableKeys`.
- **Enum-`valueOf`-mit-Fallback in Read- und Import-Pfad.**
  sortOrder/favoritesAlignment/wallpaperSurfaceMode: gleiche Regel
  „unbekannter Enum-Name → skip" in `SettingsRepositoryImpl` (:110/:255/:270)
  **und** `BackupDataAssembler.performImport` (:313-334/:355-362). →
  `String?.toEnumOrNull<T>()` in `core/`.
- **Orphan-Cleanup near-identical über 4 Repos.**
  `cleanupFavoriteComponents` (`FavoritesRepositoryImpl:269-295`) und
  `cleanupHiddenComponents` (`HiddenAppsRepositoryImpl:212-232`) sind
  strukturidentisch (read set, `intersect(installed)`, shrink → log + write).
  Die Map/Slot-Varianten (`cleanupCustomNames`, `cleanupSwipeActions`) sind
  verschieden genug zum Belassen. → generisches `cleanupStringSet(key,
  installed)` auf der #2-Basis.
- **`getThemeColor(context, attr)`-Wrapper 2× redeklariert.**
  `SettingsFragment:621-622` und `ColorCustomizationDialogFragment:305-306`
  sind private Einzeiler über das schon geteilte `Context.resolveThemeColor`
  (`ui/util/ThemeColor.kt`), differieren nur im Fallback. → direkt
  `resolveThemeColor` mit Default-Overload.

---

## Bereits abgedeckt / bewusst akzeptiert (kein offener Fund)

- **DataStore-Schreibpfad** (`purgeRepository`-Duplikation): AUDIT.md §8.7
  → `DataStorePurge.safePurge`. Der Lesepfad (#1 hier) ist die noch offene
  Kehrseite.
- **Fein granulare Use-Cases** (`SetTextColorUseCase`,
  `GetSwipeLeftAppUseCase`, `Get*SettingUseCase` …): bewusstes Design mit
  Contract-Tests. Die ~3 byte-gleichen thin `Get*Setting`-Use-Cases wären
  *technisch* faltbar, aber ein Basis-Class würde Intent verschleiern und
  den (evtl. ohnehin redundanten) try/catch parametrisieren — **bewusst
  nicht empfohlen**.
- **Datei-Größen** (`HomeFragment`, `BackupRepositoryImpl`,
  `SettingsFragment`, `AppDrawerFragment`): AUDIT.md §7 — akzeptiert.
- **`FlowCollection.collectOnStarted`, `BaseViewModel.launchSafe`,
  `DomainMessageMappers`**: bereits guter SSOT — nicht anfassen. (Zwei
  bewusste Ausnahmen: `UsageExportFragment:107-139` inlinet die
  Zwei-Launch-Form, laut Helfer-Doc erlaubt; `LayoutCustomizationDialogFragment:465-493`
  hat ein eigenes `safeRun`/`launchSafe`, weil ein Fragment die Basisklassen
  nicht erreicht — Low-Value-Einzelfall.)

---

## Empfohlene Reihenfolge (falls umgesetzt wird)

Jeder Punkt ist Mehrfile-Refactoring → laut Git-Workflow auf einen eigenen
Branch, nie direkt auf `main`.

1. **#1 + #2 gemeinsam** (`refactor/datastore-flow-base`): eine
   `SharedDataStoreFlowRepository`-Basis ist der Enabler für beide, ~100+
   LOC weg, drift-fester Lesepfad analog zu `safePurge`. Größter
   Wert/Risiko-Quotient.
2. ~~**#5 + #4** (`refactor/backup-ssot`)~~ — #5 erledigt; **#4 entfällt**:
   weitgehend Wiederentdeckung von AUDIT-2 (`extractStrictSettings` gefaltet)
   und AUDIT-3 #3 (camelCase/snake-Bug gefixt, `validateJsonTypes` bewusst
   belassen). Siehe Cross-Reference-Note bei #4. Backup-Pfad ist vorwärts/
   rückwärts unit-getestet.
3. **#3** (`refactor/settings-repo-accessors`): größte Einzeldatei-Kompression,
   reine DRY.
4. **#6** (`refactor/app-selection-base`): UI-Gerüst-Faltung.
5. **#8–#12 + Nits**: opportunistisch, wenn man ohnehin in den Dateien ist.
6. **#7 nur mit Test-Netz** und nach Abgleich mit §9.8 — sonst als §7-Schuld
   dokumentieren.

**Reine Diagnose — es wurde kein Code geändert.**
