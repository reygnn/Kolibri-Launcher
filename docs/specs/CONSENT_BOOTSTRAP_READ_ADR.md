# CONSENT_BOOTSTRAP_READ_ADR — Warum der Cold-Start-Consent-Read über `ConsentBootstrap` läuft, nicht durchs Hilt-Repository

> **Erzeugt** 2026-08-29 gegen `main` (unmittelbar nach dem Consent-Read-Doc-Drift-Fix,
> Commit `docs: Consent-Read-Drift korrigieren (attachBaseContext → onCreate)`), auf
> Branch `docs/consent-bootstrap-read-adr`.
> **Status:** ENTSCHIEDEN — Beibehalten (Option C). Kein Code-Change, reine
> Entscheidungs-Dokumentation.
> **Fokus:** der eine synchrone Consent-Read auf dem Crash-Reporting-Bootstrap-Pfad
> (`CrashReportingBootstrap.onCreate` → `ConsentBootstrap.readDecision`). **Nicht**
> im Fokus: der interaktive/Laufzeit-Consent-Pfad (`ConsentController`,
> Settings-Toggle) — der geht bereits durchs Hilt-`CrashReportConsentRepository`
> und ist nicht Gegenstand dieser Frage.
>
> **Anlass.** Der Consent-Read wurde am 2026-08-14 von `attachBaseContext` nach
> `onCreate` verschoben (weil `applicationContext` in `attachBaseContext` null ist).
> Seitdem läuft er an einer Stelle, an der der Hilt-Graph **nachweislich lebt**
> (`anrReporter` wird als `@Inject`-Feld in denselben `CrashReportingBootstrap.onCreate`-
> Aufruf gereicht). Damit stellt sich die berechtigte Architektur-Frage: Der
> historische Grund für das pre-Hilt-`object` (Hilt war in `attachBaseContext` nicht
> verfügbar) ist entfallen — soll der Read jetzt durchs injizierte Repository laufen,
> gemäß „alles durch Hilt"?

---

## Entscheidung

**Der Bootstrap-Consent-Read bleibt bei `ConsentBootstrap.readDecision` (pre-Hilt
`object`, bare `Timber.e`, fail-closed). Er wird NICHT durchs injizierte
`CrashReportConsentRepository` geroutet.**

Das ist keine Alt-Last, sondern eine bewusste, prinzipientreue Ausnahme aus derselben
Familie wie Regel 7/9 (Crash-Infra-Minimalität). Der einzige *tatsächliche* Defekt an
dieser Ecke war Doc-Drift (drei Stellen behaupteten, der Read sitze noch in
`attachBaseContext`) — bereits behoben.

## Kontext: zwei Reader auf einem Key, per Design (§3.3)

Es gibt genau **eine** Wahrheit — ein File (`@ConsentDataStore`, `acra_consent`), ein
Key (`ConsentBootstrap.CONSENT_DECISION_KEY`). Darauf lesen zwei Reader mit
**unterschiedlichem Fehler-Reporting-Kontrakt**:

| | Reader | läuft | Fehlerpfad | Rückgabe |
|---|---|---|---|---|
| **R1** | `ConsentBootstrap.readDecision` | Crash-Bootstrap, **vor** `AcraTree`-Plant | **bare `Timber.e`**, fail-closed | `ConsentDecision` (unknown/error → `NeverAsked`) |
| **R2** | `CrashReportConsentRepository.readState` | post-Wiring (Laufzeit) | **`silentError`** (DEBUG-Throw) | `ConsentReadResult` (`Loaded`/`Unavailable`) |

Das Repository teilt sogar R1s Key-/Value-Konstanten (`CrashReportConsentRepositoryImpl`
referenziert `ConsentBootstrap.CONSENT_DECISION_KEY` / `VALUE_GRANTED` / `VALUE_DENIED`)
— es gibt also keine Duplikat-Wahrheit, nur eine duplizierte triviale Lese-und-Map-
Operation mit **absichtlich verschiedenem** Error-Handling. Genau diese Verschiedenheit
ist der Punkt.

## Der Kern: das `silentError`-Kontrakt ist der Blocker

`CrashReportConsentRepositoryImpl.readState()` meldet **jeden** Fehlerpfad (I/O-Fehler,
unbekanntes Token) über `TimberWrapper.silentError(…)` — und `silentError` **wirft in
DEBUG**. Der Bootstrap-Read läuft aber, **bevor `AcraTree` geplant ist** (Plant in
`CrashReportingBootstrap.onCreate`, NACH dem Consent-Gate). Würde man R1 durch R2
ersetzen:

- **DEBUG:** ein I/O-Fehler oder unbekanntes Token beim Start würde die App
  **abstürzen** (der `silentError`-Throw) — statt fail-closed „ACRA aus". Und der Throw
  landet auf dem Crash-Bootstrap-Pfad, bevor der Reporter existiert: exakt die
  Rekursions-/Früh-Crash-Klasse, gegen die **Regel 9** gebaut ist (dieselbe Logik,
  warum `UncaughtCrashHandler`/`AcraTree` `android.util.Log` statt Timber nutzen und
  Crash-Infra `reportToAcra` statt `silentError`).
