# AUDIT-19 — Perf: Bloat, Lag & verschwendete CPU-Zeit

> **Erzeugt** 2026-08-14 gegen `main` @ `3743c735` (Version 0.99.169 / code 189),
> auf die Frage: *„Wo entsteht in einem Launcher **Bloat, Lag und verschwendete
> CPU-Zeit** — echte wiederkehrende Rechen-/IO-Last und Cold-Start-Latenz, nicht
> Style?"*
> **Rückkehr zur Perf-Linse** nach der Nicht-Perf-Serie (AUDIT-17 Korrektheit,
> AUDIT-18 UX/a11y); die ersten drei Audits waren Perf, seither wurde
> **Laufzeit-Perf nicht mehr systematisch** geprüft.
> **Methode:** fünf parallele Subsystem-Scans (Cold-Start-Pfad, Coroutine-/Flow-
> Über-Berechnung, Home-Render/Wallpaper/Clock, App-Drawer/Adapter/Suche,
> DataStore-Read/Write), **danach jeder gemeldete Fund selbst am Code
> nachgelesen**. Gegengeprüft: `ACCEPTED_LIMITATIONS.md`, `KNOWN_ISSUES.md`,
> `CLAUDE.md` — dort als bewusst markierte Punkte sind ausgeschlossen. Für einen
> **bewusst minimalistischen text-only Launcher** wurde „mehr Caching/Deko" NICHT
> als Defekt gewertet; nur echte, mess­bare Rechen-/IO-/Latenz-Verschwendung.
>
> **Status: F1–F4 gemergt; F5 auf Branch umgesetzt (Merge ausstehend); F6 bewusst
> verworfen; F7–F8 offen.** Acht Funde in zwei Clustern (DataStore-Schreiblast,
> Wallpaper-Luminanz) plus Cold-Start-Latenz. **F1** (Usage-DataStore-Split),
> **F2** (`wallpaperState`-Projektion), **F3 + F4** (bounded Luminanz-Decode +
> Projektion + URI-Memo) sind nach `main` gemergt — F3/F4 zusätzlich per
> On-Device-A/B (Perfetto-Alternative: Decode-Count) bestätigt. **F5**
> (reaktive App-Liste statt Per-`onStart`-Enumeration) ist auf Branch
> `refactor/reactive-applist-onstart` implementiert (mit Tests), noch **nicht**
> gemergt. **F6** (`runBlocking`-Consent-Read) wurde nach Perfetto-Messung
> (~4 ms warm / ~9,7 ms disk-cold; alter Trace sagte <1 ms) **bewusst verworfen**
> — Aufwand/Nutzen zu gering. F7–F8 offen.

---

## 0. Ergebnis der fünf Scans

| Achse | Ergebnis |
|---|---|
| **Cold-Start-Pfad** | 🟠 Ein garantierter Main-Thread-Block (`runBlocking`-Consent-Read) + zwei Binder-Calls in `onCreate` + doppelte App-Enumeration beim Start. Kein `Thread.sleep`, ANR-Drain/Watchdog korrekt async. |
| **Coroutine / Flow** | 🟠 Ein übersehener AUDIT-14-Sibling (`wallpaperState` ohne Projection+Distinct) + doppelte Luminanz-Pipeline. Rest exzellent auditiert (flatMapLatest-Gating, terminale Dedup). |
| **Home-Render / Wallpaper** | 🔴 Unbounded Full-Res-Bitmap-Decode für 32×32-Sample (OOM-Risiko) + Re-Klassifizierung bei jedem Transform-Edit. `onDraw`/Clock/Battery/Gesten allokationsfrei & korrekt. |
| **App-Drawer / Adapter** | 🟢 Nahezu sauber — text-only (kein Icon-Load), DiffUtil, 150 ms-Debounce, precomputed Sort-Keys. Nur die `onStart`-Re-Enumeration + Micro-Allokationen. |
| **DataStore Read/Write** | 🔴 Usage-Timestamps im geteilten Settings-File → **jeder App-Start** re-serialisiert das ganze Blob + weckt ~15–20 Collector. Der strukturelle Kern-Sink. |

Die „checked clean"-Liste (§3) ist lang — der Code ist über mehrere AUDIT-Runden
stark durchoptimiert. Die Funde konzentrieren sich auf **zwei Cluster** (geteilter
DataStore, Wallpaper-Luminanz) plus **Cold-Start-Latenz**, keine flächige Lücke.

