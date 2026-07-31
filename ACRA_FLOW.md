# ACRA_FLOW.md

**Ziel-Architektur des Crash-Reporting-Subsystems — grüne Wiese.**

Dieses Dokument beschreibt den Soll-Zustand des kompletten Crash-Reporting-Pfads
so, wie man ihn *ohne jede Altlast* bauen würde: keine Rücksicht auf heutige
Klassennamen, Key-Namen, Paketstruktur oder Bestandsinstallationen. Es ist die
Spec, gegen die implementiert wird — inklusive aller Fehlerfälle und der
Wechselwirkungen zwischen den Belangen.

Ein Rewrite auf grüner Wiese heißt: es gibt keinen Migrationszwang. Bestehende
DataStore-Keys, Prefs-Dateien und Klassen dürfen umbenannt oder verworfen
werden; der Preis ist genau ein Effekt — jede Bestandsinstallation wird beim
ersten Start nach dem Rewrite **neu gefragt**. Das ist per **A5** ausdrücklich
erlaubt und wird hier als bewusste Entscheidung getroffen, nicht als Unfall.

Referenzen auf `AUDIT-n #m` markieren, wo eine Regel aus einer real gemachten
Fehlererfahrung stammt — sie sind Provenienz der Lektion, nicht die
Begründung. Die Begründung steht jeweils daneben.

---

## 0. Was sich gegenüber dem Ist-Zustand ändert (Leitentscheidungen)

Sechs Entscheidungen prägen diese Spec und weichen bewusst vom Bestand ab:

1. **Consent ist ein einziger Sealed-Wert, nicht zwei Booleans.** `hasAsked` +
   `hasConsent` erlauben vier Zustände, von denen einer illegal ist
   (`asked=false, consent=true`), und werden ohnehin nie unabhängig
   geschrieben. Ersatz: `ConsentDecision = NeverAsked | Granted | Denied`.
   Illegaler Zustand nicht repräsentierbar, halber Lesepfad entfällt, keine
   Drift-Fläche zwischen zwei Feldern. *(→ A8)*
2. **Widerruf ist destruktiv.** ACRA-„aus" allein reicht nicht: ungesendete
   Report-Dateien überleben und könnten nach einem späteren Re-Consent
   abfließen. Revoke löscht die Warteschlange. *(→ A7)*
3. **Der Watchdog ist eine Report-Quelle, kein bloßer Killer.** Bevor er den
   Prozess killt, greift er den Main-Thread-Stack ab und schickt ihn
   ungedrosselt durch die Pipeline. Sonst sind genau die Looper-Stalls, die
   das System *nicht* als ANR deklariert, systematisch unsichtbar. *(→ §6, X1)*
4. **Die Pipeline-Lebendigkeit ist beobachtbar.** „Reporter seit Monaten tot"
   und „keine Crashes" dürfen nicht ununterscheidbar sein. Ein
   Last-Successful-Send-Marker macht den Unterschied sichtbar. *(→ C4)*
5. **Der Bootstrap ist prozess-bewusst.** ACRA spawnt einen eigenen
   Sender-Prozess; `attachBaseContext` läuft dort ebenfalls. Nur der
   Haupt-Prozess liest Consent und schaltet ACRA — der Sender-Prozess fasst
   das Gate nie an. (ACRA stubt den Reporter dort ohnehin selbst; gespart wird
   der überflüssige DataStore-Read, **nicht** eine sonst mögliche Inkonsistenz
   — §11.) *(→ X2)*
