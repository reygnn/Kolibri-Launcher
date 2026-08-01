# AUDIT-11 — Branch-Review `feature/acra-rewrite` (quellenverifiziert)

> **Erzeugt** 2026-08-01 durch einen Multi-Agent-Review (Claude Opus 4.8)
> des Branches `feature/acra-rewrite` (Version 0.99.136 / code 156,
> HEAD `753a1ea0`, Diff gegen `main`: ~5.400 Zeilen über 67 Dateien,
> drei Module).
> **Fokus:** der grüne-Wiese-Rewrite des ACRA-Subsystems in drei fachliche
> Schichten (`crashreporting/{consent,ingestion,resilience}`) plus
> Cross-Cutting-Verdrahtung (ausgedünntes `KolibriLauncherApp`, DI, MainActivity/
> SettingsFragment, Linter). Umgesetzt als vertikale Schnitte über die Module.
> **Methode:** zwei Durchläufe, je 4 Finder-Agenten (ein Schnitt pro Agent:
> Consent / Ingestion / Resilience / Cross-Cutting) und ein **adversarialer
> Verifizierer** pro Schnitt (Finding überlebt nur, wenn der Skeptiker das
> konkrete Failure-Szenario gegen den echten Quelltext bestätigen kann).
>
>  - **Durchlauf 1 — Korrektheit + Konventionen:** Bugs, Crash-Pipeline-
>    Korrektheit (Doppel-Send, Consent-Gate-Reihenfolge), harte CLAUDE.md-
>    Regeln (7/8/9/11/12). → **0 bestätigte Funde** (2 Kandidaten geprüft &
>    verworfen, siehe unten).
>  - **Durchlauf 2 — Nebenläufigkeit:** Main- vs. Disk-I/O-Thread, Dispatcher,
>    Coroutine-Scopes/Cancellation, Atomarität, Sichtbarkeit, Hilt-Scoping als
>    Nebenläufigkeitsfrage. → **2 bestätigte Funde** (#1, #2; 1 Kandidat
>    geprüft & verworfen).
>
> Beide bestätigten Funde wurden nach dem Workflow **von Hand gegen den
> Quelltext nachgeprüft** (Datei/Zeilen unten belegt) und vom Verifizierer
> herabgestuft (high→medium bzw. medium→low).

---

## Bestätigte Funde