---

## 1. Findings

| # | Cluster | Ort | Was | Severity |
|---|---|---|---|---|
| **F1** | DataStore | `AppUsageRepositoryImpl.recordPackageLaunch` | Usage-Write pro App-Start re-serialisiert das **ganze** Preferences-Blob + Fan-out an ~15–20 Collector | `high` |
| **F2** | DataStore/Flow | `WallpaperRepositoryImpl.wallpaperState` | re-parst JSON + File-Stats bei **jedem** Launch, ×3 Collector — der eine AUDIT-14-Sibling ohne Projection+Distinct | `med–high` |
| **F3** | Wallpaper | `WallpaperBitmapLuminanceImpl.loadBitmap` | **unbounded** Full-Res-Decode nur für ein 32×32-Sample → OOM-Risiko + schwerer CPU-Decode | `high` |
| **F4** | Wallpaper/Flow | `ClassifyWallpaperUseCase` (+2 Consumer) | Klassifizierung feuert bei **jedem Transform-Edit** + läuft **2×** über zwei ungeteilte Pipelines, kein Memo | `med` |
| **F5** | Cold-Start | `MainActivity.onStart` → `refreshAllData` | volle `PackageManager`-Enumeration **+ 1 `loadLabel()`-IPC pro App** bei jedem Home-Tastendruck, beim Kaltstart doppelt | `med` |
| **F6** | Cold-Start | `CrashReportingBootstrap` / `ConsentBootstrap` | `runBlocking`-Consent-DataStore-Read **synchron auf Main** in `onCreate`, vor dem ersten Frame | `med` |
| **F7** | Cold-Start | `KolibriLauncherApp.registerSystemWallpaperColorsListener` | `WallpaperManager.getWallpaperColors` (Binder) **synchron auf Main** in `onCreate` | `med` |
| **F8** | Cold-Start | `LauncherViewModel.init` / `ClockDelegate.start` | eager Delegate-Init: Sticky-Battery-`registerReceiver` (Binder) auf Main + `Eagerly` `uiState` | `med–low` |

---

### F1 — Usage-Write re-serialisiert das ganze File pro App-Start · ✅ gemergt
`data/.../data/AppUsageRepositoryImpl.kt:95-123` (`recordPackageLaunch`) ·
`data/.../di/DataStoreModule.kt:52-55` (`provideSettingsDataStore`)

> **Umgesetzt & gemergt** (Branch `refactor/usage-datastore-split` → `main`):
> eigener `@UsageDataStore`-DataStore (`kolibri_usage`, `AppConstants.USAGE_DATASTORE_NAME`)
> nach dem Muster des Consent-Splits; `AppUsageRepositoryImpl` **und**
> `UsageExportRepositoryImpl` (beide fassen die `KEY_USAGE_PREFIX`-Keys an) auf
> den qualifizierten Store umgestellt. **Bewusst ohne Migration** (wie der
> Consent-Split): auf dem Update-Build startet der Usage-Store leer, die
> `TIME_WEIGHTED_USAGE`-Reihenfolge baut sich beim Nutzen wieder auf. Die alten
> Usage-Keys bleiben unangetastet im Settings-Store liegen (Dead Weight für
> Bestands-Installs, werden aber nicht mehr geschrieben → treiben die per-Launch-
> Churn nicht mehr). Auto-Backup unverändert (ganzes `datastore/` gesichert, neuer
> File wandert mit). Qualifier-Annotation ändert die direkte `FakeDataStore`-
> Konstruktion in den Tests nicht → bestehende Usage-Tests unberührt.

`recordPackageLaunch` macht bei **jedem** App-Start ein `dataStore.edit{}` auf den
*unqualifizierten* Settings-DataStore. Preferences DataStore serialisiert das
**komplette** Preferences-Blob pro `edit{}` (nicht inkrementell) — also bei jedem
Launch das ganze File: alle Settings + alle Favoriten + jeder Custom-Name +
Hidden-Set + Order-JSON + *jedes anderen App-*Usage-Timestamp-Set. Je mehr der
Nutzer den Launcher nutzt, desto größer wird jeder Per-Launch-Write.