6. **Jede Invariante trägt eine Durchsetzung.** Test, Linter oder Review —
   und wenn Test, *welche Datei rot werden muss*. Eine Invariante ohne
   Durchsetzung ist eine Bitte, kein Gesetz. *(→ §2, Spalte „Gepinnt durch")*

Bootstrap-Read-Frage (im alten Doc als offene Punkte D1/D2 geführt) ist hier
**entschieden**: synchroner Main-Thread-Read direkt aus DataStore
(`runBlocking { dataStore.data.first() }`), DataStore als einzige Quelle. Siehe
§3.5 für die Begründung; sie ist keine Notlösung, sondern die tragende
Kostenseite der No-Window-Garantie.

---

## 1. Die drei Belange

Crash-Reporting berührt drei **voneinander unabhängige** Belange, die sich nur
die Library teilen. Ihre saubere Trennung ist der Kern der Architektur — und
ihre **Wechselwirkungen** (§7) sind der Teil, den die Trennung sonst blind
macht.

```
   ┌──────────────────────────────────────────────────────────────────────┐
   │  A · CONSENT   Darf überhaupt berichtet werden?  (Privacy-Gate)        │
   │                persistent: ConsentDecision  →  volatil: ACRA on/off    │
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

**A gated B über genau ein Gate:** das ACRA-`enabled`-Flag. Ist es aus, ist
`handleSilentException` ein No-Op — B prüft **nie** selbst Consent, es verlässt
sich darauf, dass A das Flag korrekt gesetzt hat. Ein einziger Ort, an dem
Privacy durchgesetzt wird.

---

## 2. Die Invarianten (die Gesetze)

Jede Transition weiter unten ist aus diesen abgeleitet. Verletzt eine Änderung
eine davon, ist sie falsch — egal wie plausibel sie aussieht. Die Spalte
**Gepinnt durch** ist Teil der Invariante: eine Regel ohne benannte
Durchsetzung gilt als noch nicht implementiert.

### Consent (A)

| # | Invariante | Gepinnt durch |
|---|---|---|
| **A1** | **Fail-closed beim Einschalten.** ACRA wird deaktiviert *konstruiert* (nicht „an, dann aus") und nur bei einem *positiven, bekannten* Read (`Granted`) eingeschaltet. Jede Unsicherheit (ungelesen, unlesbar, `NeverAsked`, `Denied`) lässt es AUS. | Bootstrap-Test + Controller-Test (`resolveStartupAction`); §3.5 fixiert die Init-*Reihenfolge* als Teil der Invariante |
| **A2** | **Kein lügender Read.** `Unavailable` bleibt unterscheidbar von `Denied`/`NeverAsked`. Ein Read-Fehler treibt nie einen Write und öffnet nie den Dialog. *(AUDIT-10 #2)* | `CrashReportConsentRepositoryImplTest` (Failure-Branch, impl-only); Controller-Test (`Unavailable → Skip`) |
| **A3** | **Schreiben nur bei echter Nutzerentscheidung.** Kein erschienener Dialog, kein Nicht-Activity-Kontext, kein Config-Change vor dem Tap persistiert. Nur ein echter Button-Tap schreibt. *(AUDIT-10 #6/#8)* | `ConsentDialog`-Test (Robolectric): `onResult` feuert nur bei Tap, sonst `null` |
| **A4** | **Fehlgeschlagener Write ist sichtbar und verliert sauber.** Nichts wird geschrieben, die Session ehrt den Tap in-memory, der Nutzer wird per Toast informiert. *(AUDIT-10 #11)* | Controller-Test (`Failed → Notifier aufgerufen`) |
| **A5** | **Consent reist nicht und aufersteht nicht.** Eigener DataStore-File, aus Backup + Transfer ausgeschlossen, nicht Purgeable. Frische Installation → neu fragen. | `backup_rules.xml`/`data_extraction_rules.xml`-Assertion; Manifest-Review |
| **A6** | **Cancellation ist kein Fehler.** `CancellationException` propagiert überall und wird nie in `Unavailable`/`Failed` gefaltet. | Contract-Test (fake + impl), beide Read/Write-Pfade |
| **A7** | **Widerruf ist destruktiv.** Ein Wechsel auf `Denied` löscht die ungesendete ACRA-Report-Warteschlange (`BulkReportDeleter`, **beide** Ordner, §11), damit kein Report aus der Consent-Phase nach einem späteren Re-Consent abfließt. | Controller-Test (`applyConsent(false)` ruft Queue-Purge); `AcraToggle`-Purge-Test (beide Ordner) |
| **A8** | **Illegale Zustände unrepräsentierbar.** Consent ist genau ein `ConsentDecision`-Sealed-Wert; es gibt kein Feldpaar, das inkonsistent werden kann. | Typsystem (kompiliert nicht anders); Model-Test |

### Ingestion (B)

| # | Invariante | Gepinnt durch |
|---|---|---|
| **B1** | **Ein einziges Consent-Gate.** B prüft nie selbst Consent; das ACRA-`enabled`-Flag ist die alleinige Durchsetzung. Kein zweiter Check, kein Umgehen von `handleSilentException` mit eigener Sende-Logik. | Linter: kein Consent-Read außerhalb Belang A; `checkConventions` |
| **B2** | **Ein Zustellweg, kein Doppelversand.** Jede geloggte Quelle läuft durch genau einen Pfad (`Timber.e` → `AcraTree` → `handleSilentException`). Nie zusätzlich direkt `handleException`. *(AUDIT ANR-Historie)* | Linter: kein `handleException(`/`handleSilentException(` außer in `AcraTree` |
| **B3** | **Throttling gilt nur dem Silent-Error-Strom.** 24h-Cooldown pro Typ+Site drosselt wiederholte *geloggte* WARN+-Fehler. Echte Uncaught-Crashes, ANRs und Watchdog-Stalls werden **nie** gedrosselt. | `ReportThrottle`-Test; `UnthrottledReport`-Marker-Test |
| **B4** | **Report-Kontext ist per-Report.** Priority/Tag/Message werden in eine frische Carrier-Exception gefaltet, nie in eine prozess-globale Mutable-Map (`putCustomData`). *(AUDIT-6 #4)* | Linter: kein `putCustomData(`; `ReportCarrier`-Test (pure/JVM) |
| **B5** | **Minimaler Report-Inhalt.** Nur die in §5.4 gelisteten Felder; kein Logcat-Dump, keine Geräte-ID, keine PII über Modell/Marke/OS hinaus. | `reportContent`-Assertion-Test gegen exakte Feldliste |

### Resilienz (C)

| # | Invariante | Gepinnt durch |
|---|---|---|
| **C1** | **Der Reporter crasht die App nie.** Jeder Pfad *innerhalb* der ACRA-/Throttle-/ANR-/Watchdog-Maschinerie schluckt seinen eigenen Fehler. *(Rule 7)* | Linter Rule 9 (Crash-Infra-Whitelist); Review |
| **C2** | **Nach Uncaught-Crash terminiert der Prozess deterministisch.** Kein Zombie. Den Kill besorgt ACRAs eigener `ProcessFinisher` (`exitProcess(10)`) in RELEASE **und** DEBUG (§11); **kein** Flush-Fenster (Versand ist out-of-process, §5.1). Unser Kill ist reiner Backstop. Lügende Zustände → `silentDeath`. *(Rule 9/12)* | Uncaught-Handler-Test (Backstop-Kill-Pfad erreicht); `silentDeath`-Test |
| **C3** | **Der Watchdog verblindet den ANR-Reporter nicht.** Ein Watchdog-Kill erzeugt kein `REASON_ANR`; darum captured der Watchdog **vor** dem Kill selbst einen Report. Kein stiller Verlust von Looper-Stalls. *(→ §7 X1)* | `RecoveryWatchdog`-Test: Capture-Callback vor `killSwitch` |
| **C4** | **Pipeline-Lebendigkeit ist beobachtbar.** Ein toter Reporter ist von „keine Crashes" unterscheidbar. Der dafür nötige Marker ist ein Last-Successful-**Send**-Marker — nur der trennt „toter Sender" von „keine Crashes". Er wird von einem eigenen `ReportSender`-Dekorator im `:acra`-Prozess bei HTTP-Erfolg cross-prozess gestampt (§11, X4), **nicht** von `AcraTree` (das sähe nur das Enqueue). Im Dev-Screen sichtbar. | `AcraHttpSender`-Test: HTTP-Erfolg stampt Marker; Dev-Screen-Test |

---

## 3. Belang A — Consent

### 3.1 Das mentale Modell

Genau **ein Fakt**, als Sealed-Wert — plus sein volatiler Spiegel:

| | Fakt | Typ | Speicher | Default |
|---|---|---|---|---|
| **Persistent** | `ConsentDecision` | `NeverAsked \| Granted \| Denied` | `acra_consent` DataStore, backup-excluded, **nicht** Purgeable | `NeverAsked` |
| **Volatil** | ACRA `errorReporter.enabled` | `Boolean` | Prozess-RAM | `false` (aus) |

Der persistente Fakt ist die einzige Quelle der Wahrheit; der volatile spiegelt
ihn, im Zweifel **aus**. Es gibt kein zweites Feld — `NeverAsked` *ist* der
Zustand „nie gefragt", nicht ein separates `hasAsked=false`. Das eliminiert den
illegalen Zustand (`asked=false, consent=true`) by construction. *(A8)*

Der persistente Fakt wird an **zwei** Momenten gelesen und an **zwei**
geschrieben — **ein File, ein Key, zwei Leser** (R1 läuft vor Hilt und kann das
Repository nicht injizieren):

```
   LESEN                                   SCHREIBEN
   R1  Bootstrap (attachBaseContext,       W1  Erst-Dialog (MainActivity-Gate)
       pre-Hilt, Haupt-Prozess) →              → Granted | Denied
       ACRA-Initialzustand                 W2  Settings-Toggle → Granted | Denied
   R2  Startup-Gate (MainActivity,
       post-Hilt) → fragen/rekonzilieren
```

### 3.2 Happy Path

**Erststart (`NeverAsked`):**
```
attachBaseContext:  ACRA.init(disabled) → R1=NeverAsked → bleibt AUS
Gate (R2):          readState()=Loaded(NeverAsked) → ShowDialog
Dialog "Ja":        onResult(true) → applyConsent(true):
                       toggle.setEnabled(true)     # sofort, in-memory (A1)
                       persistConsent(Granted) auf ApplicationScope:
                          W1: setConsent(Granted) → Saved
```

**Folgestart (`Granted`):**
```
attachBaseContext:  R1=Granted → ACRA.init(disabled) → setEnabled(true)  # korrekt VOR onCreate
Gate (R2):          readState()=Loaded(Granted) → Reaffirm(true)
                       reaffirmConsent(true): setEnabled(true)   # idempotent
                    kein Dialog, kein Write
```

**Widerruf (Settings → aus):** siehe §6 und **A7** (Queue-Purge).

### 3.3 Reaffirm = Rekonziliation

**Wozu Reaffirm, wenn R1 ACRA schon gesetzt hat?** Reaffirm heilt den Fall, dass
R1 *fehlschlug* (→ ACRA aus), R2 aber erfolgreich `Granted` liest — dann hebt es
ACRA auf den gespeicherten Stand. Normalfall: idempotenter No-Op. Reaffirm
persistiert nie (die Entscheidung steht schon im Store).

### 3.4 Consent-Fehler (Sad)

| # | Fehler | Verhalten | Invariante |
|---|---|---|---|
| SR1 | R1 Bootstrap-Read wirft | → als `NeverAsked` behandelt → ACRA bleibt AUS; von R2/Reaffirm rekonziliert | A1 |
| SR2 | R2 `readState()` wirft | → `Unavailable` → `Skip` (nichts tun); ACRA behält Bootstrap-Wert, kein Dialog | A2 |
| SR3 | R1 **und** R2 fehlgeschlagen | ACRA AUS, kein Dialog; nächster Start liest neu (heilt selbst) | A1+A2 |
| SR4 | Display-Getter wirft | → `NeverAsked`/`false` (nur Anzeige, kein Write folgt) | — |
| SW1 | W1/W2 Write wirft | `Failed`, nichts persistiert, Session ehrt Tap, **Toast** | A4 |
| SW2 | Write gecancelt | `CancellationException` propagiert; Persist läuft auf `ApplicationScope`, UI-Teardown cancelt ihn nicht | A6 |
| SD1 | Nicht-Activity-Kontext | `null`, `onResult` feuert nicht, nichts persistiert | A3 |
| SD2 | `show()` wirft (BadToken auf finishing Activity) | `null`, kein Persist | A3 |
| SD3 | Dialog sichtbar, Activity vor Tap zerstört | in `currentDialog` getrackt & dismissed; bleibt `NeverAsked` → nächster Start fragt erneut | A3 |
| SD4 | Config-Change bei offenem Dialog | dismissed on destroy, Gate läuft auf Recreate erneut → wieder `ShowDialog` | A3 |
| SP1 | Revoke: Queue-Purge wirft | `silentError` geschluckt; ACRA ist trotzdem aus, kein Report kann mehr *entstehen* — Restrisiko: Altbestand bleibt liegen (nächster Revoke räumt) | A7, C1 |
| RC1 | R1 AUS (Fehler), R2 liest `Granted` | `Reaffirm(true)` → ACRA an — **der Grund für Reaffirm** | A1 |
| RC2 | R1 AN, R2 liest `Denied` (Revoke-Race) | `Reaffirm(false)` → ACRA aus + Queue-Purge | A1, A7 |

### 3.5 Der Bootstrap-Read: synchron, DataStore, Haupt-Prozess (entschieden)

Ein Crash-Reporter existiert, um die *frühen* Crashes zu fangen — also muss der
ACRA-Zustand korrekt sein, **bevor der erste Crash möglich ist**, d.h. vor
`onCreate`. Das ist nicht verhandelbar und macht den Rest zu einem Binär:
entweder synchron auf dem Main-Thread lesen, oder ein Coverage-Fenster für
zustimmende Nutzer in Kauf nehmen. Wir **lesen synchron**:

```
val decision = runBlocking { consentDataStore.data.first() }   # ein einzelner Boolean-Wert
ACRA.errorReporter.setEnabled(decision == Granted)
```

- **Kosten:** eine StrictMode-DiskReadViolation in DEBUG (dokumentiertes
  Rauschen, kein Jank — ein einzelner kleiner Read an einem kontrollierten
  Init-Punkt). Das ist die *tragende Kostenseite der No-Window-Garantie*, kein
  Hack — so gehört es in die KDoc.
- **DataStore bleibt einzige Quelle** (kein SharedPreferences-Mirror). Die
  Analogie zum Throttle-Limiter (§4.3) trägt hier *nicht*: der Limiter läuft
  synchron aus dem *Crash-Handler-Thread* ohne Suspend-Kontext → dort ist SP
  alternativlos. Der Bootstrap-Read läuft auf dem *Main-Thread an einem
  kontrollierten Punkt*, wo `runBlocking` auf einem `first()` vertretbar ist.
  Ein Mirror brächte einen zweiten Speicher + Write-Through + Drift-Fläche und
  eliminiert den Main-Thread-Read nicht einmal (`getBoolean` liest beim
  First-Load ebenfalls von Platte).
- **Reihenfolge ist die Invariante (A1):** ACRA wird deaktiviert
  *konstruiert*. Falls die Library nur „init = an" kennt, ist die *erste*
  Anweisung nach `init` `setEnabled(false)`, und erst der geprüfte
  `Granted`-Read schaltet ein. Zwischen `init` und `setEnabled(false)` darf
  keine Anweisung stehen, die crashen könnte — sonst existiert das Mikro-Fenster
  mit aktivem Reporter bei unbekanntem Consent, das A1 verbietet.
- **Prozess-Gate (X2):** der gesamte Block (Read + `setEnabled`) läuft nur, wenn
  `!ACRA.isACRASenderServiceProcess()` (verifizierte API, §11). Im `:acra`-Prozess
  stubt ACRA den Reporter ohnehin selbst — der Read entfiele dort ersatzlos, nicht
  als Notlösung, sondern weil dort nichts zu entscheiden ist. `ACRA.init` selbst
  bleibt in **jedem** Prozess (es konfiguriert dort korrekt den Sender); nur unser
  Consent-Read + Toggle wird gegatet.

---

## 4. Belang B — Ingestion (die Report-Pipeline)

### 4.1 Vier Quellen, zwei ACRA-Eingänge

| Quelle | Was | ACRA-Eingang | Throttle? |
|---|---|---|---|
| **Uncaught-Crash** | echter, nicht gefangener `Throwable` | ACRAs **eigener** installierter UncaughtExceptionHandler (Auto-Report, voller `reportContent`) | **nein** (B3) |
| **Geloggter Fehler** | gefangen, aber via `Timber.e/w(t)` (WARN+) geloggt | `AcraTree` → `handleSilentException(carrier)` | **ja**, 24h/Typ+Site |
| **Post-mortem ANR** | `ApplicationExitInfo` REASON_ANR | `Timber.e(AnrException)` → `AcraTree` → `handleSilentException` | **nein**, `UnthrottledReport` |
| **Watchdog-Stall** | Main-Looper > Schwelle stumm (§6) | `Timber.e(WatchdogStallException)` → `AcraTree`, *vor* dem Kill | **nein**, `UnthrottledReport` |

Kernaussage: **echte Crashes, ANRs und Watchdog-Stalls werden nie gedrosselt;
nur der geloggte Silent-Error-Strom** (der sich in einer Session wiederholen
kann) läuft durch den Cooldown. *(B3)*

Die vierte Quelle ist die grüne-Wiese-Ergänzung: sie schließt das Loch, dass
Looper-Stalls unterhalb der System-ANR-Schwelle (§7 X1) sonst unsichtbar wären.

### 4.2 Der eine Zustellweg (geloggte Fehler, ANRs, Stalls)

```
Timber.e(throwable, msg)                              # der EINZIGE Weg (B2)
   └─ AcraTree.log(priority, tag, msg, t):
        1. Gate:      priority < WARN || t == null  → return (nichts)
        2. Throttle:  ReportThrottle.shouldSend(t) == false → return   (außer UnthrottledReport)
        3. Carrier:   buildAcraReportThrowable(priority, tag, msg, t)   # per-Report (B4)
        4. Zustellen: ACRA.errorReporter.handleSilentException(carrier)
                        └─ persistiert + plant Out-of-process-Versand (:acra, §11); nur wirksam,
                           wenn ACRA enabled — das Consent-Gate (B1). Sende-Erfolg ist HIER nicht sichtbar.
        (alles ab Schritt 3 in try/catch(Throwable) → geschluckt, C1)
        Kein Send-Marker in diesem Pfad: Schritt 4 enqueued nur. Der C4-SEND-Marker
        wird out-of-process von AcraHttpSender gestampt, nie hier (§11, X4).
```

Nie zusätzlich `handleException` direkt aufrufen — Doppelversand. *(B2)*

### 4.3 Throttling (`ReportThrottle`)

- **Zweck:** wiederholt feuernde *live* Exceptions drosseln — max. 1 Report pro
  `Klasse + erster App-Frame-Throw-Site` pro 24 h.
- **Warum 24 h / 7 Tage:** der Cooldown balanciert Signal gegen Backend-Flut —
  ein Fehler, der pro Session hundertmal feuert, ergibt höchstens einen Report
  pro Tag und Site. Cleanup entfernt Einträge > 7 Tage: eine Site, die eine
  Woche nicht mehr auftrat, gilt als kalt; hält die Prefs-Größe beschränkt. Beide
  Konstanten sind Policy, keine technische Grenze — an einem Ort benannt.
- **Speicher:** `SharedPreferences` (`acra_report_limiter`) — die eine bewusste
  Rule-5-Ausnahme, weil `shouldSend` **synchron aus dem Nicht-Coroutine-ACRA-
  Handler-Thread mitten im Crash** läuft (kein Suspend-Kontext, `runBlocking`-
  auf-DataStore wäre der StrictMode-Bug auf dem Crash-Hot-Path). Die Daten sind
  ephemere Telemetrie-Timestamps, gehören nicht ins Backup.
- **Fail-open:** uninitialisiert, Lese-/Schreibfehler, jeder `Throwable` →
  `true` (Report erlauben). Schlimmstenfalls ein Extra-Report. *(C1)*
- **`UnthrottledReport`:** Marker; wer schon upstream dedupliziert ist (ANRs via
  Watermark, Watchdog per Kill terminal) umgeht den Cooldown ganz (kein
  Prefs-Read/Write). Ohne den Bypass kollabierten alle ANRs unter einem
  Klasse+Site-Key und nur der erste käme durch.

### 4.4 Carrier-Exception statt `putCustomData` *(B4)*

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
  jeder AEI-Record genau einmal, ever. AEI-Recordzahl ist beschränkt →
  flood-safe.
- **Trade-off:** nur *harte* ANRs (System hat Prozess gekillt), dafür
  System-Multi-Thread-Dump inkl. Locks, null Background-Overhead, keine
  unmaintainte Dependency. Soft-ANRs gehen bewusst verloren — hier greift die
  Watchdog-Quelle (§6, §7 X1) als Komplement.
- **Best-effort:** der Handler **schluckt** ACRA-Sendefehler (C1), also advanced
  die Watermark auch, wenn ein Report ACRA nie erreicht → dieser ANR fällt weg.
  Akzeptiert (ist ACRA kaputt, gibt es eh nichts zu retten). Handler **nicht**
  zum Rethrow bringen, um zu „recovern" — das unterliefe den Crash-Infra-Swallow.

### 4.6 ANR- & Stall-Fehler (Sad)

| # | Fehler | Verhalten | Invariante |
|---|---|---|---|
| AN1 | AEI-Read wirft | `silentError` → `emptyList`; Watermark unverändert; nächster Start liest neu | C1 |
| AN2 | Trace-Stream null / Read wirft | Report **ohne** Dump statt ANR fallen zu lassen | C1 |
| AN3 | Watermark-Write wirft | `silentError`, Watermark nicht advanced → ANR ggf. nächsten Start erneut (Duplikat akzeptiert) | C1 |
| AN4 | Handler schluckt ACRA-Sendefehler | Watermark advanced trotzdem, ANR gedroppt (best-effort) | C1 |
| ST1 | Stack-Capture im Watchdog wirft | geschluckt; Kill läuft trotzdem — Recovery hat Vorrang vor Report | C1, C3 |

### 4.7 Report-Inhalt & Transport *(B5)*

- **Init in `attachBaseContext`:** `reportFormat = JSON`.
- **`httpSender`:** POST an `BuildConfig.ACRA_URL`, Basic-Auth
  (`ACRA_LOGIN`/`ACRA_PASSWORD`), `tlsProtocols = [TLS 1.2, TLS 1.3]`. Privater,
  selbstgehosteter Server — nie ein Drittanbieter.
- **`reportContent` (exakt diese, nicht mehr):** PACKAGE_NAME, ANDROID_VERSION,
  APP_VERSION_CODE, APP_VERSION_NAME, BRAND, PHONE_MODEL, STACK_TRACE. **Kein**
  CUSTOM_DATA (→ Carrier), kein Logcat, keine ID.
- **Akzeptierte Limitation:** die Basic-Auth-Credentials liegen im
  `BuildConfig` und sind aus dem APK extrahierbar. Bei einem eigenen,
  wegwerfbaren Ingest-Endpoint verkraftbar (kein Zugriff auf Nutzerdaten, nur
  Schreiben von Reports); gehört als bewusste Grenze in `ACCEPTED_LIMITATIONS.md`,
  nicht als „Sicherheit". Server-seitiges Rate-Limiting/Rotation ist die
  Gegenmaßnahme, nicht Client-Geheimhaltung.

---

## 5. Belang C — Resilienz (Safety-Net)

### 5.1 Uncaught-Crash-Pfad

**Was ACRA selbst tut (verifiziert, §11).** ACRAs installierter Handler
(`ErrorReporterImpl.uncaughtException`) sammelt den Report, **persistiert ihn als
Datei**, plant den Versand **out-of-process** (`JobSenderService`/
`LegacySenderService` im `:acra`-Prozess) und killt dann den Haupt-Prozess über
seinen `ProcessFinisher` (`Process.killProcess`; `exitProcess(10)`). Zwei
Konsequenzen prägen den ganzen Pfad:

1. **Es gibt im sterbenden Prozess nichts zu flushen.** Der Report ist eine
   Datei, die den Kill überlebt; ein *anderer* Prozess sendet sie. Ein
   „Flush-Fenster" (Sleep *oder* „Completion-Hook abwarten") im Haupt-Prozess
   würde auf einen Vorgang warten, der absichtlich anderswo läuft — und
   `ErrorReporter` exponiert ohnehin **keinen** Completion-Callback (§11). Darum:
   **kein Flush-Fenster, keine Wartelogik.**
2. **Der deterministische Kill ist ACRAs, nicht unserer** — RELEASE **und** DEBUG
   (`exitProcess(10)`). C2 ist erfüllt, ohne dass wir killen müssen.

- **RELEASE:** ACRAs nackter Handler — persistiert, plant Versand, killt. Nichts
  hinzuzufügen.
- **DEBUG:** die App *umschließt* ACRAs Handler (`setupGlobalExceptionHandler`,
  nur DEBUG), um **Entwickler-Diagnose** zu ergänzen — Versand *und* Kill bleiben
  ACRAs:
  ```
  handleUncaughtException(thread, t):
     Timber.e(t, ...)                          # Rule 9: plain Timber.e (Crash-Infra)
     if t is OutOfMemoryError: System.gc()     # Notfall-GC, Best-effort
     defaultExceptionHandler.uncaughtException(thread, t)   # = ACRA: persist + schedule(:acra) + exitProcess(10)
     # ACRA killt hier bereits. Der folgende Kill ist reiner Backstop —
     # nur erreicht, falls ACRA NICHT killte (Administrator-Veto auf endApplication / Handler warf):
     Process.killProcess(); exitProcess(10)    # deterministisch (C2)
  ```
- **Reihenfolge-Invariante:** Der Wrapper muss **nach** `ACRA.init` installiert
  werden, damit sein `defaultExceptionHandler` ACRAs `ErrorReporterImpl` ist (den
  ACRA in `attachBaseContext` als Default-Handler registriert), nicht der
  Vor-ACRA-Handler. Wird er davor gesetzt, delegiert er am Reporter vorbei und
  **kein Report entsteht**.
- **DEBUG-only-Wrapper — bewusst benannte, aber begrenzte Divergenz:** RELEASE
  läuft durch ACRAs nackten Handler, DEBUG durch den Wrapper. Versand- **und**
  Kill-Pfad sind in beiden identisch (ACRAs `ErrorReporterImpl` /
  `ProcessFinisher`); der Wrapper *ergänzt* nur Logging + OOM-GC, er *ersetzt*
  den ausgelieferten Pfad nicht. Damit ist die Divergenz auf die Diagnose-Zutat
  beschränkt — nicht auf den kritischen Versand/Kill.
- **Kein Throttling echter Crashes** (B3). Ein etwaiger Throttle-Aufruf hier ist
  informativ und gated nichts.

### 5.2 RecoveryWatchdog

Daemon-Thread (kein Coroutine — muss laufen, wenn der Main-Looper hängt), killt
den Prozess, wenn der Main-Looper > Schwelle (§6) nicht dispatcht. Start
**nach** `onCreate` via `Handler.post`, damit die schwere Cold-Start-Arbeit
nicht selbst einen Kill-Restart-Loop auf dem HOME-Prozess auslöst. Neu auf
grüner Wiese: er ist zugleich Report-Quelle (§6, §4.1).

### 5.3 Rule-9-Ausnahme (plain `Timber.e`)

Dateien *im* Crash-Pfad nutzen plain `Timber.e` statt `silentError`, weil ein
DEBUG-Throw dort in genau den Pfad rekursieren würde, der das Safety-Net *ist*:
App-Bootstrap, `TimberWrapper`, `Base{Activity,ViewModel}`, `ReportThrottle`,
Consent-Bootstrap-Read, `BackupFragment`-Handler, Watchdog-Capture. Überall
sonst: `silentError` (laut in DEBUG). Enforcement: `checkConventions` (Rule-9-
Whitelist) — jede neue Crash-Infra-Datei braucht dort einen Eintrag mit
Rekursions-Begründung.

### 5.4 Resilienz-Fehler (Sad)

| # | Fehler | Verhalten | Invariante |
|---|---|---|---|
| RS1 | ACRA-Init wirft in `attachBaseContext` | gefangen, `Log.e`-Fallback (Timber noch nicht da), App läuft weiter, ACRA AUS diesen Lauf | C1 |
| RS2 | `handleSilentException` wirft im AcraTree | geschluckt (`Log.e`), Report verloren, App überlebt, Marker nicht gestampt | C1, C4 |
| RS3 | Fehler *im* Uncaught-Handler | falls Default-Handler noch nicht gerufen: nachholen; dann trotzdem Prozess killen | C2 |
| RS4 | Main-Looper stallt > Schwelle | Watchdog captured Stack (§6), dann killt Prozess, OS startet HOME sauber neu | C2, C3 |

---

## 6. Der Watchdog als Report-Quelle (Stall-Capture)

Der Watchdog erfüllt zwei Aufgaben, die sich nicht ausschließen: **schnelle
Recovery** (Kill, damit der HOME-Prozess nicht sekundenlang tot wirkt) und
**Sichtbarkeit** (der Stall darf nicht spurlos verschwinden). Der Ablauf beim
Trip:

```
Trip (Main-Looper hat timeoutMs nicht getickt):
   1. Capture:  mainStack = mainThread.stackTrace       # wir sind auf dem Daemon-Thread
   2. Enqueue:  Timber.e(WatchdogStallException(mainStack))  # UnthrottledReport → §4.2
                  → handleSilentException persistiert + plant Out-of-process-Versand (:acra);
                    kleiner File-Write auf dem DAEMON-Thread, nicht dem hängenden Main-Thread
   3. Kill:     killSwitch()   # Process.killProcess + exitProcess(10)
   (1–2 in try/catch(Throwable) geschluckt — Kill hat Vorrang, ST1)
   Kein Flush-Schritt: die persistierte Datei überlebt den Kill, :acra sendet sie (§5.1, §11)
```

- **timeoutMs = 8 s (Default).** Begründung, nicht magisch: die
  System-Input-Dispatch-ANR-Schwelle liegt bei ~5 s, die
  Broadcast-Foreground-Schwelle bei ~10 s. 8 s sitzt dazwischen — lang genug,
  um bei schwerer (aber endlicher) Cold-Start-Arbeit nicht falsch zu feuern
  (deshalb erst *nach* `onCreate` gestartet), kurz genug, um vor der
  Broadcast-Schwelle zu recovern. Genau in diesem Fenster (kein pending Input →
  System deklariert *keine* input-ANR) ist der Watchdog der einzige Weg, den
  Stall überhaupt zu sehen — siehe §7 X1.
- **Warum Capture *vor* Kill:** ein `Process.killProcess` erzeugt kein
  `REASON_ANR`, also fängt der `AnrReporter` (§4.5) diesen Stall nie. Ohne
  Schritt 1–2 wäre er unsichtbar. Der Report ist `UnthrottledReport`, weil jeder
  Stall terminal ist (Prozess stirbt danach) — kein Cooldown-Kandidat.
- **Warum das trotz sofortigem Kill ankommt:** `handleSilentException`
  *persistiert* den Report und plant den Versand **out-of-process** (§11); die
  Datei überlebt den `exitProcess(10)` und wird vom `:acra`-Prozess gesendet. Der
  einzige In-Process-Schritt ist ein kleiner File-Write — und der läuft auf dem
  **Daemon-Thread**, nicht auf dem hängenden Main-Thread. Das entkräftet die
  historische Sorge des heutigen `RecoveryWatchdog` („I/O auf dem gerade
  hängenden Thread"): die I/O findet dort nicht statt. Die bewusste Umkehr der
  bisherigen „meldet nicht"-Entscheidung ist in §7 X1 ausgeführt.
- **Warum best-effort:** Recovery > Report. Wirft das Capture/Send, wird es
  geschluckt und der Kill läuft trotzdem (ST1, C3). Ein hängender Reporter darf
  die Recovery nie blockieren.

---

## 7. Wechselwirkungen zwischen A · B · C

Die saubere Trennung der drei Belange erzeugt genau einen blinden Fleck: Fehler,
die *keiner* der drei allein besitzt, weil sie an der Naht zwischen zweien
entstehen. Diese Nähte sind hier explizit.

### X1 — Watchdog-Kill (C) vs. ANR-Sichtbarkeit (B)

**Problem:** `RecoveryWatchdog` killt bei 8 s per `Process.killProcess`.
`AnrReporter` filtert `REASON_ANR`. Ein Selbst-Kill erzeugt aber kein
`REASON_ANR` — und Looper-Stalls im Fenster ~5–10 s (kein pending Input, unter
der Broadcast-Schwelle) deklariert das System oft gar nicht als ANR. Ohne
Gegenmaßnahme wären **genau die langsamen Hangs, die der Watchdog killt,
systematisch unsichtbar**: der Belang C „löst" den Hang und der Belang B erfährt
nie davon.

**Auflösung:** der Watchdog captured vor dem Kill selbst (§6, Quelle 4). C
liefert damit B seinen eigenen Report. *(C3)*

**Bewusste Umkehr einer dokumentierten Entscheidung.** Der heutige
`RecoveryWatchdog` trägt einen KDoc-Abschnitt „Why this doesn't report" mit zwei
Gründen: (a) es dupliziere `AnrReporter`, (b) es verlange I/O „auf dem gerade
hängenden Thread"; dazu eine Future-self-Note: „the fix is in `AnrReporter`, not
here." Diese Spec kehrt die Entscheidung um — begründet, nicht still:
- Grund (b) ist für den hier spezifizierten Pfad **sachlich falsch**: Capture und
  `handleSilentException`-Persist laufen auf dem **Daemon-Thread** (§6); der
  Main-Thread wird nicht angefasst.
- Grund (a) trifft **gerade nicht** zu: Selbst-Kill-Stalls erzeugen kein
  `REASON_ANR`, `AnrReporter` filtert aber auf `REASON_ANR` (verifiziert am
  heutigen Filter) und sieht sie deshalb nie — es gibt nichts zu duplizieren; ohne
  den Watchdog-Report sind sie unsichtbar. Der vorgemerkte Alternativweg („fix in
  `AnrReporter`") funktioniert für **diese** Stall-Klasse folglich nicht.
Die Umkehr ist damit keine Abweichung hinter dem Rücken der alten Entscheidung,
sondern die Auflösung genau dieses Nahtfehlers. Wird §6 umgesetzt, muss der
`RecoveryWatchdog`-KDoc entsprechend umgeschrieben werden (der „doesn't report"-
Abschnitt wird falsch).

### X2 — Bootstrap (A) läuft in jedem Prozess (C)

**Problem:** ACRA spawnt einen eigenen **Sender-Prozess** (`:acra`);
`attachBaseContext` und damit R1 laufen dort ebenfalls. Präzise, gegen 5.13.1
verifiziert (§11): `ACRA.init` installiert im Sender-Prozess **selbst schon**
keinen `ErrorReporterImpl` — `errorReporter` bleibt ein Stub, ein `setEnabled`
dort ist ein **No-Op**. Der Toggle kann also **nicht** inkonsistent werden; die
naheliegende Sorge „ACRA im Sender-Prozess falsch toggeln" trifft nicht zu. Was
*bleibt*: ein naiver Bootstrap führt im `:acra`-Prozess den überflüssigen
`runBlocking`-DataStore-Read (R1) aus — Arbeit im falschen Prozess auf einem
Store, den DataStore Preferences ausdrücklich **nicht** multi-prozess-sicher hält
(zwei Prozesse am selben File ist kein unterstützter Modus).

**Auflösung:** der Bootstrap gated R1 + `setEnabled` mit
`ACRA.isACRASenderServiceProcess()` (public, `@JvmStatic`, §11): nur der
Haupt-Prozess liest Consent und toggelt; im `:acra`-Prozess überlässt der
Bootstrap ACRA seine eigene, dafür vorgesehene Init und fasst weder Gate noch
DataStore an. Der Nutzen ist damit ehrlich benannt — **vermiedener
Fehl-Prozess-Read**, nicht „verhinderte Inkonsistenz". *(A1, C1)*

### X3 — Consent-„aus" (A) vs. liegende Report-Dateien (B)

**Problem:** `setEnabled(false)` stoppt *neue* Zustellung, aber ACRA hält
bereits erzeugte, noch nicht gesendete Reports als Dateien vor. Nach einem
Revoke und späterem Re-Consent könnten Reports abfließen, die *während der
Nicht-Consent-Phase* entstanden — ein Privacy-Leck an der Naht A↔B.

**Auflösung:** Revoke ist destruktiv — `applyConsent(false)` löscht die
ungesendete ACRA-Queue über die offizielle API `BulkReportDeleter(context)
.deleteReports(approved, nrToKeep = 0)`, und zwar für **beide** Ordner
(`approved = true` **und** `false` — ACRA hält `ACRA-approved` *und*
`ACRA-unapproved`, §11; nur einer räumt die Hälfte). Kein Griff in private
Verzeichnisse. *(A7; Sad-Path SP1)*

### X4 — Toter Reporter (B/C) vs. „keine Crashes" (Beobachter)

**Problem:** C1 schluckt jeden Reporter-Fehler, AN4/ST1 droppen best-effort.
Korrekt für Stabilität — aber ein seit Monaten kaputter Sender ist von einem
gesunden, stillen System **ununterscheidbar**. Der Belang C erkauft Stabilität
mit einem Informationsloch.

**Auflösung:** ein Last-Successful-**Send**-Marker (C4), im Dev-Screen (§8c)
sichtbar. Wichtig — und der Grund, warum die naive Variante nicht reicht: **nur
die Beobachtung eines erfolgreichen *Sends* löst das Problem.** Ein billiger
In-Process-„Enqueue"-Marker (in `AcraTree`) tut es **nicht**: er stünde bei
„keine Crashes" und bei „toter Sender" gleichermaßen still und unterscheidet die
beiden gerade nicht. Sende-Erfolg ist aber nur im `:acra`-Prozess sichtbar
(out-of-process, §11). Der Marker wird deshalb von einem **eigenen `ReportSender`**
(Dekorator um `HttpSender`) bei HTTP-Erfolg in einen cross-prozess lesbaren
Timestamp geschrieben und im Haupt-Prozess gelesen. Das ist die **eine** bewusst
zugelassene Cross-Prozess-Ablage — vertretbar, weil es ein reiner
Telemetrie-Timestamp mit Last-Write-Wins ist (kein Entscheidungszustand wie
Consent, der laut X2 gerade **nicht** multi-prozess sein darf). Der Preis der
Beobachtbarkeit ist also ehrlich benannt: nicht „billig", sondern ein
zusätzlicher Sender plus eine begründete Cross-Prozess-Ausnahme.

---

## 8. Settings-Oberfläche

Drei ACRA-Berührungen im `SettingsFragment`:

**a) Consent-Toggle** (Preference-Klick):
```
forceShowConsentDialog(activityContext) { userGaveConsent ->
   controller.applyConsent(userGaveConsent)      # ACRA-Toggle + W2-Persist + ggf. Queue-Purge via BulkReportDeleter (§3, A7, §11)
   showToastSafe(enabled/disabled)               # Bestätigung
   applyCrashReportSummary(userGaveConsent)       # Summary direkt aus dem Wert,
}                                                  # KEIN Re-Read (Race mit Persist, AUDIT-10 #1)
```
Dialog in `currentDialog` getrackt, auf `onDestroyView` dismissed (leckt sonst
den `setCancelable(false)`-Window, AUDIT-3 #12). Alle Dialog-Fehler = §3.4
SD1–SD4.

**b) Summary rendern** (`updateCrashReportSummary`): `currentConsent()` (echtes
DataStore-Read, kann werfen) → bei Fehler `silentError`, Summary bleibt alt (nur
Anzeige).

**c) Developer-Kommandos** (bewusst getrennte Handler, weil ein Throw im
Preference-Click direkt an den globalen Handler geht):
- **Reset ACRA-Timer** → `ReportThrottle.resetAll()` + Toast. Fehler →
  `silentError`, `false`. Nur Dedup-State, keine Nutzerdaten.
- **Pipeline-Status** → zeigt den Last-Successful-**Send**-Marker (C4): „letzter
  erfolgreich *gesendeter* Report vor X" bzw. „nie". Der Marker wird vom eigenen
  `ReportSender` (`AcraHttpSender`) im `:acra`-Prozess bei HTTP-Erfolg gestampt
  (§11, X4), **nicht** von `AcraTree` — der sähe nur das Enqueue, nicht den
  Versand. Der Diagnose-Anker für X4.
- **Throw-Test-Exception** → Toast, dann `Thread { sleep(800); throw }` — der
  Throw läuft auf einem nackten Thread **ohne** CoroutineExceptionHandler, reist
  also durch genau den echten Uncaught-Pfad (§5.1). Der Crash ist hier
  **gewollt**.

---

## 9. Komponenten-Verantwortlichkeiten (Ziel-Rollen & Paketlayout)

Grüne-Wiese-Packaging: alles unter `crashreporting/`, gespiegelt auf die drei
Belange — **nicht** verstreut in `ui/util/`. Das Paket ist die Architektur, die
§1 beschreibt, im Dateisystem sichtbar.

```
:domain/crashreporting/
   consent/   ConsentDecision, ConsentReadResult, ConsentWriteResult,
              CrashReportConsentRepository (interface)
   ingestion/ ReportCarrier (buildAcraReportThrowable + LoggedThrowable)
:data/crashreporting/
   consent/   CrashReportConsentRepositoryImpl, ConsentBootstrap (pre-Hilt R1)
:app/crashreporting/
   consent/   ConsentController, ConsentDialog, AcraToggle,
              ConsentSaveFailureNotifier
   ingestion/ AcraTree, ReportThrottle, AnrReporter, AcraHttpSender (ReportSender-Dekorator, :acra)
   resilience/ UncaughtCrashHandler, RecoveryWatchdog, CrashReportingBootstrap
```

| Komponente | Modul | Verantwortung | Besitzt **nicht** |
|---|---|---|---|
| `CrashReportConsentRepository` (+ Impl) | domain/data | Persistenz: `readState()→ConsentReadResult`, `setConsent()→ConsentWriteResult`, Getter (total). Wirft nie für I/O. | ACRA-Toggle, UI, Scope |
| `ConsentBootstrap` | data | **Nur** pre-Hilt-R1 + androidTest-Seed, prozess-bewusst (X2). Teilt Key mit dem Repo. | Interaktive Writes |
| `ConsentDecision`/`ConsentReadResult`/`ConsentWriteResult` | domain | Sealed-Modelle (A2/A4/A8), pure Kotlin | — |
| `ConsentController` | app | Koordinator: `resolveStartupAction`, `applyConsent` (inkl. Queue-Purge bei Revoke, A7), `reaffirmConsent`, `persistConsent`. Läuft auf `ApplicationScope`. | Dialog-Bau, DataStore |
| `ConsentDialog` | app | **Nur** Dialog bauen/zeigen, `onResult` bei echtem Tap, `null` sonst (A3) | Lesen, Persistieren, Scope |
| `AcraToggle` | app | Seam über `ACRA.errorReporter.setEnabled` + Queue-Purge (`BulkReportDeleter`, **beide** Ordner, §11) — hält den Seiteneffekt in `:app`, Controller JVM-testbar | Entscheidungslogik |
| `ConsentSaveFailureNotifier` | app | Seam: Toast bei fehlgeschlagenem Persist (A4) | Entscheidungslogik |
| `AcraTree` | app | Der eine Zustellweg: Gate → Throttle → Carrier → `handleSilentException` (persist + Out-of-process-schedule); schluckt (C1/B2/B4) | Consent-Check (B1); **Send-Erfolg** (out-of-process, → `AcraHttpSender`) |
| `AcraHttpSender` | app (`:acra`-Prozess) | `ReportSender`-Dekorator um `HttpSender`; stampt bei HTTP-Erfolg den Last-Successful-Send-Marker cross-prozess (C4/X4, §11) | Report-Inhalt, Consent-Gate |
| `ReportThrottle` | app | 24h-Throttle des Silent-Error-Stroms, fail-open (B3) | Crashes/ANRs/Stalls drosseln |
| `buildAcraReportThrowable`/`LoggedThrowable` | domain | Per-Report-Carrier (B4), pure/JVM-testbar | Android-Runtime |
| `AnrReporter` | app | AEI → `AnrReport`, Watermark-Dedup, best-effort | ACRA-Aufruf (delegiert an Handler) |
| `RecoveryWatchdog` | app | Looper-Stall-Kill **+** Stall-Capture (Daemon-Thread) → `handleSilentException`, *vor* dem Kill (§6, C3, X1) | Consent, Sende-Entscheidung |
| `UncaughtCrashHandler` | app (nur DEBUG) | Wrapper um ACRAs Handler: Log + OOM-GC, dann Delegation; **kein** Flush-Fenster; Kill ist ACRAs `ProcessFinisher` (eigener Kill nur Backstop bei Veto/Wurf) — C2, §5.1, §11 | RELEASE-Pfad (= ACRAs nackter Handler), eigenes Flushen |
| `CrashReportingBootstrap` | app | ACRA-Init-Reihenfolge (A1), Prozess-Check (X2), Handler/Watchdog-Wiring, KolibriLog-Bridge | Consent-Entscheidung (an R1/Gate) |
| `MainActivity`/`SettingsFragment` | app | Dünner Glue: Gate-Ergebnis rendern, Dialog tracken/dismissen | Alles obige |

---

## 10. Was bewusst NICHT getan wird (abzulehnende „Fixes")

- **`onResult(false)` bei Dialog-Show-Fehler** — macht aus Anzeige-Fehler ein
  „Nein". *(A3, AUDIT-10 #6/#8)*
- **`Unavailable`/`Failed` in Defaults kollabieren** — lässt I/O-Fehler echte
  Entscheidungen überschreiben / Saves vortäuschen. *(A2/A4)*
- **`ConsentDecision` zurück in zwei Booleans zerlegen** — reintroduziert den
  illegalen Zustand und die Drift-Fläche. *(A8)*
- **Zweiter Consent-Check in B** oder eigene Sende-Logik statt
  `handleSilentException` — umgeht das eine Gate. *(B1)*
- **Zusätzlicher `handleException`-Aufruf für ANRs/Stalls/Fehler** —
  Doppelversand. *(B2)*
- **ANRs/Stalls durch den Throttle-Cooldown routen** — kollabiert distinkte
  Hangs unter einem Key. *(B3, `UnthrottledReport`)*
- **`putCustomData` für Report-Kontext** — prozess-globale Map → Race, erreicht
  den Server eh nicht. *(B4, AUDIT-6 #4)*
- **Widerruf ohne Queue-Purge** — lässt Reports der Nicht-Consent-Phase später
  abfließen. *(A7, X3)*
- **Watchdog nur killen lassen (ohne Capture)** — macht die langsamsten Hangs
  unsichtbar. *(C3, X1)*
- **`ReportThrottle`/Consent nach DataStore „aufräumen"** bzw. Consent nach
  SharedPreferences — beide Speicherwahlen sind begründet (Rule 5 + §3.5/§4.3).
- **Repository `Purgeable` machen** — Factory-Reset darf Privacy nicht
  umkippen. *(A5)*
- **Bootstrap-Read im Sender-Prozess ausführen** — überflüssiger Read im falschen
  Prozess auf einem nicht multi-prozess-sicheren Store; per
  `ACRA.isACRASenderServiceProcess()` gaten (der Toggle selbst ist dort ohnehin
  ein No-Op). *(X2, §11)*
- **Handler zum Rethrow bringen, um einen ANR/Stall zu „recovern"** —
  unterläuft den Crash-Infra-Swallow. *(§4.5, §6)*
- **`silentError` in Crash-Infra-Dateien** — rekursiert ins Safety-Net. *(§5.3,
  Rule 9)*
- **Den doppelten R1/R2-Read „vereinheitlichen", indem R1 entfällt** — R1 muss
  vor Hilt laufen; ein File, zwei Leser, per Reaffirm rekonziliert ist korrekt.
- **Irgendein Flush-Fenster im sterbenden Prozess** (Sleep *oder* „Completion-
  Hook abwarten") — ACRA persistiert den Report und sendet ihn **out-of-process**;
  die Datei überlebt den Kill, es gibt in-process nichts zu flushen, und einen
  Completion-Callback exponiert `ErrorReporter` gar nicht (§11). Warten wäre
  Warten aufs Falsche. *(§5.1)*
- **Den C4-Marker in `AcraTree` als „letzter Report" stampen** — `AcraTree` sieht
  nur das *Enqueue*, nie den Sende-Erfolg (out-of-process); ein solcher Marker
  stünde bei „keine Crashes" und „toter Sender" gleich still und löst X4 gerade
  nicht. Der Marker gehört in den `:acra`-`ReportSender`. *(C4, X4, §11)*

---

## 11. ACRA-5.13.1-API-Anker (verifiziert gegen die gepinnte Version)

Jede Invariante, die von ACRAs *Verhalten* abhängt, ist hier an ein reales,
gegen `ch.acra:acra-{core,http}:5.13.1` (die per CLAUDE.md Rule 6 gepinnte,
nicht änderbare Version) verifiziertes Symbol gebunden. Die Sources wurden dafür
gelesen, nicht die Doku geglaubt. Ändert sich die Version, ist diese Tabelle die
Checkliste, die neu verifiziert werden muss — jede Zeile ist ein Punkt, an dem
die Library die Spec tragen *muss*, sonst kippt eine Invariante.

| Belang / Invariante | ACRA-Symbol (5.13.1, verifiziert) | Verhalten, auf das sich die Spec stützt |
|---|---|---|
| **X2** Prozess-Gate | `ACRA.isACRASenderServiceProcess()` — public, `@JvmStatic` | Prozessname endet auf `:acra`. **Wichtig:** `ACRA.init` installiert im Sender-Prozess **selbst schon** keinen `ErrorReporterImpl` (`errorReporter` bleibt ein Stub via `StubCreator`), `setEnabled` ist dort ein **No-Op**. Unser Gate verhindert also keinen inkonsistenten Toggle (den kann es nicht geben), sondern nur den überflüssigen `runBlocking`-DataStore-Read im zweiten Prozess. |
| **A7** Queue-Purge | `BulkReportDeleter(ctx).deleteReports(approved, nrToKeep=0)` — public | Löscht ungesendete Reports. ACRA hält **zwei** Ordner (`ReportLocator`: `ACRA-approved` **und** `ACRA-unapproved`); ein vollständiger Purge ruft **beide** (`approved=true` *und* `false`), sonst bleibt eine Hälfte liegen. Offizielle API, kein Griff in private Verzeichnisse. |
| **C2** Deterministischer Kill | `ErrorReporterImpl.uncaughtException` → `ReportExecutor.endApplication` → `ProcessFinisher` (`Process.killProcess`; `exitProcess(10)`) | ACRA killt **selbst**, in RELEASE **und** DEBUG. C2 ist keine DEBUG-only-Zutat unseres Wrappers, sondern ACRAs Garantie. Unser Kill ist nur Backstop. |
| **§5.1** „Flush" | *(kein Symbol — existiert nachweislich nicht)* | `ErrorReporter` exponiert **keinen** Completion-Callback (Interface hat nur `handleSilentException` / `handleException(_, endApplication)` / `setEnabled` / Custom-Data). Versand ist **out-of-process + asynchron**: `DefaultSenderScheduler` plant `JobSenderService`/`LegacySenderService` im `:acra`-Prozess; der Report ist eine **Datei**, die den Kill des Haupt-Prozesses überlebt. Im sterbenden Prozess gibt es **nichts zu flushen**. |
| **C4** Send-Marker | `ReportSender` / `ReportSenderFactory` (`@AutoService`), `ReportDistributor.distribute(): Boolean` | Sende-Erfolg ist **nur** im `:acra`-Prozess beobachtbar. `AcraTree` sieht ihn **nie** — es *enqueued* nur. Ein echter Send-Marker braucht einen eigenen `ReportSender` (Dekorator um `HttpSender`), der bei HTTP-Erfolg cross-prozess stampt — siehe C4/X4. |
| **B2** Zustellweg | `handleSilentException(e)` (nicht-terminal), `handleException(e, endApplication)` (terminal) | `handleSilentException` = persist + Out-of-process-schedule, **kein** Kill. Der eine Silent-Weg. Nie zusätzlich `handleException` → Doppelversand. |

Verifikationsnotiz: geprüft aus `acra-core-5.13.1-sources.jar` /
`acra-http-5.13.1-sources.jar` (`ACRA.kt`, `ErrorReporterImpl.kt`,
`ReportExecutor.kt`, `ProcessFinisher.kt`, `DefaultSenderScheduler.kt`,
`BulkReportDeleter.kt`, `ReportLocator.kt`, `ReportSender.kt`).

---

## 12. Verwandte Docs

- `CLAUDE.md` — Rule 5 (Storage), 7 (Crash-Safety), 8 (opt-in), 9
  (Timber/silentError/silentDeath), 11 (Fehler-als-Wert).
- `KNOWN_ISSUES.md §3` — der bewusste StrictMode-DiskReadViolation von R1 (§3.5).
- `ACCEPTED_LIMITATIONS.md` — extrahierbare Basic-Auth-Credentials (§4.7),
  bewusst verlorene Soft-ANRs außerhalb des Watchdog-Fensters (§4.5/§6).
- `AUDIT-10.md` — crash-consent-layering-Review (Consent-Invarianten §2).
- `AUDIT-6.md` / `AUDIT-6_ADDENDUM.md` — Carrier-Exception statt CUSTOM_DATA.

---

## 13. Reifegrad & Umsetzungsreihenfolge

Diese Spec beschreibt einen Soll-Zustand, dessen Teile **unterschiedlich reif**
sind. Das ist kein Widerspruch zur Ziel-Architektur — es ist die ehrliche
Trennung zwischen „entschieden und weitgehend gebaut" und „neu, mit
Verhaltensänderung und offenen Kanten". Wer umsetzt, liest die Reihenfolge unten
als Priorität: billig-und-reif zuerst, riskant-und-unreif zuletzt.

### Ausgereift (auslieferungsreif — entschieden, verifiziert, meist schon gebaut)

- **Consent A1–A8.** AUDIT-10-Arbeit, auf diesem Branch gebaut und gepinnt.
  Einziger Netto-Neubau: `ConsentDecision` sealed statt zwei Booleans — vom
  Typsystem erzwungen, Risiko minimal.
- **Ingestion B1–B5.** AcraTree, Carrier-Exception (`putCustomData` restlos
  entfernt), Throttle + `UnthrottledReport`, `AnrReporter` mit Watermark.
- **§5.1/C2 Uncaught-Pfad** — nach der 5.13.1-Korrektur: Out-of-process-Modell,
  Kill via ACRAs `ProcessFinisher`, kein Flush-Fenster, Reihenfolge-Invariante.
- **X2 Prozess-Gate & A7-Purge-*Mechanismus*** — an verifizierte APIs gebunden
  (`isACRASenderServiceProcess()`, `BulkReportDeleter`). Die *API-Frage* ist zu.
- **§9 Packaging-Reorg** nach `crashreporting/` — konzeptionell trivial, nur ein
  großer mechanischer Diff.

### Noch nicht ganz (echte Lücken — Pflege vor Umsetzung, absteigendes Risiko)

- **§6/X1 Watchdog-als-Report-Quelle — unreifster, riskantester Punkt.** Die
  Umkehr ist begründet, aber offen: (1) die 8-s-Schwelle ist gegen *Timing*-
  Falschauslösung argumentiert, **nicht** gegen *Slow-Device-Tail-Latency* —
  schwere legitime Main-Thread-Arbeit kann 8 s überschreiten und den
  **HOME-Prozess** killen-und-neustarten; (2) die Stall-Erkennung selbst
  (Daemon-Thread, echter Looper-Hang) ist die Flaky-Instrumented-Test-Klasse,
  die das Projekt meidet — nur der Capture-vor-Kill-Seam (C3) ist JVM-testbar;
  (3) **Kill vs. nur Capture** ist ungeklärt — die Spec verschweißt beides.
  *Braucht:* Slow-Device-Falschauslösungs-Analyse + explizite Kill/Capture-Wahl.
- **C4/X4 Send-Marker — Mechanismus unterspezifiziert, Kandidat zum Verschieben.**
  Der Cross-Prozess-Store ist nicht festgelegt (SharedPreferences ist nicht
  verlässlich multi-prozess; ehrliche Wahl: Datei mit atomarem Rename). Kosten:
  ein zusätzlicher Sender + die einzige sonst verbotene Cross-Prozess-Ablage für
  ein Dev-Screen-Diagnosewerkzeug. *Rat:* als **optional/später** markieren.
- **A7-Purge — Rest-Kanten.** Zwei ungenannte Fälle: Revoke-während-aktivem-Send-
  Race (`:acra` sendet, während der Main-Prozess löscht — benigne, aber benennen)
  und SP1-Restrisiko (Purge wirft → geschluckt → Altbestand bleibt). Je eine Zeile.

### Vielleicht Pflege (Urteilsfragen & Politur — kein Defekt)

- **Consent-Storage (DataStore + `runBlocking` vs. SP-Mirror).** Intern
  wasserdicht, aber die eine Stelle, wo grüne Wiese mehr hergäbe. Design-
  Entscheidung: bewusst bestätigen (→ „ausgereift") oder einmal neu aufmachen.
- **Die „Gepinnt durch"-Spalte insgesamt.** Als Anspruch exzellent, aber mehrere
  neue Pins (Watchdog-Trip, Multi-Prozess-Bootstrap, Cross-Prozess-Marker) liegen
  im schwer-testbaren Bereich, der mit dem strengen androidTest-Bar kollidiert.
  Einmal markieren, welcher Pin JVM-real ist und welcher einen instrumentierten
  Test bräuchte, den das Projekt evtl. gar nicht will.
- **`KNOWN_ISSUES.md §3`** beschreibt den R1-Read noch nicht — reines Doc-Follow-up.

### Empfohlene Umsetzungsreihenfolge

1. Package-Reorg + `ConsentDecision` sealed (billig, reif).
2. A7-Purge inkl. der zwei Rest-Kanten.
3. §6 Watchdog-Report — erst nach eigener Slow-Device-/Kill-vs-Capture-Analyse.
4. C4-Send-Marker — zuletzt oder bewusst weglassen.
