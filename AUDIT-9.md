# AUDIT-9 — Querschnitts-Audit (unverifiziert)

> **Erzeugt** 2026-07-26 durch einen Claude-Opus-4.8-Multi-Agent-Audit
> (Version 0.99.131 / code 151, Branch `main`, Commit `626e4d63`).
> **Fokus:** breiter kritischer Querschnitt über alle drei Module
> (`:domain`, `:data`, `:app`) — Nebenläufigkeit, Lifecycle-Leaks,
> Fehlerbehandlung, Datenverlust, Korrektheit, Sicherheit/Privacy und
> Architektur-Regeln.
> **Methode:** 10 Finder-Agenten parallel (je ein Bereich + zwei
> Querschnitts-Agenten für Coroutinen/Flow und Architektur-Regeln).

---

## STATUS: VERIFIZIERT (2026-07-27) — Verdikte je Fund eingetragen

**Update 2026-07-27:** Die ursprünglich übersprungene adversariale Verify-Phase
wurde nachgeholt — jeder Fund wurde am aktuellen Code (Commit `626e4d63` ff.)
gegengeprüft. Das **VERDIKT** (`CONFIRMED` / `PARTIAL` / `REFUTED`) steht jetzt
in der Übersichtstabelle und als Notiz-Block direkt beim jeweiligen Fund.
**Erledigt** sind bislang **#1** (REFUTED, gepinnt), **#7** (behoben),
**#4** (behoben — Main-Thread-Query → `withContext(ioDispatcher)`) und
**#2** (behoben — Import-Order gegen die von Phase 1 geschriebene Menge statt
gegen den laggenden Hot-Flow-Cache) und **#12** (behoben — `_singleScale` vor
dem Restore-Clamp setzen); alle anderen tragen ihr Verdikt, sind aber **noch
offen** — die Verdikte sind kein „done"-Haken. Die verbleibenden CONFIRMED sind
Kosmetik/Nits oder Doc-Fixes (#8, #11).

**Update 2026-07-29:** **#10** re-verifiziert → **REFUTED** (Fehlalarm; die
ursprüngliche Verify-Kette brach vor `loadFromFile`s Catch-All ab, der jeden
Throw auf `Error` → nutzersichtbares `ImportError`-Event mappt — Details bei
#10 unten).

> _Historischer Hinweis (Ursprungsfassung):_ Die Funde waren zunächst
> **Behauptungen der Finder-Agenten mit Codebeleg, aber ohne Gegenprüfung** —
> die Verify-Phase war aus Token-Budget-Gründen übersprungen. Das ist mit dem
> Update oben erledigt.

**Vor jedem Fix weiterhin gilt:**

1. **Jeden einzelnen Fund am aktuellen Code selbst verifizieren.** Existiert
   der Code so? Ist das Fehlerszenario real erreichbar, oder durch Guards /
   Lifecycle / Architektur bereits verhindert? Ist es nur Stil statt Bug?
2. **Gegen bestehende AUDIT-Dokumente und Fixes abgleichen** (siehe der
   ausdrückliche Hinweis bei Fund #1 — er überschneidet sich potenziell mit
   einem in **AUDIT-8** bereits als *behoben* dokumentierten Bug).
3. **CLAUDE.md-Ausnahmen berücksichtigen** (Rule 7/9-Ausnahmeliste,
   `KNOWN_ISSUES.md`, `ACCEPTED_LIMITATIONS.md`) — mehrere Kandidaten liegen
   auf dem bewusst paranoiden Crash-Infra-Pfad.

Kein Fund wurde als *critical* oder *high* eingestuft; die Spitze ist
*medium*. Alle Schweregrade sind **Selbsteinschätzung der Finder-Agenten**
und ebenfalls unverifiziert.

---

## Überblick

| # | Schweregrad | Bereich | Datei:Zeile | Kurzfassung |
|---|---|---|---|---|
| 1 | ✅ REFUTED | Backup | `BackupSerializer.kt:~365/376` | Fehlalarm — gepinnt durch `BackupSerializerNamingAndInfinityTest` (Details bei #1 unten) |
| 2 | ✅ behoben | Backup | `BackupDataAssembler.kt:226` | Import-Phase 2 las Favoriten über laggenden WhileSubscribed-Cache → Fix: Phase 2 nutzt die von Phase 1 geschriebene Menge (Details bei #2 unten) |
| 3 | 🟠 medium · PARTIAL | Backup | `BackupRepositoryImpl.kt:250` | Nicht-atomares Überschreiben zerstört Backup bei Schreib-Fehler |
| 4 | ✅ behoben | Nebenläufigkeit | `WallpaperDelegate.kt:421-437` | `ContentResolver.query()` lief synchron auf Main-Thread → Fix: `getDisplayName` in `withContext(ioDispatcher)` (Details bei #4 unten) |
| 5 | 🟠 medium · PARTIAL | Nebenläufigkeit | `AppUpdateSignal.kt:17` | `MutableSharedFlow` ohne Buffer → Signal verloren |
| 6 | 🟡 low · PARTIAL | Datenverlust | `UsageExportRepositoryImpl.kt:384` | Usage-Export überschreibt Zieldatei nicht-atomar |
| 7 | ✅ behoben | Konsistenz | `ResetRepositoryImpl.kt:79` | Reset löschte Wallpaper-Bilddateien nicht → Fix: `purgeRepository()` ruft jetzt `clearAll()` (Details bei #7 unten) |
| 8 | 🟡 low · CONFIRMED | Telemetrie | `KolibriLauncherApp.kt:461-485` | ANR-Watermark rückt auch bei fehlgeschlagenem Send vor |
| 9 | 🟡 low · PARTIAL | Fehlerbehandlung | `KolibriLauncherApp.kt:363-366` | `shouldSend` berechnet, nie ausgewertet; verbrennt Cooldown |
| 10 | ✅ REFUTED | Fehlerbehandlung | `UsageExportViewModel.kt:21` | Fehlalarm — `loadFromFile`-Catch-All mappt jeden Throw auf `Error` → `ImportError`-Event (Details bei #10 unten) |
| 11 | 🟡 low · CONFIRMED | Nebenläufigkeit | `CrashReportConsent.kt:81/90` | Unstrukturierter `CoroutineScope(IO).launch` pro Klick |
| 12 | ✅ behoben | Korrektheit | `ZoomableImageView.kt:341` | Restore clampte Skalierung gegen die *Vor-Restore*-Skalierung → Fix: Restore nicht mehr magnitude-clampen, nur korrupte Werte sanitisieren (Details bei #12 unten) |
| 13 | 🟡 low · PARTIAL | UX / Fehlerbehandlung | `AppManagementDelegate.kt:126-137` | Irreführender „Fehler beim Starten"-Toast bei erfolgreichem Launch |

**Als sauber bewertet (0 Funde):** Domain-Logik & ColorMath ·
AppDrawer/Adapter/DiffUtil · Architektur-Regeln (Modul-Grenzen, Rule 9/12,
kein `Toast.makeText` außerhalb `ToastSafe`, ACRA-opt-in-Reihenfolge). Der
Coroutinen-Querschnitt war insgesamt sehr sauber — alle `WhileSubscribed`
mit korrektem Timeout, kein `GlobalScope`/`runBlocking` im UI-Pfad.

---

## 🟠 Medium

### #1 — Backup-Typ-Validierung greift am Key-Format vorbei · `BackupSerializer.kt:~365/376`

> **✅ RESOLUTION (2026-07-27): REFUTED — Fehlalarm, jetzt endgültig gepinnt.**
> Verifiziert am Code + durch einen dedizierten Pinning-Test bestätigt. Die
> *Beobachtung* stimmt (`validateJsonTypes` listet nur snake_case-Skalar-Keys,
> App schreibt camelCase), ist aber **kein Bug**. Empirisch belegte Mechanik:
> 1. App kann **nie** ein non-finites Float schreiben — `encodeToJsonString`
>    wirft (`allowSpecialFloatingPointValues = false`).
> 2. Ein handgebautes non-finites Literal (`1e309`) lässt `parseBackupData`
>    das **gesamte** Backup ablehnen (`null` → Import-`Error`) — nicht die
>    up-front-Validierung, sondern der Decode-Schritt ist das Gate. Infinity
>    erreicht das Modell nie. *(Korrektur der früheren Annahme „getStrictFloat
>    coerct das" — der Strict-Pfad läuft hier gar nicht, die ganze Parse
>    schlägt fehl.)*
> 3. Finite-aber-out-of-range-Werte (`9999.0`) laufen durch den Serializer und
>    werden am Import-Rand von `coerceInSafe` (`BackupDataAssembler.kt:295`)
>    geclampt.
>
> **Schleife abgestellt:** dokumentiert in der KDoc von
> `BackupSerializer.validateJsonTypes` und gepinnt durch
> `BackupSerializerNamingAndInfinityTest` (9 `@Test`, SSOT). Künftige Audits,
> die diesen Pfad erneut melden: dort ist der Beweis.

**Kategorie:** correctness / Datenverlust
**Behauptung:** Die Typ-/Infinity-Validierung prüft nur `snake_case`-Keys für
numerische/boolesche Felder, die App schreibt aber `camelCase` → die
Validierung greift bei App-eigenem Output ins Leere.
**Fehlerszenario:** Ein Backup mit ungültigem numerischem Wert (z. B.
`Infinity`) passiert die Validierung, weil unter dem camelCase-Key nicht
geprüft wird.
**Signal:** Unabhängig von **zwei** Findern gemeldet.

> **⚠️ CROSS-REFERENCE — potenziell bereits behoben.** **AUDIT-8** (§#1)
> dokumentiert einen eng verwandten camelCase-Write / snake_case-Lookup-Bug
> (AUDIT-3 #3) als **real, aber längst behoben** (Fix-Commit `b67c5b8`), und
> beschreibt, dass `mergeWithStrictValues` nach jedem Decode läuft. Vor
> jeder Maßnahme **zwingend gegen AUDIT-8 abgleichen** — dieser Fund könnte
> denselben, bereits geschlossenen Pfad erneut melden oder eine übersehene
> Rest-Lücke betreffen. Nur Code-Verifikation klärt das.
>
> **🔁 WIEDERKEHRENDER FEHLALARM.** Dieser camelCase/snake_case-Themenkomplex
> wird von Audits **immer wieder** als vermeintlicher Bug gemeldet
> (AUDIT-3 #3 → behoben, AUDIT-8 §#1 → bestätigt behoben, jetzt AUDIT-9 #1).
> **Einschätzung des Maintainers:** der Code ist mit hoher Wahrscheinlichkeit
> **korrekt, nicht kaputt** — die Backup-Pipeline ist sehr gründlich
> unit-getestet (~259 `@Test`-Methoden laut AUDIT-8). Standardannahme daher:
> **korrekt-aber-unterdokumentiert**, nicht defekt.
>
> **📌 AUFGABE (ab 2026-07-27):** Das Thema **endgültig geradeziehen** ODER
> an der Codestelle festhalten, **WARUM** die Serialisierung so arbeitet
> (App schreibt camelCase; `@JsonNames` ist read-alias-only für
> snake_case-Legacy-Backups) — sodass künftige Audits diesen Pfad nicht
> erneut als Bug melden. Ziel: die Wiederholungs-Schleife abstellen.

---

### #2 — Import liest Favoriten über laggenden Hot-Flow-Cache · `BackupDataAssembler.kt:226`

> **✅ RESOLUTION (2026-07-27): CONFIRMED → behoben.** Jedes Glied der Kette
> am Code bestätigt: `favoriteComponentsFlow` ist in Prod ein
> `WhileSubscribed(FLOW_SHARING_TIMEOUT_MS=5000, replay=1)`-Hot-Share
> (`FavoritesRepositoryImpl:122` → `DataStoreReadFlow.shareInOrRaw(replay=1)`);
> einarmiges `WhileSubscribed` ⇒ `replayExpiration=MAX` ⇒ Replay-Wert wird nach
> dem letzten Subscriber unbegrenzt gehalten. Phase-1-`saveFavoriteComponents`
> (`edit()`) aktualisiert diesen eingefrorenen Cache nicht, solange kein
> Collector aktiv ist (Import läuft aus der Settings/Backup-Activity, Home-
> Fragment gestoppt) → `.first()` liefert den Stale-Replay-Wert. `saveOrder`
> überschreibt bedingungslos → Worst Case frische Installation: Cache=`emptySet`
> → `saveOrder(emptyList())` verwirft die importierte Reihenfolge still. Der
> Autor hatte genau diese Falle für InstalledApps bei `:179-190` bereits
> abgefangen — beim Favoriten-Re-Read fehlte der Guard.
>
> **Fix:** Phase 1 hält die geschriebene Menge in `importedFavorites`; Phase 2
> filtert die Order gegen diese authoritative Menge statt gegen den Hot-Flow.
> Nur wenn Favoriten *nicht* mitimportiert werden (kein Write in diesem Import,
> Cache kann nicht laggen), liest Phase 2 noch aus dem Flow. Gepinnt durch
> `BackupDataAssemblerImportOrderStaleCacheTest` (laggende Flow-Stub, die auf
> dem Pre-Import-Wert bleibt; schlägt fehl, sobald Phase 2 dem Flow wieder
> vertraut). Der bestehende `externalScope=null`-Testpfad konnte den Lag nie
> reproduzieren (Cold-Flow, immer frisch).

**Kategorie:** concurrency / Datenverlust
**Behauptung:** Import-Phase 2 re-liest die Favoriten über einen
`WhileSubscribed`-Hot-Flow, dessen Replay-Cache dem Phase-1-Schreibvorgang
hinterherhinken kann.
**Fehlerszenario:** Beim Import liest Phase 2 einen veralteten Cache-Stand →
die gerade geschriebene Favoriten-**Reihenfolge** wird still verworfen.

---

### #3 — Nicht-atomares Überschreiben zerstört Backup · `BackupRepositoryImpl.kt:250`

> **🔎 VERDIKT (2026-07-27, verifiziert — noch offen): PARTIAL — kein klarer
> Defekt.** Beobachtung stimmt (direkter `openOutputStream`-Write, kein
> temp+rename), aber das Ziel ist ein per `CreateDocument` gewähltes
> SAF-`content://`-Dokument — atomares temp+rename ist über beliebige
> DocumentsProvider nicht portabel möglich, direktes Schreiben ist das
> Standard-SAF-Muster. „Altes Backup zerstört" verlangt zudem, dass der Nutzer
> bewusst eine bestehende Datei zum Überschreiben wählt UND der Write
> mittendrin scheitert; Fehler werden als `BackupException` sichtbar (nicht
> still). Randfall, kein umzusetzender Fix auf dem SAF-Pfad.

**Kategorie:** Datenverlust
**Behauptung:** Das vorhandene Backup wird nicht atomar (write-temp +
rename) überschrieben.
**Fehlerszenario:** Schlägt der Schreibvorgang mittendrin fehl (Speicher
voll, Prozess-Kill), ist das **alte** Backup bereits zerstört und das neue
unvollständig → kein gültiges Backup mehr vorhanden.

---

### #4 — `ContentResolver.query()` auf dem Main-Thread · `WallpaperDelegate.kt:421-437` (+ `getDisplayName` :724-736)

> **✅ RESOLUTION (2026-07-27): CONFIRMED → behoben.** Jedes Glied der Kette
> war bestätigt: `launchSafe` startet auf `mainDispatcher`;
> `getDisplayName(imageUri)` war die erste Anweisung, noch vor dem einzigen
> `withContext(IO)` in `copyToInternal`; `getDisplayName` rief
> `contentResolver.query(...)` synchron ohne eigenen IO-Wechsel. Bei einer
> SAF-/Cloud-`content://`-URI ist das ein blockierender Binder-IPC auf dem
> Main-Thread der HOME-Activity → StrictMode/ANR-Risiko. Der `catch(Throwable)`
> verhinderte nur den Crash, nicht das Blockieren.
>
> **Fix:** `getDisplayName` ist jetzt `suspend` und wrappt seinen Body in
> `withContext(ioDispatcher)`. Der IO-Dispatcher wird per `@IoDispatcher`
> injiziert (`WallpaperDelegate`-Konstruktor ← `LauncherViewModel` ←
> `DispatcherModule`), damit Tests ihn deterministisch überschreiben können.
> Der Multi-Layer-Pfad (`onAddWallpaperLayer`) war nie betroffen — er ruft
> `getDisplayName` nicht. Gepinnt durch `WallpaperDelegateTest` →
> `onSetWallpaperImage resolves display name off the main dispatcher` (zählt
> die Dispatches auf den injizierten IO-Dispatcher; schlägt fehl, sobald der
> Query wieder inline auf Main läuft).

**Kategorie:** Nebenläufigkeit / StrictMode / ANR-Risiko
**Behauptung:** `onSetWallpaperImage` läuft über `scope.launchSafe { … }`
(startet auf `Dispatchers.Main`) und ruft zuerst `getDisplayName(imageUri)`
auf, das `context.contentResolver.query(...)` **synchron auf dem
Main-Thread** ausführt. Erst das danach aufgerufene `copyToInternal` wechselt
per `withContext(IO)` weg.
**Fehlerszenario:** Nutzer wählt ein Wallpaper von einem SAF-/Cloud-Provider
(`content://`). Der DISPLAY_NAME-Query wird zum Binder-Call in einen ggf.
kalten Fremd-Provider und blockiert den Main-Thread der HOME-Activity →
StrictMode-`DiskReadViolation`/IPC in DEBUG, im Extremfall kurzer
Freeze/ANR. Inkonsistent zum Clipboard-Pfad, der `coerceToText` bewusst auf
`Dispatchers.IO` wickelt.

---

### #5 — `MutableSharedFlow` ohne Buffer verliert Update-Signal · `AppUpdateSignal.kt:17`

> **🔎 VERDIKT (2026-07-27, verifiziert — noch offen): PARTIAL — Mechanismus
> real, aber mitigiert.** Der Signalverlust-Pfad existiert (Rendezvous-Flow,
> `emit` suspendiert bei fehlendem Collector bis zum 3s-Timeout, wird
> gecancelt). Aber Collector (`listenForAppUpdates()`) und Apps-Flow-Sub sind
> im selben Scope lifecycle-gekoppelt: das Signal kann nur verloren gehen,
> während der Collector fehlt — also genau dann, wenn die Apps-Flow
> unsubscribed ist und ihr `onStart`-Requery beim nächsten Subscribe ohnehin
> neu lädt. Kein Stale-/Missing-App-Defekt. `extraBufferCapacity = 1` bleibt
> günstiges Konsistenz-Hardening, kein Muss.

**Kategorie:** MutableSharedFlow ohne Buffer/Subscriber
**Behauptung:** `MutableSharedFlow<Unit>()` mit `replay = 0` /
`extraBufferCapacity = 0`. Einziger Collector ist
`AppManagementDelegate.listenForAppUpdates()` (ViewModel-Scope); Sender ist
`PackageUpdateReceiver.processPackageUpdate()`, umschlossen von
`withTimeout(3000)`.
**Fehlerszenario:** Kommt ein Package-Broadcast, während (noch) kein
Collector aktiv ist (Prozess wird durch den Broadcast gerade erst
gestartet), suspendiert `emit` bis zum 3s-Timeout, wird gecancelt
(`CancellationException` verschluckt) → Signal verloren, App-Update-Refresh
über diesen Pfad fällt aus.
**Mildernd:** `InstalledAppsRepositoryImpl` requery't per `onStart` bei
erneuter Subscription → begrenzter Realimpact.
**Inkonsistenz:** Schwester-Trigger sind korrekt gepuffert
(`AppUpdateModule.kt:18` `extraBufferCapacity=1`, `ErrorEventBus.kt`
`replay=5`, `BaseViewModel.kt:41` bewusst `Channel(BUFFERED)`).
**Fix-Hinweis:** `extraBufferCapacity = 1` (oder `replay = 1`).

---

## 🟡 Low

### #6 — Usage-Export überschreibt Zieldatei nicht-atomar · `UsageExportRepositoryImpl.kt:384`

> **🔎 VERDIKT (2026-07-27, verifiziert — noch offen): PARTIAL — kein
> actionable Defekt.** Direkter `openOutputStream`-Write auf eine
> SAF-`content://`-URI, kein temp+rename — dieselbe SAF-Einschränkung wie #3
> (atomarer Rename nicht portabel möglich), plus der Export ist trivial
> reproduzierbar. Beobachtung korrekt, keine Maßnahme nötig.

**Kategorie:** Datenverlust — dasselbe Muster wie #3, geringeres Gewicht
(Export ist reproduzierbar).

### #7 — Reset löscht Wallpaper-Bilddateien nicht · `ResetRepositoryImpl.kt:79`

> **✅ RESOLUTION (2026-07-27): CONFIRMED → behoben.** Es war ein echter
> Code/Kommentar-Widerspruch: `WallpaperFileManager.clearAll()`'s KDoc nannte
> Factory Reset als Aufrufer, aber der Reset-Pfad
> (`ResetRepositoryImpl.purgeAll` → `WallpaperRepositoryImpl.purgeRepository`)
> leerte nur den DataStore. `gcOrphans` räumt verwaiste Dateien nur beim
> nächsten Kaltstart und mit 60-s-Cutoff — kein promptes Aufräumen. **Fix:**
> `purgeRepository()` löscht jetzt zusätzlich die Bilddateien via
> `clearAll()` (IO-gewrappt); der Code passt damit zum bestehenden KDoc.
> Gepinnt durch `WallpaperRepositoryImplTest` → `purgeRepository also deletes
> on-disk wallpaper files`.

**Kategorie:** Konsistenz
**Behauptung:** Der Daten-Reset leert nur den DataStore, lässt aber die
Wallpaper-Bilddateien auf der Platte liegen → verwaiste Dateien.

### #8 — ANR-Watermark rückt trotz fehlgeschlagenem Send vor · `KolibriLauncherApp.kt:461-485` vs. `AnrReporter.kt:57-64`

> **🔎 VERDIKT (2026-07-27, verifiziert — noch offen): CONFIRMED.** Alle drei
> Glieder bestätigt: `markReported` läuft nur, wenn `handler(report)` normal
> zurückkehrt; der Prod-Handler (`Timber.e` → `AcraTree.reportErrorToAcra`)
> schluckt jeden `handleSilentException`-Fehler, kann also nie werfen; das
> Klassen-KDoc verspricht dennoch Retry-bei-Fehler. Ergebnis: Watermark rückt
> immer vor, die zugesicherte „transient ACRA failure doesn't permanently lose
> ANRs"-Eigenschaft ist toter Code. **Leichter Fix:** entweder Handler werfen
> lassen oder das KDoc korrigieren (Retry-Versprechen streichen).

**Kategorie:** contract/implementation mismatch, Telemetrie-Verlust
**Behauptung:** `AnrReporter.reportPendingAnrs` rückt die persistierte
Watermark nur nach erfolgreichem `handler(report)` vor, und das KDoc
verspricht Retry bei Handler-Fehler. Der Produktions-Handler ist aber
`Timber.e(...)` → `AcraTree.reportErrorToAcra` (`:590-606`), das jeden
Fehler von `handleSilentException` **schluckt**. Der Handler kann also nie
werfen → `markReported` läuft immer → Watermark rückt immer vor.
**Fehlerszenario:** ACRA-Init fehlgeschlagen oder Send scheitert für einen
post-mortem ANR → Report verloren, Watermark trotzdem vorgerückt → die im
KDoc garantierte „retried next launch"-Behandlung greift nie.

### #9 — `shouldSend` berechnet, nie ausgewertet + Cooldown-Nebeneffekt · `KolibriLauncherApp.kt:363-366`

> **🔎 VERDIKT (2026-07-27, verifiziert — noch offen): PARTIAL — Dead Code
> real, Schaden widerlegt.** `shouldSend` fließt nur in ein Log, nie in einen
> Guard; der Default-Handler läuft bedingungslos (DEBUG-only, `:237`). ABER:
> die echte Drosselung passiert in `AcraTree.log` (`:579`), erreicht über den
> `Timber.e` bei `:360` derselben Invocation — der belegt den 24h-Slot schon,
> bevor `:363` läuft. Dadurch liefert `:363` `false` und schreibt **nichts**
> (Write ist an `shouldSend` geknüpft). Also **kein** stiller Cooldown-Verbrauch
> und **keine** ungedrosselte Sendung. Übrig bleibt ein kosmetischer,
> irreführender „blocked"-Log in DEBUG.

**Kategorie:** dead/misleading logic + unbeabsichtigter Seiteneffekt
**Behauptung:** `val shouldSend = CrashReportLimiter.shouldSendReport(...)`
wird geloggt („Report blocked by spam protection"), aber nie ausgewertet —
der Default-(ACRA-)Handler läuft `:381` bedingungslos. Zusätzlich schreibt
`shouldSendReport` den Cooldown-Timestamp (`CrashReportLimiter.kt:124-126`).
**Fehlerszenario (nur DEBUG, `:237`):** Ein fataler Crash loggt „blocked by
spam protection", wird aber trotzdem gesendet, und verbrennt still das
24h-Dedup-Slot der Exception-Klasse. Liest sich wie Enforcement, erzwingt
nichts.

### #10 — Usage-Export-ViewModel verschluckt Throws · `UsageExportViewModel.kt:21`

> **✅ RESOLUTION (2026-07-29): REFUTED — Fehlalarm, Verify-Kette zu früh
> abgebrochen.** Das ursprüngliche CONFIRMED stützte sich auf zwei korrekte,
> aber unvollständige Beobachtungen: `UsageExportViewModel` überschreibt
> `errorEvent` nicht (Default `null`, `BaseViewModel.kt:165`) und
> `ImportUsageFromFileUseCase` hat kein try/catch (delegiert nur, `:15-17`).
> Der Verify-Agent hat aber **nicht** in `loadFromFile` selbst hineingesehen:
> `UsageExportRepositoryImpl.loadFromFile` (`:434-439`) hat einen Catch-All
> `catch(CancellationException){throw} catch(Throwable){ silentError; return
> UsageImportResult.Error("Load failed: …") }`; der JSON-Parsing-Pfad
> `importFromJson` hat zusätzlich seinen eigenen `catch(Throwable)→Error`
> (`:172-174`). Ein unerwarteter Throw kann `loadFromFile` also gar nicht als
> Exception verlassen — er wird zu `Error`, und `handleImportResult` mappt das
> auf `sendEvent(UsageExportUiEvent.ImportError(...))` (`:75-78`). **In Release
> bekommt der Nutzer also immer Feedback** (Success / InvalidFormat /
> UnsupportedVersion / Error).
>
> Der einzige „Swallow" ist DEBUG-only und **by design**: `silentError`
> (`:437`) wirft in DEBUG erneut (`TimberWrapper.crashInDebug`, `:67-68`) —
> der Throw eskaliert dann durch `launchSafe`→`handleError`→
> `showErrorToastIfSupported()` = No-op (weil `errorEvent == null`), Spinner
> stoppt, kein Toast. Das ist genau die gewollte Rule-9-Semantik („loud in
> dev"), kein Production-Defekt. **Nichts umzusetzen.**

**Kategorie:** Fehlerbehandlung
**Behauptung:** Verschluckt unerwartete Throws ohne Nutzer-Feedback.

### #11 — Unstrukturierter Scope pro Klick · `CrashReportConsent.kt:81/90`

> **🔎 VERDIKT (2026-07-27, verifiziert — noch offen): CONFIRMED als harmloser
> struktureller Nit.** Das unreferenzierte `CoroutineScope(Dispatchers.IO)`
> pro Klick existiert wie beschrieben, und `saveConsent` fängt intern ab (kein
> uncaught Throw möglich). Echter Struktur-Verstoß, funktional harmlos — auf
> dem Rule-9-Bootstrap-Pfad toleriert. Deckt sich mit der Selbst-Bewertung.

**Kategorie:** launch ohne strukturierte Nebenläufigkeit
**Behauptung:** Pro Button-Klick ein neuer, unreferenzierter
`CoroutineScope(Dispatchers.IO)` mit `launch { saveConsent(...) }` — kein
Parent-Job, keine Cancellation, kein ExceptionHandler.
**Bewertung:** In der Praxis harmlos (`CrashReportConsentStore.saveConsent`
fängt selbst ab, Write ist kurz), aber struktureller Verstoß. Datei liegt auf
dem Crash-Infra-Bootstrap-Pfad (Rule-9-Ausnahmeliste) → toleriert, nicht
ideal.

### #12 — `applyTransform` clampt gegen veraltete Bounds · `ZoomableImageView.kt:341`

> **✅ RESOLUTION (2026-07-28): CONFIRMED → behoben.** Am Code verifiziert: der
> veraltete Term ist NICHT die Bild-/View-Größe (`updateSingleBaseScale()` bei
> `:340` frischt die korrekt auf), sondern der current-*scale*-Term: die
> Bound-Getter (`effectiveMin/MaxScale`) lesen `_singleScale` als `currentScale`,
> und die `coerceIn`-Argumente werden ausgewertet, *bevor* `:341` zuweist — also
> noch gegen den Vor-Restore-Wert. Erreichbares Szenario: Wallpaper größer als
> View (`baseScale < 1`), Vor-Restore `_singleScale ≈ 1.0` ⇒ `effectiveMaxScale =
> maxOf(5.0, 1.0*3) = 5.0`; eine gespeicherte Skalierung > 5× (interaktiv
> erreichbar, weil die Decke beim Reinzoomen inkrementell mitwächst) wird still
> auf 5× gekappt. Nur der Single-Layer-Pfad ist live betroffen — der
> Layer-Restore (`applyTransform(layerIndex, …)` `:613`) berechnet die Grenzen
> aus `computeLayerBaseScale` und liest die aktuelle Skalierung nicht.
>
> **Fix (nach unabhängigem Review verfeinert):** Der Restore wird **nicht**
> magnitude-geclampt. Grund: Ein persistierter Zoom (z. B. 8×) war legitim, aber
> die dynamische Decke, die ihn erlaubte (sie wächst mit dem aktuellen Scale),
> lässt sich beim Restore nicht rekonstruieren — Magnitude-Clamping und
> Restore-Treue widersprechen sich also grundsätzlich. Der erste Ansatz
> (`_singleScale = scale` vor `coerceIn`) hätte den `coerceIn` faktisch zum
> No-op gemacht (Decke ≥ Wert für jeden positiven Input) und dabei still die
> Schutzwirkung gegen korrupte persistierte Werte verloren (Infinity → NaN/Inf-
> Matrix, negativ → negativer Scale). Stattdessen: den Gesten-Pfad die
> interaktive Eingabe bounden lassen und beim Restore nur **sanitisieren** —
> `_singleScale = if (scale.isFinite() && scale > 0f) scale else DEFAULT_SCALE`.
> `updateSingleBaseScale()` bleibt (frischt `_singleBaseScale` für spätere
> Gesten-Bounds). Gepinnt durch `ZoomableImageViewRestoreScaleRobolectricTest`
> (3 `@Test`: legit 8× wird geehrt statt auf 5× gekappt; moderater In-Range-Zoom
> unverändert; korrupte Werte — ±Inf/NaN/negativ/0 — fallen auf `DEFAULT_SCALE`
> zurück). View-embedded Bounds (Layout-Maße + Drawable-Intrinsics) →
> Robolectric statt JVM, per Rule 10.

**Kategorie:** correctness
**Behauptung:** Single-Layer `applyTransform` clampt die wiederhergestellte
Skalierung gegen veraltete current-scale-Bounds.

### #13 — Irreführender Fehler-Toast bei erfolgreichem Launch · `AppManagementDelegate.kt:126-137`

> **🔎 VERDIKT (2026-07-27, verifiziert — noch offen): PARTIAL.** Reihenfolge
> und der irreführende `error_launching_app`-Toast bei einem Fehler der
> *nachgelagerten* Use-Cases (`recordAppLaunchUseCase`/`refreshAppsUseCase`)
> sind bestätigt und erreichbar. Aber „redundant im launchSafe-Netz" ist
> falsch: dieser Aufruf übergibt kein `defaultErrorToast` (Default `null`), der
> innere Catch ist also die *einzige* Quelle des Toasts — nur das
> `silentError`-Logging ist doppelt.

**Kategorie:** UX / Fehlerbehandlung (+ Rule-11-Doppelabsicherung)
**Behauptung:** `onAppClicked` setzt zuerst `UiEvent.LaunchApp(app)` ab (App
startet), danach laufen `recordAppLaunchUseCase`/`refreshAppsUseCase`. Der
innere `catch (Throwable)` fängt Fehler dieser **nachgelagerten** Use-Cases
und zeigt `R.string.error_launching_app` — obwohl die App bereits gestartet
ist. Der innere try/catch liegt zudem redundant im vorhandenen
`launchSafe`-Netz.
**Fehlerszenario:** App startet korrekt; `recordAppLaunchUseCase`
(DataStore-Write) wirft → Nutzer sieht „Fehler beim Starten der App".

---

## Nachträglich beim #4-Fix gefunden (nicht Teil der 13, offen)

### #N1 — `deleteFile` / `clearAll` / `gcOrphans` machten synchrone Disk-IO auf dem Main-Thread · `WallpaperFileManager.kt:148/168/284`

> **✅ RESOLUTION (2026-07-27): CONFIRMED → behoben.** Beim adversarialen
> Review von #4 aufgefallen, **nicht durch #4 verursacht** (bestand vorher
> schon) und **nicht** vom #4-Finder gemeldet.
> `WallpaperFileManager`s disk-mutierende Methoden (`deleteFile()`,
> `clearAll()`, `gcOrphans()`) sind weder `suspend`
> noch `withContext`-gewrappt; sie führen `File.delete()` /
> `dir.listFiles()` synchron aus. Aufgerufen wurden sie aus mehreren
> `launchSafe`-Blöcken, die auf `mainDispatcher` starten: `persist` (nach dem
> DataStore-Suspend zurück auf Main), `onCommitWallpaperEditMode`,
> `onCancelWallpaperEditMode`, der `onAddWallpaperLayer`-Rollback,
> `onClearWallpaper` → `clearAll` und der `start()`-Observe-Collect →
> `gcOrphans`.
>
> **Severity:** dieselbe StrictMode-Klasse wie #4, aber deutlich geringer —
> interner App-Speicher (lokales Dateisystem, µs–low-ms), **kein** Binder-IPC
> in einen kalten Fremd-Provider. StrictMode-`DiskWriteViolation` in DEBUG
> möglich, echtes ANR-Risiko praktisch nicht.
>
> **Fix:** Die sechs Main-Thread-Aufrufstellen im `WallpaperDelegate` sind
> jetzt in `withContext(ioDispatcher)` gewrappt (derselbe injizierte
> Dispatcher wie beim #4-Fix) — konsistent mit der bereits bestehenden
> Konvention in `WallpaperRepositoryImpl.purgeRepository` (die `clearAll()`
> schon am Call-Site IO-wrappte, #7-Fix). Bewusst **nicht** die
> `WallpaperFileManager`-Signaturen auf `suspend` umgestellt, um den
> Robolectric-Testsatz der Klasse (~12 synchrone `gcOrphans`/`deleteFile`-
> Tests) nicht anfassen zu müssen. `gcOrphans` behält korrekte
> Cancellation-Propagation (`catch(CancellationException){ throw e }`).
> Gepinnt durch drei `WallpaperDelegateTest`-Guards (`onClearWallpaper …`,
> `onRemoveWallpaperLayer … off the main dispatcher`, `start runs gcOrphans
> off the main dispatcher`), die die Dispatches auf den injizierten
> IO-Dispatcher zählen.
>
> _Hinweis:_ Der reine Read-Pfad `fileExists()` (im Observe/Parse-Flow) wurde
> **nicht** angefasst — kein Teil von #N1, anderer Dispatcher-Kontext.

---

## Methodik & Grenzen

- **10 Finder-Agenten** parallel, je ein abgegrenzter Bereich; zwei davon
  querschnittlich (Coroutinen/Flow, Architektur-Regeln).
- **Verify-Phase am 2026-07-27 nachgeholt** (ursprünglich aus Token-Budget
  übersprungen). Jeder Fund wurde adversarial am Code gegengeprüft; die
  Verdikte stehen oben in der Tabelle und beim jeweiligen Fund.
- Die „0 Funde"-Bereiche bedeuten **„kein Finder-Agent hat hier etwas
  gemeldet"**, nicht „bewiesen fehlerfrei".
- Zeilennummern beziehen sich auf Commit `626e4d63`.

**Verifikations-Ergebnis:** CONFIRMED #2, #4, #8, #11, #12 · PARTIAL #3,
#5, #6, #9, #13 · REFUTED #1, #10 · behoben #1, #2, #4, #7, #12.
(#10 am 2026-07-29 von CONFIRMED auf REFUTED korrigiert.) Das zuvor einzige
offen & klar actionable **#2** (Stale-Cache verwirft Import-Reihenfolge) ist
seit 2026-07-27 behoben (Phase 2 nutzt die von Phase 1 geschriebene Menge,
gepinnt durch `BackupDataAssemblerImportOrderStaleCacheTest`); ebenso **#4**
(Main-Thread-Query → `withContext(ioDispatcher)`) und **#12** (Restore-Clamp
gegen die Vor-Restore-Skalierung, gepinnt durch
`ZoomableImageViewRestoreScaleRobolectricTest`). Die restlichen CONFIRMED
sind Kosmetik/Nits oder Doc-Fixes (#8, #11), die PARTIALs überwiegend
kein umzusetzender Defekt. #10 wurde am 2026-07-29 auf REFUTED korrigiert.
