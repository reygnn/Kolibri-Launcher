# ACRA_FLOW.md

**Ziel-Architektur des Crash-Reporting-Subsystems — bester aktueller Kandidat, noch in Konvergenz.**

Dieses Dokument beschreibt den Crash-Reporting-Pfad so, wie er nach heutigem Stand
am saubersten aussähe: ohne Rücksicht auf heutige Klassennamen, Key-Namen,
Paketstruktur, Bestandsinstallationen oder darauf, was schon gebaut ist. Es gibt in
dieser Fassung **keinen** Ist-Zustand und **keine** Migration — aber sehr wohl noch
offene Fragen. Nichts hier ist beschlossen. Der Großteil ist gut begründet und steht
als starke Default-Empfehlung; die wenigen echten Gabelungen sind als solche
markiert und **nicht** vorentschieden. Ziel ist, jetzt genau hinzusehen, damit nicht
später wieder eine Audit-Runde „entdeckt", was hier vorschnell als erledigt galt.

Die **einzige** Fessel ist die Realität der APIs. Kein Baustein darf etwas
verlangen, was ACRA 5.13.1 oder die Android-Plattform nicht hergeben. Wo eine
API etwas *erzwingt*, steht das als Zwang da — nicht als „Kompromiss", den man
später wegverhandeln könnte. Wo die API mehrere Wege offen lässt, wird der
ideale gewählt und begründet. Jede library-abhängige Annahme ist in **§13** an
ein reales, gegen die Sources verifiziertes Symbol gebunden; diese Tabelle ist
der Vertrag zwischen „geile Lösung" und „die API gibt es her".

Referenzen auf `AUDIT-n #m` markieren, wo eine Regel aus einer real gemachten
Fehlererfahrung stammt — Provenienz der Lektion, nicht ihre Begründung. Die
Begründung steht daneben.

---

## 0. Leitprinzipien (die Haltung, aus der alles folgt)

Sieben Prinzipien prägen jede spätere Entscheidung. Sie sind vorwärtsgerichtet —
sie beschreiben, was gilt, nicht was sich gegenüber irgendetwas ändert.

1. **Illegale Zustände sind unrepräsentierbar.** Consent ist *ein* Sealed-Wert,
   nicht zwei Booleans mit vier Kombinationen (eine davon illegal). Was das
   Typsystem verbietet, kann kein Test vergessen. *(→ A8)*
2. **Fail-closed ist der Default an jeder Unsicherheit.** Der Reporter wird
   *deaktiviert konstruiert* und nur bei einem positiven, bekannten Consent
   eingeschaltet. Ungelesen, unlesbar, nie gefragt, widerrufen → AUS. *(→ A1)*
3. **Ein Fehler bleibt ein Fehler — er wird als Wert zurückgegeben, nie zu
   einem plausiblen Default kollabiert.** Ein unlesbarer Consent-Store ist
   `Unavailable`, kein „nie gefragt"; ein fehlgeschlagener Write ist `Failed`,
   kein stilles `Unit`. Ein Default, der von einer echten Antwort ununter­
   scheidbar ist, *handelt* — und überschreibt Entscheidungen. *(→ A2, A4)*
4. **Privacy hat genau ein Gate.** Ob berichtet werden darf, entscheidet
   ausschließlich das ACRA-`enabled`-Flag. Die Ingestion prüft Consent nie
   selbst. Ein Ort, an dem Privacy durchgesetzt wird, ist ein Ort, an dem sie
   falsch sein kann. Dass das Gate am *Capture* sitzt (`enabled`), nicht am
   *Send*, ist zugleich eine Data-at-rest-Garantie: bei `enabled=false`
   schreibt ACRA **gar keine** Report-Datei — verifiziert für beide Pfade
   (`ErrorReporterImpl.uncaughtException` bailt vor `ReportBuilder.build`,
   `ReportExecutor.execute` bailt vor `saveCrashReportFile`; §13). Ein
   Nicht-Zustimmender hinterlässt also nichts auf Platte. Genau das koppelt
   dieses Prinzip an G4 (§3.5): würde das Gate an den Send wandern, wäre der
   Sync-Read entbehrlich — aber der Preis wären lokale Crash-Dateien für
   Nicht-Zustimmende. *(→ B1, §3.5, G4)*
5. **Der Reporter wird nie selbst zur Crash-Quelle.** Jeder Pfad innerhalb der
   Crash-Maschinerie schluckt seinen eigenen Fehler; Recovery hat immer Vorrang
   vor Berichterstattung. *(→ C1)*
6. **Nichts Unsichtbares.** „Reporter seit Monaten tot" und „keine Crashes"
   dürfen nicht ununterscheidbar sein; ein vom System gekillter Looper-Stall
   darf nicht spurlos verschwinden. Jede Lücke in der Sichtbarkeit ist ein Bug.
   *(→ C3, C4)*
7. **Jede Invariante trägt eine Durchsetzung.** Test, Linter oder Review — und
   bei Test: *welche Datei rot werden muss*. Eine Invariante ohne benannte
   Durchsetzung ist eine Bitte, kein Gesetz.

Über die sieben Prinzipien hinaus gibt es vier Stellen, an denen die APIs mehrere
Wege offen lassen und die Wahl echte Substanz hat. Hier tendieren wir aktuell — aber
diese vier sind **nicht** beschlossen; sie sind die offenen Gabelungen, die der
nächste Abschnitt mit beiden Seiten aufmacht:

- **Uncaught-Pfad:** aktuelle Tendenz zu *einem* Handler in RELEASE *und* DEBUG
  statt eines DEBUG-only-Wrappers. *(→ §5.1, G1)*
- **Watchdog:** aktuelle Tendenz zu Kill *und* Capture statt nur einem von beidem.
  *(→ §6, G2)*
- **Pipeline-Lebendigkeit:** aktuelle Tendenz zu einem eigenen Send-Marker per Datei
  mit atomarem Rename. Ob die Beobachtbarkeit den Cross-Prozess-Aufwand wert ist,
  bleibt offen. *(→ C4, X4, G3)*
- **Bootstrap-Consent-Read:** aktuelle Tendenz zu synchron auf dem Main-Thread — die
  teuerste und strittigste der vier. *(→ §3.5, G4)*

---

## Offene Gabelungen (noch nicht konvergiert)

Vier Stellen sind **nicht** entschieden — hier lassen die APIs mehrere Wege offen
und die Wahl hat Substanz. Der Rest des Dokuments beschreibt jeweils die aktuell
präferierte Variante als Default; hier stehen beide Seiten, damit die Gabelung
sichtbar bleibt und nicht später als „übersehen" wieder aufgemacht werden muss. Jede
trägt ein Kriterium, das sie schließen würde.

### G1 — Uncaught-Pfad: ein Handler vs. DEBUG-only-Wrapper  *(§5.1)*

- **Tendenz:** ein einziger, in RELEASE und DEBUG identischer `UncaughtCrashHandler`.
- **Dafür:** der Test deckt den Auslieferungsstand; OOM-GC und Log helfen beiden
  Builds; keine selbstgemachte Build-Divergenz.
- **Dagegen / Alternative:** minimal weniger Code im RELEASE-Pfad bei einem
  DEBUG-only-Wrapper — aber die Divergenz wäre selbstgemacht, nicht
  Plattform-erzwungen.
- **Reststreit:** gering. Diese Gabelung ist am ehesten reif zum Schließen.
- **Schließt sich, wenn:** feststeht, dass kein RELEASE-Verhalten gebraucht wird, das
  der DEBUG-Pfad nicht auch will — aktuell der Fall.

### G2 — Watchdog: Kill **und** Capture vs. nur eines  *(§6)*

- **Tendenz:** killen *und* vorher capturen.
- **Dafür (Kill):** die App ist der HOME-Launcher; ein toter Main-Thread heißt toter
  Home-Button des ganzen Geräts. Kill → OS startet HOME sauber neu.
- **Dagegen / Alternative:** Selbst-Kill ist aggressiv und trippt auf langsamen
  Geräten evtl. bei legitimer (endlicher) Single-Message-Arbeit false. Die
  konservativere Haltung „nie selbst killen, nur berichten" (Capture ohne Kill) ist
  vertretbar — für viele die sauberere Grenze.
- **Reststreit:** echt. Das ist eine Wertentscheidung, keine API-Wand.
- **Schließt sich, wenn:** die real gemessene Trip-Rate auf dem langsamsten
  Zielgerät bekannt ist. Häufige False-Positives → Schwelle hoch oder Kill raus;
  keine → Kill bleibt.

### G3 — Pipeline-Lebendigkeit: eigener Send-Marker vs. gar keine Observability  *(C4/X4)*

- **Tendenz:** eigener `ReportSender`-Dekorator, der bei HTTP-Erfolg cross-prozess
  einen Timestamp in eine Datei (atomarer Rename) stampt.
- **Dafür:** Prinzip 6 — ein seit Monaten toter Sender darf nicht von „keine
  Crashes" ununterscheidbar sein.
- **Dagegen / Alternative:** ein eigener Sender plus die *einzige* zugelassene
  Cross-Prozess-Ablage, nur für einen Dev-Screen. Für einen Solo-Launcher am
  ehesten Gold-Plating. Alternative: gar keine Liveness-Anzeige (ein
  In-Process-Enqueue-Marker löst X4 gerade **nicht**, also entweder richtig oder gar
  nicht).