Gleichzeitig re-emittiert `DataStore.data` bei diesem Write die volle
`Preferences` an **alle** lebenden Cold-Flow-Collector auf dem geteilten Store —
auf dem Home-Screen ~15–20 gleichzeitig (`GetDrawerAppsUseCase` kombiniert
sortOrder+hidden+customNames+usage; `GetFavoriteAppsUseCase`
favorites+hidden+order+customNames; `LayoutSettingsUseCase` ×5;
`ObserveUiColorsUseCase` ×3; `WallpaperRepositoryImpl.wallpaperState` → siehe F2).
Der häufigste Writer ist an das größte Payload und die breiteste Fan-out-Menge
gekoppelt.

**Fix:** Usage in einen **eigenen** DataStore-File auslagern (`@UsageDataStore`,
exakt der Präzedenzfall des Consent-Splits, `DataStoreModule.kt:44-70`). Das
isoliert den Per-Launch-Write auf ein kleines File und nimmt die Usage-Churn aus
jedem Settings-/Favoriten-/Names-Collector. **Einzelner wirkungsvollster Eingriff**
— löst mechanisch auch den Trigger von F2 und den Großteil der DataStore-Low-Notes.

### F2 — `wallpaperState` re-parst JSON + File-Stats bei jedem Launch, ×3 · 🔧 auf Branch umgesetzt

> **Umgesetzt** (Branch `fix/wallpaper-state-reparse`, **noch nicht gemergt**):
> `wallpaperState` projiziert jetzt via `filterToWallpaperKeys()` auf die sechs
> Wallpaper-Keys und `distinctUntilChanged` **vor** dem `parseWallpaperState`-`map`
> — Parse (JSON) + Per-Layer-`fileExists()`-Stat laufen nur noch bei echter
> Wallpaper-Änderung. Zwei Regressionstests (unrelated Key → kein Re-Emit/Re-Stat;
> Wallpaper-Key → doch). **Severity-Korrektur:** F1 hat den schlimmsten Trigger
> bereits entfernt — der Usage-Tick (pro App-Start) lag im geteilten Store und
> re-emittierte `wallpaperState`; seit dem Usage-Split feuert F2 nur noch bei
> **user-getriebenen** Settings-Writes (Favorit/Hidden/Name/Farbe/Layout/Order/
> FAB/Swipe), nicht mehr pro Launch. Damit real eher **Low/Konsistenz-Fix** als
> die ursprünglich notierte MED-HIGH — der eine übersehene AUDIT-14-Sibling wird
> jetzt konsistent mit den Geschwistern.
`data/.../data/WallpaperRepositoryImpl.kt:82-92` (Flow), `:98-159` (`parseWallpaperState`)

`wallpaperState` liegt auf demselben geteilten Settings-DataStore und ist der
**einzige** AUDIT-14-Sibling, der nie den Projection+Distinct-Fix bekam:

```kotlin
override val wallpaperState: Flow<WallpaperState> =
    dataStore.safeReadFlow("Error reading wallpaper preferences")
        .map { preferences -> parseWallpaperState(preferences) }   // kein distinct, keine Key-Projektion
```

`parseWallpaperState` ist nicht billig: Multi-Layer-`JSONArray`-Parse **plus**
`wallpaperFileManager.fileExists(uri)` (Filesystem-Stat) **pro Layer**. Der Flow
wird von **≥3 lebenden Collectorn** gleichzeitig gehalten (`ThemingDelegate.kt:62`
via `ObserveUiColorsUseCase`, `:67` via `ResolveWallpaperSurfaceUseCase`,
`WallpaperDelegate.kt:336` direkt). → jeder Usage-Tick (F1) = **3× (JSON-Parse +
Per-Layer-File-Stats)** für ein unverändertes Wallpaper. Das nachgelagerte
`distinctUntilChanged` in `ClassifyWallpaperUseCase` / `WallpaperDelegate` läuft
**nach** dem `.map` — es unterdrückt nur den Bitmap-Recompute, nicht den Parse.

Jeder Schwester-Flow wurde in AUDIT-14 gefixt (`usageFlow` projiziert die Key-
Untermenge, dann `distinctUntilChanged`; `favoriteComponentsFlow`, `hiddenAppsFlow`,
`customNamesFlow`, `favoriteComponentsOrderFlow` tragen alle `distinctUntilChanged`).
`wallpaperState` wurde übersehen.

**Fix:** Wie `usageFlow` — Wallpaper-Keys projizieren + `distinctUntilChanged`
**vor** dem Parse-`.map`:

```kotlin
dataStore.safeReadFlow(...)
    .map { prefs -> prefs.asMap().filterKeys { it.name in WALLPAPER_KEYS } }
    .distinctUntilChanged()
    .map { parseWallpaperState(it) }   // jetzt nur bei echter Wallpaper-Änderung
```

Ein bloßes trailing `distinctUntilChanged` nach dem aktuellen `.map` reicht
**nicht** — es dedupt nur den Output, der Parse+Stat läuft weiter bei jedem
fremden Write. (F1s Store-Split entfernt zusätzlich den Per-Launch-*Trigger*.)

### F3 — Unbounded Full-Res-Decode nur für ein 32×32-Sample · ✅ gemergt
`data/.../wallpaper/WallpaperBitmapLuminanceImpl.kt:95-99` (`loadBitmap`), `:107` (`classify`)

> **Umgesetzt & gemergt** (Branch `fix/wallpaper-luminance-perf` → `main`; per
> On-Device-A/B bestätigt):
> `loadBitmap` macht jetzt einen Zwei-Pass-Decode (`inJustDecodeBounds` →
> `inSampleSize`), die Power-of-two-Mathe als reine, JVM-testbare
> `luminanceInSampleSize` (`data/.../wallpaper/LuminanceDownsampling.kt`, Budget
> 256² px). Fällt bei unbekannten Bounds (`-1`) oder kleinen Bildern auf `1`
> (Full-Decode) zurück. Bewusst NICHT mit dem `:app`-Render-Decoder geteilt
> (anderes Modul, an Render-/Legacy-Budgets gekoppelt). Regression: reiner
> JVM-Test der Mathe (`LuminanceDownsamplingTest`) **plus** ein Verdrahtungs-Test
> im Robolectric-Impl-Test, der den Zwei-Pass pinnt (Bounds- + Decode-Pass = 2
> `openInputStream` — ein Revert auf Ein-Pass fällt auf 1 und wird rot). Die
> bestehenden Fixture-Tests (amoled/transparent) bleiben grün — der Median/
> Coverage bleibt stabil, weil ohnehin final auf 32×32 skaliert wird.

```kotlin
BitmapFactory.decodeStream(input)   // kein inSampleSize, kein bounds-Pass
// ... dann: Bitmap.createScaledBitmap(bitmap, 32, 32, true)
```

Das dekodiert das **ganze** Wallpaper in voller Auflösung (ARGB_8888), nur um
anschließend auf ein 32×32-Gitter zu sampeln. Beim in der Codebasis wiederholt
zitierten „POCO 108 MP"-Fall (gegen den der *Render*-Pfad via
`decodeBoundedWallpaperBitmap` bereits bounded ist) ist das eine ~400-MB-
Allokation + schwerer CPU-Decode — die **eine** Stelle, die das Bounding vergaß.
Echtes OOM-Risiko und der größte einzelne CPU/Memory-Peak dieses Bereichs.

**Fix:** Zwei-Pass-Decode wie `BoundedBitmapDecoder` — Bounds mit
`inJustDecodeBounds` lesen, `inSampleSize` auf ein kleines Ziel (≥ 32², z. B.
~128–256 px) berechnen, downsampled dekodieren, dann `createScaledBitmap` auf
32×32. Reduziert die Decode-Kosten um Größenordnungen ohne Änderung des Median-
Ergebnisses.

### F4 — Klassifizierung feuert bei jedem Transform-Edit + läuft doppelt · ✅ gemergt
`domain/.../usecase/ClassifyWallpaperUseCase.kt:82-85`, Consumer
`ObserveUiColorsUseCase.kt:35` + `ResolveWallpaperSurfaceUseCase.kt:26`

> **Umgesetzt & gemergt** (Branch `fix/wallpaper-luminance-perf` → `main`; per
> On-Device-A/B bestätigt):
> **F4.1** — `kolibriInternalFlow` projiziert jetzt vor dem `distinctUntilChanged`
> auf `pickDominantUri(state)` (`.map { … }.distinctUntilChanged().map { uri → … }`),
> Transform-only-Änderungen (gleiche URI) triggern keinen Decode mehr.
> Regressionstest in `ClassifyWallpaperUseCaseTest` (Transform-only → `compute`
> exakt 1×). **F4.2** — 1-Entry-URI-Memo im `@Singleton WallpaperBitmapLuminanceImpl`
> (Mutex, Lock spannt den Decode → zwei zeitgleiche identische Consumer-Aufrufe
> deduplizieren auf einen Decode); `null` wird mitgecacht (deterministisch pro
> URI). Memo-Tests im Robolectric-Impl-Test (gleiche URI → kein zweiter Open;
> andere URI → neuer Decode). Zusammen neutralisiert das Memo auch F3s
> redundante Re-Runs.

