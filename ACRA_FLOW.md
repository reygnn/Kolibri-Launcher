# ACRA_FLOW.md

**Ziel-Architektur des gesamten ACRA-Subsystems (Consent, Ingestion,
Resilienz) — Happy & Sad, ideale Welt.**

Dieses Dokument beschreibt den **Soll-Zustand** des kompletten
Crash-Reporting-Pfads, auf grüner Wiese gedacht: nicht „wie der Code heute
ist", sondern wie das Subsystem architektonisch sauber aussieht — inklusive
aller Fehler, die auftreten können. Es ist die Spec, gegen die implementiert
wird: erst wenn der Flow hier stimmt, wird er in Code gegossen.

Referenzen auf `AUDIT-n #m` markieren das Finding, das eine Regel erzwungen
hat.

> **Zwei offene Punkte (D1, D2) stehen noch aus** — siehe §8. Beide betreffen
> den Consent-Bootstrap-Read: **D1** die Coverage-vs-Main-Thread-Frage, **D2**
> die Speicherwahl (DataStore vs. SharedPreferences-Mirror). Alles andere ist
> committed. D1 und D2 brauchen Sign-off, bevor Code entsteht.

---

## 1. Die drei Belange

ACRA berührt drei **voneinander unabhängige** Belange, die sich nur die
Library teilen. Sie sauber zu trennen ist der Kern der ganzen Architektur:

```
   ┌──────────────────────────────────────────────────────────────────────┐
   │  A · CONSENT   Darf überhaupt berichtet werden?  (Privacy-Gate)        │
   │                persistent: hasAsked/hasConsent  →  volatil: ACRA on/off│
   └───────────────────────────────┬──────────────────────────────────────┘
                                    │ gated ausschließlich über das
                                    │ ACRA-enabled-Flag (ein einziges Gate)
                                    ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │  B · INGESTION  Was wird berichtet, und wie kommt es zum Server?       │
   │     Quellen → Throttle → Carrier-Exception → ACRA → HTTPS              │
   └───────────────────────────────┬──────────────────────────────────────┘
                                    │
                                    ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │  C · RESILIENZ  Die App überlebt eigene Crashes; der Reporter wird     │
   │     nie selbst zur Crash-Quelle.  (Safety-Net um A und B)              │
   └──────────────────────────────────────────────────────────────────────┘
```

**A gated B über genau ein Gate:** das ACRA-`enabled`-Flag. Ist es aus,
ist `handleSilentException` ein No-Op — B prüft **nie** selbst Consent, es
verlässt sich darauf, dass A das Flag korrekt gesetzt hat. Ein einziger Ort,
an dem Privacy durchgesetzt wird.

---

## 2. Die Invarianten (die Gesetze)

Jede Transition unten ist aus diesen abgeleitet. Verletzt eine Änderung eine
davon, ist sie falsch — egal wie plausibel sie aussieht.

### Consent (A)
- **I1 — Fail-closed beim Einschalten.** ACRA startet AUS und wird nur bei
  einem *positiven, bekannten* Consent-Read eingeschaltet. Jede Unsicherheit
  (ungelesen, unlesbar, nicht-gefragt) lässt es AUS. Einschalten nie auf
  Verdacht.
