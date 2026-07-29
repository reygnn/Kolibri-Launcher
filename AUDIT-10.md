# AUDIT-10 — Branch-Review `refactor/crash-consent-layering` (unverifiziert)

> **Erzeugt** 2026-07-29 durch einen Claude-Opus-4.8-`/code-review`-Lauf
> (Version 0.99.136 / code 156, Branch `refactor/crash-consent-layering`,
> HEAD `055c4883`, Diff gegen `main`).
> **Fokus:** die saubere Architektur-Umstellung des ACRA-Consent-Flows
> (AUDIT-9 #11) — der frühere unstrukturierte `CoroutineScope(IO).launch`
> pro Dialog-Klick wurde durch eine `CrashReportConsentRepository` +
> Use-Cases + `CrashReportConsentController` (App-lifetime-Scope) ersetzt,
> inklusive vollem Contract-Test-Tripel und Sad-Path-Coverage.
> **Methode:** 8 Finder-Winkel (3 Korrektheit, 3 Cleanup, 1 Altitude,
> 1 Conventions), Dedup ohne separate Verify-Phase, high effort.

---

## Gesamtbild

Sauberer, gut getesteter Refactor. Der eigentliche Auslöser — AUDIT-9 #11
(unstrukturierter Scope pro Klick in `CrashReportConsent.kt`) — ist damit
strukturell behoben: Persistenz läuft jetzt über den injizierten
`@ApplicationScope`-`SupervisorJob` statt über einen orphaned Detached-Scope.
Entfernte Funktionen (`resetConsent`, `hasAsked(context)`,
`setConsent(context)`, `showConsentDialog`) haben **keine** verwaisten
Aufrufer; `saveConsent` bleibt korrekt als `androidTest`-Seed-Helper erhalten.
Die Rule-9-Trennung (Bootstrap `Timber.e` vs. post-Hilt `silentError`) ist
respektiert, das Contract-Test-Tripel (Contract + Fake- + Impl-Contract-Test)
ist vollständig, Hilt-Qualifier-Idiome (`@param:ConsentDataStore`,
`@param:ApplicationScope`) folgen dem Hausstil.

**Fünf** kleinere Funde, alle **low**. Kein `critical`/`high`. Am ehesten
handlungswürdig ist #1 (Stale-Summary), die restlichen vier sind
vorbestehend-oder-kosmetisch. Schweregrade sind Selbsteinschätzung des Reviews
und **unverifiziert** (keine adversariale Verify-Phase gelaufen).

**Explizit als sauber bewertet / geprüft-und-verworfen:**

- Der `MainDispatcherRule`-deklariert-aber-plain-`runTest`-Stil im neuen
  Contract-Test ist **kein** Verstoß — er entspricht dem etablierten Muster
  aller bestehenden Contract-Tests (`FavoritesRepositoryContract` etc.); der
  Code berührt `Dispatchers.Main` nicht.
- Kein verwaister Aufrufer der entfernten Store-Methoden.
- Cross-Modul-Test-Fixture-Verdrahtung (`data/src/test` → `domain`-testFixtures
  + `data`-testFixtures) ist per Präzedenz (11 weitere Impl-Contract-Tests)
  vorhanden.
- Kein Duplicate-Binding-Risiko durch den neuen qualifizierten
  `@ConsentDataStore`-Provider neben dem unqualifizierten Settings-Store.

---

## Überblick

| # | Schweregrad | Kategorie | Datei:Zeile | Kurzfassung |
|---|---|---|---|---|
| 1 | 🟡 low · CONFIRMED | race-condition | `SettingsFragment.kt:480` (Read `:567`) | Settings-Summary liest Consent aus dem DataStore, während der Persist-Write auf einem anderen Scope noch läuft → evtl. Stale-Wert |
| 2 | 🟡 low · PLAUSIBLE | correctness | `CrashReportConsentRepositoryImpl.kt:40` | `read()` verschluckt jeden Read-Fehler zu `false` → kann ACRA für einen zustimmenden Nutzer transient deaktivieren / den Dialog erneut zeigen |
| 3 | 🟡 low · PLAUSIBLE | altitude (Rule 10) | `MainActivity.kt:533` | Consent-Entscheidung (`if hasBeenAsked()`) liegt in der Activity statt im dafür eingeführten Controller → nicht JVM-getestet |
| 4 | 🟡 low · CONFIRMED | efficiency | `MainActivity.kt:538` | Zwei getrennte DataStore-Reads (`hasBeenAsked` + `currentConsent`) beim häufigsten Start |
| 5 | 🟡 low · PLAUSIBLE | simplification | `MainActivity.kt:539` | Redundantes `lifecycleScope.launch` um das synchrone `ACRA.setEnabled` → kein Nebenläufigkeitsgewinn, Cancel-Fenster |

---

## 🟡 Low

### #1 — Settings-Summary kann veralteten Consent zeigen · `SettingsFragment.kt:480` (Read bei `:567`)

**Kategorie:** race-condition
**Behauptung:** Nach dem Dialog-Ergebnis persistiert
`crashReportConsentController.persistConsent(userGaveConsent)` den Write auf
dem `applicationScope`; separat wird `updateCrashReportSummary()` auf
`viewLifecycleOwner.lifecycleScope` gestartet und liest
`crashReportConsentController.currentConsent()` (ein frisches
`dataStore.data.first()`). Die beiden `launch`es haben keine
Happens-before-Ordnung.
**Fehlerszenario:** Nutzer tippt Accept/Decline im manuellen
Crash-Report-Dialog. Gewinnt der Read das Rennen gegen den noch laufenden
Write, zeigt die Settings-Summary den **vorherigen** enabled/disabled-Zustand,
bis der Screen erneut geöffnet wird. (Vorbestehendes Race-Muster — schon die
alte Fassung schrieb asynchron und las die Summary separat —, aber beide
Seiten laufen jetzt durch den in diesem Diff berührten Controller.)
**Bewertung:** Rein kosmetisch (falsches Summary-Label bis zum nächsten
Öffnen), ACRA selbst wird synchron korrekt gesetzt. Optionaler Fix: die
Summary aus dem gerade bekannten `userGaveConsent` setzen, statt neu aus dem
Store zu lesen; oder den Persist awaiten, bevor die Summary aktualisiert wird.

### #2 — `read()` verschluckt jeden Read-Fehler zu `false` · `CrashReportConsentRepositoryImpl.kt:40`

**Kategorie:** correctness / robustness
**Behauptung:** `read()` fängt jede `Exception` und liefert `false`. Ein
transienter DataStore-Read-Fehler meldet damit stumm „kein Consent / nicht
gefragt" an das Startup-Gate und an das ACRA-Re-Affirm.
**Fehlerszenario:** Bei einem Nutzer **mit** Consent führt ein transienter
Fehler in `currentConsent()` (`MainActivity.kt:538`) zum Fallback `false` und
`ACRA.errorReporter.setEnabled(false)` — Crash-Reporting, das der Bootstrap
korrekt aktiviert hatte, wird für die gesamte Session deaktiviert. Analog
liefert ein transienter Fehler von `hasBeenAsked()` (`:533`) `false` und der
Dialog erscheint erneut, obwohl der Nutzer bereits geantwortet hat.
**Bewertung:** Richtung ist privacy-safe und entspricht dem alten Verhalten,
aber der Swallow-to-false macht den Inline-Kommentar „cheap re-affirm …
covers a bootstrap read that failed" irreführend — das Re-Affirm kann einen
korrekten enabled-Zustand aktiv **herabstufen**. Tritt nur bei (seltenem)
DataStore-Read-Fehler auf. Niedrige Priorität.

### #3 — Consent-Entscheidung liegt in der Activity, nicht im Controller · `MainActivity.kt:533`

**Kategorie:** altitude (CLAUDE.md Rule 10)
**Behauptung:** Die Entscheidung „schon gefragt → re-affirm, sonst Dialog
zeigen" steht in `MainActivity.checkAndShowCrashReportConsent`, obwohl der
`CrashReportConsentController` genau zur Koordination dieses Flows eingeführt
wurde.
**Fehlerszenario:** Rule 10 („state transitions, decisions belong in a
ViewModel, use case, or helper"): der Zweig gehört in den Controller — z. B.
ein `resolveConsentAction()`, das `ShowDialog` / `AffirmEnabled(consent)`
zurückgibt —, damit er JVM-testbar ist. Aktuell sitzt die Entscheidung in
einer Android-Runtime-Klasse ohne Unit-Coverage; die neuen Tests pinnen nur
die dünne Delegation des Controllers, nicht das Gate selbst.
**Bewertung:** Wartbarkeit/Testbarkeit, kein Laufzeitfehler. Niedrige Priorität.

### #4 — Zwei getrennte DataStore-Reads im „bereits beantwortet"-Zweig · `MainActivity.kt:538`

**Kategorie:** efficiency
**Behauptung:** Der Zweig collectet den Consent-Store zweimal:
`hasBeenAsked()` und danach `currentConsent()`, jeweils eine eigene
`dataStore.data.first()`-Flow-Collection derselben Datei.
**Fehlerszenario:** Beim häufigsten Start (der Nutzer hat schon geantwortet)
zahlt jeder Kaltstart zwei volle Reads derselben Datei. Ein einzelner
kombinierter Read (ein Repository-Call, der beide Flags liefert, oder ein
wiederverwendeter Snapshot) würde die Startup-I/O halbieren.
**Bewertung:** Reine Effizienz auf dem Startpfad. Niedrige Priorität.

### #5 — Redundantes `lifecycleScope.launch` um synchrones `ACRA.setEnabled` · `MainActivity.kt:539`

**Kategorie:** simplification
**Behauptung:** Der „bereits beantwortet"-Zweig wickelt das synchrone
`ACRA.errorReporter.setEnabled(consent)` + `Timber.i` in ein frisches
`lifecycleScope.launch`, obwohl `checkAndShowCrashReportConsent` bereits eine
`suspend`-Funktion unter `mainActivityExceptionHandler` ist.
**Fehlerszenario:** Der zusätzliche Coroutine-Hop bringt keine Nebenläufigkeit
(`setEnabled` ist synchron) und öffnet ein subtiles Fenster: wird
`lifecycleScope` durch einen Config-Change zwischen `return` und dem Ablauf
des `launch` gecancelt, läuft das Re-Affirm nie. Ein Inline-Aufruf (die
Funktion ist bereits in einem supervidierten `suspend`-Kontext) ist einfacher
und cancel-sicher.
**Bewertung:** Cleanup; das Re-Affirm ist ohnehin nur belt-and-suspenders zum
Bootstrap-Set. Niedrige Priorität.

---

## Methodik & Grenzen

- **8 Finder-Winkel** sequenziell in einem Kontext (kein Multi-Agent-Fan-out):
  3× Korrektheit (Zeile-für-Zeile, entfernte-Behavior, Cross-File-Tracer),
  3× Cleanup (Reuse, Simplification, Efficiency), 1× Altitude, 1× Conventions
  (CLAUDE.md).
- **Keine separate adversariale Verify-Phase** (high-effort-Recall-Modus, nur
  Dedup). Die Verdikte (`PLAUSIBLE`/`CONFIRMED`) stammen aus der Inline-
  Prüfung während des Reviews, nicht aus einer dedizierten Gegenprüfungsrunde.
- Zeilennummern beziehen sich auf HEAD `055c4883`.
- „Als sauber bewertet" heißt **„kein Finder-Winkel hat hier etwas gemeldet"**,
  nicht „bewiesen fehlerfrei".