Zwei Defekte:

1. **Re-Fire auf Transform:** `distinctUntilChanged()` liegt auf dem **ganzen**
   `WallpaperState` (`:84`), aber die Luminanz hängt nur an der URI ab (bzw. am
   Bottom-Layer `imageUri`+`alpha`+`blendModeName`, `:108-119`). Da `WallpaperState`
   jedes Layers `scale`/`translateX`/`translateY` enthält, re-emittiert **jeder
   Pan/Zoom-Save** einen distinkten State → voller Decode (F3) bei praktisch jedem
   Gesten-Ende während des Wallpaper-Editierens.
2. **Doppelte Pipeline:** `ClassifyWallpaperUseCase` ist **nicht** `@Singleton` und
   `invoke()` liefert je Aufruf einen **frischen, ungeteilten** Cold-Flow. Sowohl
   `ObserveUiColorsUseCase` (Textfarbe) als auch `ResolveWallpaperSurfaceUseCase`
   (Drawer-Surface) injizieren es und sind in `ThemingDelegate.start()` live →
   dieselbe URI wird bei einer echten Änderung **2×** dekodiert + gescannt
   (`WallpaperBitmapLuminanceImpl` ist `@Singleton`, aber zustandslos → kein Memo).

**Fix:** Auf den Klassifizierungs-Key (URI + ggf. alpha/blend) projizieren **vor**
dem `distinct`, **und** `compute()` im `@Singleton WallpaperBitmapLuminanceImpl`
per URI memoisieren (1-Entry-Cache reicht). Der URI-Memo-Cache neutralisiert F4.2
(Doppel-Pipeline) und F4.1 (Re-Fire) zusammen und ist die billigste Einzeländerung.

### F5 — Volle `PackageManager`-Re-Enumeration bei jedem `onStart` · 🔧 auf Branch umgesetzt

> **Umgesetzt** (Branch `refactor/reactive-applist-onstart`, **noch nicht gemergt**):
> „beides" aus der Analyse — die reaktive Schicht **vervollständigt** und das
> Per-`onStart`-Enumerieren **gedroppt** (subsumiert die Stufe-1-Cold-Start-
> Dopplung). Konkret: (1) `PackageEvent.Changed` + `ACTION_PACKAGE_CHANGED` im
> Receiver-Filter → enable/disable jetzt reaktiv; (2) Locale via
> `KolibriLauncherApp.onConfigurationChanged` (feuert prozessweit) →
> `triggerAppsUpdate` über einen erweiterten Hilt-Entry-Point (Locale ist kein
> Package-Event, passt nicht in `PackageEvent`); (3) `MainActivity.onStart` ruft
> nur noch `refreshDynamicUiData()` (Clock). Damit deckt die reaktive Schicht
> install/uninstall/update (ADDED/REMOVED/ADDED-replacing) + enable/disable
> (CHANGED) + Locale ab; Prozess-tot-Änderungen fängt der Cold-Start-Prime. Der
> Collector ignoriert den Payload (jedes Event → Full-Refresh, RECONCILE_SPEC §9),
> `PACKAGE_CHANGED`-Geschwätzigkeit fängt die 250 ms-Debounce + `distinctUntilChanged`
> ab. Tests: `mapToPackageEvent(CHANGED)→Changed` + `handleReceive(CHANGED)`.
> **Restrisiko:** `PACKAGE_CHANGED` ist chatty — durch Debounce+Distinct beherrscht,
> aber ein On-Device-A/B auf der `drawer_apps_enumerate`-Slice (Foreground vorher/
> nachher) wäre der saubere Beweis.

`app/.../ui/main/MainActivity.kt:607-615` (`onStart` → `refreshAllData`) ·
`app/.../delegate/AppManagementDelegate.kt:96-103,220` ·
`data/.../data/InstalledAppsRepositoryImpl.kt:167-257`

