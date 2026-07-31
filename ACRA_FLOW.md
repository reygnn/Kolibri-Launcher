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
   das Gate nie an. *(→ X2)*
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
| **A7** | **Widerruf ist destruktiv.** Ein Wechsel auf `Denied` löscht die ungesendete ACRA-Report-Warteschlange, damit kein Report aus der Consent-Phase nach einem späteren Re-Consent abfließt. | Controller-Test (`applyConsent(false)` ruft Queue-Purge) |
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
| **C2** | **Nach Uncaught-Crash terminiert der Prozess deterministisch.** Kein Zombie: Flush-Fenster, dann harter Kill. Lügende Zustände → `silentDeath`. *(Rule 9/12)* | Uncaught-Handler-Test (Kill-Pfad erreicht); `silentDeath`-Test |
| **C3** | **Der Watchdog verblindet den ANR-Reporter nicht.** Ein Watchdog-Kill erzeugt kein `REASON_ANR`; darum captured der Watchdog **vor** dem Kill selbst einen Report. Kein stiller Verlust von Looper-Stalls. *(→ §7 X1)* | `RecoveryWatchdog`-Test: Capture-Callback vor `killSwitch` |
| **C4** | **Pipeline-Lebendigkeit ist beobachtbar.** Ein toter Reporter ist von „keine Crashes" unterscheidbar (Last-Successful-Send-Marker, im Dev-Screen sichtbar). | `AcraTree`-Test: erfolgreicher Send stampt Marker; Dev-Screen-Test |

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
                        └─ (nur wirksam, wenn ACRA enabled — das Consent-Gate, B1)
        5. Marker:    bei Erfolg: Last-Successful-Send stampen (C4)
        (alles ab Schritt 3 in try/catch(Throwable) → geschluckt, C1)
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

- **RELEASE:** ACRAs eigener installierter UncaughtExceptionHandler fängt den
  Crash, sendet den vollen Report, Prozess endet.
- **DEBUG:** die App *umschließt* ACRAs Handler (`setupGlobalExceptionHandler`,
  nur DEBUG), um Resilienz-Belange zu ergänzen — der Versand bleibt ACRAs
  Handler:
  ```
  handleUncaughtException(thread, t):
     Timber.e(t, ...)                          # Rule 9: plain Timber.e (Crash-Infra)
     if t is OutOfMemoryError: System.gc()     # Notfall-GC, Best-effort
     defaultExceptionHandler.uncaughtException(thread, t)   # = ACRA sendet
     finally: flushWindow()                    # siehe unten
              Process.killProcess(); exitProcess(10)        # deterministisch (C2)
  ```
- **`flushWindow()`:** bevorzugt ACRAs eigenen Completion-/Callback-Hook
  abwarten (bounded), nicht ein geratenes `Thread.sleep(500)`. Nur wenn die
  Library keinen Hook exponiert, ist ein *beschränkter* Sleep der Fallback — und
  dann als „geraten, Obergrenze X" dokumentiert, nicht als „genug".
- **DEBUG-only-Wrapper — bewusst benannte Divergenz:** RELEASE läuft durch
  ACRAs nackten Handler, DEBUG durch den Wrapper. Man testet also in DEBUG einen
  anderen Pfad, als man ausliefert. Das ist gewollt (die Zusatz-Belange sind
  Entwickler-Diagnose), muss aber bekannt sein: der *Versand*-Pfad ist in beiden
  identisch (ACRAs Handler), nur die Umhüllung unterscheidet sich.
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
   2. Report:   Timber.e(WatchdogStallException(mainStack))  # UnthrottledReport → §4.2
   3. Flush:    bounded window (wie §5.1)
   4. Kill:     killSwitch()   # Process.killProcess + exitProcess(10)
   (1–3 in try/catch(Throwable) geschluckt — Kill hat Vorrang, ST1)
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

### X2 — Bootstrap (A) läuft in jedem Prozess (C)

**Problem:** ACRA spawnt einen eigenen **Sender-Prozess**; `attachBaseContext`
und damit R1 laufen dort ebenfalls. Ein naiver Bootstrap würde im
Sender-Prozess erneut Consent lesen und ACRA toggeln — doppelte Arbeit,
potenziell inkonsistent, und DataStore Preferences ist nicht multi-prozess-
sicher (zwei Prozesse, die denselben File halten, ist explizit kein
unterstützter Modus).

**Auflösung:** der Bootstrap ist **prozess-bewusst**. Nur der Haupt-Prozess
führt R1 + `setEnabled` aus; im Sender-Prozess (Prozessname endet auf ACRAs
Sender-Suffix) überlässt der Bootstrap ACRA seine eigene, dafür vorgesehene
Init und fasst das Gate nicht an. *(A1, C1)*

### X3 — Consent-„aus" (A) vs. liegende Report-Dateien (B)

**Problem:** `setEnabled(false)` stoppt *neue* Zustellung, aber ACRA hält
bereits erzeugte, noch nicht gesendete Reports als Dateien vor. Nach einem
Revoke und späterem Re-Consent könnten Reports abfließen, die *während der
Nicht-Consent-Phase* entstanden — ein Privacy-Leck an der Naht A↔B.

**Auflösung:** Revoke ist destruktiv — `applyConsent(false)` löscht die
ungesendete ACRA-Queue. *(A7; Sad-Path SP1)*

### X4 — Toter Reporter (B/C) vs. „keine Crashes" (Beobachter)

