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

Nur **zwei** kleinere Funde, beide **low**. Kein `critical`/`high`.
Schweregrade sind Selbsteinschätzung des Reviews und **unverifiziert**
(keine adversariale Verify-Phase gelaufen).

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
| 1 | 🟡 low · PLAUSIBLE | race-condition | `SettingsFragment.kt:480/567` | Settings-Summary liest Consent aus dem DataStore, während der Persist-Write auf einem anderen Scope noch läuft → evtl. Stale-Wert |
| 2 | 🟡 low · CONFIRMED | efficiency | `MainActivity.kt:533-543` | Zwei DataStore-Reads + redundantes ACRA-Re-Affirm bei jedem Start (dupliziert den Bootstrap-Read) |

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

### #2 — Redundante DataStore-Reads + ACRA-Re-Affirm auf dem Startpfad · `MainActivity.kt:533-543`

**Kategorie:** efficiency / altitude
**Behauptung:** Im „bereits beantwortet"-Zweig führt
`checkAndShowCrashReportConsent` nacheinander `hasBeenAsked()` **und**
`currentConsent()` aus — jeweils eine eigene `dataStore.data.first()`-Flow-
Collection — und startet danach eine Coroutine, die
`ACRA.errorReporter.setEnabled(consent)` aufruft.
**Fehlerszenario:** Beim häufigsten Start (der Nutzer hat schon geantwortet)
zahlt jeder Kaltstart zwei redundante Consent-Store-Reads plus ein ACRA-Toggle,
das `KolibriLauncherApp.attachBaseContext` bereits aus demselben gespeicherten
Wert am Bootstrap gesetzt hat. Ein einzelner kombinierter Read (oder das
Weglassen des Re-Affirm) würde genügen.
**Bewertung:** Kein Korrektheitsfehler — der Kommentar an Ort und Stelle
begründet das Re-Affirm bewusst als „covers a bootstrap read that failed".
Nur Effizienz/Altitude auf einem Hot-Path (jeder Launch). Niedrige Priorität.

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