`onStart()` ruft unbedingt `refreshAllData()` → `refreshInstalledApps()` →
`triggerAppsUpdate()`, was nach 250 ms Debounce eine volle `queryIntentActivities`
**plus ein `info.loadLabel(packageManager)`-IPC pro installierter App**
(`:248`) fährt — bei 100+ Apps 100+ Binder-Round-Trips, **bei jedem Home-
Tastendruck**. Beim Kaltstart doppelt: die `observeInstalledAppsUseCase`-
Subscription primed sofort einen Load, der über `merge(flowOf(Unit))` den Debounce
**umgeht** (`:144-148`), und `onStart` feuert einen zweiten Trigger.

Weitgehend verschwendet: der runtime `PackageUpdateReceiver`
(`KolibriLauncherApp.kt:178-179`, registriert für `PACKAGE_ADDED`/`REMOVED`) hält
die Liste **reaktiv** frisch, solange der Prozess lebt, und `appsStateFlow` bleibt
via permanenten Collector heiß. Die `onStart`-Re-Enumeration fängt real nur
Package-Änderungen ab, die bei **totem** Prozess passierten — für einen Launcher
selten. Läuft auf `Dispatchers.IO` (kein Main-Jank), aber verbrennt CPU/IPC/Akku
bei jedem Home-Press und kann auf dem IO-Dispatcher mit einem gleichzeitigen
Drawer-Open konkurrieren.

**Fix:** unbedingten `onStart`-App-Refresh droppen und auf den
`PackageUpdateReceiver` verlassen; falls ein Cold-Start-Safety-Re-Poll erwünscht
bleibt, auf **einmal pro Prozess** gaten (erster `onStart`) statt bei jedem.
Clock-Refresh unconditional lassen.

### F6 — `runBlocking`-Consent-Read blockiert Main in `onCreate`
`app/.../crashreporting/resilience/CrashReportingBootstrap.kt:164-168` (aus
`KolibriLauncherApp.kt:151-153`) · `data/.../ConsentBootstrap.kt:70-78`

Der Default-`readDecision`-Lambda macht `runBlocking { ConsentBootstrap.readDecision(app) }`
→ `consentDataStore.data.first()`: ein **synchroner Main-Thread-DataStore-First-
Read auf jedem Cold-Start**, ausgeführt in `Application.onCreate` **bevor** es
zurückkehrt, also vor dem ersten Frame. DataStores First-Read ist garantiert Disk-
I/O (open + read + Preferences-Deserialisierung des `acra_consent`-Files) plus
Coroutine-Spin-up; `runBlocking` parkt den Main-Thread bis fertig. Bereits als
`LaunchTrace.Names.COLD_START_CONSENT_READ` getract — also bekannt — aber weiter
auf dem kritischen Pfad. `MainActivity.checkAndShowCrashReportConsent`
(`:554-565`) bestätigt Consent ohnehin asynchron nach; die Kommentare nennen den
Bootstrap-Read einen „redundant backup", die Korrektheits-Kosten des Deferrings
sind also gering.

**Fix:** Read auf `applicationScope` (schon `Dispatchers.IO`,
`KolibriLauncherApp.kt:59`) verschieben und `reportPendingAnrsAsync` an dessen
Abschluss hängen, statt `runBlocking` auf Main.

### F7 — `WallpaperManager.getWallpaperColors` (Binder) auf Main in `onCreate`
`app/.../KolibriLauncherApp.kt:170-172,200-219`

`registerSystemWallpaperColorsListener()` läuft in `onCreate` auf dem Main-Thread
und macht `WallpaperManager.getInstance(this)` + `getWallpaperColors(FLAG_SYSTEM)`
(`:218`) — ein Binder-Call in `WallpaperManagerService` — synchron, bevor
`onCreate` zurückkehrt. Bereits getract (`COLD_START_WALLPAPER_COLORS`). Der
registrierte Listener allein würde die aktuellen Farben liefern; der eager
Initial-Poll ist der blockierende Teil.

**Fix:** Listener synchron registrieren (billig), aber den initialen
`getWallpaperColors`-Poll auf `applicationScope` (IO) verschieben. Das
`StateFlow.value =` des Signals ist idempotent (per bestehendem Kommentar), eine
etwas spätere erste Emission ist harmlos.

### F8 — Eager ViewModel-Init: Sticky-Battery-Binder + `Eagerly` `uiState`
`app/.../LauncherViewModel.kt:292-299` (`init`), `:247-251` (`uiState`) ·
`app/.../delegate/ClockDelegate.kt:62-82,166-180` · `app/.../ui/base/BaseActivity.kt:60,87`