- **Billigeres Komplement (source-geprüft): Rückstau zählen statt Send stampen.**
  Ein toter Sender äußert sich als liegenbleibende Report-Dateien. Die sind aus
  dem Hauptprozess ohne eigene Ablage lesbar: `ReportLocator` ist **public API**
  (`ReportLocator.kt:27–35`, `approvedReports`/`unapprovedReports: Array<File>`),
  und ein Server-Ausfall kommt als `ReportSenderException` durch, die die Datei
  **nicht** löscht (`ReportDistributor.kt:77–82`) → der Ordner wächst. „N approved
  Reports, ältester vor X" = Sender tot; leer = gesund *oder* nie gecrasht.
- **Warum es den Marker trotzdem nicht ersetzt:** File-Count gibt **keine
  positive HTTP-Bestätigung**. Leere Ordner können auch „Server-URL falsch, nie
  gecrasht" heißen — das siehst du nie. Nur ein echter Send-Erfolg (HTTP 200)
  bestätigt den Pfad Ende-zu-Ende; das sieht nur der `:acra`-Prozess. File-Count
  ist also ein billiger Dead-Server-**Rückstau**-Detektor, kein Liveness-Beweis.
- **Test-Gotcha (source-geprüft):** in einem **debuggable Build** sendet ACRA
   per Default gar nicht und löscht die Datei dennoch
  (`ReportDistributor.kt:96,118–120,66`) — außer `sendReportsInDevMode=true`.
  Der Rückstau-Zähler wie der Marker sind im Debug-Build also blind; siehe §8
  (Throw-Test).
- **Reststreit:** „nice-to-have vs. Aufwand". Der `ReportLocator`-Zähler senkt
  den Aufwand-Arm (kein zweiter Sender, keine Cross-Prozess-Ablage), zum Preis
  der positiven HTTP-Bestätigung.
- **Schließt sich, wenn:** ein realer „ist der Reporter überhaupt noch am Leben?"-
  Zweifel auftritt. Bis dahin kann der Marker warten; der `ReportLocator`-Zähler
  wäre die Nullaufwand-Zwischenstufe.

### G4 — Bootstrap-Consent-Read: synchron auf dem Main-Thread vs. async  *(§3.5)*

- **Tendenz:** synchron (`runBlocking`) auf dem Main-Thread, vor `onCreate`.
- **Dafür:** No-Window-Garantie — die frühen Crashes eines zustimmenden Nutzers sind
  ab dem ersten Moment abgedeckt.
- **Dagegen / Alternative:** ein `runBlocking`-Disk-Read bei *jedem* Cold-Start plus
  eine StrictMode-Violation. Async lesen und ACRA erst nach bestätigtem Consent
  einschalten verliert nur das schmale Fenster, bis der Read fertig ist — für einen
  **opt-in**-privacy-by-default-Reporter evtl. akzeptabel.
- **Aber diese Gabelung ist nicht unabhängig von Prinzip 4.** Async-lesen ohne
  Coverage-Fenster ginge *nur*, wenn das Gate von Capture auf Send wanderte
  (ACRA immer `enabled`, ein eigener `ReportSender` verwirft bei fehlendem
  Consent zur Sendezeit). Der Preis dafür ist source-belegt: bei `enabled=false`
  schreibt ACRA keine Datei (§13, Prinzip 4), bei `enabled=true` **schon** —
  Capture-always hieße lokale Crash-Dateien für Nicht-Zustimmende. Für
  privacy-by-default ist das der teurere Weg. Damit gewinnt das Capture-Gate,
  und mit ihm der Sync-Read — nicht wegen No-Window-Coverage (das war die
  schwächere Begründung), sondern wegen Data-at-rest.
- **Reststreit:** dadurch kleiner als zuvor angenommen — die Kopplung an
  Prinzip 4 entscheidet die Grundsatzfrage; offen bleibt nur noch das *Wie*
  (Read-Dauer, StrictMode-Buchung).
- **Schließt sich, wenn:** die reale Bootstrap-Read-Dauer gemessen ist. Sie
  entscheidet nicht mehr sync-vs-async (das ist über Data-at-rest gefallen),
  sondern nur noch, wie laut die StrictMode-Violation in `KNOWN_ISSUES.md`
  gebucht wird.

---

## 1. Die drei Belange

Crash-Reporting berührt drei **voneinander unabhängige** Belange, die sich nur
die Library teilen. Ihre saubere Trennung ist der Kern der Architektur — und ihre
**Wechselwirkungen** (§7) sind der Teil, den die Trennung sonst blind macht.

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
   │     Quellen → Carrier → ACRA → HTTPS (out-of-proc) · Server-Dedup+Limit│
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
Privacy durchgesetzt wird (Prinzip 4).

---

## 2. Die Invarianten (die Gesetze)

Jede Transition weiter unten ist aus diesen abgeleitet. Verletzt eine Änderung
eine davon, ist sie falsch — egal wie plausibel sie aussieht. Die Spalte
**Gepinnt durch** ist Teil der Invariante (Prinzip 7).

### Consent (A)

