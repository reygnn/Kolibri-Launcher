# AUDIT-5 — Whole-Codebase Correctness Audit

> **Erzeugt** 2026-07-23 durch einen Claude-Opus-4.8-Multi-Agent-Audit
> (Version 0.99.100 / code 120, Branch `main`).
> **Methode:** 10 Bereichs-Slices parallel abgefächert — `:domain` core/math,
> `:domain` Use-Cases, `:data` Standard-Repositories, `:data`
> Backup/Wallpaper/Receiver, ViewModels/Delegates, Fragments/Activities/
> Lifecycle, Custom-Views/Home-Rendering, App-Init/ACRA/Services, eine
> reine **Concurrency-Linse** über `:data` + ViewModels, sowie
> DI-Graph/Contracts/Build. Jeder Slice-Agent musste jedes Finding am
> echten Code mit einem konkreten Trigger→Fehler-Szenario belegen. Jedes
> gemeldete Finding wurde anschließend von einem separaten **adversarialen
> Verifikations-Agenten** gegengeprüft, dessen Auftrag es war, das Finding
> zu *widerlegen* (Default REJECTED, CONFIRMED nur bei am Code
> reproduzierbarem Trigger→Fehler). Style-Nits, dokumentiert-bewusste
> Muster (Rule-5/7/8/9/11-Ausnahmen, `ACCEPTED_LIMITATIONS.md`,
> `KNOWN_ISSUES.md`, ADR-Contract-Ausnahmen) sowie in AUDIT/-2/-3/-4
> bereits gefixte oder bewusst akzeptierte Findings waren als Ausschluss
> vorgegeben.
> **Reine Diagnose — es wurde kein Code geändert.**
>
> **Selbst-Verifikation (2026-07-23):** Alle drei bestätigten Findings
> wurden vom Orchestrator unabhängig am Quellcode nachgeprüft (nicht nur
> aus den Agenten-Reports übernommen) — inklusive der Gate-/Fallback-
> Pfade, die die adversariale Verifikation als Widerlegungs-Kandidaten
> benannt hatte (`InstalledAppsStateRepositoryImpl.getCurrentApps`
> Fallback, `GestureDelegate` Swipe-Gate, `AcraTree.log`-Doppelpfad).
> Ergebnis: alle drei bestätigt, alle drei nach adversarialer Prüfung von
> Medium auf **Low** korrigiert.
>
> **Statistik:** 6 Roh-Findings → 3 in der adversarialen Verifikation
> verworfen (siehe „Verworfene Findings") → **3 bestätigt: 3 Low.**
> Sieben von zehn Slices (`:domain` core/math, `:domain` Use-Cases außer
> #1, `:data` Standard-Repos, ViewModels, Custom-Views/Home-Rendering,
> Concurrency-Linse, DI/Contracts/Build) meldeten *keine* bestätigten
> Findings.

---

## Executive summary

Der Code ist weiterhin in sehr gutem Zustand. Die tiefen Slices —
Use-Case-Logik, ColorMath/Coerce-Mathe, DataStore-Repository-Semantik,
ViewModel-Config-Change-Handling, Coroutine-/Flow-Semantik (eigene
Concurrency-Linse: kein Befund), der Hilt-Graph, die Modul-Richtung, die
Version-Pins und die Contract-Triples — kamen ohne Befund zurück.

Die drei bestätigten Findings sind allesamt **Low** und liegen jenseits
des normalen Betriebs bzw. jenseits der User-sichtbaren Fläche:

1. Ein **Cold-Start-Randfall**, in dem eine Swipe-Geste die konfigurierte
   Swipe-App löscht, wenn sie *vor* dem ersten erfolgreichen App-Load
   ausgelöst wird (`HandleSwipeActionUseCase`).
2. Eine **kleine, beschränkte Leak-/Anti-Pattern-Fläche**: ein
   `registerForActivityResult` in `onViewCreated` statt als Feld/
   `onCreate`, das pro Home↔Drawer-Runde eine Registrierung akkumuliert
   (`HomeFragment`).
3. Eine **Telemetrie-Doppelzustellung**: post-mortem-ANRs gehen zweimal an
   ACRA und umgehen dabei den `CrashReportLimiter`, entgegen dem eigenen
   KDoc der Methode (`KolibriLauncherApp.reportPendingAnrsAsync`).

Keins ist ein Absturz, kein User-sichtbarer Datenverlust im täglichen
Gebrauch und kein Sicherheits-/Privacy-Leck. #1 ist der einzige mit
echtem (wenn auch schmalem) Nutzer-Impact; #2 und #3 sind
Hygiene-Findings.

Ein leichter roter Faden über #1 und #3: **eine Zusicherung im
Kommentar/KDoc, die der Code an einer Stelle nicht einhält** — der
Swipe-Pfad kann „App nicht geladen" nicht von „App deinstalliert"
unterscheiden, und `reportPendingAnrsAsync` verspricht im KDoc einen
Limiter, den der explizite `handleException`-Pfad umgeht.

---

## #1 — Swipe im Cold-Start-Fenster löscht die konfigurierte Swipe-App · 🟢 Low

**Datei:** `domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/usecase/HandleSwipeActionUseCase.kt:39–51`
**Kategorie:** data-loss (latent)
**Ursprüngliche Slice-Severity:** Medium → **nach adversarialer Prüfung: Low**

### Befund

`HandleSwipeActionUseCase` löst den zur Swipe-Richtung hinterlegten
`componentName` auf, indem es die App in der aktuellen Liste sucht:

```kotlin
val appToLaunch = installedAppsStateRepository.getCurrentApps().find {
    it.componentName == componentName
}

return if (appToLaunch != null) {
    …
} else {
    KolibriLog.w("App for swipe $slot not found: $componentName. Clearing setting.")
    swipeActionsRepository.setSwipeAction(slot, null)   // Zeile 49 — persistentes remove()
    Result.NoAction
}
```

Der `else`-Zweig behandelt „`componentName` nicht in der aktuellen Liste"
als „App deinstalliert" und **persistiert das Löschen** der
Swipe-Zuweisung (`SwipeActionsRepositoryImpl` → `preferences.remove(...)`).
Er kann aber „App wirklich weg" nicht von „App-Liste noch nicht geladen"
unterscheiden.

### Trigger (am Code verifiziert)

- `getCurrentApps()` fällt auf `lastSuccessfulAppList` zurück
  (`InstalledAppsStateRepositoryImpl.kt:80–85`). Dieser Fallback entkräftet
  den *transienten* Load-Fehler nach dem ersten erfolgreichen Load — sobald
  einmal eine nicht-leere Liste geladen wurde, liefert `getCurrentApps()`
  nie wieder leer. **Diese Hälfte des ursprünglichen Findings wurde
  verworfen.**
- **Reproduzierbar bleibt das Cold-Start-Fenster:** In einem frischen
  Prozess ist `lastSuccessfulAppList` leer und `_rawAppsFlow.value` leer,
  also liefert `getCurrentApps()` eine leere Liste. Android kill-restartet
  den HOME-Prozess routinemäßig; die Swipe-Listener sind ab
  `onViewCreated` sofort scharf.
- Die Swipe-Handler in `GestureDelegate.onSwipeFromLeftToRight` /
  `onSwipeFromRightToLeft` (Zeilen 148–168) haben **kein App-geladen-Gate** —
  der einzige Guard ist `if (_isLockingInProgress.value) return`. Der im
  Kommentar (Zeile 383–385) erwähnte „short-circuit" betrifft nur das
  Lock-Fenster, nicht das App-Loading.

Ergebnis: Ein Swipe in genau diesem Startfenster ruft
`getCurrentApps()` → leer → `find` = `null` → `setSwipeAction(slot, null)`
und **löscht die konfigurierte Swipe-App dauerhaft**. Der Nutzer muss sie
manuell neu setzen.

### Warum trotzdem nur Low

Das Fenster ist schmal (bis zum ersten erfolgreichen App-Load, i. d. R.
Sub-Sekunde) und erfordert eine Swipe-Geste genau darin. Kein Absturz, kein
Massendatenverlust — es trifft eine einzelne Einstellung. Aber der Verlust
ist **persistent und still**, deshalb ein echtes (Low-)Finding und kein Nit.

### Fix-Richtung (nicht umgesetzt)

Vor dem Clear ein Guard auf „Liste überhaupt geladen", z. B.
`if (currentApps.isNotEmpty()) { … clear … }` bzw. das Clearing nur dann,
wenn die App-Liste als *geladen* gilt. Das Auto-Clearing bei echter
Deinstallation bleibt erhalten; nur das leere Startfenster wird
ausgenommen. Kein Contract-Bruch: `setSwipeAction` bleibt unverändert, nur
der Aufruf wird gated.

---

## #2 — `registerForActivityResult` in `onViewCreated` akkumuliert Registrierungen · 🟢 Low

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/HomeFragment.kt:363, 418, 427–443`
**Kategorie:** leak (klein, beschränkt) / Anti-Pattern
**Ursprüngliche Slice-Severity:** Medium → **nach adversarialer Prüfung: Low**

### Befund

`layerPickerLauncher` ist ein einfaches nullbares Feld (Zeile 363), und
`registerLayerImagePicker()` wird aus `onViewCreated` (Zeile 418)
aufgerufen — **nicht** als Feld-Initializer und **nicht** aus `onCreate`:

```kotlin
private var layerPickerLauncher: ActivityResultLauncher<String>? = null   // 363
…
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {      // 379
    …
    registerLayerImagePicker()                                             // 418
    …
}

private fun registerLayerImagePicker() {                                   // 427
    layerPickerLauncher = registerForActivityResult(
        WallpaperImagePicker.contract()
    ) { uri -> … viewModel.onAddWallpaperLayer(uri) … }                    // 428–442
}
```

`Fragment.registerForActivityResult` registriert gegen die **Fragment**-
Lifecycle (`getLifecycle()`) und deregistriert erst bei `ON_DESTROY` des
Fragments — **nicht** bei View-Zerstörung. `HomeFragment` ist die
NavHost-Start-Destination, deren Fragment-Instanz für die gesamte
Activity-Lebensdauer lebt, während ihre View bei jedem Home↔AppDrawer-
Wechsel zerstört und neu erzeugt wird (bestätigt durch den eigenen
`onDestroyView`-Kommentar, Zeile ~1578). Jede View-Neuerzeugung ruft
`onViewCreated` → `registerLayerImagePicker()` erneut und legt eine
**neue** Registrierung unter einem neuen auto-inkrementierten Key
(`fragment_<who>_rq#N`) an; die alten bleiben liegen.

### Warum nur Low (adversarial korrigiert)

- Die akkumulierten Objekte sind winzig: je ein Callback-Lambda, ein paar
  Registry-Map-Einträge und ein `LifecycleObserver` — alle referenzieren
  **dieselbe** lebende Fragment-Instanz, **nicht** geleakte alte
  Fragments/Views/Themed-Contexts (anders als das schwere AUDIT-4-#3-Leak).
- Das Wachstum ist durch die Fragment-/Activity-Lebensdauer beschränkt und
  wird bei Fragment-`ON_DESTROY` vollständig freigegeben.
- Kein funktionaler Bug: nur der jeweils zuletzt gesetzte Launcher wird
  aufgerufen; stale Callbacks bleiben inert und feuern nie doppelt.

Damit: ein echtes, aber kleines, beschränktes Leak-/Anti-Pattern — Low.
Abgrenzung zu AUDIT-4 #3: dort ging es um den nicht genullten
RecyclerView-Adapter (schweres Leak, gefixt); dies ist eine **andere,
separate** Stelle desselben Fragments.

### Fix-Richtung (nicht umgesetzt)

Den Launcher genau einmal pro Fragment-Instanz erzeugen — als
Feld-Initializer oder in `onCreate` (wie es `BackupFragment`
export/import, `UsageExportFragment` und `SettingsFragment`
`requestPermissionLauncher` bereits tun). Das erfüllt weiterhin das
„vor `onStart()`"-Timing-Requirement aus dem KDoc.

---

## #3 — Post-mortem-ANRs umgehen den `CrashReportLimiter` und gehen doppelt an ACRA · 🟢 Low

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/KolibriLauncherApp.kt:457–476` (i. V. m. `AcraTree.log` 556–601)
**Kategorie:** correctness (Telemetrie)
**Ursprüngliche Slice-Severity:** Medium → **nach adversarialer Prüfung: Low**

### Befund

Der Callback in `reportPendingAnrsAsync` zustellt jeden ausstehenden
ANR-`ApplicationExitInfo`-Datensatz auf **zwei** Wegen:

```kotlin
Timber.e(synthetic, "ANR (post-mortem from ApplicationExitInfo)")   // 465
try {
    ACRA.errorReporter.handleException(synthetic)                    // 467
} catch (e: Throwable) { … }
```

> **Historisch (pre-intent-gating):** dieser Abschnitt beschreibt den
> damaligen Stand mit `CrashReportLimiter` und dem Level-Gate. Beide sind
> überholt — der Limiter fiel mit dem ACRA-Rewrite weg, das WARN-Level-Gate
> mit §23 (report-by-intent; `AcraTree` gatet jetzt auf Intent-Tag). Bewusst
> als Snapshot eingefroren.

- **Weg A (Zeile 465):** `Timber.e(synthetic, …)` landet im unbedingt
  gepflanzten `AcraTree` (Zeile ~228, „für DEBUG und RELEASE"). Dessen
  `log()` (556–581) lässt Priority ≥ WARN mit non-null Throwable durch,
  ruft `CrashReportLimiter.shouldSendReport(t)` (563) und bei Erfolg
  `reportErrorToAcra` → `ACRA.errorReporter.handleSilentException(t)` (591).
  **Das ist ACRA-Zustellung Nr. 1** — sie *konsumiert* den 24h-Slot des
  Limiters.
- **Weg B (Zeile 467):** `ACRA.errorReporter.handleException(synthetic)`
  ist eine **zweite** Zustellung und konsultiert den `CrashReportLimiter`
  **gar nicht** (der Limiter ist ausschließlich in `AcraTree.log` und im
  Uncaught-Handler verdrahtet; ACRA selbst kennt ihn nicht).

Folgen:
1. Der **erste** ANR geht **zweimal** raus (einmal silent via Tree, einmal
   via explizitem `handleException`).
2. Jeder **weitere** gleich-signaturige ANR — `AnrException` hat immer
   dieselbe Klasse + denselben Konstruktions-Site, also denselben
   Limiter-Key — wird auf Weg A vom Limiter geblockt, geht aber auf Weg B
   **trotzdem** unbedingt raus.

Das widerspricht direkt dem eigenen KDoc der Methode (Zeile 450–452):
„*Der ACRA-Dedup-Limiter … greift weiterhin — bei einem wiederkehrenden ANR
verlässt nur der erste innerhalb des 24h-Fensters das Gerät.*"

### Warum nur Low (adversarial korrigiert)

Der persistierte Watermark in `AnrReporter` garantiert, dass jeder
`ApplicationExitInfo`-ANR-Datensatz über die Prozess-Lebensdauer **genau
einmal** verarbeitet (nicht pro Launch neu gesendet) wird; AEI hält nur
eine beschränkte Zahl Records. Die Zustellung passiert nur bei erteiltem
ACRA-Consent auf einen **selbst-gehosteten** Server (kein Third-Party).
Kein User-sichtbarer Absturz, kein Datenverlust, kein Leak — der konkrete
Schaden sind ein paar redundante Reports auf dem eigenen Server, genau die
Last, gegen die der Limiter existiert (Rule-5-Rationale). Nicht von
AUDIT/-2/-3/-4 abgedeckt: AUDIT hatte `reportPendingAnrsAsync` nur auf
try/catch-Wrapping und `AcraTree` isoliert geprüft, nie den Doppelpfad
verbunden. Keine Rule-7/8/9-Ausnahme (dies ist nicht die dokumentierte
paranoide Init-Fläche, sondern ein echter Doppel-Send).

### Fix-Richtung (nicht umgesetzt)

Genau **einen** Zustellweg wählen. Am konsistentesten: den expliziten
`ACRA.errorReporter.handleException(synthetic)` (Zeile 467) entfernen und
die Zustellung dem `AcraTree`/Limiter-Pfad überlassen (dann greift der
Limiter und der KDoc stimmt wieder). Das `Timber.e` (465) bleibt als
Rule-9-konformer Log-Eintrag ohnehin bestehen. Alternativ den expliziten
Pfad behalten, aber vorher durch `CrashReportLimiter.shouldSendReport`
gaten und das doppelte Tree-`handleSilentException` unterbinden — die
erste Variante ist schlanker.

---

## Verworfene Findings (adversariale Verifikation)

Drei Roh-Findings wurden vom Widerlegungs-Agenten (und in der
Orchestrator-Nachprüfung bestätigt) verworfen:

### V1 — `isZipFile` verkennt gültiges ZIP bei Short-Read
`data/.../BackupRepositoryImpl.kt:177` · **REJECTED.** `input.read(magic)`
verlangt `read == 2`. Der `InputStream`-Vertrag erlaubt zwar Short-Reads,
aber der *konkrete* Trigger (Restore eines gültigen ZIP aus einem
Cloud-SAF-Provider) liefert real keinen 1-Byte-Erst-Read: Pipe-basierte
SAF-Streams geben `min(verfügbar, count)` nach Blockieren auf ≥1 Byte
zurück, und jedes echte ZIP beginnt mit der 4-Byte-Signatur `PK\x03\x04`.
Der Fehlpfad (gültiges ZIP → Legacy-JSON-Fallback → `InvalidFormat`) ist
nicht konkret erreichbar — reines Vertrags-Lawyering ohne belegbaren
Runtime-Effekt (Audit-Ausschluss „could theoretically").

### V2 — ZIP-Import ohne Dekompressions-Größenlimit (Zip-Bomb)
`data/.../BackupRepositoryImpl.kt:311` · **REJECTED.** Zwar prüft das Gate
nur die *komprimierte* Größe (`statSize`) und die Inflate-Reads sind
uncapped — aber der behauptete Fehler überlebt das Re-Read nicht: (1) die
Heap-OOM-Hälfte ist bereits in AUDIT §9.13 als primärer OOM-Kandidat
identifiziert und via `catch (Throwable)` → `ImportResult.Error`
strukturell adressiert (out of scope, bereits berichtet); (2) die
Disk-Fill-Hälfte via gestreamter Wallpaper-Extraktion wird in
`copyFromInputStream` (`catch Throwable`) gefangen → `null`, und die
partielle Waise wird beim nächsten Start von `gcOrphans` eingesammelt.
Ergebnis für eine feindliche Datei: korrekter `Error` + transienter,
selbst-heilender Speicherdruck — kein Crash, kein persistenter Leak, kein
Privacy-Leak. Erfordert zudem bewussten Import einer präparierten Datei.

### V3 — `handleUncaughtException` berechnet `shouldSend`, blockt den Report aber nie damit
`KolibriLauncherApp.kt:361` · **REJECTED.** Der zentrale Wirkmechanismus
reproduziert nicht: Zeile 358 `Timber.e(throwable, …)` dispatcht in den
`AcraTree`, dessen `log()` `CrashReportLimiter.shouldSendReport(t)` auf
**demselben** Throwable **vor** Zeile 361 aufruft und den 24h-Slot
konsumiert. Wenn Zeile 361 `shouldSendReport(throwable)` erneut berechnet,
ist `shouldSend` bereits `false` — der `if (shouldSend)`-Schreibzweig wird
nicht genommen. Die Behauptung „konsumiert den Cooldown-Slot und blockt
einen nachfolgenden Timber.e" ist damit faktisch falsch; der Slot war
bereits durch den regulären ACRA-Send der fatalen Crash-Zeile (358)
konsumiert. Residuum: `shouldSend` an 361 dient nur einem DEBUG-Log — eine
redundante Neuberechnung ohne Runtime-Effekt, Nit at most. AUDIT.md:985
hatte diesen Handler bereits geprüft.

---

## Was NICHT gefunden wurde (bestätigt sauber)

Die folgenden Slices meldeten nach adversarialer Verifikation **keinen**
bestätigten Befund — bewusst festgehalten, weil „sauber" hier ein Ergebnis
ist:

- **`:domain` core/math** — `ColorMath`, `CoerceExtensions`, `AppConstants`,
  `KolibriLog`, `TimberWrapper` und alle `domain/model/`-Datenklassen:
  keine Overflow-/Clamping-/NaN-/Default-Drift-Fehler, keine
  Android-Import-Verletzung der Pure-Kotlin-Reinheit.
- **`:domain` Use-Cases** (~55 Dateien) — außer #1 keine Logik-,
  Ordering-, Null-/Empty- oder Flow-Mapping-Fehler.
- **`:data` Standard-Repositories** — Favorites/HiddenApps/CustomNames/
  Settings/SwipeActions/Shortcut/FabPosition/ScreenLock/Reset/InstalledApps/
  AppUsage/TimeBasedEvents/UsageExport: keine Read-modify-write-Races außer
  Transaktion, keine Key-Mismatches, Purge-/Reset-Vollständigkeit (nach
  AUDIT-4 #1) sauber.
- **ViewModels + Delegates** — keine Config-Change-/SavedState-Verluste
  (nach AUDIT-3 sauber), keine SharedFlow-Buffer-Overflows, kein
  `WhileSubscribed`-/`advanceUntilIdle`-Hazard in Produktionscode.
- **Custom-Views / Home-Rendering** — `ZoomableImageView`,
  `HomeGestureLayout`, die Layout-Kalkulatoren, `wallpaperfab/`,
  `WallpaperEditController`: keine Per-Frame-Allokations-Regression (nach
  AUDIT-2 A16), keine Matrix-/Touch-Index-Fehler.
- **Concurrency-Linse** (eigener Slice über `:data` + ViewModels) —
  `shareIn`/`stateIn`-`SharingStarted`, Replay/Timeout, `MutableSharedFlow`-
  Buffer, `withContext(IO)`-Abdeckung, DataStore-Read/Write-Races: kein
  Befund.
- **DI-Graph / Contracts / Build** — `RepositoryModule`, `DataStoreModule`,
  `AppModule`, `DispatcherModule`, `libs.versions.toml`,
  `app/build.gradle.kts`: keine fehlenden `@Binds`/`@Provides`, keine
  Scope-Mismatches, keine Modul-Richtungs-Verletzung, keine
  Version-Pin-Verletzung, kein Repository ohne Contract-Triple (die
  dokumentierten ADR-Ausnahmen bleiben ausgenommen).

---

## Empfohlene Reihenfolge (falls gefixt wird)

Alle drei sind Low; keiner ist dringend. Falls angegangen:

1. **#1** zuerst — einziger echter (wenn schmaler) Nutzer-Impact; der Fix
   ist ein Ein-Zeilen-Guard vor dem Clear und gut unit-testbar
   (`HandleSwipeActionUseCase` ist reine Domain-Logik).
2. **#3** — Ein-Zeilen-Entfernung des doppelten Zustellwegs, bringt KDoc und
   Code wieder in Deckung; verifizierbar am `AcraTree`/Limiter-Pfad.
3. **#2** — reine Hygiene (Launcher als Feld/`onCreate`), angleichbar an das
   bereits korrekte Muster der anderen Fragments.