- **I2 — Kein lügender Read.** „Store unlesbar" bleibt unterscheidbar von
  „Nein / nie gefragt". Ein Read-Fehler treibt nie einen Write und öffnet nie
  den Frage-Dialog. *(AUDIT-10 #2)*
- **I3 — Schreiben nur bei echter Nutzerentscheidung.** Ein Dialog, der nicht
  erscheint, ein Nicht-Activity-Kontext, ein Config-Change vor dem Tap —
  nichts davon persistiert. Nur ein echter Button-Tap schreibt. *(AUDIT-10
  #6/#8)*
- **I4 — Fehlgeschlagener Write ist sichtbar und verliert sauber.** Nichts
  wird geschrieben, die Session ehrt den Tap in-memory, der Nutzer wird per
  Toast informiert. *(AUDIT-10 #11)*
- **I6 — Consent reist nicht und aufersteht nicht.** Eigener DataStore-File,
  aus Backup + Transfer ausgeschlossen, nicht Purgeable. Frische
  Installation → neu fragen.
- **I7 — Cancellation ist kein Fehler.** `CancellationException` propagiert
  überall und wird nie in `Unavailable`/`Failed` gefaltet.

### Ingestion (B)
- **I8 — Ein einziges Consent-Gate.** B prüft nie selbst Consent; das
  ACRA-`enabled`-Flag ist die alleinige Durchsetzung. Kein zweiter Check, kein
  Umgehen von `handleSilentException` mit eigener Sende-Logik.
- **I9 — Ein einziger Zustellweg, kein Doppelversand.** Jede Report-Quelle
  läuft durch genau einen Pfad (`Timber.e` → `AcraTree` →
  `handleSilentException`). Nie zusätzlich direkt `handleException` aufrufen —
  das würde doppelt senden. *(AUDIT der ANR-Historie)*
- **I10 — Throttling gilt nur dem Silent-Error-Strom.** Der 24h-Cooldown
  drosselt wiederholte *geloggte* WARN+-Fehler. Echte Uncaught-Crashes und
  post-mortem ANRs werden **nie** gedrosselt (ANRs opten via
  `UnthrottledReport` aus; Crashes laufen an `AcraTree` vorbei).
- **I11 — Report-Kontext ist per-Report.** Priority/Tag/Message werden in eine
  frische Carrier-Exception gefaltet, nie in eine prozess-globale
  Mutable-Map (`putCustomData`) geschrieben. *(AUDIT-6 #4)*
- **I-priv — Minimaler Report-Inhalt.** Nur die in §5.4 gelisteten Felder;
  kein Logcat-Dump, keine Geräte-ID, keine PII über Modell/Marke/OS hinaus.

### Resilienz (C)
- **I5 — Der Reporter crasht die App nie.** Jeder Pfad *innerhalb* der
  ACRA-/Limiter-/ANR-Maschinerie schluckt seinen eigenen Fehler. *(Rule 7)*
- **I12 — Nach einem Uncaught-Crash terminiert der Prozess deterministisch.**
  Kein Zombie: der Handler gibt ACRA ein Flush-Fenster und killt dann hart. Für
  lügende Zustände (halb-migriert, HOME-Restart-Loop) `silentDeath` statt
  weiterlaufen. *(Rule 9)*

---

## 3. Belang A — Consent

### 3.1 Das mentale Modell

Genau **zwei Fakten**, klar getrennt:

| | Fakt | Speicher | Default |
|---|---|---|---|
| **Persistent** | `hasAsked` (Dialog je beantwortet?) + `hasConsent` (Ja?) | `acra_consent` DataStore, backup-excluded, **nicht** Purgeable | beide `false` |
| **Volatil** | ACRA `errorReporter.enabled` | Prozess-RAM | `false` (aus) |

Der persistente Fakt ist die einzige Quelle der Wahrheit; der volatile soll
ihn spiegeln, im Zweifel **aus**.

Der persistente Fakt wird an **zwei** Momenten gelesen und **zwei**
geschrieben — **ein File, ein Satz Keys, zwei Leser** (R1 läuft vor Hilt und
kann das Repository nicht injizieren):

```
   LESEN                                   SCHREIBEN
   R1  Bootstrap (attachBaseContext,       W1  Erst-Dialog (MainActivity-Gate)
       pre-Hilt) → ACRA-Initialzustand     W2  Settings-Toggle
   R2  Startup-Gate (MainActivity,         (beide schreiben hasConsent +
       post-Hilt) → fragen/rekonzilieren    hasAsked in EINEM Write)
```

### 3.2 Happy Path

**Erststart (nie gefragt):**
```
attachBaseContext:  ACRA.init → setEnabled(false) → R1 read=false → bleibt AUS
Gate (R2):          readState()=Loaded(hasAsked=false) → ShowDialog
Dialog "Ja":        onResult(true) → applyConsent(true):
                       setEnabled(true)          # sofort, in-memory (I1)
                       persistConsent(true) auf ApplicationScope:
                          W1: setConsent(true) → Saved
```

**Folgestart (schon zugestimmt):**
```
attachBaseContext:  R1 read=true → setEnabled(true)   # korrekt VOR onCreate
Gate (R2):          readState()=Loaded(hasAsked=true) → Reaffirm(true)
                       reaffirmConsent(true): setEnabled(true)  # idempotent
                    kein Dialog, kein Write
```

**Nachträgliche Änderung (Settings):** siehe §6.

### 3.3 Reaffirm = Rekonziliation

**Wozu Reaffirm, wenn R1 ACRA schon gesetzt hat?** Reaffirm heilt den Fall,
dass R1 *fehlschlug* (→ ACRA aus), R2 aber erfolgreich Consent liest — dann
hebt es ACRA auf den gespeicherten Stand. Normalfall: idempotenter No-Op.
Reaffirm persistiert nie (die Entscheidung steht schon im Store).

### 3.4 Consent-Fehler (Sad)

| # | Fehler | Verhalten | Invariante |
|---|---|---|---|
| SR1 | R1 Bootstrap-Read wirft | → `false` → ACRA bleibt AUS; von R2/Reaffirm rekonziliert | I1 |
| SR2 | R2 `readState()` wirft | → `Unavailable` → `Skip` (nichts tun); ACRA behält Bootstrap-Wert, kein Dialog | I2 |
| SR3 | R1 **und** R2 fehlgeschlagen | ACRA AUS, kein Dialog; nächster Start liest neu (heilt selbst) | I1+I2 |
| SR4 | Display-Getter (`hasConsent`/`hasAsked`) wirft | → `false` (nur Anzeige, kein Write folgt) | — |
| SW1 | W1/W2 Write wirft | `Failed`, nichts persistiert, Session ehrt Tap, **Toast** | I4 |
| SW2 | Write gecancelt | `CancellationException` propagiert; Persist läuft auf `ApplicationScope`, UI-Teardown cancelt ihn nicht | I7 |
| SD1 | Nicht-Activity-Kontext | `null`, `onResult` feuert nicht, nichts persistiert | I3 |
| SD2 | `show()` wirft (z.B. BadToken auf finishing Activity) | `null`, kein Persist | I3 |
| SD3 | Dialog sichtbar, Activity vor Tap zerstört | in `currentDialog` getrackt & dismissed; `hasAsked` bleibt `false` → nächster Start fragt erneut | I3 |
| SD4 | Config-Change bei offenem Dialog | dismissed on destroy, Gate läuft auf Recreate erneut → wieder `ShowDialog` | I3 |
| RC1 | R1 AUS (Fehler), R2 liest Consent=true | `Reaffirm(true)` → ACRA an — **der Grund für Reaffirm** | I1 |
| RC2 | R1 AN, R2 liest Consent=false (Revoke-Race) | `Reaffirm(false)` → ACRA aus | I1 |

---

## 4. Belang B — Ingestion (die Report-Pipeline)

### 4.1 Drei Quellen, zwei ACRA-Eingänge

| Quelle | Was | ACRA-Eingang | Throttle? |
|---|---|---|---|
| **Uncaught-Crash** | echter, nicht gefangener `Throwable` | ACRAs **eigener** installierter UncaughtExceptionHandler (Auto-Report, voller `reportContent`) | **nein** (I10) |
| **Geloggter Fehler** | gefangen, aber via `Timber.e/w(t)` (WARN+) geloggt | `AcraTree` → `handleSilentException(carrier)` | **ja**, 24h/Typ+Site |
| **Post-mortem ANR** | `ApplicationExitInfo` REASON_ANR | `Timber.e(AnrException)` → `AcraTree` → `handleSilentException` | **nein**, `UnthrottledReport` |

Kernaussage: **echte Crashes und ANRs werden nie gedrosselt; nur der geloggte
Silent-Error-Strom** (der sich in einer Session wiederholen kann) läuft durch
den Cooldown. *(I10)*

### 4.2 Der eine Zustellweg (geloggte Fehler + ANRs)

```
Timber.e(throwable, msg)                              # der EINZIGE Weg (I9)
   └─ AcraTree.log(priority, tag, msg, t):
        1. Gate:      priority < WARN || t == null  → return (nichts)
        2. Throttle:  CrashReportLimiter.shouldSendReport(t) == false → return
        3. Carrier:   buildAcraReportThrowable(priority, tag, msg, t)  # per-Report (I11)
        4. Zustellen: ACRA.errorReporter.handleSilentException(carrier)
                        └─ (nur wirksam, wenn ACRA enabled — das Consent-Gate, I8)
        (alles ab Schritt 3 in try/catch(Throwable) → geschluckt, I5)
```

Nie zusätzlich `handleException` direkt aufrufen — Doppelversand. *(I9)*

### 4.3 Throttling (`CrashReportLimiter`)

- **Zweck:** wiederholt feuernde *live* Exceptions drosseln — max. 1 Report pro
  `Klasse + erster App-Frame-Throw-Site` pro 24 h.
- **Speicher:** `SharedPreferences` (`acra_report_limiter`) — die eine
  bewusste Rule-5-Ausnahme, weil `shouldSendReport` **synchron aus dem
  Nicht-Coroutine-ACRA-Handler-Thread mitten im Crash** läuft (kein
  Suspend-Kontext, `runBlocking`-auf-DataStore wäre der StrictMode-Bug). Die
  Daten sind ephemere Telemetrie-Timestamps, gehören nicht ins Backup.
- **Fail-open:** uninitialisiert, Lese-/Schreibfehler, jeder `Throwable` →
  `true` (Report erlauben). Schlimmstenfalls ein Extra-Report. *(I5)*
- **`UnthrottledReport`:** Marker; wer schon upstream dedupliziert ist (ANRs
  via Watermark), umgeht den Cooldown ganz (kein Prefs-Read/Write). Ohne den
  Bypass würden alle ANRs unter einem Klasse+Site-Key kollabieren und nur der
  erste durchkommen.
- **Cleanup:** Einträge > 7 Tage werden lazy entfernt.

### 4.4 Carrier-Exception statt `putCustomData` *(I11)*

`buildAcraReportThrowable` faltet den Log-Kontext in die Message einer frischen
`LoggedThrowable`, Original als `cause`:
```
"[W/Tag] OriginalType: message"   (+ Diagnose-Note bei CancellationException)
```
- **Warum kein `putCustomData`:** das war eine *prozess-globale* Mutable-Map →
  zwei Reports auf verschiedenen Threads konnten sich die Metadaten vertauschen
  (AUDIT-6 #4). Zudem listet `reportContent` kein `CUSTOM_DATA` → die Daten
  erreichten den Server nie.
- **Warum eine Exception:** per-Report by construction (kein Shared State, kein
  Lock), landet in `STACK_TRACE` (das *ist* gelistet), Original-Trace bleibt
  unter „Caused by:". Frischer Trace zeigt bei fälschlich geloggter
  `CancellationException` direkt auf den fehlerhaften `catch`.

### 4.5 Post-mortem ANR (`AnrReporter`)

Kein Live-Sampling (kein `ANRWatchDog`), sondern beim **nächsten Start** die
System-`ApplicationExitInfo` auslesen:
```
onCreate → reportPendingAnrsAsync (ApplicationScope):
   newAnrsSinceLastReport(): AEI filtern (REASON_ANR, timestamp > Watermark),
                             chronologisch, je → AnrReport(desc, importance, threadDump)
   je Report:  handler(report) = Timber.e(AnrException(...))  → §4.2
               markReported(report): Watermark = report.timestamp
```
- **Dedup = Watermark** (`anr_reporter_last_reported_ts` im settingsDataStore):
  jeder AEI-Record genau einmal, ever. AEI-Recordzahl ist beschränkt → flood-safe.
- **Trade-off:** nur *harte* ANRs (System hat Prozess gekillt), dafür
  System-Multi-Thread-Dump inkl. Locks, null Background-Overhead, keine
  unmaintainte Dependency. Soft-ANRs („Warten"-Tap) gehen bewusst verloren.
- **Best-effort:** der Handler (`Timber.e` → `AcraTree`) **schluckt**
  ACRA-Sendefehler (I5), also advanced die Watermark auch, wenn ein Report ACRA
  nie erreicht → dieser ANR fällt weg. Akzeptiert (ist ACRA kaputt, gibt es eh
  nichts zu retten). Handler **nicht** zum Rethrow bringen, um zu „recovern" —
  das unterliefe den Crash-Infra-Swallow, hinter dem er sitzt.

### 4.6 ANR-Fehler (Sad)

| # | Fehler | Verhalten | Invariante |
|---|---|---|---|
| AN1 | AEI-Read wirft | `silentError` → `emptyList`; Watermark unverändert; nächster Start liest neu | I5 |
| AN2 | Trace-Stream null / Read wirft | Report **ohne** Dump statt ANR fallen zu lassen | I5 |
| AN3 | Watermark-Write wirft | `silentError`, Watermark nicht advanced → ANR ggf. nächsten Start erneut (Duplikat akzeptiert) | I5 |
| AN4 | Handler schluckt ACRA-Sendefehler | Watermark advanced trotzdem, ANR gedroppt (best-effort) | I5 |

### 4.7 Report-Inhalt & Transport *(I-priv)*

- **Init in `attachBaseContext`:** `reportFormat = JSON`.
- **`httpSender`:** POST an `BuildConfig.ACRA_URL`, Basic-Auth
  (`ACRA_LOGIN`/`ACRA_PASSWORD`), `tlsProtocols = [TLS 1.2, TLS 1.3]`. Privater,
  selbstgehosteter Server — nie ein Drittanbieter.
- **`reportContent` (exakt diese, nicht mehr):** PACKAGE_NAME, ANDROID_VERSION,
  APP_VERSION_CODE, APP_VERSION_NAME, BRAND, PHONE_MODEL, STACK_TRACE.
  **Kein** CUSTOM_DATA (→ Carrier), kein Logcat, keine ID.

---

## 5. Belang C — Resilienz (Safety-Net)

### 5.1 Uncaught-Crash-Pfad

- **RELEASE:** ACRAs eigener installierter UncaughtExceptionHandler fängt den
  Crash, sendet den vollen Report, Prozess endet.
- **DEBUG:** die App *umschließt* ACRAs Handler (`setupGlobalExceptionHandler`,
  nur DEBUG), um Resilienz-Belange zu ergänzen — der eigentliche Versand bleibt
  ACRAs Handler:
  ```
  handleUncaughtException(thread, t):
     Timber.e(t, ...)                          # Rule 9: plain Timber.e (Crash-Infra)
     if t is OutOfMemoryError: System.gc()     # Notfall-GC, Best-effort
     defaultExceptionHandler.uncaughtException(thread, t)   # = ACRA sendet
     finally: Thread.sleep(500)                # Flush-Fenster
              Process.killProcess(); exitProcess(10)        # deterministisch (I12)
  ```
- **Kein Throttling echter Crashes** (I10). Der Limiter-Aufruf hier ist
  informativ und gated nichts.

### 5.2 RecoveryWatchdog

Daemon-Thread (kein Coroutine — muss laufen, wenn der Main-Looper hängt),
killt den Prozess, wenn der Main-Looper > 8 s nicht dispatcht. Start **nach**
`onCreate` via `Handler.post`, damit die schwere Cold-Start-Arbeit nicht selbst
einen Kill-Restart-Loop auf dem HOME-Prozess auslöst.

### 5.3 Rule-9-Ausnahme (plain `Timber.e`)

Dateien *im* Crash-Pfad nutzen plain `Timber.e` statt `silentError`, weil ein
DEBUG-Throw dort in genau den Pfad rekursieren würde, der das Safety-Net *ist*:
`KolibriLauncherApp`, `TimberWrapper`, `Base{Activity,ViewModel}`,
`CrashReportLimiter`, `CrashReportConsentStore`, `BackupFragment`-Handler.
Überall sonst: `silentError` (laut in DEBUG).

### 5.4 Resilienz-Fehler (Sad)

| # | Fehler | Verhalten | Invariante |
|---|---|---|---|
| RS1 | ACRA-Init wirft in `attachBaseContext` | gefangen, `Log.e`-Fallback (Timber noch nicht da), App läuft weiter, ACRA AUS diesen Lauf | I5 |
| RS2 | `handleSilentException` wirft im AcraTree | geschluckt (`Log.e`), Report verloren, App überlebt | I5 |
| RS3 | Fehler *im* Uncaught-Handler | falls Default-Handler noch nicht gerufen: nachholen; dann trotzdem Prozess killen | I12 |
| RS4 | Main-Looper stallt > 8 s | RecoveryWatchdog killt Prozess, OS startet HOME sauber neu | I12 |

---

## 6. Settings-Oberfläche

Drei ACRA-Berührungen im `SettingsFragment`:

**a) Consent-Toggle** (Preference-Klick):
```
forceShowConsentDialog(activityContext) { userGaveConsent ->
   controller.applyConsent(userGaveConsent)      # ACRA-Toggle + W2-Persist (§3)
   showToastSafe(enabled/disabled)               # Bestätigung
   applyCrashReportSummary(userGaveConsent)       # Summary direkt aus dem Wert,
}                                                  # KEIN Re-Read (Race mit Persist, AUDIT-10 #1)
```
Dialog in `currentDialog` getrackt, auf `onDestroyView` dismissed (leckt sonst
den `setCancelable(false)`-Window, AUDIT-3 #12). Alle Dialog-Fehler = §3.4
SD1–SD4.

**b) Summary rendern** (`updateCrashReportSummary`): `currentConsent()` (echtes
DataStore-Read, kann werfen) → bei Fehler `silentError`, Summary bleibt alt
(nur Anzeige).

**c) Developer-Kommandos** (zwei bewusst getrennte Handler, weil ein Throw im
Preference-Click direkt an den globalen Handler geht):
- **Reset ACRA-Timer** → `CrashReportLimiter.resetAllLimits()` + Toast. Fehler
  → `silentError`, `false`. Nur Dedup-State, keine Nutzerdaten.
- **Throw-Test-Exception** → Toast, dann `Thread { sleep(800); throw }` — der
  Throw läuft auf einem nackten Thread **ohne** CoroutineExceptionHandler,
  reist also durch genau den echten Uncaught-Pfad (§5.1). Der Crash ist hier
  **gewollt**.

---

## 7. Komponenten-Verantwortlichkeiten (Ziel-Rollen)

| Komponente | Modul | Verantwortung | Besitzt **nicht** |
|---|---|---|---|
| `CrashReportConsentRepository` (+ Impl) | domain/data | Persistenz: `readState()→ConsentReadResult`, `setConsent()→ConsentWriteResult`, Getter (total). Wirft nie für I/O. | ACRA-Toggle, UI, Scope |
| `CrashReportConsentStore` | data | **Nur** pre-Hilt-Bootstrap-Read (R1) + androidTest-Seed. Teilt Keys mit dem Repo. | Interaktive Writes |
| `ConsentReadResult`/`ConsentWriteResult`/`CrashReportConsentState` | domain | Fehler-als-Wert-Modelle (I2/I4), pure Kotlin | — |
| `CrashReportConsentController` | app | Koordinator: `resolveStartupAction`, `applyConsent`, `reaffirmConsent`, `persistConsent`. Läuft auf `ApplicationScope`. | Dialog-Bau, DataStore |
| `CrashReportConsent` | app | **Nur** Dialog bauen/zeigen, `onResult` bei echtem Tap, `null` sonst (I3) | Lesen, Persistieren, Scope |
| `CrashReportToggle` | app | Seam über `ACRA.setEnabled` — hält den Seiteneffekt in `:app`, Controller JVM-testbar | Entscheidungslogik |
| `ConsentSaveFailureNotifier` | app | Seam: Toast bei fehlgeschlagenem Persist (I4) | Entscheidungslogik |
| `AcraTree` | app | Der eine Zustellweg: Gate → Throttle → Carrier → `handleSilentException`, schluckt (I5/I9/I11) | Consent-Check (I8) |
| `CrashReportLimiter` | app | 24h-Throttle des Silent-Error-Stroms, fail-open (I10) | Crashes/ANRs drosseln |
| `buildAcraReportThrowable`/`LoggedThrowable` | domain | Per-Report-Carrier (I11), pure/JVM-testbar | Android-Runtime |
| `AnrReporter` | app | AEI → `AnrReport`, Watermark-Dedup, best-effort | ACRA-Aufruf (delegiert an Handler) |
| `KolibriLauncherApp` | app | ACRA-Init-Reihenfolge, Uncaught-Handler, OOM-Recovery, Watchdog, KolibriLog-Bridge | Consent-Entscheidung (an R1/Gate) |
| `MainActivity`/`SettingsFragment` | app | Dünner Glue: Gate-Ergebnis rendern, Dialog tracken/dismissen | Alles obige |

---

## 8. Offene Punkte: der Consent-Bootstrap-Read (D1 + D2)

Zwei zusammenhängende Entscheidungen, beide **nicht committed**; brauchen
Sign-off vor Code. D1 legt das *Verhalten* fest, D2 den *Mechanismus*.

### D1 — Coverage-Fenster vs. Main-Thread-Read

### Das Dreieck (nicht alle drei gleichzeitig)

```
        (a) ACRA-Zustand korrekt, BEVOR der erste Crash möglich ist (vor onCreate)
                                   /\
                                  /  \
     (c) kein Main-Thread- ──────/────\────── (b) kein Coverage-Fenster
         Disk-Read im Bootstrap                    für zustimmende Nutzer
```

Für einen Crash-Reporter ist **(a) nicht verhandelbar** — der Sinn ist, gerade
die *frühen* Crashes zu fangen. Damit ist D1 ein Binär:

- **D1-A — (c) opfern: synchroner Bootstrap-Read (Status-quo-Mechanik).**
  `runBlocking { consentDataStore.data.first() }`. Kein Fenster; Kosten: ein
  Main-Thread-Disk-Read → StrictMode-DiskReadViolation in DEBUG (dokumentiertes
  Rauschen, kein Jank — ein einzelner Boolean).
- **D1-B — (b) opfern: ACRA startet AUS, async-Enable nach Hilt.** Kein
  Main-Thread-Read, nur ein Leser; Kosten: ein Fenster, in dem der Crash eines
  zustimmenden Nutzers verloren geht.

**Empfehlung: D1-A.** Early-Crash-Coverage wiegt schwerer als ein sauberes
StrictMode-Log; die StrictMode-Zeile ist bekannt & dokumentiert
(`KNOWN_ISSUES.md §3`). D1-B verlöre genau die wertvollsten Crashes.

### D2 — Speicher des Bootstrap-Bits (nur relevant, wenn D1-A)

Wenn synchron gelesen wird (D1-A), woher? Zwei Optionen:

- **D2-A — DataStore bleibt einzige Quelle,** synchron via
  `runBlocking { consentDataStore.data.first() }`. Ein Speicher, keine
  Drift-Fläche.
- **D2-B — SharedPreferences-Mirror „wie der Limiter".** Jeder Write schreibt
  zusätzlich einen SP-Boolean durch, den der Bootstrap synchron via
  `getBoolean` liest.

**Empfehlung: D2-A.** Die Analogie zum Limiter trägt nicht: der Limiter läuft
synchron aus dem *Crash-Handler-Thread* (kein Suspend-Kontext) → SP ist dort
alternativlos. Der Bootstrap-Read läuft auf dem *Main-Thread an einem
kontrollierten Init-Punkt*, wo `runBlocking` auf einem `first()` vertretbar
ist. D2-B brächte einen zweiten Speicher + Write-Through + Drift-Fläche (Mirror
müsste zusätzlich backup-excluded sein) und eliminiert den Main-Thread-Read
**nicht** — `getBoolean` liest beim First-Load ebenfalls von der Platte. Bei
D2-A ist der `runBlocking`-Read **kein Hack**, sondern die tragende Kostenseite
der No-Window-Garantie — so gehört es in die KDoc.

> **Kopplung:** Fällt D1 auf D1-B, ist D2 gegenstandslos (kein synchroner
> Bootstrap-Read mehr, nur noch der eine async Repository-Leser).

---

## 9. Was bewusst NICHT getan wird (abzulehnende „Fixes")

- **`onResult(false)` bei Dialog-Show-Fehler** — macht aus Anzeige-Fehler ein
  „Nein". *(I3, AUDIT-10 #6/#8)*
- **`Unavailable`/`Failed` in Defaults kollabieren** — lässt I/O-Fehler echte
  Entscheidungen überschreiben / Saves vortäuschen. *(I2/I4)*
- **Zweiter Consent-Check in B** oder eigene Sende-Logik statt
  `handleSilentException` — umgeht das eine Gate. *(I8)*
- **Zusätzlicher `handleException`-Aufruf für ANRs/Fehler** — Doppelversand.
  *(I9)*
- **ANRs durch den Limiter-Cooldown routen** — kollabiert distinkte Hangs unter
  einem Key. *(I10, `UnthrottledReport`)*
- **`putCustomData` für Report-Kontext** — prozess-globale Map → Race, erreicht
  den Server eh nicht. *(I11, AUDIT-6 #4)*
- **`CrashReportLimiter`/Consent nach DataStore „aufräumen"** bzw. Consent nach
  SharedPreferences — beide Speicherwahlen sind begründet (Rule 5 + §8).
- **Repository `Purgeable` machen** — Factory-Reset darf Privacy nicht
  umkippen. *(I6)*
- **Handler zum Rethrow bringen, um einen ANR zu „recovern"** — unterläuft den
  Crash-Infra-Swallow. *(§4.5)*
- **`silentError` in Crash-Infra-Dateien** — rekursiert ins Safety-Net. *(§5.3,
  Rule 9)*
- **Den doppelten R1/R2-Read „vereinheitlichen", indem R1 entfällt** — R1 muss
  vor Hilt laufen; ein File, zwei Leser, per Reaffirm rekonziliert ist korrekt.

---

## 10. Verwandte Docs

- `CLAUDE.md` — Rule 5 (Storage), 7 (Crash-Safety), 8 (opt-in), 9
  (Timber/silentError/silentDeath), 11 (Fehler-als-Wert).
- `KNOWN_ISSUES.md §3` — der bewusste StrictMode-DiskReadViolation von R1.
- `AUDIT-10.md` — crash-consent-layering-Review (Consent-Invarianten §2).
- `AUDIT-6.md` / `AUDIT-6_ADDENDUM.md` — Carrier-Exception statt CUSTOM_DATA.