| # | Invariante | Gepinnt durch |
|---|---|---|
| **A1** | **Fail-closed beim Einschalten.** ACRA wird deaktiviert *konstruiert* und nur bei einem positiven, bekannten Read (`Granted`) eingeschaltet. Jede Unsicherheit (ungelesen, unlesbar, `NeverAsked`, `Denied`) lässt es AUS. | Bootstrap-Test + Controller-Test (`resolveStartupAction`); §3.5 fixiert die Init-*Reihenfolge* als Teil der Invariante |
| **A2** | **Kein lügender Read.** `Unavailable` bleibt unterscheidbar von `Denied`/`NeverAsked`. Ein Read-Fehler treibt nie einen Write und öffnet nie den Dialog. Ein *vorhandener, unbekannter* Token (weder `GRANTED`/`DENIED`) ist `Unavailable`, **nicht** `NeverAsked` (§3.1, SR5). *(AUDIT-10 #2)* | `ConsentRepositoryImplTest` (Failure-Branch **inkl. Unknown-Token → `Unavailable`**, impl-only); Controller-Test (`Unavailable → Skip`) |
| **A3** | **Schreiben nur bei echter Nutzerentscheidung.** Kein erschienener Dialog, kein Nicht-Activity-Kontext, kein Config-Change vor dem Tap persistiert. Nur ein echter Button-Tap schreibt. *(AUDIT-10 #6/#8)* | `ConsentDialog`-Test (Robolectric): `onResult` feuert nur bei Tap, sonst `null` |
| **A4** | **Fehlgeschlagener Write ist sichtbar und verliert sauber.** Nichts wird geschrieben, die Session ehrt den Tap in-memory, der Nutzer wird per Toast informiert. *(AUDIT-10 #11)* | Controller-Test (`Failed → Notifier aufgerufen`) |
| **A5** | **Consent reist nicht und aufersteht nicht.** Eigener DataStore-File, aus Backup + Transfer ausgeschlossen, nicht Purgeable. Frische Installation → neu fragen. | `backup_rules.xml`/`data_extraction_rules.xml`-Assertion; Manifest-Review |
| **A6** | **Cancellation ist kein Fehler.** `CancellationException` propagiert überall und wird nie in `Unavailable`/`Failed` gefaltet. | Contract-Test (fake + impl), beide Read/Write-Pfade |
| **A7** | **Widerruf ist destruktiv.** Ein Wechsel auf `Denied` löscht die ungesendete ACRA-Report-Warteschlange (`BulkReportDeleter`, **beide** Ordner, §13), damit kein Report aus der Consent-Phase nach einem späteren Re-Consent abfließt. | Controller-Test (`applyConsent(false)` ruft Queue-Purge); `AcraToggle`-Purge-Test (beide Ordner) |
| **A8** | **Illegale Zustände unrepräsentierbar.** Consent ist genau ein `ConsentDecision`-Sealed-Wert; es gibt kein Feldpaar, das inkonsistent werden kann. | Typsystem (kompiliert nicht anders); Model-Test |

### Ingestion (B)

| # | Invariante | Gepinnt durch |
|---|---|---|
| **B1** | **Ein einziges Consent-Gate.** B prüft nie selbst Consent; das ACRA-`enabled`-Flag ist die alleinige Durchsetzung. Kein zweiter Check, kein Umgehen von `handleSilentException` mit eigener Sende-Logik. | Linter: kein Consent-Read außerhalb Belang A; `checkConventions` |
| **B2** | **Ein Zustellweg, kein Doppelversand.** Jede geloggte Quelle läuft durch genau einen Pfad (`Timber.e` → `AcraTree` → `handleSilentException`). Nie zusätzlich direkt `handleException`. | Linter: kein `handleException(`/`handleSilentException(` außer in `AcraTree` |
| **B3** | **Kein Client-seitiges Throttling.** Der Client sendet jeden Consent-gateten Report; Flut-Kontrolle (Fingerprint-Dedup **und** Ingestion-Rate-Limit) ist ausschließlich server-seitig. ACRA pact den Versand nicht (verifiziert: `setOverrideDeadline(0)`, `MAX_SEND_REPORTS=5`, Job-Coalescing; §13). | Linter: kein client-seitiger Report-Throttle / `shouldSend` in `AcraTree`; Server-Config (Dedup + Edge-Rate-Limit) |
| **B4** | **Report-Kontext ist per-Report.** Priority/Tag/Message werden in eine frische Carrier-Exception gefaltet, nie in eine prozess-globale Mutable-Map (`putCustomData`). *(AUDIT-6 #4)* | Linter: kein `putCustomData(`; `ReportCarrier`-Test (pure/JVM) |
| **B5** | **Minimaler Report-Inhalt.** Nur die in §4.7 gelisteten Felder; kein Logcat-Dump, keine Geräte-ID, keine PII über Modell/Marke/OS hinaus. | `reportContent`-Assertion-Test gegen exakte Feldliste |

### Resilienz (C)

| # | Invariante | Gepinnt durch |
|---|---|---|
| **C1** | **Der Reporter crasht die App nie.** Jeder Pfad *innerhalb* der ACRA-/ANR-/Watchdog-Maschinerie schluckt seinen eigenen Fehler. *(Rule 7)* | Linter Rule 9 (Crash-Infra-Whitelist); Review |
| **C2** | **Nach Uncaught-Crash terminiert der Prozess deterministisch.** Kein Zombie. Den Kill besorgt ACRAs eigener `ProcessFinisher` (`exitProcess(10)`) in RELEASE **und** DEBUG (§13); **kein** Flush-Fenster (Versand ist out-of-process, §5.1). Unser Kill ist reiner Backstop. Lügende Zustände → `silentDeath`. | Uncaught-Handler-Test (Backstop-Kill-Pfad erreicht); `silentDeath`-Test |
| **C3** | **Der Watchdog verblindet den ANR-Reporter nicht.** Ein Watchdog-Kill erzeugt kein `REASON_ANR`; darum captured der Watchdog **vor** dem Kill selbst einen Report. Kein stiller Verlust von Looper-Stalls. *(→ §7 X1)* | `RecoveryWatchdog`-Test: Capture-Callback vor `killSwitch` |
| **C4** | **Pipeline-Lebendigkeit ist beobachtbar.** Ein toter Reporter ist von „keine Crashes" unterscheidbar. Der dafür nötige Marker ist ein Last-Successful-**Send**-Marker, cross-prozess vom `:acra`-Sender bei HTTP-Erfolg gestampt (§13, X4), **nicht** von `AcraTree` (das sähe nur das Enqueue). Im Dev-Screen sichtbar. | `AcraHttpSender`-Test: HTTP-Erfolg stampt Marker; Dev-Screen-Test |

---

## 3. Belang A — Consent

### 3.1 Das mentale Modell

Genau **ein Fakt**, als Sealed-Wert — plus sein volatiler Spiegel:

| | Fakt | Typ | Speicher | Default |
|---|---|---|---|---|
| **Persistent** | `ConsentDecision` | `NeverAsked \| Granted \| Denied` | `acra_consent` DataStore, backup-excluded, **nicht** Purgeable | `NeverAsked` |
| **Volatil** | ACRA `errorReporter.enabled` | `Boolean` | Prozess-RAM | `false` (aus) |

Der persistente Fakt ist die einzige Quelle der Wahrheit; der volatile spiegelt
ihn, im Zweifel **aus** (Prinzip 2). Es gibt kein zweites Feld — `NeverAsked`
*ist* der Zustand „nie gefragt", nicht ein separates `hasAsked=false`. Das
eliminiert den illegalen Zustand (`asked=false, consent=true`) by construction
(A8).

**Serialisierung (festgelegt, kein „Boolean").** `ConsentDecision` ist tristate,
ein `Boolean` kann ihn nicht tragen. Persistiert wird **ein** String-Preference-
Key `consent_decision` mit den Werten `"GRANTED"` / `"DENIED"`; **fehlt** der Key,
ist die Entscheidung `NeverAsked`. Abwesenheit *ist* der Default — kein Sentinel,
kein null-Boolean, keine vierte Kodierung. Damit ist der Persistenz-Rand ebenso
tristate wie das Modell (A8 reicht bis auf die Platte).

**Ein *vorhandener, aber unbekannter* Wert ist kein `NeverAsked`.** Liest der
Store einen String, der weder `"GRANTED"` noch `"DENIED"` ist (verstümmelter
Write, manipuliert, Token einer künftigen Version), wäre „als `NeverAsked`
behandeln" ein lügender Read: wir *haben* einen Datensatz, können ihn nur nicht
deuten — genau der A2-Fall. Deshalb → `Unavailable` (R2 → `Skip`, kein Dialog,
kein Write) bzw. bei R1 → nicht-`Granted` → ACRA bleibt AUS (fail-closed). Das
heilt nicht von selbst (jeder Start liest denselben Müll → ACRA bleibt aus, wird
nie neu gefragt), aber der Settings-Toggle (W2) überschreibt den Müll mit einem
sauberen Token — die Recovery ist da, nur nicht automatisch. Echte Datei-
Corruption wirft ohnehin `IOException` → derselbe `Unavailable`-Pfad (SR2/SR5).
*(A2, Prinzip 2)*

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

**Widerruf (Settings → aus):** siehe §6, §8 und **A7** (Queue-Purge).

### 3.3 Reaffirm = Rekonziliation

**Wozu Reaffirm, wenn R1 ACRA schon gesetzt hat?** Reaffirm heilt den Fall, dass
R1 *fehlschlug* (→ ACRA aus), R2 aber erfolgreich `Granted` liest — dann hebt es
ACRA auf den gespeicherten Stand. Normalfall: idempotenter No-Op. Reaffirm
persistiert nie (die Entscheidung steht schon im Store). Der doppelte R1/R2-Read
ist kein Kompromiss, sondern die einzige API-mögliche Form: R1 muss vor Hilt
laufen (attachBaseContext), R2 hat den injizierten Store — ein File, zwei Leser,
per Reaffirm rekonziliert.

### 3.4 Consent-Fehler (Sad)

| # | Fehler | Verhalten | Invariante |
|---|---|---|---|
| SR1 | R1 Bootstrap-Read wirft | → als `NeverAsked` behandelt → ACRA bleibt AUS; von R2/Reaffirm rekonziliert | A1 |
| SR2 | R2 `readState()` wirft | → `Unavailable` → `Skip` (nichts tun); ACRA behält Bootstrap-Wert, kein Dialog | A2 |
| SR3 | R1 **und** R2 fehlgeschlagen | ACRA AUS, kein Dialog; nächster Start liest neu (heilt selbst) | A1+A2 |
| SR4 | Display-Getter wirft | → `NeverAsked`/`false` (nur Anzeige, kein Write folgt) | — |
| SR5 | Store liest unbekannten Token (weder `GRANTED`/`DENIED` noch abwesend) | → `Unavailable` → `Skip` (R2) bzw. AUS (R1), **nie** `NeverAsked`; Recovery via Settings-Toggle | A2 |
| SW1 | W1/W2 Write wirft | `Failed`, nichts persistiert, Session ehrt Tap, **Toast** | A4 |
| SW2 | Write gecancelt | `CancellationException` propagiert; Persist läuft auf `ApplicationScope`, UI-Teardown cancelt ihn nicht | A6 |
| SD1 | Nicht-Activity-Kontext | `null`, `onResult` feuert nicht, nichts persistiert | A3 |
| SD2 | `show()` wirft (BadToken auf finishing Activity) | `null`, kein Persist | A3 |
| SD3 | Dialog sichtbar, Activity vor Tap zerstört | in `currentDialog` getrackt & dismissed; bleibt `NeverAsked` → nächster Start fragt erneut | A3 |
| SD4 | Config-Change bei offenem Dialog | dismissed on destroy, Gate läuft auf Recreate erneut → wieder `ShowDialog` | A3 |
| SP1 | Revoke: Queue-Purge wirft | `silentError` geschluckt; ACRA ist trotzdem aus, kein Report kann mehr *entstehen* — Restrisiko: Altbestand bleibt liegen (nächster Revoke räumt) | A7, C1 |
| RC1 | R1 AUS (Fehler), R2 liest `Granted` | `Reaffirm(true)` → ACRA an — **der Grund für Reaffirm** | A1 |
| RC2 | R2 liest `Denied` (Folgestart) | `Reaffirm(false)` → ACRA bleibt AUS, idempotent, **kein** Purge (nichts war an). „R1=AN, R2=`Denied`" ist **nicht erreichbar** — zwischen R1 und R2 läuft kein Write (Settings ist vor dem Gate unerreichbar), also ist die einzige reale R1≠R2-Divergenz die von RC1. Destruktiver Widerruf inkl. Queue-Purge nur via Settings-`applyConsent(false)` (A7). | A1 |

### 3.5 Der Bootstrap-Read: synchron, DataStore, Haupt-Prozess (Tendenz, Gabelung G4)

Ein Crash-Reporter existiert, um die *frühen* Crashes zu fangen — also sollte der
ACRA-Zustand korrekt sein, **bevor der erste Crash möglich ist**, d.h. vor
`onCreate`. Dieser Anspruch macht den Rest zu einem Binär: entweder synchron auf dem
Main-Thread lesen, oder ein (schmales) Coverage-Fenster für zustimmende Nutzer in
Kauf nehmen. Aktuell **tendieren wir zum synchronen Read** — die Gegenseite (async,
Fenster akzeptieren) steht in G4:

```
val decision = runBlocking { consentDataStore.readDecision() }   # ein kleiner Read
ACRA.errorReporter.setEnabled(decision == Granted)
```

- **Warum das *inhärent* ist, kein Hack:** die Entscheidung ist *persistiert* und
  muss vor `onCreate` von Platte gelesen werden. Ein persistierter Fakt vor dem
  ersten möglichen Crash bedeutet einen synchronen Disk-Read — *unabhängig* vom
  Speicher. Ein SharedPreferences-Mirror eliminiert den Main-Thread-Read nicht
  (`getBoolean` liest beim First-Load ebenfalls von Platte), brächte aber einen
  zweiten Speicher + Write-Through + Drift-Fläche. Die einzige Alternative wäre
  das Coverage-Fenster — genau das, was ein Crash-Reporter nicht haben darf.
- **Der eigentliche Grund für sync ist Data-at-rest, nicht Coverage (Kopplung
  an Prinzip 4).** Das No-Window-Argument allein wäre für einen opt-in-Reporter
  angreifbar (async verlöre nur ein schmales Fenster, G4). Es *sync* zu machen,
  ist erst dann zwingend, wenn das Privacy-Gate am *Capture* sitzt — und das
  tut es hier, source-belegt: bei `enabled=false` schreibt ACRA **keine**
  Report-Datei. Der Uncaught-Handler bailt vor dem Bauen
  (`ErrorReporterImpl.uncaughtException`: `if (!isEnabled) { handToDefault; return }`,
  `ErrorReporterImpl.kt:89–94`), und der Silent-Pfad bailt vor dem Speichern
  (`ReportExecutor.execute`: `if (!isEnabled) { warn; return }` **vor**
  `saveCrashReportFile`, `ReportExecutor.kt:76–79`). Ergo: `disabled` = null
  Crash-Daten auf Platte für Nicht-Zustimmende. Die einzige Bauform, die den
  Sync-Read spart (Gate am Send, `enabled` immer an, Consent-Check im eigenen
  `ReportSender`), erkauft das mit genau solchen lokalen Dateien — für
  privacy-by-default der falsche Tausch. Deshalb Capture-Gate + Sync-Read.
  *(§13, Prinzip 4)*
- **DataStore bleibt einzige Quelle.** Der Bootstrap-Read läuft auf dem
  *Main-Thread an einem kontrollierten Punkt* (vor `onCreate`), wo `runBlocking`
  auf einem einzelnen Read vertretbar ist — anders als ein Aufruf mitten aus dem
  Nicht-Coroutine-Crash-Handler-Thread, wo suspend-only-DataStore nicht ginge.
  Ein solcher Fall existiert hier nicht mehr (der frühere `ReportThrottle`, die
  einzige `SharedPreferences`-Ausnahme, ist entfallen, §4.3) — Consent ist der
  einzige Bootstrap-Read, und er bleibt DataStore.
- **Kosten (ehrlich benannt):** eine StrictMode-DiskReadViolation in DEBUG — ein
  einzelner kleiner Read an einem kontrollierten Init-Punkt, kein Jank. Das ist
  die tragende Kostenseite der No-Window-Garantie und gehört so in die KDoc,
  nicht als „Rauschen, das wir dulden".
- **Reihenfolge ist die Invariante (A1):** ACRA wird deaktiviert *konstruiert*.
  Kennt die Library nur „init = an", ist die *erste* Anweisung nach `init`
  `setEnabled(false)`, und erst der geprüfte `Granted`-Read schaltet ein.
  Zwischen `init` und `setEnabled(false)` darf keine Anweisung stehen, die
  crashen könnte — sonst existiert das Mikro-Fenster mit aktivem Reporter bei
  unbekanntem Consent, das A1 verbietet.
- **Prozess-Gate (X2):** der gesamte Block (Read + `setEnabled`) läuft nur, wenn
  `!ACRA.isACRASenderServiceProcess()` (verifizierte API, §13). Im `:acra`-Prozess
  ist `errorReporter` ohnehin ein Stub (`setEnabled` = No-Op) — der Read entfiele
  dort ersatzlos, weil dort nichts zu entscheiden ist. `ACRA.init` selbst bleibt
  in **jedem** Prozess (es konfiguriert dort korrekt den Sender); nur unser
  Consent-Read + Toggle wird gegatet.

---

## 4. Belang B — Ingestion (die Report-Pipeline)

### 4.1 Vier Quellen, zwei ACRA-Eingänge

| Quelle | Was | ACRA-Eingang |
|---|---|---|
| **Uncaught-Crash** | echter, nicht gefangener `Throwable` | ACRAs **eigener** installierter UncaughtExceptionHandler (Auto-Report, voller `reportContent`) |
| **Geloggter Fehler** | gefangen, aber via `Timber.e/w(t)` (WARN+) geloggt | `AcraTree` → `handleSilentException(carrier)` |
| **Post-mortem ANR** | `ApplicationExitInfo` REASON_ANR | `Timber.e(AnrException)` → `AcraTree` → `handleSilentException` |
| **Watchdog-Stall** | Main-Looper > Schwelle stumm (§6) | `Timber.e(WatchdogStallException)` → `AcraTree`, *vor* dem Kill |

Kernaussage: **der Client drosselt nichts.** Jede Quelle sendet (Consent-gated);
Flut-Kontrolle — Dedup identischer Reports *und* Ingestion-Rate-Limit — ist
ausschließlich server-seitig (§4.3, B3). Ein früherer Client-`ReportThrottle`
ist bewusst entfallen: er deckte nur den Silent-Error-Strom, nie den
Crash-Loop (Uncaught umgeht `AcraTree`), und war die einzige
`SharedPreferences`-Ausnahme.

Die vierte Quelle schließt das Loch, dass Looper-Stalls unterhalb der
System-ANR-Schwelle (§7 X1) sonst unsichtbar wären (Prinzip 6).

### 4.2 Der eine Zustellweg (geloggte Fehler, ANRs, Stalls)

```
Timber.e(throwable, msg)                              # der EINZIGE Weg (B2)
   └─ AcraTree.log(priority, tag, msg, t):
        1. Gate:      priority < WARN || t == null  → return (nichts)
        2. Carrier:   buildAcraReportThrowable(priority, tag, msg, t)   # per-Report (B4)
        3. Zustellen: ACRA.errorReporter.handleSilentException(carrier)
                        └─ persistiert + plant Out-of-process-Versand (:acra, §13); nur wirksam,
                           wenn ACRA enabled — das Consent-Gate (B1). Sende-Erfolg ist HIER nicht sichtbar.
        (alles ab Schritt 2 in try/catch(Throwable) → geschluckt, C1)
        Kein Client-Throttle-Schritt: der Client sendet jeden Report, Flut-Kontrolle
        ist server-seitig (§4.3). Kein Send-Marker in diesem Pfad: Schritt 3 enqueued nur.
```

Nie zusätzlich `handleException` direkt aufrufen — Doppelversand. *(B2)*

### 4.3 Flut-Kontrolle — server-seitig, nicht im Client

**Es gibt keinen Client-Throttle.** Der Client sendet jeden Consent-gateten
Report; Dedup *und* Rate-Limit sitzen auf dem (privaten, selbstgehosteten)
Server. Zwei Fluten, ein Ort:

- **Silent-Error-Strom** — ein geloggter Fehler, der sich in einer Session
  wiederholt.
- **Crash-Loop** — Startup-Crash, den das OS als HOME-Prozess sofort neu
  startet, wieder crasht … im Crash-Takt.

**Warum server-seitig (und warum ACRA das *nicht* für uns puffert):** gegen die
5.13.1-Sources verifiziert (§13) sendet ACRA **ohne Intervall** —
`DefaultSenderScheduler` „schedules sending instantly" (`setOverrideDeadline(0)`).
Die einzigen Dämpfer sind **Job-Coalescing** (fixe Job-ID `0` → ein Burst
*innerhalb eines App-Lebens* kollabiert zu wenigen Läufen) und **`MAX_SEND_REPORTS
= 5`** pro Sende-Pass. Keiner davon pact einen **Crash-Loop** (jeder Crash = neuer
Prozess = neuer Sofort-Job) noch unterdrückt er einen Silent-Burst vollständig.
Ein Client-`ReportThrottle` wiederum sitzt nur in `AcraTree` und sähe den
Crash-Loop (Uncaught) nie. Also gehört die Kontrolle an die *eine* Stelle, die
beide Fluten sieht:

1. **Dedup am Storage** — identische Fingerprints (normalisierter `STACK_TRACE`,
   den der Client in `reportContent` ohnehin sendet, B5) zu **einem** Eintrag mit
   Zähler kollabieren, statt N Tickets zu öffnen. Löst die *Ticket-Hygiene*.
2. **Rate-Limit an der Ingestion-Kante** — Body früh hashen und gleiche
   Fingerprints pro Zeitfenster ablehnen, **bevor** voll geparst wird. Löst die
   *Last* (Dedup allein schützt nur den Storage, nicht den HTTP-/Parse-Pfad).

**Kosten (ehrlich):** ein Crash-Loop kostet Geräte-Akku/-Daten und Ingestion-CPU
im Crash-Takt — der Preis dafür, dass der Client fail-safe bleibt (nie einen
echten Crash droppt) und keine `SharedPreferences`-Ausnahme mehr trägt. Ein
per-Frame feuernder Silent-Error ist selbst ein Bug, den man *einmal* sehen und
fixen will, nicht client-seitig maskieren.

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
System-`ApplicationExitInfo` auslesen (API 30+; wir sind auf 36 — §13):
```
onCreate → reportPendingAnrsAsync (ApplicationScope):
   newAnrsSinceLastReport(): getHistoricalProcessExitReasons filtern
                             (REASON_ANR, timestamp > Watermark), chronologisch,
                             je → AnrReport(desc, importance, getTraceInputStream)
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
- **Best-effort:** der Handler **schluckt** einen Fehler des
  `handleSilentException`-**Enqueues** (C1) — *kein* Sendefehler, der Versand ist
  out-of-process und hier gar nicht sichtbar (§4.2). Also advanced die Watermark
  auch, wenn der Report nicht einmal persistiert wird → dieser ANR fällt weg.
  Akzeptiert (ist ACRA kaputt, gibt es eh nichts zu retten). Handler **nicht**
  zum Rethrow bringen, um zu „recovern" — das unterliefe den Crash-Infra-Swallow.

### 4.6 ANR- & Stall-Fehler (Sad)

| # | Fehler | Verhalten | Invariante |
|---|---|---|---|
| AN1 | AEI-Read wirft | `silentError` → `emptyList`; Watermark unverändert; nächster Start liest neu | C1 |
| AN2 | Trace-Stream null / Read wirft | Report **ohne** Dump statt ANR fallen zu lassen | C1 |
| AN3 | Watermark-Write wirft | `silentError`, Watermark nicht advanced → ANR ggf. nächsten Start erneut (Duplikat akzeptiert) | C1 |
| AN4 | Handler schluckt `handleSilentException`-**Enqueue**-Fehler (nicht Send — der ist out-of-process, §4.2) | Watermark advanced trotzdem, ANR gedroppt (best-effort) | C1 |
| ST1 | Stack-Capture im Watchdog wirft | geschluckt; Kill läuft trotzdem — Recovery hat Vorrang vor Report | C1, C3 |

### 4.7 Report-Inhalt & Transport *(B5)*

- **Init in `attachBaseContext`:** `reportFormat = JSON`.
- **`httpSender`:** POST an `BuildConfig.ACRA_URL`, Basic-Auth
  (`ACRA_LOGIN`/`ACRA_PASSWORD`), `tlsProtocols = [TLS 1.2, TLS 1.3]`. Privater,
  selbstgehosteter Server — nie ein Drittanbieter.
- **`reportContent` (exakt diese, nicht mehr):** PACKAGE_NAME, ANDROID_VERSION,
  APP_VERSION_CODE, APP_VERSION_NAME, BRAND, PHONE_MODEL, STACK_TRACE. **Kein**
  CUSTOM_DATA (→ Carrier), kein Logcat, keine ID.

---

## 5. Belang C — Resilienz (Safety-Net)

### 5.1 Uncaught-Crash-Pfad — ein Pfad, beide Builds

**Was ACRA selbst tut (verifiziert, §13).** ACRAs installierter Handler
(`ErrorReporterImpl.uncaughtException`) sammelt den Report, **persistiert ihn als
Datei**, plant den Versand **out-of-process** (`JobSenderService`/
`LegacySenderService` im `:acra`-Prozess) und killt dann den Haupt-Prozess über
seinen `ProcessFinisher` (`Process.killProcess(myPid())`; `exitProcess(10)`) — in
RELEASE **und** DEBUG. Zwei Konsequenzen prägen den ganzen Pfad:

1. **Es gibt im sterbenden Prozess nichts zu flushen.** Der Report ist eine
   Datei, die den Kill überlebt; ein *anderer* Prozess sendet sie. Ein
   „Flush-Fenster" (Sleep *oder* „Completion-Hook abwarten") würde auf einen
   Vorgang warten, der absichtlich anderswo läuft — und `ErrorReporter` exponiert
   ohnehin **keinen** Completion-Callback (§13). Darum: **kein Flush-Fenster,
   keine Wartelogik.**
2. **Der deterministische Kill ist ACRAs, nicht unserer** (`exitProcess(10)`). C2
   ist erfüllt, ohne dass wir killen müssen.

**Aktuelle Tendenz (Gabelung G1): ein einziger Handler in beiden Builds.** Kein
DEBUG-only-Wrapper. Der `UncaughtCrashHandler` umschließt ACRAs Handler in RELEASE
*und* DEBUG identisch — er ergänzt nur zwei Dinge, die beide Builds *gleich* wollen,
und delegiert dann den kritischen Versand+Kill an ACRA:

```
handleUncaughtException(thread, t):
   if t is OutOfMemoryError: System.gc()     # Best-effort: Speicher freimachen,
                                             #   BEVOR ACRA einen Report allokiert
   Timber.e(t, ...)                          # Rule 9: plain Timber.e (Crash-Infra)
   defaultExceptionHandler.uncaughtException(thread, t)   # = ACRA: persist + schedule(:acra) + exitProcess(10)
   # ACRA killt hier bereits. Der folgende Kill ist reiner Backstop —
   # nur erreicht, falls ACRA NICHT killte (Administrator-Veto auf endApplication / Handler warf):
   Process.killProcess(Process.myPid()); exitProcess(10)    # deterministisch (C2)
```

- **Warum unified statt DEBUG-only:** die Divergenz „RELEASE nackter Handler,
  DEBUG Wrapper" wäre eine *selbstgemachte*, keine von der Plattform erzwungene.
  Ein einziger Pfad ist testbar (der Test deckt den Auslieferungsstand), und die
  zwei Zutaten helfen *beide* Builds: OOM-GC gibt ACRA im RELEASE-OOM-Fall die
  Chance, den Report überhaupt zu allokieren; das Log ist billig und schadet nie.
  Die einzige Divergenz, die *bleibt*, ist die, die die Plattform erzwingt
  (`BuildConfig.DEBUG`-abhängiges Verhalten gibt es hier bewusst **nicht**).
- **Reihenfolge-Invariante:** Der Wrapper muss **nach** `ACRA.init` installiert
  werden, damit sein `defaultExceptionHandler` ACRAs `ErrorReporterImpl` ist (den
  ACRA in `attachBaseContext` als Default-Handler registriert), nicht der
  Vor-ACRA-Handler. Wird er davor gesetzt, delegiert er am Reporter vorbei und
  **kein Report entsteht**.
- **Kein Throttling echter Crashes** (B3).

### 5.2 RecoveryWatchdog

Daemon-Thread (kein Coroutine — muss laufen, wenn der Main-Looper hängt), killt
den Prozess, wenn der Main-Looper > Schwelle (§6) nicht dispatcht. Start **nach**
`onCreate` via `Handler.post`, damit die schwere Cold-Start-Arbeit nicht selbst
einen Kill-Restart-Loop auf dem HOME-Prozess auslöst. Er ist zugleich
Report-Quelle (§6, §4.1).

### 5.3 Rule-9-Ausnahme (plain `Timber.e`)

Dateien *im* Crash-Pfad nutzen plain `Timber.e` statt `silentError`, weil ein
DEBUG-Throw dort in genau den Pfad rekursieren würde, der das Safety-Net *ist*:
App-Bootstrap, `TimberWrapper`, `Base{Activity,ViewModel}`,
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

## 6. Der Watchdog als Report-Quelle (Stall-Capture) — Tendenz (Gabelung G2)

Der Watchdog erfüllt zwei Aufgaben, die sich **nicht ausschließen** und die wir
aktuell zusammenführen (G2 hält die Alternative „nur berichten" offen): **schnelle
Recovery** (Kill, damit der
HOME-Prozess nicht sekundenlang tot wirkt) und **Sichtbarkeit** (der Stall darf
nicht spurlos verschwinden, Prinzip 6). Der Ablauf beim Trip:

```
Trip (Main-Looper hat timeoutMs nicht getickt):
   1. Capture:  mainStack = mainThread.stackTrace       # wir sind auf dem Daemon-Thread
   2. Enqueue:  Timber.e(WatchdogStallException(mainStack))  # → §4.2
                  → handleSilentException persistiert + plant Out-of-process-Versand (:acra);
                    kleiner File-Write auf dem DAEMON-Thread, nicht dem hängenden Main-Thread
   3. Kill:     killSwitch()   # Process.killProcess + exitProcess(10)
   (1–2 in try/catch(Throwable) geschluckt — Kill hat Vorrang, ST1)
   Kein Flush-Schritt: die persistierte Datei überlebt den Kill, :acra sendet sie (§5.1, §13)
```

**Tendenz: Kill *und* Capture, nicht nur eines** (die offene Alternative „nur
berichten" steht in G2). Die zwei Rollen sind hier bewusst nicht verschweißt,
sondern *begründet zusammengeführt*:

- **Warum überhaupt killen (nicht nur berichten)?** Diese App ist der
  HOME-Launcher. Ein wirklich blockierter Main-Thread heißt: der Home-Button des
  ganzen Geräts ist tot. Ein Kill lässt das OS den HOME-Prozess **sauber neu
  starten** — der Nutzer bekommt sein Gerät zurück. Für eine gewöhnliche App wäre
  „nur berichten" vertretbar; für den Launcher ist Recovery der Kernnutzen.
- **Warum Capture *vor* Kill?** Ein `Process.killProcess` erzeugt kein
  `REASON_ANR`, also fängt der `AnrReporter` (§4.5) diesen Stall nie. Ohne
  Schritt 1–2 wäre er unsichtbar.
- **Warum das trotz sofortigem Kill ankommt:** `handleSilentException`
  *persistiert* den Report und plant den Versand **out-of-process** (§13); die
  Datei überlebt `exitProcess(10)` und wird vom `:acra`-Prozess gesendet. Der
  einzige In-Process-Schritt ist ein kleiner File-Write — und der läuft auf dem
  **Daemon-Thread**, nicht auf dem hängenden Main-Thread. Die historische Sorge
  „I/O auf dem gerade hängenden Thread" ist damit sachlich ausgeräumt.

**`timeoutMs = 8 s` (Default, begründet).** Die System-Input-Dispatch-ANR-
Schwelle liegt bei ~5 s, die Broadcast-Foreground-Schwelle bei ~10 s. 8 s sitzt
dazwischen — lang genug, um bei schwerer (aber endlicher) Cold-Start-Arbeit nicht
falsch zu feuern (deshalb erst *nach* `onCreate` gestartet), kurz genug, um vor
der Broadcast-Schwelle zu recovern. Genau in diesem Fenster (kein pending Input →
System deklariert *keine* input-ANR) ist der Watchdog der einzige Weg, den Stall
überhaupt zu sehen (§7 X1).

**Was die Schwelle *nicht* deckt — und warum das akzeptiert ist.** Der Watchdog
trippt nur, wenn eine **einzelne** Looper-Message > 8 s blockiert; legitime
Arbeit, die zwischen Messages zurückgibt, pumpt den Looper und trippt nie. Eine
einzelne Main-Thread-Operation, die legitim > 8 s braucht, ist selbst ein Bug —
und wird auf dem langsamsten Zielgerät ggf. false-getrippt. Das ist die bewusst
akzeptierte Kante (§10, `ACCEPTED_LIMITATIONS.md`): der Preis dafür, dass ein
echter Wedge den HOME-Prozess nicht sekundenlang tot lässt — solange G2 zugunsten
des Kills ausfällt. Fällt G2 auf „nur berichten", verschwindet diese Kante.

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
systematisch unsichtbar**.

**Auflösung:** der Watchdog captured vor dem Kill selbst (§6, Quelle 4). C liefert
damit B seinen eigenen Report. Es gibt nichts zu „duplizieren" — `AnrReporter`
sieht diese Klasse Stalls per Konstruktion (`REASON_ANR`-Filter) nie. *(C3)*

### X2 — Bootstrap (A) läuft in jedem Prozess (C)

**Problem:** ACRA spawnt einen eigenen **Sender-Prozess** (`:acra`);
`attachBaseContext` und damit R1 laufen dort ebenfalls. Verifiziert (§13):
`ACRA.init` installiert im Sender-Prozess **selbst schon** keinen
`ErrorReporterImpl` — `errorReporter` bleibt ein Stub, ein `setEnabled` dort ist
ein **No-Op**. Der Toggle kann also **nicht** inkonsistent werden. Was *bleibt*:
ein naiver Bootstrap führt im `:acra`-Prozess den überflüssigen
`runBlocking`-DataStore-Read (R1) aus — Arbeit im falschen Prozess auf einem
Store, den DataStore Preferences ausdrücklich **nicht** multi-prozess-sicher hält.

**Auflösung:** der Bootstrap gated R1 + `setEnabled` mit
`ACRA.isACRASenderServiceProcess()` (public, `@JvmStatic`, §13): nur der
Haupt-Prozess liest Consent und toggelt. Der Nutzen ist ehrlich benannt —
**vermiedener Fehl-Prozess-Read**, nicht „verhinderte Inkonsistenz". *(A1, C1)*

### X3 — Consent-„aus" (A) vs. liegende Report-Dateien (B)

**Problem:** `setEnabled(false)` stoppt *neue* Zustellung, aber ACRA hält bereits
erzeugte, noch nicht gesendete Reports als Dateien vor. Nach einem Revoke und
späterem Re-Consent könnten Reports abfließen, die *während der Nicht-Consent-
Phase* entstanden — ein Privacy-Leck an der Naht A↔B.

**Auflösung:** Revoke ist destruktiv — `applyConsent(false)` löscht die
ungesendete ACRA-Queue über die offizielle API `BulkReportDeleter(context)
.deleteReports(approved, nrToKeep = 0)`, und zwar für **beide** Ordner
(`approved = true` **und** `false` — ACRA hält `ACRA-approved` *und*
`ACRA-unapproved`, §13; nur einer räumt die Hälfte). Kein Griff in private
Verzeichnisse. *(A7; Sad-Path SP1)*

**Rest-Kante (benannt, nicht offen):** Revoke-während-aktivem-Send-Race — der
`:acra`-Prozess sendet gerade, während der Haupt-Prozess löscht. Benigne: der
schlimmste Ausgang ist *ein* bereits in Zustellung befindlicher Report, der
durchgeht; alles noch nicht Gesendete ist weg. Kein Cross-Prozess-Lock nötig
(der Nutzen rechtfertigt die Komplexität nicht) — die Race ist dokumentiert und
akzeptiert.

### X4 — Toter Reporter (B/C) vs. „keine Crashes" (Beobachter)

**Problem:** C1 schluckt jeden Reporter-Fehler, AN4/ST1 droppen best-effort.
Korrekt für Stabilität — aber ein seit Monaten kaputter Sender ist von einem
gesunden, stillen System **ununterscheidbar**.

**Vorschlag (Mechanismus, Gabelung G3):** ein Last-Successful-**Send**-Marker (C4).
Nur die Beobachtung eines erfolgreichen *Sends* löst das Problem — ein
In-Process-„Enqueue"-Marker in `AcraTree` stünde bei „keine Crashes" und „toter
Sender" gleichermaßen still. Sende-Erfolg ist aber nur im `:acra`-Prozess
sichtbar (out-of-process, §13). Deshalb:

- Ein eigener `ReportSender` (Dekorator um `HttpSender`, via `ReportSenderFactory`
  registriert, §13) stampt bei HTTP-Erfolg einen Timestamp.
- **Ablage (vorgeschlagen):** eine **Datei mit atomarem Rename**
  (`File.renameTo` auf demselben Filesystem) unter `filesDir` — *nicht*
  SharedPreferences (nicht verlässlich multi-prozess). Der Sender schreibt im
  `:acra`-Prozess, der Haupt-Prozess liest für den Dev-Screen. Last-Write-Wins,
  reiner Telemetrie-Timestamp.

Das ist die **eine** bewusst zugelassene Cross-Prozess-Ablage — vertretbar, weil
es kein Entscheidungszustand ist (anders als Consent, der laut X2 gerade **nicht**
multi-prozess sein darf). Der Preis ist ehrlich benannt: ein zusätzlicher Sender
plus eine begründete Cross-Prozess-Ausnahme für ein Diagnosewerkzeug.

---

## 8. Settings-Oberfläche

Drei ACRA-Berührungen im `SettingsFragment`:

**a) Consent-Toggle** (Preference-Klick):
```
forceShowConsentDialog(activityContext) { userGaveConsent ->
   controller.applyConsent(userGaveConsent)      # ACRA-Toggle + W2-Persist + ggf. Queue-Purge via BulkReportDeleter (§3, A7, §13)
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
- **Pipeline-Status** → zeigt den Last-Successful-**Send**-Marker (C4): „letzter
  erfolgreich *gesendeter* Report vor X" bzw. „nie". Der Marker wird vom eigenen
  `ReportSender` (`AcraHttpSender`) im `:acra`-Prozess bei HTTP-Erfolg gestampt
  (§13, X4), **nicht** von `AcraTree`. Der Diagnose-Anker für X4.
- **Throw-Test-Exception** → Toast, dann `Thread { sleep(800); throw }` — der
  Throw läuft auf einem nackten Thread **ohne** CoroutineExceptionHandler, reist
  also durch genau den echten Uncaught-Pfad (§5.1). Der Crash ist hier **gewollt**.
  **Gotcha (source-geprüft):** in einem debuggable Build wird der so erzeugte
  Report per Default **nicht gesendet** und die Report-Datei dennoch gelöscht
  (`ReportDistributor.kt:96,118–120,66`), es sei denn `sendReportsInDevMode=true`.
  Der Pipeline-Status/Marker bleibt dann stumm, obwohl der Uncaught-Pfad korrekt
  lief — beim Ende-zu-Ende-Test also `sendReportsInDevMode` setzen oder gegen
  einen Release-Build testen, sonst jagt man ein Phantom.

---

## 9. Komponenten-Verantwortlichkeiten (Rollen & Paketlayout)

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
   ingestion/ AcraTree, AnrReporter, AcraHttpSender (ReportSender-Dekorator, :acra)
   resilience/ UncaughtCrashHandler, RecoveryWatchdog, CrashReportingBootstrap
```

| Komponente | Modul | Verantwortung | Besitzt **nicht** |
|---|---|---|---|
| `CrashReportConsentRepository` (+ Impl) | domain/data | Persistenz: `readState()→ConsentReadResult`, `setConsent()→ConsentWriteResult`, Getter (total). Wirft nie für I/O. | ACRA-Toggle, UI, Scope |
| `ConsentBootstrap` | data | **Nur** pre-Hilt-R1 + androidTest-Seed, prozess-bewusst (X2). Teilt Key mit dem Repo. | Interaktive Writes |
| `ConsentDecision`/`ConsentReadResult`/`ConsentWriteResult` | domain | Sealed-Modelle (A2/A4/A8), pure Kotlin | — |
| `ConsentController` | app | Koordinator: `resolveStartupAction`, `applyConsent` (inkl. Queue-Purge bei Revoke, A7), `reaffirmConsent`, `persistConsent`. Läuft auf `ApplicationScope`. | Dialog-Bau, DataStore |
| `ConsentDialog` | app | **Nur** Dialog bauen/zeigen, `onResult` bei echtem Tap, `null` sonst (A3) | Lesen, Persistieren, Scope |
| `AcraToggle` | app | Seam über `ACRA.errorReporter.setEnabled` + Queue-Purge (`BulkReportDeleter`, **beide** Ordner, §13) — hält den Seiteneffekt in `:app`, Controller JVM-testbar | Entscheidungslogik |
| `ConsentSaveFailureNotifier` | app | Seam: Toast bei fehlgeschlagenem Persist (A4) | Entscheidungslogik |
| `AcraTree` | app | Der eine Zustellweg: Gate → Carrier → `handleSilentException` (persist + Out-of-process-schedule); schluckt (C1/B2/B4) | Consent-Check (B1); Flut-Kontrolle (server-seitig, §4.3); **Send-Erfolg** (→ `AcraHttpSender`) |
| `AcraHttpSender` | app (`:acra`-Prozess) | `ReportSender`-Dekorator um `HttpSender`; stampt bei HTTP-Erfolg den Last-Successful-Send-Marker cross-prozess in eine Datei mit atomarem Rename (C4/X4, §13) | Report-Inhalt, Consent-Gate |
| `buildAcraReportThrowable`/`LoggedThrowable` | domain | Per-Report-Carrier (B4), pure/JVM-testbar | Android-Runtime |
| `AnrReporter` | app | AEI → `AnrReport`, Watermark-Dedup, best-effort | ACRA-Aufruf (delegiert an Handler) |
| `RecoveryWatchdog` | app | Looper-Stall-Kill **+** Stall-Capture (Daemon-Thread) → `handleSilentException`, *vor* dem Kill (§6, C3, X1) | Consent, Sende-Entscheidung |
| `UncaughtCrashHandler` | app (RELEASE **und** DEBUG) | Wrapper um ACRAs Handler: OOM-GC + Log, dann Delegation; **kein** Flush-Fenster; Kill ist ACRAs `ProcessFinisher` (eigener Kill nur Backstop bei Veto/Wurf) — C2, §5.1, §13 | eigenes Flushen; Build-abhängige Divergenz |
| `CrashReportingBootstrap` | app | ACRA-Init-Reihenfolge (A1), Prozess-Check (X2), Handler/Watchdog-Wiring, KolibriLog-Bridge | Consent-Entscheidung (an R1/Gate) |
| `MainActivity`/`SettingsFragment` | app | Dünner Glue: Gate-Ergebnis rendern, Dialog tracken/dismissen | Alles obige |

---

## 10. Was bewusst NICHT getan wird (abzulehnende „Fixes")

- **`onResult(false)` bei Dialog-Show-Fehler** — macht aus Anzeige-Fehler ein
  „Nein". *(A3, AUDIT-10 #6/#8)*
- **`Unavailable`/`Failed` in Defaults kollabieren** — lässt I/O-Fehler echte
  Entscheidungen überschreiben / Saves vortäuschen. *(A2/A4)*
- **`ConsentDecision` zurück in zwei Booleans zerlegen** oder als roher Boolean
  persistieren — reintroduziert den illegalen Zustand bzw. verliert `NeverAsked`.
  *(A8, §3.1)*
- **Zweiter Consent-Check in B** oder eigene Sende-Logik statt
  `handleSilentException` — umgeht das eine Gate. *(B1)*
- **Zusätzlicher `handleException`-Aufruf für ANRs/Stalls/Fehler** —
  Doppelversand. *(B2)*
- **Einen Client-seitigen `ReportThrottle` (wieder) einführen** — Flut-Kontrolle
  gehört server-seitig (Fingerprint-Dedup + Ingestion-Rate-Limit): ein
  Client-Throttle deckt nur den Silent-Strom, nie den Crash-Loop, und ist die
  einzige `SharedPreferences`-Ausnahme. *(B3, §4.3)*
- **`putCustomData` für Report-Kontext** — prozess-globale Map → Race, erreicht
  den Server eh nicht. *(B4, AUDIT-6 #4)*
- **Widerruf ohne Queue-Purge** — lässt Reports der Nicht-Consent-Phase später
  abfließen. *(A7, X3)*
- **Watchdog nur killen lassen (ohne Capture)** — macht die langsamsten Hangs
  unsichtbar (C3, X1); das bleibt ein Anti-Pattern unabhängig davon, wie G2 fällt.
  (Ob *überhaupt* gekillt wird — Kill vs. nur berichten — ist dagegen die offene
  Gabelung G2, kein abzulehnender „Fix".) *(§6)*
- **DEBUG-only-Divergenz im Uncaught-Pfad** — aktuell abgelehnt zugunsten eines
  Pfads für beide Builds; die Abwägung selbst ist Gabelung G1. *(§5.1)*
- **Den C4-Marker in `AcraTree` stampen** — `AcraTree` sieht nur das *Enqueue*,
  nie den Sende-Erfolg; ein solcher Marker löst X4 gerade nicht. *(C4, X4, §13)*
- **Den C4-Marker in SharedPreferences ablegen** — nicht verlässlich
  multi-prozess; die Ablage ist Datei-mit-atomarem-Rename. *(X4)*
- **Consent nach SharedPreferences verlagern** — DataStore ist die einzige
  Quelle; der Bootstrap-`runBlocking` ist am kontrollierten Main-Thread-Punkt
  API-begründet (§3.5).
- **Repository `Purgeable` machen** — Factory-Reset darf Privacy nicht
  umkippen. *(A5)*
- **Bootstrap-Read im Sender-Prozess ausführen** — überflüssiger Read im falschen
  Prozess; per `ACRA.isACRASenderServiceProcess()` gaten. *(X2, §13)*
- **Handler zum Rethrow bringen, um einen ANR/Stall zu „recovern"** —
  unterläuft den Crash-Infra-Swallow. *(§4.5, §6)*
- **`silentError` in Crash-Infra-Dateien** — rekursiert ins Safety-Net. *(§5.3,
  Rule 9)*
- **Irgendein Flush-Fenster im sterbenden Prozess** — ACRA sendet out-of-process,
  die Datei überlebt den Kill, ein Completion-Callback existiert gar nicht (§13).
  *(§5.1)*

---

## 11. Akzeptierte Grenzen (von den APIs / der Plattform erzwungen)

Diese Grenzen sind überwiegend die Stellen, an denen die Lösung an eine harte
API-/Plattform-Wand stößt — echte Zwänge, keine Baustellen, unabhängig davon, wie
die offenen Gabelungen ausgehen. Wo eine zusätzlich an einer Gabelung hängt
(Watchdog → G2), ist das vermerkt.

- **StrictMode-DiskReadViolation beim Bootstrap-Read (§3.5).** Ein persistierter
  Fakt vor dem ersten möglichen Crash *ist* ein synchroner Disk-Read. Unvermeidbar
  bei jedem Speicher. Gehört nach `KNOWN_ISSUES.md`.
- **Crash-Loop kostet Ingestion-Last, nicht durch den Client gebremst (§4.3).**
  ACRA sendet ohne Intervall (`setOverrideDeadline(0)`, `MAX_SEND_REPORTS=5`,
  Coalescing; §13), und es gibt keinen Client-Throttle. Ein Startup-Crash-Loop
  POSTet im Crash-Takt; die Bremse ist server-seitig (Fingerprint-Rate-Limit an
  der Ingestion-Kante). Gehört nach `ACCEPTED_LIMITATIONS.md`.
- **Soft-ANRs außerhalb des Watchdog-Fensters gehen verloren (§4.5/§6).**
  `ApplicationExitInfo` liefert nur *harte* ANRs; das ~5–10-s-Fenster deckt der
  Watchdog, darunter/darüber bleibt eine Lücke. Live-Sampling (`ANRWatchDog`)
  wäre die Alternative — bewusst nicht gewählt (Overhead, unmaintaint). Gehört
  nach `ACCEPTED_LIMITATIONS.md`.
- **Watchdog-Falschauslösung auf Slow-Device-Tail (§6).** Eine legitime einzelne
  Main-Thread-Operation > 8 s wird false-getrippt. Der Preis für Recovery des
  HOME-Prozesses — gültig, *solange* G2 zugunsten des Kills ausfällt. Fällt G2 auf
  „nur berichten", verschwindet diese Kante.
- **Extrahierbare Basic-Auth-Credentials im `BuildConfig` (§4.7).** Aus dem APK
  extrahierbar; bei einem wegwerfbaren Ingest-Endpoint verkraftbar (nur Schreiben
  von Reports, kein Zugriff auf Nutzerdaten). Gegenmaßnahme ist server-seitiges
  Rate-Limiting/Rotation, nicht Client-Geheimhaltung. Gehört nach
  `ACCEPTED_LIMITATIONS.md`.
- **Cross-Prozess-Send-Marker als Datei (§X4).** Die *einzige* zugelassene
  Cross-Prozess-Ablage; kein Entscheidungszustand, Last-Write-Wins. Der Preis der
  Beobachtbarkeit.

---

## 12. Settings- und Bootstrap-Reihenfolge (die harten Sequenz-Invarianten)

Drei Reihenfolgen sind Teil der Korrektheit, nicht Implementierungsdetail:

1. **`init` → `setEnabled(false)` ohne Zwischenanweisung** (A1, §3.5): sonst
   Mikro-Fenster mit aktivem Reporter bei unbekanntem Consent.
2. **`ACRA.init` → dann `UncaughtCrashHandler` installieren** (§5.1): sonst
   delegiert der Wrapper am Reporter vorbei und kein Report entsteht.
3. **`onCreate` fertig → dann `RecoveryWatchdog` starten** (§5.2/§6): sonst
   killt die schwere Cold-Start-Arbeit den HOME-Prozess in einen Restart-Loop.

---

## 13. API-Realisierbarkeit — ACRA-5.13.1-Anker (gegen die Sources verifiziert)

Dies ist der Vertrag zwischen „geile Lösung" und „die API gibt es her". Jede
Invariante, die von ACRAs *Verhalten* abhängt, ist an ein reales, gegen
`ch.acra:acra-{core,http}:5.13.1` (die per CLAUDE.md Rule 6 gepinnte, nicht
änderbare Version) verifiziertes Symbol gebunden — aus den Sources gelesen, nicht
der Doku geglaubt. Ändert sich die Version, ist diese Tabelle die Checkliste, die
neu verifiziert werden muss.

| Belang / Invariante | ACRA-Symbol (5.13.1, verifiziert) | Verhalten, auf das sich die Spec stützt |
|---|---|---|
| **X2** Prozess-Gate | `ACRA.isACRASenderServiceProcess()` — public, `@JvmStatic` (`ACRA.kt:192`) | Default `errorReporter = StubCreator.createErrorReporterStub()` (`ACRA.kt:106`); im Sender-Prozess bleibt es Stub (`ACRA.kt:163`), echter `ErrorReporterImpl` nur `if (!senderServiceProcess)` (`ACRA.kt:166`). `setEnabled` im `:acra`-Prozess = No-Op → kein inkonsistenter Toggle möglich; unser Gate spart nur den überflüssigen Read. |
| **A7** Queue-Purge | `BulkReportDeleter(ctx).deleteReports(approved: Boolean, nrToKeep: Int)` — public (`BulkReportDeleter.kt:32`) | Löscht ungesendete Reports. ACRA hält **zwei** Ordner (`ReportLocator`: `ACRA-approved` **und** `ACRA-unapproved`); vollständiger Purge ruft **beide**. Offizielle API, kein Griff in private Verzeichnisse. |
| **C2** Deterministischer Kill | `ProcessFinisher` → `Process.killProcess(Process.myPid())`; `exitProcess(10)` (`ProcessFinisher.kt:91–92`) | ACRA killt **selbst**, RELEASE **und** DEBUG. C2 ist ACRAs Garantie, nicht die unseres Wrappers. Unser Kill ist nur Backstop. |
| **§5.1** „Flush" | *(kein Symbol — existiert nachweislich nicht)* | `ErrorReporter` (`ErrorReporter.kt`) exponiert **keinen** Completion-Callback — nur `handleSilentException` (`:55`) / `handleException(_, endApplication)` (`:70`) / `setEnabled` / Custom-Data. Versand ist **out-of-process + asynchron**; der Report ist eine **Datei**, die den Kill überlebt. Im sterbenden Prozess **nichts zu flushen**. |
| **C4** Send-Marker | `ReportSender` (`ReportSender.kt:32`), `ReportSenderFactory : Plugin` (`ReportSenderFactory.kt:33`), `ReportDistributor.distribute(File): Boolean` (`ReportDistributor.kt:60`) | Sende-Erfolg ist **nur** im `:acra`-Prozess beobachtbar. `AcraTree` sieht ihn **nie** — es *enqueued* nur. Ein echter Send-Marker braucht einen eigenen `ReportSender` (Dekorator um `HttpSender`), der bei HTTP-Erfolg cross-prozess in eine Datei stampt. |
| **B2** Zustellweg | `handleSilentException(e)` nicht-terminal (`ErrorReporter.kt:55`); `handleException(e, endApplication)` terminal (`:70`) | `handleSilentException` = persist + Out-of-process-schedule, **kein** Kill. Der eine Silent-Weg. Nie zusätzlich `handleException` → Doppelversand. |
| **§4.5** Post-mortem ANR | `ActivityManager.getHistoricalProcessExitReasons` + `ApplicationExitInfo.REASON_ANR` / `.getTraceInputStream()` — Android API 30+ (Ziel ist 36) | Harte ANRs samt System-Multi-Thread-Dump, ohne Live-Sampling. |
| **B3/§4.3** Sender-Kadenz (kein Intervall) | `DefaultSenderScheduler` „schedules sending instantly", `setOverrideDeadline(0)` (`DefaultSenderScheduler.kt:76`), Job-ID `0` (`:51`); `MAX_SEND_REPORTS = 5` (`ACRAConstants.kt:44`, break in `SendingConductor.kt:47`); `DefaultRetryPolicy` nur bei Total-Fail | ACRA pact Sends **nicht** per Intervall. Dämpfer sind nur Coalescing (fixe Job-ID → Burst *innerhalb eines App-Lebens* kollabiert) + 5 Reports/Pass. Ein Crash-Loop (neuer Prozess je Crash) sendet im Crash-Takt → Flut-Kontrolle (Dedup + Rate-Limit) muss server-seitig sein, ein Client-Throttle wäre überflüssig und deckte den Loop eh nicht. |
| **Prinzip 4 / G4** Capture-Gate = Data-at-rest | `ErrorReporterImpl.uncaughtException` (`ErrorReporterImpl.kt:89–94`); `ReportExecutor.execute` (`ReportExecutor.kt:76–79`) | Bei `enabled=false` bailt der Uncaught-Handler vor `ReportBuilder.build` (`return` nach `handReportToDefaultExceptionHandler`) und der Silent-Pfad vor `saveCrashReportFile` (`if (!isEnabled) { warn; return }`). ⇒ `disabled` = **keine** Report-Datei = null Data-at-rest. Das koppelt G4 an Prinzip 4: Capture-Gate erzwingt den Sync-Read, weil Send-Gate lokale Dateien für Nicht-Zustimmende hieße. |
| **C4/G3** Rückstau-Liveness (Komplement) | `ReportLocator(ctx)` public, `approvedReports`/`unapprovedReports: Array<File>` (`ReportLocator.kt:27–35`); Delete-on-Send (`ReportDistributor.kt:66`), Datei überlebt `ReportSenderException` (`:77–82`) | Rückstau ist aus dem Hauptprozess zählbar (public API, kein Reflection); ein toter Server lässt Dateien liegen. **Aber** kein positiver HTTP-Beweis (leer = gesund *oder* nie gecrasht), und im debuggable Build wird per Default nicht gesendet, die Datei aber gelöscht (`:96,118–120`) → im Debug blind. Billiges Komplement, kein Ersatz für den Send-Marker. |

Verifikationsnotiz: geprüft aus `acra-core-5.13.1-sources.jar` /
`acra-http-5.13.1-sources.jar` (`ACRA.kt`, `ErrorReporter.kt`,
`ErrorReporterImpl.kt`, `ReportExecutor.kt`, `ProcessFinisher.kt`,
`BulkReportDeleter.kt`, `ReportLocator.kt`, `ReportSender.kt`,
`ReportSenderFactory.kt`, `ReportDistributor.kt`, `DefaultSenderScheduler.kt`,
`SendingConductor.kt`, `JobSenderService.kt`, `DefaultRetryPolicy.kt`,
`ACRAConstants.kt`); Zeilen­nummern gegen den Stand der gepinnten
5.13.1-Sources.

---

## 14. Verwandte Docs

- `CLAUDE.md` — Rule 5 (Storage), 7 (Crash-Safety), 8 (opt-in), 9
  (Timber/silentError/silentDeath), 11 (Fehler-als-Wert).
- `KNOWN_ISSUES.md` — der bewusste StrictMode-DiskReadViolation von R1 (§3.5, §11).
- `ACCEPTED_LIMITATIONS.md` — extrahierbare Basic-Auth-Credentials (§4.7), bewusst
  verlorene Soft-ANRs (§4.5/§6), Watchdog-Slow-Device-Kante (§6, §11).
- `AUDIT-10.md` — crash-consent-layering-Review (Consent-Invarianten §2).
- `AUDIT-6.md` / `AUDIT-6_ADDENDUM.md` — Carrier-Exception statt CUSTOM_DATA.