`LauncherViewModel` wird zuerst konstruiert, wenn `BaseActivity.onCreate`
`viewModel.event` berührt (`:60/87`), sein `init{}` läuft also früh auf dem Main-
Thread. `init` ruft eager `.start()` auf allen sechs Delegates; `ClockDelegate.start()`
macht synchrones `updateTimeAndDate()` + `getInitialBatteryState()`, letzteres ein
`registerReceiver(null, ACTION_BATTERY_CHANGED, …)`-Sticky-Read (`:166-173`) — ein
Binder-Round-Trip zu `system_server`, auf Main. Zusätzlich nutzt `uiState`
`SharingStarted.Eagerly` (`:247-251`), startet die 4-fach-`combine` sofort
unabhängig von Subscribern. Modest, aber Main-Thread-Binder + eager Flow-Wiring in
denselben Frames wie F5–F7.

**Fix:** Sticky-Battery-Read über den bereits vorhandenen async-Battery-Pfad
(`onResume`); `SharingStarted.WhileSubscribed` fürs `uiState` erwägen, passend zu
den anderen Flows.

---

## 2. Low- / Micro-Notes (festgehalten, nicht als Fix eingeplant)

- **`SettingsRepositoryImpl.valueFlow`/`enumFlow` ohne `distinctUntilChanged`**
  (`SettingsRepositoryImpl.kt:115-126`; nur `sortOrderFlow` hat es, `:139-141`,
  mit expliziter AUDIT-14-F2-Begründung „die anderen sind UI-cheap"). Auf dem Home-
  Screen re-laufen ~8 dieser Maps pro geteiltem Store-Write; die terminalen
  `StateFlow`s dedupen per Equality → **kein** Downstream-Render, nur die billige
  Single-Key-Map re-läuft. F1s Store-Split entfernt den Per-Launch-Trigger ohnehin.
  Bewusst gewählt, nicht separat lohnend.
- **Drawer `isAutoLaunchEnabled()` als frischer `.first()` pro Filter-Pass**
  (`AppDrawerFragment.kt:486-495`) — spinnt pro debouncetem Keystroke eine frische
  Flow-Collection auf, nur um ein selten änderndes Boolean zu lesen. Sollte aus dem
  bereits kollektierten `HomeSettings`/`_homeSettings`-StateFlow lesen
  (`AppManagementDelegate.kt:82`). DataStore serviert aus dem In-Memory-Cache →
  keine Disk-I/O, daher `low`.
- **`submitList(list.toList())`** (`AppDrawerFragment.kt:536`) — redundante
  `ArrayList`-Kopie pro Submit/Keystroke; die Filter-Ausgabe ist bereits ein
  frischer, immutabler Snapshot. `list` direkt submitten.
- **`AppDrawerAdapter.updateName`** (`AppDrawerAdapter.kt:235-239`) setzt
  `maxLines = 1` + `ellipsize = END` pro Bind/Payload — konstant pro Row, gehört in
  `onCreateViewHolder`/XML (einmal pro View statt pro Bind). Gleiches für den
  konstanten `setShadowLayer` in `updateColors`. Micro.

---

## 3. Gegengeprüft & sauber (Auszug)

- **App-Drawer:** text-only (`AppInfo` trägt **keinen** `Drawable`, Rows sind reine
  `TextView`s → **kein** Icon-/`loadIcon`-Work auf Bind); `ListAdapter` + `DiffUtil`
  (Diff auf Background-Thread, `notifyDataSetChanged` nur als Catch-Fallback);
  Suche 150 ms-debounced; Sort/Normalisierung precomputed (`displayNameLower`,
  `componentName` als Body-`val`); `itemAnimator = null` + `setHasFixedSize(true)`;
  Sortierung auf `Dispatchers.Default`, per `flatMapLatest` auf Sort-Mode gegated →
  Usage-Ticks re-sortieren in ALPHABETICAL nicht (AUDIT-14 F2).
- **Home-Render:** `ZoomableImageView.onDraw` (`:799-845`) allokationsfrei
  (reused `drawMatrix`/`bitmapPaint`/`selectionBounds`/`tmpMatrix`), läuft nur auf
  `invalidate()`, nie im Idle; `WallpaperViewBinder.applyFullRebuild` per
  `WallpaperViewDiff` auf echte Layer-ID-Änderungen gegated → unveränderte States
  bleiben `Noop`/`UpdatePropertiesOnly`, **kein** Re-Decode; Render-Pfad nutzt den
  bounded `decodeBoundedWallpaperBitmap` + Latest-Wins-Cancel via
  `WallpaperRenderScheduler`.
- **Clock/Battery:** tickt per `ACTION_TIME_TICK` (Minute, **nicht** Sekunde),
  Formatter gecacht + nur bei Locale/12-24h-Wechsel neu (`ClockDelegate.kt:141-152`);
  Battery-`StateFlow` dedupt identische Prozent, HomeFragment mappt per-Feld mit
  `distinctUntilChanged` → häufige Charging-Ticks lösen keine Redraws aus.
- **Flow-Topologie:** `GetDrawerAppsUseCase` (flatMapLatest-Gating + terminale
  Dedup), `GetFavoriteAppsUseCase` (terminal distinct + `flowOn(Default)`),
  `rawAppsFlow` als geteilter `StateFlow`, `AppSearchFilter` folded die Query einmal
  gegen precomputed `displayNameLower`. Kein `Eagerly`-vs-`WhileSubscribed`-Misuse
  mit Restart-Churn, kein unnötiger `flatMapLatest`-Cancel jenseits des Sort-Mode-
  Switch.
- **DataStore:** kein `shareIn(replay=1)`-Stale-Trap (bewusst entfernt,
  `DataStoreReadFlow.kt:51-56` dokumentiert warum); `.first()`-Point-Reads auf
  IO/suspend, nicht Main; `edit{}`-Transaktionen minimal, Batch-Pfade vorhanden
  (`setCustomNamesInBatch`, `updateComponentVisibilities`); Favoriten/hidden/order/
  custom-names/usage tragen `distinctUntilChanged` gegen die geteilten Store-Cross-
  Emissions (die Fan-out-Mitigation ist da — nur die Write-Amplification-Hälfte F1
  fehlt).
- **Cold-Start:** kein `Thread.sleep` auf dem Startpfad; ANR-Drain
  (`reportPendingAnrsAsync`) + Watchdog-Start korrekt async/post-`onCreate`;
  `WallpaperFileManager`-Blocking-I/O (`gcOrphans`/`deleteFile`/`clearAll`) korrekt
  auf `ioDispatcher`, `gcOrphans` einmal pro Prozess.

---

## 4. Fazit

Rückkehr zur Perf-Linse nach der Nicht-Perf-Serie: das Grundgerüst ist über
mehrere Runden stark durchoptimiert (§3 ist lang), die Funde bündeln sich in
**zwei Clustern** plus Cold-Start-Latenz:

- **DataStore-Schreiblast** (F1/F2) — der geteilte Settings-Store koppelt den
  häufigsten Writer (Usage, pro App-Start) an das größte Payload und ~15–20
  Collector; `wallpaperState` ist der eine übersehene AUDIT-14-Sibling.
- **Wallpaper-Luminanz** (F3/F4) — unbounded Full-Res-Decode (OOM-Risiko),
  redundant bei jedem Transform-Edit, und doppelt über zwei ungeteilte Pipelines.
- **Cold-Start-Latenz** (F5–F8) — ein garantierter Main-Thread-Block
  (`runBlocking`-Consent), zwei Binder-Calls in `onCreate`, doppelte App-
  Enumeration.

**Empfohlene Umsetzungs-Reihenfolge (höchster Hebel, minimales Risiko):**

1. **F1** — Usage-DataStore-Split. Erschlägt die Write-Amplification **und** den
   Per-Launch-Trigger von F2 auf einen Schlag. (Branch z. B. `refactor/usage-datastore-split`)
2. **F3 + F4** — bounded Luminanz-Decode + URI-Memo. Killt den größten
   CPU/Memory-Peak. (Branch z. B. `fix/wallpaper-luminance-perf`)
3. **F6** — Consent-Read von Main verschieben. Der eine garantierte Main-Thread-
   Block vor dem ersten Frame. (Branch z. B. `fix/cold-start-consent-read`)

F2, F5, F7, F8 danach als Follow-ups im jeweiligen Cluster-Branch.

**Status: nichts umgesetzt** — reine Read-Only-Analyse, kein Code verändert. Jeder
Cluster als eigener Branch, sobald Umsetzung freigegeben ist.