### #1 — `medium` · `onCreate` ist nicht prozess-gated → ANR-Drain läuft im `:acra`-Sender-Prozess und schreibt den geteilten Settings-DataStore cross-process

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/crashreporting/resilience/CrashReportingBootstrap.kt:115-119`
(Aufrufer: `KolibriLauncherApp.kt:160`)
**Kategorie:** cross-process-data-race (Nebenläufigkeit)

**Kern:** `attachBaseContext` gated seinen Consent-Read bewusst auf
`if (!ACRA.isACRASenderServiceProcess())` (`CrashReportingBootstrap.kt:94`,
Kommentar §X2: „only the main process reads consent"). `onCreate`
(`:115-119`) tut das **nicht** — es plant `AcraTree`, treibt den ANR-Drain
und startet den Watchdog **unbedingt**. `KolibriLauncherApp.onCreate:160` ruft
es ebenfalls ungated auf. ACRA betreibt seinen HTTP-Sender in einem eigenen
`:acra`-Prozess; dessen Application-Instanz durchläuft `onCreate` also mit.

**Failure-Szenario:** Ein früher enqueuter ACRA-Report lässt den
`:acra`-Prozess anlaufen (dokumentierter Dead-Sender-Backlog X4 hält ihn am
Respawnen). Dessen `onCreate` startet `reportPendingAnrsAsync`. `AnrReporter`
injiziert den **unqualifizierten** `DataStore<Preferences>` — also den
Settings-Store (`AnrReporter.kt:69`, KDoc „Watermark … lives in the project's
settings DataStore") — und `reportPendingAnrs` ruft nach jedem Handler
`markReported`, das das Watermark `anr_reporter_last_reported_ts` vorrückt
(`AnrReporter.kt:83-89`, `:121-134`). Im `:acra`-Prozess ist `ACRA.errorReporter`
aber ein No-op-Stub (ACRA_FLOW.md §X2) und `AcraTree` schluckt ohnehin — der
ANR-Handler wirft nie, also rückt das Watermark über einen **frischen, nie
gesendeten** ANR vor. Startet der Haupt-(HOME-)Prozess neu, liest er das bereits
vorgerückte Watermark, filtert genau diesen ANR heraus (`timestamp > lastReported
== false`, `:105`) und meldet ihn nie → **stiller ANR-Verlust**. Zusätzlich
schreiben nun zwei Prozesse `dataStore.edit{}` auf dieselbe Datei eines
**nicht multiprozess-sicheren** DataStore (Standard-`PreferenceDataStoreFactory`,
kein Cross-Process-Lock; ACRA_FLOW.md §X2 nennt den Store explizit nicht
multiprozess-sicher) → Last-Write-Wins kann einen echten, gleichzeitigen
User-Setting-Write des Hauptprozesses überschreiben.

**Warum kein Doku-Freibrief:** §AN4 akzeptiert ein Watermark-Vorrücken nur für
Enqueue-Fehler **im Hauptprozess**; §X2 gated den Consent-**Read** exakt wegen
der Nicht-Multiprozess-Sicherheit dieses Stores — lässt aber den strikt
schlimmeren ANR-Cross-Process-**Write** ungated.

**Fix-Richtung:** In `CrashReportingBootstrap.onCreate` dieselbe
`!ACRA.isACRASenderServiceProcess()`-Klammer setzen wie in `attachBaseContext`
(mindestens um den ANR-Drain; Tree-Plant und Watchdog im `:acra`-Prozess sind
bestenfalls nutzlos, gehören aber ebenfalls hinter das Gate).

**Status (2026-08-01): behoben.** `onCreate` returnt früh im Sender-Prozess
(gesamter Body hinter dem Gate; Prozess-Prädikat als Default-Parameter injiziert,
Muster wie `UncaughtCrashHandler.killSwitch`). Gepinnt durch
`CrashReportingBootstrapProcessGateTest` (testDebug): Sender-Prozess ruft
`reportPendingAnrs` **nie**, Hauptprozess **einmal**.

---

### #2 — `low` · Consent-Widerruf löscht die ACRA-Report-Dateien (Disk-I/O) synchron auf dem Main-Thread

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/crashreporting/consent/ConsentController.kt:86-95` (Purge-Aufruf `:91`)
**Kategorie:** main-thread-blocking (Nebenläufigkeit)

**Kern:** `applyConsent(granted)` läuft synchron auf dem Caller-Thread. Nur der
Persist-Write wurde bewusst ausgelagert (`persistConsent` →
`applicationScope.launch`, `:118-119`) — der **gleichermaßen I/O-lastige** Purge
`acraToggle.purgeReportQueue()` (`:91`) blieb inline. `AcraToggleImpl.purgeReportQueue`
führt `BulkReportDeleter.deleteReports(true,0)` + `deleteReports(false,0)` aus
(`AcraToggle.kt:38-46`) = Verzeichnis-Enumeration + `File.delete()` = echtes
Datei-I/O. Beide Aufrufer rufen `applyConsent` aus dem Negativ-Button-Listener
von `ConsentDialog` (`MainActivity.kt` / `SettingsFragment.kt`), der auf
`Dispatchers.Main` läuft.

**Failure-Szenario:** Nutzer tippt „Ablehnen"/Widerruf (Erststart-Dialog oder
Einstellungen → Absturzberichte). Der Button-Listener läuft auf dem Main-Thread
→ `applyConsent(false)` → `purgeReportQueue()` enumeriert die approved- +
unapproved-Ordner und löscht jede Datei — alles auf dem Main-Thread. Bei
nicht-leerer Queue ruckelt der Launcher-Home-Screen; in DEBUG trippt
`StrictMode.detectAll().penaltyLog().penaltyFlashScreen()` (KolibriLauncherApp)
garantiert eine Disk-Write-Violation.

**Warum nur `low`:** Die ACRA-Queue ist beschränkt (Reports drainen beim Start;
realistisch 0–wenige Dateien), das Löschen dauert Millisekunden, kein 5-s-ANR.
Realer, garantierter Schaden: DEBUG-StrictMode-Violation + kleiner Main-Thread-
Jank + Inkonsistenz zum bereits ausgelagerten Persist.

**Fix-Richtung:** `setEnabled(granted)` muss synchron bleiben (in-memory, stoppt
ACRA-Capture sofort), aber `purgeReportQueue()` in denselben `applicationScope`
verschieben wie den Persist — analog zum bestehenden Muster.

