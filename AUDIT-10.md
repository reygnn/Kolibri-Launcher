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
>
> **Nachtrag 2026-07-29 (2. Lauf):** Ein zweiter `/code-review`-Durchlauf
> (gleicher Branch, gleiche Methode) hat zwei bislang nicht erfasste Funde
> ergänzt — darunter **#6, der erste `high`-Fund dieses Reviews** (eine
> echte Regression im Fehlerpfad des Consent-Dialogs). Die Zahlen #6/#7
> hängen chronologisch hinten an, #6 ist aber der neue Top-Prioritätsfund.

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

**Sieben** Funde: **einer `high`** (#6, im 2. Lauf ergänzt), **sechs `low`**.
Kein `critical`. Klar handlungswürdig ist **#6**: der `onResult(false)`, den
`forceShowConsentDialog` auch auf seinen **Fehlerpfaden** (Nicht-Activity-Kontext,
gefangene `show()`-Exception) feuert, persistiert seit dem Refactor über den
Controller `hasAsked=true` — ein einmaliges Anzeige-Versagen unterdrückt den
Dialog dann **dauerhaft** und schreibt eine Ablehnung fest, die der Nutzer nie
getroffen hat. Das ist eine Regression: vorher persistierte `onResult` nicht
(der Write saß in den Button-Handlern). Die übrigen sechs sind
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
| 6 | 🔴 **high** · CONFIRMED | correctness (Regression) | `MainActivity.kt:549` · `SettingsFragment.kt:466` (Ursprung `CrashReportConsent.kt:49`/`:85`) | `onResult(false)` aus den **Fehlerpfaden** von `forceShowConsentDialog` persistiert jetzt `hasAsked=true` → ein Anzeige-Versagen unterdrückt den Consent-Dialog dauerhaft und schreibt eine nie getroffene Ablehnung fest |
| 1 | 🟡 low · CONFIRMED | race-condition | `SettingsFragment.kt:480` (Read `:567`) | Settings-Summary liest Consent aus dem DataStore, während der Persist-Write auf einem anderen Scope noch läuft → evtl. Stale-Wert |
| 2 | 🟡 low · PLAUSIBLE | correctness | `CrashReportConsentRepositoryImpl.kt:40` | `read()` verschluckt jeden Read-Fehler zu `false` → kann ACRA für einen zustimmenden Nutzer transient deaktivieren / den Dialog erneut zeigen |
| 3 | 🟡 low · PLAUSIBLE | altitude (Rule 10) | `MainActivity.kt:533` | Consent-Entscheidung (`if hasBeenAsked()`) liegt in der Activity statt im dafür eingeführten Controller → nicht JVM-getestet |
| 4 | 🟡 low · CONFIRMED | efficiency | `MainActivity.kt:538` | Zwei getrennte DataStore-Reads (`hasBeenAsked` + `currentConsent`) beim häufigsten Start |
| 5 | 🟡 low · PLAUSIBLE | simplification | `MainActivity.kt:539` | Redundantes `lifecycleScope.launch` um das synchrone `ACRA.setEnabled` → kein Nebenläufigkeitsgewinn, Cancel-Fenster |
| 7 | 🟡 low · CONFIRMED | test-coverage | `CrashReportConsentControllerTest.kt:40` | Test beweist den eigentlichen Zweck (Persist überlebt UI-Teardown) nicht — `persistConsent` wird ohne cancelbaren Caller-Scope aufgerufen; der Fehlerpfad aus #6 ist nirgends abgedeckt |

---

## 🔴 High

### #6 — Fehlerpfad-`onResult(false)` persistiert `hasAsked=true` → Dialog dauerhaft unterdrückt · `MainActivity.kt:549` · `SettingsFragment.kt:466` (Ursprung `CrashReportConsent.kt:49`/`:85`)

**Kategorie:** correctness / Regression · **CONFIRMED**
**Behauptung:** Der Refactor hat die Persistenz aus den Dialog-Button-Handlern
in den `onResult`-Callback der Aufrufer verschoben
(`crashReportConsentController.persistConsent(userGaveConsent)`).
`forceShowConsentDialog` ruft `onResult(false)` aber **nicht nur bei
„Ablehnen"**, sondern auch auf seinen **Fehlerpfaden**:

- `CrashReportConsent.kt:49` — Kontext ist keine `Activity`,
- `CrashReportConsent.kt:85` — `catch (e: Exception)` um `dialog.show()`.

Dadurch schreibt jetzt jeder gescheiterte Dialog-Versuch `persistConsent(false)`
→ `hasAsked=true` **und** `consent=false` in den DataStore.

**Fehlerszenario (MainActivity, `high`):** Beim Erststart ruft
`checkAndShowCrashReportConsent()` → `forceShowConsentDialog(this)`.
`AlertDialog.show()` wirft eine `WindowManager$BadTokenException` (Activity
finisht / Fenster-Token noch nicht attached — ein bekanntes Android-Muster).
Der `catch` bei `:85` ruft `onResult(false)` → `persistConsent(false)` →
`hasAsked=true`. Ab dem nächsten Start liefert `hasBeenAsked()` `true`, der
Consent-Dialog erscheint **nie wieder**, und ACRA bleibt dauerhaft aus — der
Nutzer hat „abgelehnt", ohne den Dialog je gesehen zu haben. In der alten
Fassung persistierte `onResult` **nicht** (der `saveConsent`-Write saß
ausschließlich in den Positive/Negative-Button-Handlern), sodass dasselbe
Anzeige-Versagen den Dialog beim nächsten Start einfach erneut zeigte —
**eine echte Regression dieses Refactors.**

**Fehlerszenario (SettingsFragment, `medium`, gleiche Ursache):** Nutzer tippt
die Crash-Report-Preference; `forceShowConsentDialog(activityContext)` scheitert
(BadTokenException auf finishender Activity, oder `activityContext` ist kurz
keine Activity). `onResult(false)` überschreibt ein evtl. vorhandenes
`consent=true` mit `consent=false`, deaktiviert ACRA und zeigt einen
„disabled"-Toast — für eine Aktion, die der Nutzer nie bestätigt hat.

**Bewertung:** Für einen Privacy-/Consent-Flow gravierend, weil das
Anzeige-Versagen still eine Nutzer-Entscheidung fälscht und den Dialog
permanent stilllegt. **Fix (Wurzel):** „Dialog wurde angezeigt und beantwortet"
sauber von „konnte nicht angezeigt werden" trennen — z. B. `onResult` nur aus
den echten Button-Taps aufrufen und für das Anzeige-Versagen einen separaten
Fehler-/`onDismiss`-Pfad verwenden (kein `persistConsent`). Beide Aufrufstellen
(MainActivity + SettingsFragment) profitieren von derselben Trennung.

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

### #7 — Controller-Test beweist den eigentlichen Zweck nicht · `CrashReportConsentControllerTest.kt:40`

**Kategorie:** test-coverage · **CONFIRMED**
**Behauptung:** Der `CrashReportConsentController` existiert laut eigenem KDoc,
damit `persistConsent` den Write auf dem App-lifetime-Scope ausführt und so
einen sofortigen UI-Teardown **überlebt**. Der Test ruft `persistConsent`
jedoch direkt aus der Test-Coroutine auf — **ohne cancelbaren Caller-Scope** —
und prüft nur, dass der Write auf den injizierten Scope verschoben wird
(`advanceUntilIdle`). Der Überlebensfall (Caller-Scope wird gecancelt, Write
läuft trotzdem durch) wird nie ausgelöst.
**Fehlerszenario:** Würde ein künftiger Refactor den Write versehentlich auf
den Lifecycle-Scope des Aufrufers legen, liefen die bestehenden Tests weiter
grün — genau die Regression, gegen die diese Klasse eingeführt wurde, bliebe
unentdeckt. Zusätzlich ist die Fehlerpfad-Persistenz aus #6
(`onResult(false)` → `persistConsent(false)`) nirgends getestet.
**Bewertung:** Test-Härte; kein Laufzeitfehler. Niedrige Priorität. Fix: einen
eigenen `CoroutineScope` als Caller simulieren, ihn nach `persistConsent`
canceln und zeigen, dass `setConsent` dennoch aufgerufen wird.

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