**Problem:** C1 schluckt jeden Reporter-Fehler, AN4/ST1 droppen best-effort.
Korrekt für Stabilität — aber ein seit Monaten kaputter Sender ist von einem
gesunden, stillen System **ununterscheidbar**. Der Belang C erkauft Stabilität
mit einem Informationsloch.

**Auflösung:** ein Last-Successful-Send-Marker (C4), im Dev-Screen (§8c)
sichtbar. Billig, und rettet den einen Fall, in dem das ganze Subsystem
unbemerkt sinnlos geworden ist.

---

## 8. Settings-Oberfläche

Drei ACRA-Berührungen im `SettingsFragment`:

**a) Consent-Toggle** (Preference-Klick):
```
forceShowConsentDialog(activityContext) { userGaveConsent ->
   controller.applyConsent(userGaveConsent)      # ACRA-Toggle + W2-Persist + ggf. Queue-Purge (§3, A7)
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
- **Pipeline-Status** → zeigt den Last-Successful-Send-Marker (C4): „letzter
  erfolgreicher Report vor X" bzw. „nie". Der Diagnose-Anker für X4.
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
   ingestion/ AcraTree, ReportThrottle, AnrReporter
   resilience/ UncaughtCrashHandler, RecoveryWatchdog, CrashReportingBootstrap
```

| Komponente | Modul | Verantwortung | Besitzt **nicht** |
|---|---|---|---|
| `CrashReportConsentRepository` (+ Impl) | domain/data | Persistenz: `readState()→ConsentReadResult`, `setConsent()→ConsentWriteResult`, Getter (total). Wirft nie für I/O. | ACRA-Toggle, UI, Scope |
| `ConsentBootstrap` | data | **Nur** pre-Hilt-R1 + androidTest-Seed, prozess-bewusst (X2). Teilt Key mit dem Repo. | Interaktive Writes |
| `ConsentDecision`/`ConsentReadResult`/`ConsentWriteResult` | domain | Sealed-Modelle (A2/A4/A8), pure Kotlin | — |
| `ConsentController` | app | Koordinator: `resolveStartupAction`, `applyConsent` (inkl. Queue-Purge bei Revoke, A7), `reaffirmConsent`, `persistConsent`. Läuft auf `ApplicationScope`. | Dialog-Bau, DataStore |
| `ConsentDialog` | app | **Nur** Dialog bauen/zeigen, `onResult` bei echtem Tap, `null` sonst (A3) | Lesen, Persistieren, Scope |
| `AcraToggle` | app | Seam über `ACRA.setEnabled` + Queue-Purge — hält den Seiteneffekt in `:app`, Controller JVM-testbar | Entscheidungslogik |
| `ConsentSaveFailureNotifier` | app | Seam: Toast bei fehlgeschlagenem Persist (A4) | Entscheidungslogik |
| `AcraTree` | app | Der eine Zustellweg: Gate → Throttle → Carrier → `handleSilentException` → Marker; schluckt (C1/B2/B4/C4) | Consent-Check (B1) |
| `ReportThrottle` | app | 24h-Throttle des Silent-Error-Stroms, fail-open (B3) | Crashes/ANRs/Stalls drosseln |
| `buildAcraReportThrowable`/`LoggedThrowable` | domain | Per-Report-Carrier (B4), pure/JVM-testbar | Android-Runtime |
| `AnrReporter` | app | AEI → `AnrReport`, Watermark-Dedup, best-effort | ACRA-Aufruf (delegiert an Handler) |
| `RecoveryWatchdog` | app | Looper-Stall-Kill **+** Stall-Capture als Report-Quelle (§6, C3) | Consent, Sende-Entscheidung |
| `UncaughtCrashHandler` | app | DEBUG-Wrapper um ACRAs Handler: Log, OOM-GC, Flush, deterministischer Kill (C2) | RELEASE-Versand (= ACRA) |
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
- **Bootstrap-Read im Sender-Prozess ausführen** — doppelt, nicht
  multi-prozess-sicher. *(X2)*
- **Handler zum Rethrow bringen, um einen ANR/Stall zu „recovern"** —
  unterläuft den Crash-Infra-Swallow. *(§4.5, §6)*
- **`silentError` in Crash-Infra-Dateien** — rekursiert ins Safety-Net. *(§5.3,
  Rule 9)*
- **Den doppelten R1/R2-Read „vereinheitlichen", indem R1 entfällt** — R1 muss
  vor Hilt laufen; ein File, zwei Leser, per Reaffirm rekonziliert ist korrekt.
- **`Thread.sleep(N)` als Flush-„Beweis"** — die Zahl ist geraten; ACRAs
  Completion-Hook ist der richtige Anker, ein bounded Sleep nur der dokumentierte
  Fallback. *(§5.1)*

---

## 11. Verwandte Docs

- `CLAUDE.md` — Rule 5 (Storage), 7 (Crash-Safety), 8 (opt-in), 9
  (Timber/silentError/silentDeath), 11 (Fehler-als-Wert).
- `KNOWN_ISSUES.md §3` — der bewusste StrictMode-DiskReadViolation von R1 (§3.5).
- `ACCEPTED_LIMITATIONS.md` — extrahierbare Basic-Auth-Credentials (§4.7),
  bewusst verlorene Soft-ANRs außerhalb des Watchdog-Fensters (§4.5/§6).
- `AUDIT-10.md` — crash-consent-layering-Review (Consent-Invarianten §2).
- `AUDIT-6.md` / `AUDIT-6_ADDENDUM.md` — Carrier-Exception statt CUSTOM_DATA.