**Status (2026-08-01): behoben.** Der Widerruf-Purge läuft nun in einem
`applicationScope.launch` (mit `CancellationException`-Rethrow + Best-effort-
Swallow, da der App-Scope keinen `CoroutineExceptionHandler` hat); nur der
In-Memory-Toggle bleibt synchron. Gepinnt durch den neuen Test
`applyConsent false defers the purge onto the injected app scope` in
`ConsentControllerTest` (verify(exactly = 0) vor `advanceUntilIdle`, exactly = 1
danach).

---

## Geprüft & verworfen (Nachvollziehbarkeit)

Drei Kandidaten wurden von Findern erhoben, vom adversarialen Verifizierer aber
gegen Quelltext + Spec **widerlegt** — hier dokumentiert, damit ein späterer Lauf
sie nicht als „neu" wieder aufmacht. Keiner ist ein offener Punkt.

- **[D1 · Durchlauf 1 · Consent] — Status: verworfen.**
  `CrashReportConsentRepositoryImpl.kt:50` — Unknown-Token-`silentError` liegt im
  `try`, wirft in DEBUG und wird vom eigenen `catch` doppelt gemeldet.
  *Begründung:* beabsichtigtes Rule-9-Fail-Loud in DEBUG (im Kommentar
  dokumentiert); RELEASE liefert korrekt `Unavailable`, Tests setzen
  `preventCrashForTesting`. Rein kosmetischer DEBUG-Doppel-Log, Produktions-
  Consent-State nie falsch.
- **[D2 · Durchlauf 1 · Ingestion] — Status: verworfen.**
  `AnrReporter.kt:142` — `readWatermark` fällt bei Read-Fehler auf `0L` zurück
  (statt „diesen Start überspringen"), meldet theoretisch alte ANRs neu.
  *Begründung:* ACRA_SPEC.md §B.5 deklariert das durch beschränkte AEI-Recordzahl
  + serverseitige Fingerprint-Dedup (§S) explizit als flood-safe; Rule-11-Schaden-
  Test greift nicht (harmlose, deduplizierte Duplikate, keine handelnde
  Fehlentscheidung).
- **[D3 · Durchlauf 2 · Consent] — Status: verworfen.**
  `ConsentBootstrap.kt:70` / `CrashReportingBootstrap.kt:95` — R1-Consent-Read via
  `runBlocking` blockiert den Main-Thread beim Kaltstart ohne Timeout (ANR-Risiko).
  *Begründung:* ACRA_FLOW.md §3.5 + Entscheidung G4 sind eine ausführliche,
  bewusste Design-Begründung für exakt diesen synchronen Read (Variante A als
  einzige Konstruktion ohne Coverage-Window); der Read ist ein einzelner
  String-Key aus einer dedizierten Ein-Key-Datei (kleinstmöglicher DataStore-Read),
  weit unter der ANR-Schwelle. Der vorgeschlagene `withTimeout` würde die
  No-Window-Garantie (Prinzip 4) opfern.

Die übrigen Finder-Winkel lieferten gar keine Funde: Resilience und Cross-Cutting
in Durchlauf 1, Ingestion in Durchlauf 2.

---

## Gesamtbild

Der Branch ist unter der Korrektheits-/Konventions-Brille **sauber** (0 Funde) —
konsistent mit den bereits durchlaufenen AUDIT-10-Runden. Die Nebenläufigkeits-
Brille förderte **zwei echte Funde** zutage, beide in der Prozess-/Thread-
Zuordnung der Bootstrap-Verdrahtung, nicht in der Fachlogik der Schichten:

| # | Schwere | Datei | Kern |
|---|---------|-------|------|
| 1 | medium  | `CrashReportingBootstrap.kt:115` | `onCreate` ungated → ANR-Drain im `:acra`-Prozess schreibt Settings-DataStore cross-process (stiller ANR-Verlust + Write-Contention) |
| 2 | low     | `ConsentController.kt:91` | Widerruf-Purge (Datei-I/O) inline auf dem Main-Thread statt auf `applicationScope` |

Beide Fixes sind klein und lokal; #1 ist der Prioritätsfund (eine `!isACRASenderServiceProcess()`-Klammer um den ANR-Drain). **Beide am 2026-08-01 behoben** (Code + je ein pinnender Test; Details in den Status-Zeilen oben).
