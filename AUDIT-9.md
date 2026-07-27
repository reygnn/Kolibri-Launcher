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

## ⚠️ STATUS: UNVERIFIZIERT — zuerst prüfen, dann handeln

**Diese Funde sind NICHT durch eine adversariale Verify-Phase gelaufen.**
Die geplante zweite Stufe (jeder Fund wird von einem eigenen Agenten mit dem
Auftrag „widerlege das" gegengeprüft) wurde bewusst übersprungen, um das
Token-Budget zu schonen. Was hier steht, sind **Behauptungen der
Finder-Agenten mit Codebeleg, aber ohne Gegenprüfung**.

**Konsequenz — vor jedem Fix gilt:**

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
| 2 | 🟠 medium | Backup | `BackupDataAssembler.kt:226` | Import-Phase 2 liest Favoriten über laggenden WhileSubscribed-Cache |
| 3 | 🟠 medium | Backup | `BackupRepositoryImpl.kt:250` | Nicht-atomares Überschreiben zerstört Backup bei Schreib-Fehler |
| 4 | 🟠 medium | Nebenläufigkeit | `WallpaperDelegate.kt:421-437` | `ContentResolver.query()` synchron auf Main-Thread |
| 5 | 🟠 medium | Nebenläufigkeit | `AppUpdateSignal.kt:17` | `MutableSharedFlow` ohne Buffer → Signal verloren |
| 6 | 🟡 low | Datenverlust | `UsageExportRepositoryImpl.kt:384` | Usage-Export überschreibt Zieldatei nicht-atomar |
| 7 | 🟡 low | Konsistenz | `ResetRepositoryImpl.kt:79` | Reset löscht Wallpaper-Bilddateien nicht (nur DataStore) |
| 8 | 🟡 low | Telemetrie | `KolibriLauncherApp.kt:461-485` | ANR-Watermark rückt auch bei fehlgeschlagenem Send vor |
| 9 | 🟡 low | Fehlerbehandlung | `KolibriLauncherApp.kt:363-366` | `shouldSend` berechnet, nie ausgewertet; verbrennt Cooldown |
| 10 | 🟡 low | Fehlerbehandlung | `UsageExportViewModel.kt:21` | Verschluckt Throws ohne Nutzer-Feedback |
| 11 | 🟡 low | Nebenläufigkeit | `CrashReportConsent.kt:81/90` | Unstrukturierter `CoroutineScope(IO).launch` pro Klick |
| 12 | 🟡 low | Korrektheit | `ZoomableImageView.kt:341` | `applyTransform` clampt Skalierung gegen veraltete Bounds |
| 13 | 🟡 low | UX / Fehlerbehandlung | `AppManagementDelegate.kt:126-137` | Irreführender „Fehler beim Starten"-Toast bei erfolgreichem Launch |

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

**Kategorie:** concurrency / Datenverlust
**Behauptung:** Import-Phase 2 re-liest die Favoriten über einen
`WhileSubscribed`-Hot-Flow, dessen Replay-Cache dem Phase-1-Schreibvorgang
hinterherhinken kann.
**Fehlerszenario:** Beim Import liest Phase 2 einen veralteten Cache-Stand →
die gerade geschriebene Favoriten-**Reihenfolge** wird still verworfen.

---

### #3 — Nicht-atomares Überschreiben zerstört Backup · `BackupRepositoryImpl.kt:250`

**Kategorie:** Datenverlust
**Behauptung:** Das vorhandene Backup wird nicht atomar (write-temp +
rename) überschrieben.
**Fehlerszenario:** Schlägt der Schreibvorgang mittendrin fehl (Speicher
voll, Prozess-Kill), ist das **alte** Backup bereits zerstört und das neue
unvollständig → kein gültiges Backup mehr vorhanden.

---

### #4 — `ContentResolver.query()` auf dem Main-Thread · `WallpaperDelegate.kt:421-437` (+ `getDisplayName` :724-736)

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

**Kategorie:** Datenverlust — dasselbe Muster wie #3, geringeres Gewicht
(Export ist reproduzierbar).

### #7 — Reset löscht Wallpaper-Bilddateien nicht · `ResetRepositoryImpl.kt:79`

**Kategorie:** Konsistenz
**Behauptung:** Der Daten-Reset leert nur den DataStore, lässt aber die
Wallpaper-Bilddateien auf der Platte liegen → verwaiste Dateien.

### #8 — ANR-Watermark rückt trotz fehlgeschlagenem Send vor · `KolibriLauncherApp.kt:461-485` vs. `AnrReporter.kt:57-64`

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

**Kategorie:** Fehlerbehandlung
**Behauptung:** Verschluckt unerwartete Throws ohne Nutzer-Feedback.

### #11 — Unstrukturierter Scope pro Klick · `CrashReportConsent.kt:81/90`

**Kategorie:** launch ohne strukturierte Nebenläufigkeit
**Behauptung:** Pro Button-Klick ein neuer, unreferenzierter
`CoroutineScope(Dispatchers.IO)` mit `launch { saveConsent(...) }` — kein
Parent-Job, keine Cancellation, kein ExceptionHandler.
**Bewertung:** In der Praxis harmlos (`CrashReportConsentStore.saveConsent`
fängt selbst ab, Write ist kurz), aber struktureller Verstoß. Datei liegt auf
dem Crash-Infra-Bootstrap-Pfad (Rule-9-Ausnahmeliste) → toleriert, nicht
ideal.

### #12 — `applyTransform` clampt gegen veraltete Bounds · `ZoomableImageView.kt:341`

**Kategorie:** correctness
**Behauptung:** Single-Layer `applyTransform` clampt die wiederhergestellte
Skalierung gegen veraltete current-scale-Bounds.

### #13 — Irreführender Fehler-Toast bei erfolgreichem Launch · `AppManagementDelegate.kt:126-137`

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

## Methodik & Grenzen

- **10 Finder-Agenten** parallel, je ein abgegrenzter Bereich; zwei davon
  querschnittlich (Coroutinen/Flow, Architektur-Regeln).
- **Verify-Phase übersprungen** (Token-Budget) — daher der Status-Hinweis
  oben. Ein vollständiger Lauf würde jeden Fund adversarial gegenprüfen und
  Schweregrade korrigieren.
- Die „0 Funde"-Bereiche bedeuten **„kein Finder-Agent hat hier etwas
  gemeldet"**, nicht „bewiesen fehlerfrei".
- Zeilennummern beziehen sich auf Commit `626e4d63`.

**Nächster sinnvoller Schritt:** Verifikation der medium-Funde (#1–#5),
beginnend mit dem Backup-Cluster (#1–#3) und dem AUDIT-8-Abgleich für #1.
