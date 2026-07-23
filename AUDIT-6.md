# AUDIT-6 — Whole-Codebase Correctness Audit

> **Erzeugt** 2026-07-23 durch einen Claude-Opus-4.8-Multi-Agent-Audit
> (Version 0.99.102 / code 122, Branch `main`).
> **Methode:** 12 Bereichs-Slices parallel abgefächert — `:domain`
> Use-Cases (A–M / N–Z), `:domain` core/model/repository, `:data`
> Standard-Repositories (inkl. `CrashReport*`), `:data`
> Service/Wallpaper/Receiver, `ui/home` (inkl. wallpaper/wallpaperfab),
> `ui/main` + delegate + base, `ui/appdrawer`+appcontextmenu+favorites,
> `ui/settings`+swipeactions+customnames+hiddenapps+onboarding,
> `ui/backup`+usageexport+color-/layoutcustomization, `ui/util`+extensions+
> flow, sowie App-Init/ACRA/DI (`KolibriLauncherApp`, `MainActivity`,
> `AppModule`/`AppUpdateModule`). Jeder Slice-Agent musste jedes Finding am
> echten Code mit einem konkreten Trigger→Fehler-Szenario belegen. Jedes
> gemeldete Finding wurde anschließend von einem separaten **adversarialen
> Verifikations-Agenten** gegengeprüft, dessen Auftrag es war, das Finding
> zu *widerlegen* (Default REJECTED, CONFIRMED nur bei am Code
> reproduzierbarem Trigger→Fehler). Style-Nits, dokumentiert-bewusste
> Muster (Rule-5/7/8/9/11-Ausnahmen, `ACCEPTED_LIMITATIONS.md`,
> `KNOWN_ISSUES.md`, ADR-Contract-Ausnahmen) sowie in AUDIT/-2/-3/-4/-5
> bereits gefixte oder bewusst akzeptierte Findings waren als Ausschluss
> vorgegeben.
> **Reine Diagnose — es wurde kein Code geändert.**
>
> **Statistik:** 8 Roh-Findings → 3 in der adversarialen Verifikation
> verworfen (siehe „Verworfene Findings") → **5 bestätigt: 2 Medium, 3 Low.**
> Sieben von zwölf Slices (`:domain` Use-Cases A–M und N–Z, `:domain`
> core/model, `:data` Standard-Repos, `:data` Service/Wallpaper/Receiver,
> `ui/backup`, `ui/util`) meldeten *keine* bestätigten Findings.

---

## Executive summary

Der Code bleibt in gutem Zustand. Die tiefen Slices — Use-Case-Logik,
ColorMath/Coerce-Mathe, DataStore-Repository-Semantik, das
Backup-Assemble/Parse, die System-API-Impls (`LauncherApps`, Wallpaper,
`PackageUpdateReceiver`), die Resource-Mapper und Flow-Operatoren — kamen
ohne bestätigten Befund zurück.

Die fünf bestätigten Findings gruppieren sich auffällig um **das
Wallpaper-Feature** (zwei Medium) plus drei kleine, verstreute Low-Punkte:

1. **Wallpaper-Layer verrutschen**, wenn beim Full-Rebuild ein Bitmap
   *nicht* lädt: die übersprungene Layer verschiebt die Indizes, während
   die Property-Updates weiter nach Original-Index angewandt werden —
   genau die „scrambled wallpaper"-Klasse, gegen die die Identity-Diff
   in `RebuildPlan.kt` geschrieben wurde, hier über den Load-Fehler-Pfad
   reaktiviert (`WallpaperViewBinder`).
2. Ein **Cancel während eines noch laufenden Layer-Adds** unterläuft die
   Rollback-Zusicherung des Edit-Sessions: der `copyToInternal`-Suspend
   gibt den Main-Thread frei, ein synchroner Cancel dazwischen kippt die
   Edit-Flag, und der wieder aufgenommene Add persistiert die
   „abgebrochene" Layer trotzdem (`WallpaperDelegate`).
3. Ein **Triple-Tap feuert die Double-Click-Aktion doppelt**, weil
   `registerClick()` den Zeitstempel auch beim Treffer bedingungslos
   fortschreibt (`DoubleClickDetector`).
4. Eine **ACRA-Custom-Data-Race**: nebenläufige Reports können ihre
   Diagnose-Metadaten (`log_tag`/`log_message`/`log_priority`) über den
   Prozess-globalen ErrorReporter vertauschen (`KolibriLauncherApp`).
5. Ein **hartkodierter englischer Toast** im Secure-Window-Toggle,
   Verstoß gegen die Localization-Rule (`SettingsFragment`).

Keins ist ein Absturz oder ein Sicherheits-/Privacy-Leck. #1 und #2 sind
die einzigen mit echtem (wenn auch schmalem) Nutzer-Impact und liegen
beide im Wallpaper-Multi-Layer-Pfad; #3–#5 sind Hygiene-Findings.

Roter Faden über #1 und #2: **eine dokumentierte Invariante, die der Code
in einem Nebenpfad nicht einhält** — die Identity-Diff garantiert
Layer-Stabilität nur, solange jeder Layer lädt, und der Edit-Session-
Kommentar verspricht synchrones Rollback nur auf dem Lese-Pfad, nicht
gegen einen suspendierten Add.

---

## #1 — Wallpaper-Layer verrutschen, wenn ein Bitmap beim Full-Rebuild nicht lädt · 🟠 Medium

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/wallpaper/WallpaperViewBinder.kt:195–210` (i. V. m. `applyUpdates` :236–237, `WallpaperViewDiff.diff`)
**Kategorie:** correctness
**Severity:** Medium (in der adversarialen Prüfung bestätigt)

### Befund

`WallpaperViewDiff.diff` baut `loadSpecs`
(`target.layers.mapNotNull(fromLayerState)`) und `updates`
(`target.layers.filter { imageUri != null }.mapIndexed { viewIndex, … }`)
über **dieselben** bildtragenden Layer in derselben Reihenfolge, also gilt
`updates[i].layerIndex == i` (0..M-1).

In `applyFullRebuild` wird jede Layer, deren `bitmapLoader.load(spec.imageUri)`
`null` liefert, per `continue` übersprungen — die View hält danach *weniger*
Layer als `plan.layers`, und die Indizes verschieben sich:

```kotlin
for (spec in plan.layers) {
    val bitmap = bitmapLoader.load(spec.imageUri) ?: continue   // ~197: skip, kein Reindex
    view.addLayer(bitmap, …)
}
…
applyUpdates(view, plan.updates)                                 // wendet update.layerIndex direkt an
```

`applyUpdates` (`:236–237`) greift die Ziel-Layer strikt über den
**Original-Index** (`view.getLayer(update.layerIndex)`) — kein Id-basiertes
Lookup, kein Reindex.

### Trigger (am Code verifiziert)

4 Layer, `spec1` scheitert beim Dekodieren → View = `[spec0, spec2, spec3]`
an Indizes 0,1,2, während `updates` die Indizes 0,1,2,3 tragen:

- `upd1` (spec1-Props) landet auf **spec2**s Layer,
- `upd2` (spec2-Props) auf **spec3**s Layer,
- `upd3` trifft `update.layerIndex(3) >= view.layerCount(3)` → break →
  spec3s Layer behält spec2s Props und bekommt nie sein eigenes Transform.

Der Null-Pfad ist real erreichbar: `loadBitmapFromUri`
(`HomeFragment.kt:1454–1471`) liefert `null` bei
`FileNotFoundException`/`SecurityException` (gelöschte Datei, widerrufene
persistierte Content-URI-Permission) und bei `OutOfMemoryError` für große,
vom Nutzer via SAF gewählte Bilder. Bleibt die Datei weg, persistiert der
Mismatch über Rebuilds hinweg (Snapshot-Ids `[id0,id2,id3]` werden nie
gleich Target `[id0,id1,id2,id3]` → FullRebuild läuft erneut → re-scramble).

Nur ein Ausfall des **terminalen** Layer-Suffixes ist benign; jeder Ausfall
einer ersten/mittleren Layer verrutscht die Zuordnung. Die überlebenden
Layer rendern mit fremden Transform/Alpha/Blend/Visibility — exakt die
„scrambled wallpaper"-Klasse, gegen die die lange ARCHITECTURAL NOTE in
`RebuildPlan.kt` geschrieben wurde, hier über den Load-Fehler-Pfad
reaktiviert.

### Warum Medium

Erfordert ein Advanced-Opt-in-Multi-Layer-Setup **plus** einen
Load-Fehler einer nicht-terminalen Layer. Kein Absturz, kein Datenverlust
— aber **persistente visuelle Korruption**, die eine spezifisch
dokumentierte Invariante aushebelt.

### Fix-Richtung (nicht umgesetzt)

Die Update-zu-Layer-Zuordnung gegen übersprungene Loads robust machen:
entweder Updates gegen die **tatsächlich** hinzugefügten Layer aufbauen/
anwenden (added Spec-Ids tracken, Updates per Id matchen) oder in
`applyUpdates` die Ziel-Layer per stabiler Id statt per Positionsindex
auflösen. Minimal: `plan.updates` vor dem Anwenden auf die Specs filtern,
deren `addLayer` erfolgreich war.

---

## #2 — Cancel während eines laufenden Layer-Adds unterläuft die Edit-Session-Zusicherung · 🟠 Medium

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/delegate/WallpaperDelegate.kt:476` (i. V. m. `onCancelWallpaperEditMode` :416, `WallpaperFileManager.copyToInternal` :66, `DelegateScope.launchSafe` :53)
**Kategorie:** concurrency
**Severity:** Medium (in der adversarialen Prüfung bestätigt)

### Befund

`onAddWallpaperLayer` läuft in `launchSafe` (dispatcht auf den
single-threaded `mainDispatcher`) und ruft
`wallpaperFileManager.copyToInternal(imageUri)` — eine `suspend fun`, die
`withContext(Dispatchers.IO)` macht und damit den Main-Thread mitten im
Kopiervorgang freigibt. Die Session-Control-Methoden
(`onEnter`/`onCommit`/`onCancelWallpaperEditMode`) sind dagegen schlichte,
synchrone Main-Thread-Methoden ohne `launchSafe`.

### Trigger (am Code verifiziert)

Der Cancel-FAB (`WallpaperEditController.kt:138`) ist während des Kopierens
scharf — kein Input-blockierendes Overlay; der Add wird aus dem
Picker-`ActivityResult`-Callback (`HomeFragment.kt:454`) gefeuert.
Interleaving auf dem single-threaded Dispatcher (deterministisch):

1. `C1` suspendiert in `copyToInternal` (großes Bild, ~100 ms+),
2. Nutzer tippt **Cancel** → `onCancelWallpaperEditMode` restauriert
   synchron `_wallpaperState` auf den Snapshot (ohne die neue Layer),
   leert `pendingRemovalsOnCancel`, setzt `_isWallpaperEditMode = false`
   und plant einen async Snapshot-Save,
3. Kopie nimmt wieder auf → der Check `if (_isWallpaperEditMode.value)`
   an Zeile 476 liest jetzt **false**, die neue Datei wird also **nicht**
   getrackt; die Continuation liest den restaurierten Snapshot, hängt die
   Layer an, setzt `_wallpaperState.value = newState` und persistiert ihn.

Ergebnis: eine **explizit abgebrochene** Layer wird nach dem Cancel doch
hinzugefügt und persistiert, plus ein Last-Writer-wins-Rennen zwischen den
zwei async Saves (Snapshot vs. newState) → Memory/Disk-Mismatch. Das
bricht die „Cancel rollt alle Änderungen zurück"-Invariante der
ARCHITECTURAL NOTE (Zeilen 56–64, 82–91), die synchrones Restore nur auf
dem Fragment-Lese-Pfad garantiert und einen post-Cancel wieder aufnehmenden
Add nie absichert. Alle gelisteten Regression-Guards (Zeilen 93–101)
prüfen nur sequenzielle, nicht-interleavte Aufrufe.

*Korrektur ggü. Rohbefund:* die Streu-Datei wird von der nun persistierten
Layer referenziert, ist also kein echter GC-Orphan — ein Correctness-Bug
(unerwünschte Layer bleibt), kein File-Leak. Der Defekt hält.

### Warum Medium

Schmales Timing-Fenster (Cancel während der Kopie), aber realistische
Nutzeraktion ohne jeden Guard, und das Interleaving ist auf dem
single-threaded Dispatcher deterministisch. Kein Absturz, aber eine
verletzte Rollback-Zusicherung mit persistentem Fehlzustand.

### Fix-Richtung (nicht umgesetzt)

Die Edit-Mode-/Tracking-Entscheidung **vor** dem Suspend-Punkt festhalten,
oder den Add gegen einen bei Methodeneintritt erfassten
Session-Token/Generation-Counter re-validieren, sodass ein über eine
Session-Grenze resumender Add verworfen (bzw. seine Datei in die korrekte
Pending-Menge geroutet) wird. Alternativ Edit-Session-Mutationen durch
einen einzigen Actor/`Mutex` serialisieren, sodass ein Add nicht mit
Cancel/Commit interleaven kann.

---

## #3 — Triple-Tap feuert die Double-Click-Aktion doppelt · 🟢 Low

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/DoubleClickDetector.kt:37`
**Kategorie:** correctness
**Severity:** Low (in der adversarialen Prüfung bestätigt)

### Befund

`registerClick()` setzt `lastClickTime = now` **bedingungslos** — auch auf
dem Aufruf, der gerade `true` für einen erkannten Double-Click
zurückgegeben hat, ohne Reset danach:

```kotlin
// lastClickTime=0
// tap1 @T1 → (T1-0) < 300 = false, lastClickTime=T1
// tap2 innerhalb 300ms → true, feuert, lastClickTime=T2
// tap3 innerhalb 300ms von T2 → (T3-T2) < 300 = true, feuert ERNEUT
```

Drei Taps innerhalb `AppConstants.DOUBLE_CLICK_THRESHOLD` (300L) liefern
`true` auf Tap 2 **und** Tap 3; N schnelle Taps feuern N-1-mal.

### Trigger (am Code verifiziert)

Der Caller `HomeFragment.DoubleClickListener.onClick` (`:1229`) leitet
`if (detector.registerClick()) onDoubleClick()` ohne Zusatz-Guard weiter,
und `GestureDelegate.onTimeDoubleClick`/`onDateDoubleClick`/
`onBatteryDoubleClick` (`:517–527`) senden je Invocation
`UiEvent.OpenClock`/`OpenCalendar`/`OpenBatterySettings`. Ein schneller
Triple-Tap auf Uhr/Datum/Batterie emittiert das Open-App-Event also
zweimal.

### Warum Low

Folge ist benign (eine bereits öffnende Activity erneut öffnen) — kein
Absturz, kein Datenverlust. Aber ein echter Logikfehler.

### Fix-Richtung (nicht umgesetzt)

Nach einem Treffer die Paarung zurücksetzen, z. B.
`if (isDoubleClick) lastClickTime = 0L else lastClickTime = now` (oder
`lastClickTime = 0` vor dem `return true`).

---

## #4 — ACRA-Custom-Data-Race kann nebenläufige Crash-Reports fehletikettieren · 🟢 Low

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/KolibriLauncherApp.kt:596–602`
**Kategorie:** concurrency (Telemetrie)
**Severity:** Low (in der adversarialen Prüfung bestätigt)

### Befund

`reportErrorToAcra()` schreibt drei Pro-Report-Felder in den
**Prozess-globalen** ErrorReporter und ruft erst danach
`handleSilentException(t)`:

```kotlin
ACRA.errorReporter.putCustomData("log_priority", …)
ACRA.errorReporter.putCustomData("log_tag", …)
ACRA.errorReporter.putCustomData("log_message", …)
ACRA.errorReporter.handleSilentException(t)
```

`ACRA.errorReporter` ist ein Prozess-Singleton; `putCustomData` mutiert
seine geteilte `customData`-Map, `handleSilentException` snapshottet sie
beim Report-Bau. Kein Lock, kein `synchronized`, kein Thread-Local, kein
Pro-Report-Kanal (grep bestätigt: null `synchronized` in der Datei); der
umschließende try/catch schützt nur gegen uninitialisiertes ACRA, nicht
gegen Interleaving.

### Trigger (am Code verifiziert)

`AcraTree.log()` ist ein `Timber.Tree.log`-Override, aufgerufen auf dem
Thread, der `Timber.w/e` aufruft; `Timber.Forest.log` iteriert die Trees
ohne Cross-Thread-Lock. Der `CrashReportLimiter` dedupt nach Exception-
**Typ**, also passieren zwei *verschiedene* Typen beide. Konkret:
Coroutine A (`Dispatchers.IO`) fängt eine `IOException` → `Timber.e`;
Coroutine B fängt eine `IllegalStateException` → `Timber.e`. A führt die
drei Puts aus (`log_message="A"`), B interleavt seine Puts
(`log_message="B"`) bevor A `handleSilentException(ioEx)` erreicht — A's
Report für `ioEx` geht mit B's Tag/Message/Priority raus. Der Stacktrace
bleibt korrekt; nur die Diagnose-Custom-Felder sind cross-kontaminiert.

Keine Rule-9-Ausnahme deckt das: die Crash-Infra-Liste segnet nur bare
`Timber.e` (Rekursions-Vermeidung); Rule 7 betrifft `catch(Throwable)`.

### Warum Low

Impact begrenzt auf Report-Metadaten-Genauigkeit (kein Absturz, kein
Datenverlust, korrekter Stacktrace), das Zeitfenster ist wenige
Instruktionen breit und braucht zwei verschiedene, gleichzeitig rennende
Fehlertypen.

### Fix-Richtung (nicht umgesetzt)

Die put+handle-Sequenz unter einem Lock serialisieren (z. B. demselben,
den `CrashReportLimiter` nutzt, oder einem dedizierten), oder
Priority/Tag/Message lokal an Exception/Report hängen statt über die
geteilte `errorReporter`-Custom-Data-Map.

---

## #5 — Hartkodierter englischer Toast im Secure-Window-Toggle · 🟢 Low

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/settings/SettingsFragment.kt:249`
**Kategorie:** convention (Localization-Rule)
**Severity:** Low (in der adversarialen Prüfung bestätigt)

### Befund

Beim Aktivieren des „Secure Window / Anti-Ghosting"-Switch wird ein Toast
mit dem Literal `"Screenshots disabled"` gezeigt statt via `stringResource`:

```kotlin
Toast.makeText(requireContext(), "Screenshots disabled", Toast.LENGTH_SHORT).show()  // 249
```

Jede andere user-sichtbare Zeichenkette in *demselben* Fragment löst über
eine String-Resource auf: der Permission-denied-Toast (`:127`,
`R.string.calendar_permission_denied_toast`), die Crash-Report-Toasts
(`:545–547`), Summaries (`:654–656`/`:961–964`) und der Picker-Titel
(`:907`). Das Projekt liefert eine deutsche Locale (`res/values-de`); der
Rule-13-grandfatherte deutsche Kommentar davor ist unkritisch, das
Kotlin-String-Literal ist es nicht.

### Trigger (am Code verifiziert)

Deutsche Locale, Nutzer öffnet Settings, kippt „Secure Window"-Switch ON →
`shouldEnable == true` → Zeile 249 feuert den untranslateten
„Screenshots disabled"-Toast. Verstoß gegen die CLAUDE.md-Localization-
Rule („no hardcoded strings in Kotlin or XML"); keine Rule-Ausnahme deckt
das (nicht Crash-Infra, nicht Rule 7/9).

### Warum Low

Kosmetisch, nur die eine weitere gelieferte Locale betroffen, kein
funktionaler/Sicherheits-Impact.

### Fix-Richtung (nicht umgesetzt)

`R.string.secure_window_screenshots_disabled` in `res/values/strings.xml`
und `res/values-de/strings.xml` anlegen und das Literal durch
`getString(R.string.secure_window_screenshots_disabled)` ersetzen.

---

## Verworfene Findings (adversariale Verifikation)

Drei Roh-Findings wurden vom Widerlegungs-Agenten verworfen:

### V1 — Irreführender „error launching app"-Toast nach erfolgreichem Launch
`app/.../ui/main/delegate/AppManagementDelegate.kt:128` · **REJECTED.**
Das Finding unterstellt, `recordAppLaunchUseCase`/`refreshAppsUseCase`
könnten eine `IOException` bis zum Delegate-Catch (`:133`) propagieren und
so nach einem erfolgreichen Launch einen falschen Toast erzeugen. Beide
Use-Cases sind aber dünne Pass-throughs zu Impls, die jeden `Throwable`
intern schlucken (`AppUsageRepositoryImpl.recordPackageLaunch` :84–105
wrappt `dataStore.edit{}` in `catch`). Der Catch kann post-Launch nicht
feuern — nicht erreichbar.

### V2 — Phase-2-Order-Import liest ggf. stale Favorites aus WhileSubscribed-Flow
`data/.../BackupDataAssembler.kt:230` · **REJECTED.** Das beschriebene
Stale-Replay tritt nur auf, solange `favoriteComponentsFlow` **heiß** ist —
was während eines Backup-Imports nicht realistisch erreichbar ist. Der
Import läuft aus `BackupFragment` in der `SettingsActivity`; der einzige
langlebige Consumer des Flows sitzt in `MainActivity`, die dann nicht im
Vordergrund ist. Der Flow ist während des Imports kalt → kein Replay,
Race nicht erreichbar.

### V3 — Drag-Reorder nutzt async `submitList` als Arbeitsmodell; schnelle Drags persistieren Scramble
`app/.../ui/favorites/FavoritesAdapter.kt:144` · **REJECTED.** Der
behauptete Scramble verlangt, dass `currentList` **stale** ist, während
`bindingAdapterPosition` **frisch** ist — diese Divergenz ist unmöglich:
`moveItem`s Basis (`FavoritesAdapter.kt:103` `currentList`) und die
`onMove`-Positionen (`FavoritesSortFragment.kt:127–128`
`viewHolder.bindingAdapterPosition`/`target…`) stammen aus **demselben**
Snapshot. Kein reproduzierbarer Trigger.

---

## Was NICHT gefunden wurde (bestätigt sauber)

Die folgenden Slices meldeten nach adversarialer Verifikation **keinen**
bestätigten Befund — bewusst festgehalten, weil „sauber" hier ein Ergebnis
ist:

- **`:domain` Use-Cases** (A–M und N–Z, ~52 Dateien) — keine Filter-/
  Sort-/Mapping-Fehler, keine Off-by-one, keine Null-/Empty-Fehldefaults,
  keine `combine`/`distinctUntilChanged`-Bugs.
- **`:domain` core / model / repository** — `ColorMath`,
  `CoerceExtensions`, `KolibriLog`/`TimberWrapper`-Lambda-Wiring,
  `DispatcherModule`-Scope, Data-Class-Equality/Copy: kein Befund, keine
  Pure-Kotlin-Verletzung.
- **`:data` Standard-Repositories** (inkl. `CrashReportConsentStore`,
  `CrashReportLimiter`) — DataStore-Read/Write-Races, Backup-Assemble/
  Parse, Consent-Gating (Rule 8), Limiter-Sync-Korrektheit: kein Befund.
- **`:data` Service / Wallpaper / Receiver** — `ShortcutLauncherServiceImpl`,
  `WallpaperRepositoryImpl`, `PackageUpdateReceiver`: keine
  Receiver-Lifecycle-Fehler, keine Cursor-/Stream-Leaks, keine unbehandelten
  System-API-Nulls.
- **`ui/backup` + usageexport + color-/layoutcustomization** — File-IO/
  Uri-Permission, Import-Validierung, `BackupFragment`-`CoroutineExceptionHandler`
  (Rule-9-Rekursion), Color-Parsing, Export-Formatierung: kein Befund.
- **`ui/util` + extensions + flow** (inkl. `DomainMessageMappers`) —
  Resource-Mapping-Vollständigkeit (keine fehlenden Sealed-Branches),
  Extension-Edge-Cases, Bundle-Wrap/Unwrap, Flow-Operatoren: kein Befund.

---

## Empfohlene Reihenfolge (falls gefixt wird)

Zwei Medium, drei Low; keiner ist dringend. Falls angegangen:

1. **#1 + #2** zusammen — beide im Wallpaper-Multi-Layer-Pfad, bilden eine
   natürliche Einheit; #1 ist ein Id-basiertes-Lookup-Refactor in
   `applyUpdates`, #2 ein Session-Token-Guard vor dem Suspend. Beide gut
   testbar (Diff/Binder rein, Delegate über Fakes).
3. **#3** — Ein-Zeilen-Reset in `DoubleClickDetector`, unit-testbar (reine
   Logik).
4. **#4** — put+handle unter Lock serialisieren; verifizierbar am
   `AcraTree`/Limiter-Pfad.
5. **#5** — reine Localization-Hygiene (String-Resource in beiden Locales),
   angleichbar an das bereits korrekte Muster des restlichen Fragments.