- **RELEASE:** `silentError` würde reporten, aber `AcraTree` ist noch nicht geplant →
  No-op. Kein Schaden, aber auch kein Nutzen.

Das ist der **einzige, aber ausreichende** Grund gegen das Routing.

## Was *kein* Blocker ist

Die Tri-State-Asymmetrie (`ConsentReadResult.Unavailable` vs. R1s `NeverAsked`) ist
harmlos: R1 tut nur `setEnabled(decision == Granted)`, also mappt `Unavailable → nicht
Granted → ACRA aus` sauber. Der Rückgabetyp ist nicht der Grund — `silentError` ist es.
Auch die Hilt-Verfügbarkeit ist kein Argument *für* das Routing: sie macht es nur
*möglich*, nicht *richtig*.

## Verworfene Optionen

- **A — Read direkt durch `repository.readState()`.** ✗ Reintroduziert den DEBUG-Throw
  auf dem Crash-Bootstrap-Pfad (Regel 9). Direkter Regelverstoß.
- **B — dem Repository eine zweite, leise `readDecisionFailClosed()` geben.** ✗ Spaltet
  den Fehler-Kontrakt des Repositories nach Caller. Das Repo ist per KDoc bewusst
  „post-ACRA, loud"; ein leiser Zweitpfad verwässert genau diese Eigenschaft und
  verlagert Bootstrap-Belange in ein Allzweck-Repository.
- **D — kleiner `@Inject`-`BootstrapConsentReader` (bare-log, fail-closed), injiziert
  statt static.** ~ Sauberer nach **DI-Purity**, aber koppelt Crash-Infra stärker an
  den Hilt-Graph: heute ist die einzige Hilt-Abhängigkeit des `onCreate`-Bootstraps
  `anrReporter` (reingereicht); ein injizierter Reader macht die Gate-Konstruktion vom
  Graph-Aufbau abhängig. Für Crash-Infra ist **Minimalität** (Regel 7/9) das höhere
  Gut. Das static `object` ist zudem bereits stateless, voll testbar
  (`@VisibleForTesting readDecision(dataStore)` + der Lambda-Seam im Gate) und
  SSOT-teilend — der Purity-Gewinn ist kosmetisch, die Kopplungs-Kosten real.

## Das Prinzip: zwei Begriffe von „sauber"

Der Projekt-Nordstern ist **DI-Purity** („alles durch Hilt, keine Layer-Hacks").
Crash-Infra folgt einem zweiten, im Code fest verankerten Sauberkeits-Prinzip —
**Minimalität/Unabhängigkeit** (Regel 7/9): der Gate soll so wenig wie möglich
voraussetzen, *bevor* der Reporter steht. `ConsentBootstrap.readDecision` gehört in
diese Familie. Damit ist es **kein Layer-Hack** (die Modul-Richtung `:app → :data`
bleibt gewahrt) **und kein DI-Versehen**, sondern die *dokumentierte* Carve-out, die die
Architektur am Crash-Reporting-Bootstrap ohnehin macht. Eine Architektur mit **einer**
klar abgegrenzten, begründeten Ausnahme ist sauber; das Auflösen dieser Ausnahme würde
Crash-Infra-Minimalität gegen DI-Purity tauschen — an der einen Stelle, wo Minimalität
gewinnt.

## Invarianten, die diese Entscheidung schützt

- **A1 / Regel 8** (privacy-by-default): `setEnabled(false)` zuerst, `enable` nur bei
  verifiziertem `Granted`; jeder andere Ausgang (inkl. I/O-Fehler, unbekanntes Token)
  → fail-closed ACRA-aus. R1s `NeverAsked`-Mapping liefert genau das.
- **Regel 9** (keine Rekursion auf dem Crash-Pfad): kein `silentError`/DEBUG-Throw
  bevor `AcraTree` steht.
- **§3.3 SSOT:** ein File, ein Key, zwei Reader — bleibt erhalten.

## Auslöser für ein Re-Open

Diese Entscheidung neu bewerten, falls:
- `CrashReportConsentRepositoryImpl.readState` sein Fehler-Reporting von `silentError`
  auf einen nicht-werfenden Pfad umstellt (dann fällt Blocker A weg — aber prüfen, ob
  R2s Semantik dann noch zu R1s fail-closed passt), **oder**
- der Consent-Read je wieder vor die `AcraTree`-Plant *und* vor die KolibriLog-
  Verdrahtung rückt (dann gälten wieder mehr „pre-wiring"-Gründe), **oder**
- die Crash-Infra-Minimalitäts-Doktrin (Regel 7/9) grundsätzlich aufgegeben wird.

## Referenzen

- `CLAUDE.md` Regel 7, 8, 9 (Crash-Infra-Multi-Layer, ACRA opt-in, Report-by-Intent).
- `ACRA_SPEC.md` (A.2 Tri-State, A1 fail-closed, X2 Prozess-Gate).
- Code: `ConsentBootstrap.readDecision` (`:data`),
  `CrashReportingBootstrap.onCreate` (`:app`, der `readDecision`-Lambda-Seam),
  `CrashReportConsentRepositoryImpl.readState` (`:data`, der `silentError`-Kontrakt),
  `ConsentController.resolveStartupAction` (`:app`, der R2-Laufzeit-Consumer).
