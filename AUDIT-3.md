# AUDIT-3 — Whole-Codebase Correctness Audit

> **Erzeugt** 2026-07-21 durch einen Claude-Opus-4.8-Multi-Agent-Audit.
> **Methode:** 5 Bereichs-Slices (`:domain`, `:data`, ViewModels/Coroutines,
> Fragments/Activities/Lifecycle, Build/DI/ACRA/Contracts); der Lifecycle-Slice
> fächerte in drei Sub-Agenten auf (Home/Views, Activities/App-Entry,
> Main/Delegates, Drawer/Dialogs). Jeder Agent verifizierte jedes Finding am
> echten Code und musste ein konkretes Trigger→Fehler-Szenario liefern;
> Style-Nits und dokumentiert-bewusste Muster (Rule-5/7/8/9-Ausnahmen,
> `ACCEPTED_LIMITATIONS.md`, `KNOWN_ISSUES.md`, ADR-only-Contracts) wurden
> verworfen.
> **Reine Diagnose — es wurde kein Code geändert.**
>
> **Selbst-Verifikation (2026-07-21):** Alle High/Medium-Findings wurden vom
> Orchestrator unabhängig am Quellcode nachgeprüft (nicht nur aus den
> Agenten-Reports übernommen). Ergebnis: alle acht bestätigt; zwei
> Severity-Korrekturen — #4 ist kein user-sichtbarer Bug, sondern
> disconnected/vestigial (Intent-Frage), #6 ist transient/self-correcting und
> auf 🟢 herabgestuft. Verifikations-Notiz je Finding unten.
>
> Anders als AUDIT-2 (38 Findings, ausnahmslos `low`) hat dieser Lauf den Fokus
> auf **Korrektheit** statt Aufräum-Schulden gelegt: Config-Change-Verhalten,
> Flow-/Coroutine-Semantik, Backup-Round-Trip, Lifecycle-Leaks, Crash-Pfade.
> Ergebnis (nach Selbst-Verifikation): **3 echte Bugs (High: #1–#3), 1 High-gelistete
Intent-Frage (#4, kein Fix-Kandidat), 3 Medium (#5, #7, #8), 9 Low (inkl. #6),
3 Doc-Drift.**

> **✅ RESOLUTION (2026-07-21):** Alle 19 Findings abgearbeitet, in vier
> Branches nach `main` gemergt und gepusht. #4 wurde per Git-Archäologie
> als Intent-Frage zugunsten „löschen" aufgelöst (der dynamische
> `maxFavoritesOnHome`-Wert wurde bewusst mit dem SplitScreen-Refactor
> `b35f98e` aufgegeben) und die Konstante von `MAX_FALLBACK_FAVORITES_ON_HOME`
> auf `MAX_FAVORITES_ON_HOME` umbenannt. High/Medium jeweils mit Regressions-
> Tests; #2 zusätzlich mit Robolectric-Tests; #3 negativ-verifiziert. #8
> defensiv gefixt (Precondition in Produktion nicht erreichbar). Zwei
> Scope-Entscheidungen: bei #3 bleibt `validateJsonTypes` unangetastet
> (recover-vs-reject ist eine eigene Frage) und „both keys present" gewinnt
> camelCase; bei #7 wurde der Event-Bus auf einen `Channel` umgestellt
> (single-consumer, kein Re-Delivery). Branches:
> `chore/audit3-drop-dead-maxfavorites-flow` (#4),
> `fix/audit3-config-loss-and-backup` (#1–#3), `fix/audit3-medium` (#5–#8),
> `fix/audit3-low-and-docs` (#9–#19).

---

## Executive summary

Der Code ist weiterhin in sehr gutem Zustand — Hilt-Graph, Versions-Pins,
Modul-Richtung, ACRA-Reihenfolge, `CrashReportLimiter`-Mathe, ColorMath,
CoerceExtensions, Contract-Triples und die durchgängige
ViewBinding-/`viewLifecycleOwner`-Disziplin sind sauber. Aber dieser Lauf hat,
anders als Runde 2, **echte Korrektheitsdefekte** gefunden.

Zwei systemische Muster:

- **Config-Change ist die schwächste Stelle.** Zwei ViewModels verlieren
  nicht-committete Auswahl bei Rotation, weil `initialize()` unbedingt in
  `onCreate` läuft (der `OnboardingViewModel` zeigt direkt nebenan das korrekte
  `isInitialized`-Muster). Mehrere Dialoge werden bei Rotation nicht dismissed →
  `WindowLeaked`.
- **Serialisierungs-Asymmetrie im Backup.** Geschrieben wird camelCase (kein
  `@SerialName`), der Strict-Fallback-Pfad liest snake_case → auf dem
  Fallback-Pfad gehen alle Skalar-Settings still verloren.

Ein Finding ist eine mutmaßliche **Feature-Regression** aus dem
Split-Screen-Refactor (`maxFavoritesOnHome`-Limit ist tot) und braucht eine
Intent-Entscheidung, bevor Code angefasst wird.

---

## Coverage / Statistik

| Slice | Verifizierte Findings | davon High/Med |
|---|---:|---:|
| `:domain` (core, ~50 use cases, models) | 3 | 0 |
| `:data` (repos, DataStore, backup, wallpaper) | 3 | 1 |
| ViewModels / Coroutines / Delegates | 4 | 2 |
| Fragments / Activities / Lifecycle | 8 | 3 |
| Build / DI / ACRA / Contract-Tests | 4 | 0 (nur Doc-Drift) |
| **Summe** | **~19 sachlich** (Überschneidungen zusammengeführt) | — |

---

## 🔴 High — echte Bugs

### 1. Config-Change verliert nicht-committete Auswahl (zwei ViewModels)

- **`app/.../ui/hiddenapps/HiddenAppsActivity.kt:70`** + **`HiddenAppsViewModel.kt:83`**
- **`app/.../ui/swipeactions/SwipeActionsActivity.kt:47`** + **`SwipeActionsViewModel.kt:100`**

`viewModel.initialize()` wird unbedingt in `onCreate` aufgerufen — kein
`savedInstanceState`-Guard, kein `isInitialized`-Flag im VM. Bei einem
Config-Change (Rotation, Dark-Mode, Locale, Font-Size, Split-Screen) ruft die
neu erzeugte Activity `initialize()` erneut auf dem **retained** VM auf, das
`selectedComponents.value = initialHiddenComponents` (bzw. die Swipe-Slots) aus
dem Repo neu liest.

**Trigger → Fehler:** User öffnet Hidden Apps, toggelt mehrere Apps (nur
in-memory `selectedComponents`; Persistenz passiert ausschließlich in
`onDoneClicked`), rotiert das Gerät → alle noch nicht committeten Toggles sind
still weg. Identisch für Swipe-Slot-Zuweisungen.

**Belegt als Versehen:** `OnboardingViewModel.loadInitialData()` macht denselben
Load, ist aber korrekt mit `isInitialized`-Flag geschützt und restauriert
`launchMode` aus `savedInstanceState`. Die Asymmetrie ist der Beweis.
**Fix:** gleichen `isInitialized`-Guard in beide VMs (oder `initialize()` auf
`savedInstanceState == null` gaten).

> **✅ Verifiziert:** HiddenApps — `onAppToggled` (`HiddenAppsViewModel.kt:116`)
> mutiert nur in-memory `selectedComponents.value`, Persistenz allein in
> `onDoneClicked:128` (`updateHiddenAppsUseCase`); `initialize:100` resettet
> `selectedComponents.value = initialHiddenComponents`, ohne Guard. SwipeActions
> identisch: Slot-Zuweisung mutiert `swipeLeft/RightComponent.value`
> (`:154/162/174/175`), Persistenz nur in `onDoneClicked:185/186`, `initialize:117/118`
> überschreibt. `OnboardingViewModel:107` hat den `isInitialized`-Guard — Beweis
> des Versehens.

### 2. `AppContextMenuDialogFragment` crasht statt sauber abzubrechen

- **`app/.../ui/appcontextmenu/AppContextMenuDialogFragment.kt:182`** (Ursache in
  `onCreate`, Zeilen 118–144)

Die `dismiss()`-Aufrufe bei Parse-Fehler in `onCreate` verhindern **nicht**,
dass `onCreateView`/`onViewCreated` in derselben Fragment-Transaktion laufen
(das Remove ist eine nachgelagerte Transaktion). Auf den Fehlerpfaden bleibt
`appInfo` (`lateinit var`) uninitialisiert, und `onViewCreated`
(`binding.appNameText.text = appInfo.displayName`, plus `loadActions()` über
`menuContext`) wirft eine ungefangene `UninitializedPropertyAccessException`.

**Trigger → Fehler:** ein malformed/fehlendes `ARG_APP_INFO`-Parcelable —
z. B. korruptes `savedInstanceState` beim Process-Death-Restore oder ein
Parcelable-Class-Mismatch — lässt den `?: run { dismiss(); return }`-Zweig (oder
den `catch` bei 136) feuern; der Dialog crasht dann in `onViewCreated` statt
sauber zu dismissen. Untergräbt den „CRASH-SAFE"-Vertrag im Klassen-Header.
**Fix:** ein Guard-Flag in `onViewCreated` konsultieren und dort früh
zurückkehren.

> **✅ Verifiziert:** `appInfo` ist `private lateinit var` (`:102`); die
> Null-/Catch-Pfade in `onCreate` (`:125-129`, `:136-143`) rufen `dismiss()`,
> lassen aber `onViewCreated` in derselben Transaktion laufen → `:182`
> `appInfo.displayName` wirft `UninitializedPropertyAccessException`.
> **Trigger-Häufigkeit gering** (korruptes/fehlendes `ARG_APP_INFO`, z. B.
> Process-Death-Restore) — der Crash-Mechanismus selbst ist aber real.

### 3. Backup-Strict-Fallback setzt alle Skalar-Settings still auf Default

- **`data/.../BackupSerializer.kt:324-357`** (`extractStrictSettings`) +
  **`:365-433`** (`validateJsonTypes`)

Die App serialisiert mit `json.encodeToString` und **ohne `@SerialName`**, also
sind die JSON-Keys die Kotlin-Property-Namen in **camelCase** (`"textColor"`,
`"swipeLeftApp"`, `"chipBackgroundColor"`, `"layoutScale"`, …). Die
`@JsonNames("text_color")`-Annotationen in `BackupData.kt` wirken nur beim
*Lesen*, nicht beim Schreiben. Der org.json-Strict-Parse-/Merge-Pfad und der
Type-Validator schlagen aber für jedes Skalar-Feld **nur snake_case** nach.

**Trigger → Fehler:** jedes Backup, das den kotlinx-Pfad in eine
`SerializationException` kippt (ein hand-editiertes/korruptes camelCase-Feld
genügt), fällt auf `tryStrictParsing` → `parseStrictly`. Die Collections
überleben (deren Helper lesen korrekt camelCase: `"favoriteComponents"`,
`"customAppNames"`, `wallpaperLayers`), aber **jedes Skalar-Setting** —
`textColor`, `chipBackgroundColor`, alle Skala-Floats, `swipeLeft/RightApp`,
sämtliche Gesten/QoL/Power-User-Booleans — wird über snake_case-Keys gelesen,
die nicht existieren, und fällt still auf den Base-Default. Sekundäreffekt:
`validateJsonTypes` (der Infinity/NaN/Type-Confusion-Guard) ist gegen die
eigenen (camelCase) Backups ein No-Op — er feuert nur bei snake_case-Input.
**Fix:** die Strict-Path-Keys auf camelCase umstellen (oder beide Formen
akzeptieren, passend zu `@JsonNames`).

> **✅ Verifiziert (stärkster Beleg):** `Json { }` in `BackupSerializer.kt:48-52`
> setzt **keine** `namingStrategy` → `encodeToString` schreibt die
> Property-Namen = camelCase. `LauncherSettings`/`WallpaperLayerBackup`
> (`domain/.../BackupData.kt`) tragen **nur `@JsonNames`, kein `@SerialName`** —
> `@JsonNames` ist reiner Lese-Alias, ändert die Ausgabe nicht. Dass genau
> `sortOrder`/`favoritesAlignment`/`wallpaperSurfaceMode` im Strict-Path schon
> einen camelCase-First-Fallback haben (`BackupSerializer.kt:340-348`) belegt,
> dass das Problem teilweise erkannt, aber für die übrigen ~20 Skalar-Felder nicht
> nachgezogen wurde. **Trigger-gated:** feuert nur, wenn der kotlinx-Primärpfad
> vorher wirft (Type-Mismatch / fehlendes `settings` / struktureller
> JSON-Defekt) — der Happy-Path ist verlustfrei.

### 4. `maxFavoritesOnHome` — kein Writer (statisches Limit, Intent-Klärung nötig)

- **`app/.../ui/main/delegate/AppManagementDelegate.kt:88`** (Deklaration,
  init = `MAX_FALLBACK_FAVORITES_ON_HOME` = 500), gelesen bei **`:146`**,
  exponiert über **`LauncherViewModel.kt:257`**

Repo-weit gibt es **keinen Writer** auf `_maxFavoritesOnHome`. Die StateFlow
wird von keinem Fragment collected; ihr einziger interner Consumer ist
`onToggleFavorite`, das `maxFavoritesOnHome.value` als `currentMaxFavorites` in
`ToggleFavoriteUseCase` reicht.

**Folge:** der `ToggleFavoriteUseCase.Result.Error.LimitReached`-Zweig feuert
konstant erst bei 500 → das „Max Favorites on Home"-Limit ist **statisch** statt
dynamisch. Git-History (`89856f0 "Dynamisches MAX für die HomeScreen Favoriten"`
gefolgt von `b35f98e "SplitScreen"`) deutet auf ein dynamisches Max, das der
Split-Screen-Refactor abgeklemmt hat — der Setter, der die StateFlow einst
speiste, ist weg.

> **✅ Verifiziert:** `_maxFavoritesOnHome` (`AppManagementDelegate.kt:88`) hat
> repo-weit **keinen** `.value=`-Writer — die StateFlow-Indirektion ist
> vestigial. **Aber kein user-sichtbarer Bug:** der Favoriten-Cap ist ohnehin
> zusätzlich hart in `GetFavoriteAppsUseCase.kt:124/158` als `.take(500)`
> durchgesetzt; es fehlt nur der *dynamische* Max. Daher **Intent-Frage, kein
> reiner Fix-Kandidat:** neu verdrahten (aus `_homeSettings` / Fragment-Messung)
> **oder** Flow + Indirektion löschen und Konstante inlinen. **Kein Code-Fix vor
> dieser Klärung.**

---

## 🟡 Medium

### 5. `MainActivity.confirmRemoveWallpaper()` — untracked Dialog leakt das Fenster

- **`app/.../ui/main/MainActivity.kt:958-965`**

Jeder andere home-verankerte AlertDialog wird in `currentDialog` getrackt und in
`onDestroy` (Zeile 650) dismissed; `confirmRemoveWallpaper` ist die einzige
Ausnahme — `.show()` auf einem frischen `MaterialAlertDialogBuilder` ohne
Zuweisung an `currentDialog`. **Trigger:** Long-Press → „Remove wallpaper", dann
Config-Change/`finish()` → `onDestroy`'s `currentDialog?.dismiss()` ist null →
`WindowLeaked`, Activity-View-Tree bleibt bis GC gehalten. **Fix:** wie
`showAccessibilityDialog`/`showDialog` an `currentDialog` zuweisen (vorherigen
dismissen).

> **✅ Verifiziert:** `confirmRemoveWallpaper` (`MainActivity.kt:958-965`) ruft
> `.show()` **ohne** Zuweisung an `currentDialog` — im Gegensatz zu `:855`
> (`currentDialog = builder.show()`) und `:903-922` (`currentDialog = …` +
> `setOnDismissListener`-Null). `onDestroy:650` dismissed nur `currentDialog` →
> dieser Dialog leakt sein Fenster.

### 6. `HomeSettings()`-Default `sortOrder` widerspricht dem echten Default  🟢 (herabgestuft: transient)

- **`domain/.../model/HomeSettings.kt:4`**

Default `sortOrder = SortOrder.ALPHABETICAL`, aber projektweit gilt
`AppConstants.DEFAULT_SORT_ORDER = SortOrder.TIME_WEIGHTED_USAGE`
(`core/AppConstants.kt:80`). `AppManagementDelegate.kt:82` seedet seine StateFlow
mit einem bare `HomeSettings()`, `sortOrder`-LiveData wird daraus abgeleitet
(`:84`). **Folge:** beim Cold Start — bevor `ObserveHomeSettingsUseCase` das
erste Mal emittiert — meldet jeder Consumer dieser LiveData ALPHABETICAL,
während effektiv TIME_WEIGHTED_USAGE sortiert. State-Reporting-Mismatch, **keine**
Fehlsortierung der Liste selbst (die liest `settingsRepository.sortOrderFlow`
direkt). **Fix:** bare Default auf `AppConstants.DEFAULT_SORT_ORDER` setzen.

> **✅ Verifiziert & herabgestuft auf 🟢:** `_homeSettings` hat einen Writer
> (`AppManagementDelegate.kt:111`, `_homeSettings.value = settings`), also ist
> der Mismatch **transient** — er korrigiert sich mit der ersten Emission von
> `ObserveHomeSettingsUseCase` selbst. Nur das Cold-Start-Fenster betroffen,
> keine dauerhafte Fehlmeldung. Trotzdem ein Ein-Zeilen-Fix wert (Konsistenz).

### 7. Event-Bus dropt Nav-Events ohne aktiven Collector

- **`app/.../ui/base/BaseViewModel.kt:34`** (`_event = MutableSharedFlow<E>()`,
  replay=0/buffer=0)

Collectors hängen via `collectOnStarted` (`repeatOnLifecycle(STARTED)`) — in
jedem Fenster mit null Subscribern verwirft `emit()` den Wert sofort statt zu
suspendieren/puffern. **Trigger:** ein Nav-/Toast-Event, das *nach* einem
suspendierenden Use-Case emittiert wird, während der Collector gerade
(Config-Change/Stop) abgebaut ist — z. B. `OnboardingViewModel.onDoneClicked`
(`NavigateToMain` nach `completeOnboardingUseCase`) oder
`HiddenAppsViewModel.onDoneClicked` (`NavigateUp` nach
`updateHiddenAppsUseCase`) → Event verloren, User bleibt auf dem Screen hängen.
Selten (die meisten Events feuern synchron im Click-Handler unter STARTED), aber
strukturelles Risiko. **Fix:** kleines `extraBufferCapacity` mit `DROP_OLDEST`
oder `replay=1` für Nav-Events.

### 8. ZIP-Export verwirft doppelt referenzierte Bild-Layer beim Restore

- **`data/.../BackupRepositoryImpl.kt:201-217`** (`writeZipBackup`)

Dedup keyt auf `file.absolutePath`: referenzieren zwei Layer dieselbe interne
Datei, werden die Bytes des zweiten übersprungen (`dedupSet.add` == false), der
Layer aber trotzdem als `imageFileName = "wallpapers/layer_N.img"` gestempelt.
Dieser Eintrag existiert im ZIP nie → beim Import kann `resolveZipImages` ihn
nicht mappen, der Layer behält seine Origin-Device-`file://`-URI,
`importMultiLayerWallpaper` findet sie unzugänglich, der Layer wird still
verworfen. Erfordert zwei Layer, die sich eine interne Datei teilen (z. B. ein
duplizierter Layer). **Fix:** stamped `imageFileName` auf den bereits im ZIP
vorhandenen Eintrag mappen statt einen neuen zu vergeben.

> **✅ Verifiziert:** `BackupRepositoryImpl.kt:206-210` — `entryName` ist
> index-basiert (`wallpapers/layer_$index.img`, immer eindeutig), aber
> `dedupSet.add(file.absolutePath)` (`:207`) verhindert das Schreiben der Bytes
> für den zweiten Layer, während `layer.copy(imageFileName = entryName)` (`:210`)
> ihn trotzdem stempelt → verwaister ZIP-Verweis.

---

## 🟢 Low / Aufräumen

9. **`data/.../FavoritesOrderRepositoryImpl.kt:287-303`** (`removeComponentFromOrder`)
   — liest `favoriteComponentsOrderFlow.first()` außerhalb der Transaktion, dann
   `saveOrder()`. Genau der Stale-Snapshot-Race, den `FavoritesRepositoryImpl`
   und `HiddenAppsRepositoryImpl` bewusst *in* `dataStore.edit {}` gezogen haben.
   Impact begrenzt (Order-Writes sind in der Praxis UI-serialisiert).

10. **`domain/.../model/BackupData.kt:58`** (`toLayerState()`) —
    `id = id ?: "layer_${System.currentTimeMillis()}_restored"`. Ein
    Multi-Layer-Backup mit lauter `null`-IDs (Legacy-JSON) im selben
    Millisekunden-Fenster erzeugt identische IDs, verletzt die
    prozessweit-eindeutige Invariante von `WallpaperLayerState.newId()`.
    Mutation-Helper keyen auf Index (contained), aber ID-basierte DiffUtil/stable
    IDs würden kollabieren. **Fix:** Atomic-Counter-Suffix wie in `newId()`.

11. **Sub-Frame-Flash** in **`app/.../ui/hiddenapps/HiddenAppsViewModel.kt:97-100`**
    und identisch **`SwipeActionsViewModel.kt:114-118`** — `initialize()` setzt
    `allAppsMasterList.value` und suspendiert dann bei `…UseCase().first()`, bevor
    `selectedComponents` gesetzt wird. Der init-block-`combine`-Collector (gleicher
    Main-Dispatcher) emittiert dazwischen einen Teilzustand (volle Liste, leere
    Selektion) → kurzer „nichts ausgewählt"-Flash beim Öffnen. **Fix:** beide Werte
    zuerst laden, dann Master-Liste + Selektion ohne Suspend dazwischen zuweisen.

12. **Dialog-Window-Leaks bei Config-Change** (nicht in Feld getrackt / nicht in
    `onDestroyView` dismissed): **`app/.../ui/settings/SettingsFragment.kt`**
    (Factory-Reset / Calendar-Rationale / Forced-Consent),
    **`app/.../ui/backup/BackupFragment.kt:208`** (Import-Options),
    **`app/.../ui/usageexport/UsageExportFragment.kt:88`** (Import-Mode). Rotation
    mit offenem Dialog → „Activity has leaked window". `CustomNamesActivity` zeigt
    das korrekte `currentDialog`-Muster.

13. **Adapter nicht gelöst in `onDestroyView`**:
    **`app/.../ui/favorites/FavoritesSortFragment.kt:248`** (zusätzlich
    `ItemTouchHelper` nicht detached, `adapter`/`itemTouchHelper`-Felder nicht
    genullt) und **`app/.../ui/appcontextmenu/AppContextMenuDialogFragment.kt:459`**.
    `AppDrawerFragment.onDestroyView` (`adapter = null`) ist die Referenz. Kleine,
    beschränkte Leak-Fläche.

14. **`app/.../ui/colorcustomization/ColorCustomizationDialogFragment.kt:102/248`**
    — `observeWallpaperSurface()`/`observeChanges()` collecten via rohem
    `viewLifecycleOwner.lifecycleScope.launch { flow.collect { … } }` **ohne**
    `repeatOnLifecycle(STARTED)`, anders als der Rest (`collectOnStarted`). Kein
    Leak/Crash (Scope canceled bei `onDestroyView`, `_binding` geguardet), aber
    beide Flows laufen weiter, während der Dialog nur STOPPED ist (verschwendete
    Arbeit).

15. **`app/.../ui/usageexport/UsageExportViewModel.kt:32/49`** — rohes
    `viewModelScope.launch` ohne `launchSafe`/`CoroutineExceptionHandler` (einziges
    VM, das direkt `ViewModel` erbt, nicht `BaseViewModel`). Aktuell risikoarm (Use
    Cases geben `Result`/`UsageImportResult` zurück statt zu werfen), aber
    inkonsistente ungeschützte Launch-Fläche. **Fix:** auf `BaseViewModel.launchSafe`
    migrieren.

16. **`domain/.../usecase/HandleSwipeActionUseCase.kt:26`** — der
    `if (slot == SWIPE_FROM_LEFT_TO_RIGHT) … else …`-Zweig funnelt `SwipeSlot.NONE`
    in den `swipeRightAppFlow`. In Prod nicht getriggert (Caller `GestureDelegate`
    passt nur die zwei echten Richtungen), rein latent. **Fix:** `when(slot)` mit
    explizitem `NONE -> NoAction`.

---

## 📋 Doc-Drift (kein Codefehler, aber irreführend)

17. **`app/build.gradle.kts:39,50`** — `compileSdk = 37` / `targetSdk = 37`,
    widerspricht **beiden** CLAUDE.md-Dateien (root: „Compile 36 — Android 16
    only"; global: `minSdk=targetSdk=compileSdk=36`). Bewusst (SDK-37-Lift
    2026-07-18 für `core-ktx 1.19.0`, dokumentiert in
    `gradle/libs.versions.toml:80`, mit `// DO NOT CHANGE !!!` auf 37), aber die
    CLAUDE.md-Invariante wurde nie nachgezogen. **Risiko:** eine spätere Session,
    die CLAUDE.md vertraut, könnte auf 36 „zurückkorrigieren" und den
    `core-ktx 1.19.0`-Compile brechen. (minSdk ist weiterhin korrekt 36.)

18. **Root `CLAUDE.md`, Architecture-Abschnitt** — „all tests live in
    `:app/src/test/` for now" ist falsch. Tests sind gesplittet: Contracts in
    `domain/src/testFixtures/`, Fake-Contract-Tests + Fakes in
    `domain/src/test|testFixtures/`, Impl-Contract-Tests in `data/src/test/`.
    `:app/src/test/` hält nur ViewModel/UI-Tests.

19. **`data/build.gradle.kts:16`** — Header-Kommentar behauptet, `:data` deklariere
    `AppUpdateModule`; das Modul liegt aber in `:app`
    (`app/.../di/AppUpdateModule.kt`). Kosmetischer Kommentarfehler.

---

## Sauber bestätigt (keine Defekte)

- **Hilt-Graph:** 19 `@Binds` in `RepositoryModule`, alle Interface↔Impl korrekt,
  je genau ein Binding, alle `@Singleton`. `Purgeable` korrekt ohne Binding
  (Mixin). Dispatcher-Qualifier konsistent; `DataStore`, `ApplicationScope`,
  `PackageManager`, `WallpaperManager`, `MutableSharedFlow<Unit>` korrekt
  `@Singleton`; Dispatcher bewusst unscoped.
- **Build-Pins** alle intakt (Hilt 2.60.1, ACRA 5.13.1, fragment 1.8.9,
  JUnit 4.13.2, material 1.14.0, kotlin-test 2.3.21, core-testing 2.2.0).
  Modul-Richtung `:app→:data→:domain` sauber, keine Back-Edges;
  `versionName/versionCode` nur in `:app`.
- **ACRA/Rule 8:** korrekte Reihenfolge `initAcra{}` → `setEnabled(false)` →
  Consent lesen → `setEnabled(true)` nur bei Consent.
  `CrashReportLimiter`-Cooldown-Mathe korrekt (`(now - lastSent) > COOLDOWN`,
  first-report `lastSent=0` erlaubt, atomarer Check-and-Record, fail-open,
  7-Tage-Cleanup einmal pro Prozess). `silentError`/`silentDeath`-Semantik sound.
- **Contract-Triples:** alle 17 Interfaces belegt — 13 mit ausführbaren
  Contract-Paaren, 4 ADR-only mit dokumentierter Begründung. Kein Fake, der an
  seinem Contract vorbei mogelt; alle intentional drifts offengelegt.
- **`:domain` Kern:** ColorMath = WCAG-exakt (0.03928-Schwelle,
  0.2126/0.7152/0.0722), `calculateTonalShadowColor`-Branches decken `[0,1]`
  lückenlos, CoerceExtensions NaN/Infinity korrekt, `ObserveInstalledAppsUseCase`
  Retry-Backoff korrekt, alle sealed `Result`/`Error`/`Failure`-Zweige erreichbar
  und richtig gemappt, `WallpaperState`-Index-Helper bounds-checked.
- **`:data`:** einzelne DataStore-Instanz (keine Dual-Instance-Korruption),
  Read/Write-Keys konsistent, `.use{}` durchgängig, kein Zip-Slip,
  Bitmap-Recycling korrekt, `PackageUpdateReceiver` Action-Filter +
  `goAsync()`/`finish()` sauber, Happy-Path-Backup-Round-Trip verlustfrei.
- **Lifecycle:** ViewBinding überall in `onDestroyView`/`onDestroy` genullt,
  Flow-Collection durchgängig `viewLifecycleOwner`-scoped mit
  `repeatOnLifecycle(STARTED)` (bis auf #14), Receiver-Register/Unregister
  balanciert, `ScreenLockAccessibilityService` cancelt Scope in
  `onUnbind`+`onDestroy`, `WallpaperDelegate`/`GestureDelegate`
  read-modify-write auf Main-Dispatcher race-frei.
- **Rule 9/11/12** in Prod-Code eingehalten (kein bare `Timber.e` außerhalb der
  Crash-Infra-Ausnahmeliste, kein `Timber.Forest.*`, keine can't-throw-Catches).

---

## Empfohlene Reihenfolge

1. **🔴 #4 zuerst klären** (Intent: `maxFavoritesOnHome` reaktivieren vs. löschen)
   — blockiert Code-Änderung dort.
2. **🔴 #1–#3** auf einem Branch (`fix/audit3-config-loss-and-backup`): der
   `isInitialized`-Guard (#1), der `onViewCreated`-Guard (#2), die
   camelCase-Keys im Strict-Path (#3). Alle mit Test.
3. **🟡 #5–#8** als Folge-Batch (Dialog-Tracking, `HomeSettings`-Default,
   Event-Bus-Buffer, ZIP-Dedup-Mapping).
4. **🟢 / Doc-Drift** separat oder gebündelt mit dem nächsten Chore-Commit.
