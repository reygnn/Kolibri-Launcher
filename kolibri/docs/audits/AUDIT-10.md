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
>
> **Nachtrag 2026-07-29 (3. Lauf):** Ein dritter `/code-review`-Durchlauf
> (gleicher Branch, gleiche Methode, HEAD `03ba81fe`) hat **keine neuen
> Funde** ergeben — er hat unabhängig exakt dieselben sieben reproduziert.
> Zugewinn: **#6 wurde diesmal direkt gegen den Quelltext verifiziert**
> statt nur aus der Inline-Prüfung übernommen — die beiden Fehlerpfade
> `CrashReportConsent.kt:49` (Nicht-Activity-Kontext) und `:85` (gefangene
> `show()`-Exception) rufen belegt `onResult(false)`, und beide Aufrufer
> (`MainActivity.kt:549`, `SettingsFragment.kt:466`) verdrahten `onResult`
> nachweislich in `persistConsent`. Damit steigt #6 von „Selbsteinschätzung"
> zu quellenverifiziert. Ebenfalls gegengeprüft und weiterhin sauber: nur
> eine einzige `consentDataStore`-Extension (kein Doppel-Instanz-Risiko),
> keine verwaisten Aufrufer der entfernten Store-Methoden, Contract-Test-
> Tripel vollständig. Die übrigen sechs Schweregrade bleiben unverändert.
>
> **Nachtrag 2026-07-29 (4. Lauf):** Ein vierter `/code-review`-Durchlauf
> (gleicher Branch, HEAD `f085c002`) hat gezielt nach Funden **jenseits** der
> bisherigen sieben gesucht und **drei neue** ergänzt, alle `low`: **#8** —
> die Rule-9-Grandfather-Begründung für `CrashReportConsent.kt` ist nach dem
> Refactor veraltet (die Datei importiert `CrashReportConsentStore` nicht mehr,
> liegt also nicht mehr auf dem Bootstrap-Pfad → die zwei `Timber.e` sollten
> auf `silentError` migrieren); **#9** — der `try`-Block in
> `forceShowConsentDialog` trennt „angezeigt" strukturell nicht von „nicht
> anzeigbar" (der Kern hinter #6, mit eigenem Leak-/Doppel-`onResult`-Eckfall);
> **#10** — triviale Doku-Drift in `CLAUDE.md` (Interface-/Use-Case-Bestand
> nicht nachgezogen). Keiner der drei berührt die Top-Priorität #6; #8 und #9
> lassen sich zusammen mit dem #6-Fix miterledigen.
>
> **Nachtrag 2026-07-29 (5. Lauf):** Ein fünfter `/code-review`-Durchlauf
> (gleicher Branch, HEAD `bb38340c`, bewusst **ohne** vorheriges Lesen dieser
> Datei, um eine unabhängige Gegenprobe zu erhalten) hat vier bekannte Funde
> unabhängig reproduziert (#1 Summary-Race, #2 Read-Swallow, #3 Activity-Gate,
> #4 Doppel-Read) und **einen bislang nicht erfassten** ergänzt: **#11**
> (`low`) — der **Write**-Pfad `setConsent()` verschluckt einen DataStore-
> `edit`-Fehler still (`silentError`), während ACRA in-memory bereits
> umgeschaltet wurde → Session- und Persistenz-Zustand divergieren ohne
> Nutzer-Feedback. Komplementär zu #2 (Read-Swallow) und Gegenrichtung zu #6
> (dort wird ein *falscher* Wert *erfolgreich* geschrieben; hier wird *gar
> nichts* geschrieben). Bemerkenswert: der unabhängige Lauf hat die
> `high`-Regression **#6 nicht** reproduziert (blinder Fleck — der Fehlerpfad-
> `onResult(false)` wurde nicht bis zur Persistenz verfolgt), was zeigt, dass
> #6 subtil ist und der Mehr-Lauf-Ansatz trägt.
>
> **Nachtrag 2026-07-29 (6. Lauf):** Ein sechster `/code-review`-Durchlauf
> (gleicher Branch, HEAD `fc1dc8cc`) hat **diese Datei bewusst zuerst gelesen**
> und gezielt nach Funden **jenseits** der bisherigen elf gesucht. Ergebnis:
> die `high`-Regression #6 sowie #1–#3 unabhängig reproduziert und **einen
> bislang nicht erfassten** ergänzt: **#12** (`low`) — die Apply-Sequenz
> `persistConsent(x)` → `ACRA.errorReporter.setEnabled(x)` → `Timber.i(…)` ist
> in `MainActivity` und `SettingsFragment` identisch dupliziert. Der Controller
> zentralisiert nur die Persistenz, nicht das Anwenden der Entscheidung →
> Drift-Risiko bei künftigen Änderungen (z. B. `CrashReportLimiter`-Reset oder
> geänderte Reihenfolge). Ergänzt #5 (dort der redundante `launch`-Wrapper an
> *einer* Stelle) um die *zwischen* den Stellen liegende Duplizierung.

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

**Zwölf** Funde: **einer `high`** (#6, im 2. Lauf ergänzt), **elf `low`**.
Kein `critical`. Klar handlungswürdig ist **#6**: der `onResult(false)`, den
`forceShowConsentDialog` auch auf seinen **Fehlerpfaden** (Nicht-Activity-Kontext,
gefangene `show()`-Exception) feuert, persistiert seit dem Refactor über den
Controller `hasAsked=true` — ein einmaliges Anzeige-Versagen unterdrückt den
Dialog dann **dauerhaft** und schreibt eine Ablehnung fest, die der Nutzer nie
getroffen hat. Das ist eine Regression: vorher persistierte `onResult` nicht
(der Write saß in den Button-Handlern). Die übrigen elf sind
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
| 8 | 🟡 low · CONFIRMED | conventions (Rule 9) | `CrashReportConsent.kt:48`/`:84` | Grandfather-Begründung veraltet: die Datei liegt nach dem Refactor nicht mehr auf dem Bootstrap-Pfad (kein Store-Import mehr) → die zwei `Timber.e` sollten auf `silentError` migrieren; das macht die #6-Fehlerpfade in DEBUG laut. CLAUDE.md Rule 9 („on the bootstrap path") ist ebenfalls falsch geworden |
| 9 | 🟡 low · PLAUSIBLE | robustness (Wurzel #6) | `CrashReportConsent.kt:53`–`88` | `try` trennt „angezeigt" nicht von „nicht anzeigbar": ein Throw **nach** erfolgreichem `show()` → `null`-Rückgabe (Dialog ungetrackt → geleaktes Fenster), sofortiges `onResult(false)` und ein **zweites** `onResult` beim späteren Button-Tap |
| 10 | 🟡 low · CONFIRMED | doc-drift (trivial) | `CLAUDE.md` | Architektur-Inventar nach dem Refactor nicht nachgezogen: neues `CrashReportConsentRepository`, drei Use-Cases und `CrashReportConsentRepositoryImpl` fehlen; Bestandszahlen („19 of them", „~50 use cases") veraltet |
| 11 | 🟡 low · PLAUSIBLE | correctness / robustness | `CrashReportConsentRepositoryImpl.kt:52` (via `CrashReportConsentController.kt:39`) | `setConsent()` verschluckt einen DataStore-`edit`-Fehler still → Consent wird nicht persistiert, obwohl ACRA in-memory schon umgeschaltet wurde; Session ≠ Store, ohne Nutzer-Feedback |
| 12 | 🟡 low · PLAUSIBLE | simplification / reuse | `MainActivity.kt:546` · `SettingsFragment.kt:466` | Apply-Sequenz `persistConsent` → `ACRA.setEnabled` → `Timber.i` in beiden Aufrufern identisch dupliziert; Controller zentralisiert nur die Persistenz, nicht das Anwenden → Drift-Risiko |

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

## 🟡 Low (Nachtrag 4. Lauf)

### #8 — Rule-9-Grandfather für `CrashReportConsent.kt` ist nach dem Refactor veraltet · `CrashReportConsent.kt:48`/`:84`

**Kategorie:** conventions (CLAUDE.md Rule 9) / altitude · **CONFIRMED**
**Behauptung:** CLAUDE.md Rule 9 führt `CrashReportConsent.kt` in der Ausnahmeliste
mit der Begründung, die Datei liege „on the bootstrap path" — „The store's reads
happen via runBlocking in attachBaseContext before Hilt". Genau diese Kopplung hat
der Refactor entfernt: `CrashReportConsent.kt` importiert `CrashReportConsentStore`
**nicht mehr** (verifiziert: kein `data.`-Import), liest bzw. persistiert keinen
Consent und ist jetzt ein reiner, post-Hilt laufender View-Concern. Die neue KDoc
der Datei sagt es selbst: „the dialog's catches are **not** on the pre-Hilt
bootstrap path (unlike the store)". Damit trägt die Grandfather-Begründung nicht
mehr, und die beiden `Timber.e` (`:48` Nicht-Activity-Kontext, `:84` gefangene
`show()`-Exception) fallen unter die Rule-9-Grundform → sollten auf
`TimberWrapper.silentError` migrieren.
**Nutzen / Zusammenhang mit #6:** Beide Zeilen sind exakt die Fehlerpfade aus #6,
die still `onResult(false)` → `persistConsent(false)` auslösen. Als `silentError`
wären sie in DEBUG **laut** und würden die #6-Regression (Anzeige-Versagen fälscht
eine Ablehnung) schon während der Entwicklung sichtbar machen, statt sie nur zu
loggen. Zusätzlich ist die CLAUDE.md-Rule-9-Ausnahmeliste jetzt sachlich falsch
(„on the bootstrap path") und sollte nachgezogen werden.
**Bewertung:** Konvention + Diagnosewert, kein Laufzeitfehler. Niedrige Priorität,
aber deckungsgleich mit dem #6-Fix — beim Beheben von #6 mitnehmen.

### #9 — `forceShowConsentDialog`-`try` trennt „angezeigt" nicht von „nicht anzeigbar" · `CrashReportConsent.kt:53`–`88`

**Kategorie:** robustness / structural (Wurzel von #6) · **PLAUSIBLE**
**Behauptung:** Der `try`-Block umschließt `HtmlCompat.fromHtml`, den Builder,
`dialog.show()` **und** die Post-`show()`-UI-Manipulation
(`findViewById(...).movementMethod`) gemeinsam; der `catch (Exception)` feuert
pauschal `onResult(false)` und gibt `null` zurück, unterscheidet also nicht, ob der
Dialog zum Zeitpunkt des Throws bereits sichtbar ist.
**Fehlerszenario:** Wirft etwas **nach** einem erfolgreichen `dialog.show()` (der
Post-show-Zweig ist praktisch der einzige Kandidat und damit unwahrscheinlich, aber
die Struktur lässt es zu), dann steht der Dialog auf dem Schirm, während (a)
`onResult(false)` sofort `hasAsked=true`/`consent=false` persistiert, (b) die
Rückgabe `null` beim Aufrufer verhindert, dass `currentDialog` gesetzt wird → der
sichtbare, `setCancelable(false)`-Dialog wird bei onDestroy/Config-Change **nicht**
dismissed → geleaktes Fenster (genau das, was das Tracking verhindern soll), und
(c) ein anschließender Button-Tap `onResult` ein **zweites** Mal feuert.
**Bewertung:** Geringe Eintrittswahrscheinlichkeit, aber struktureller Kern hinter
#6: solange „angezeigt + beantwortet" und „konnte nicht angezeigt werden" denselben
`onResult`/Return teilen, bleibt jeder Anzeige-Teilfehler zweideutig. Der für #6
vorgeschlagene Fix (separater Fehler-/`onDismiss`-Pfad ohne `onResult`/`persistConsent`)
räumt diesen Eckfall gleich mit ab. Niedrige Priorität.

### #10 — CLAUDE.md-Architektur-Inventar nach dem Refactor veraltet · `CLAUDE.md`

**Kategorie:** doc-drift (trivial) · **CONFIRMED**
**Behauptung:** Das neue Interface `CrashReportConsentRepository`, die drei
Use-Cases (`GetCrashReportConsentUseCase` / `HasAskedCrashReportConsentUseCase` /
`SetCrashReportConsentUseCase`) und `CrashReportConsentRepositoryImpl` erscheinen im
Architektur-Abschnitt nicht; die Bestandszahlen stimmen nicht mehr
(„domain/repository/ interfaces … — 19 of them", „~50 fine-grained use cases", und
die `data/`-Dateiliste nennt nur `CrashReportConsentStore`, nicht den neuen Impl).
**Bewertung:** Reiner Doku-Nit; „~50" ist ohnehin als ungefähr markiert, die
konkrete „19" wandert mit. Optional beim nächsten CLAUDE.md-Touch nachziehen.
Trivial — der schwächste Fund dieses Reviews.

---

## 🟡 Low (Nachtrag 5. Lauf)

### #11 — `setConsent()`-Write-Fehler wird still verschluckt → Session- ≠ Persistenz-Zustand · `CrashReportConsentRepositoryImpl.kt:52` (via `CrashReportConsentController.kt:39`)

**Kategorie:** correctness / robustness · **PLAUSIBLE**
**Behauptung:** `CrashReportConsentRepositoryImpl.setConsent` fängt jede
`Exception` um `dataStore.edit { … }` und meldet sie nur via
`TimberWrapper.silentError` — der Aufruf kehrt normal zurück, „als ob"
persistiert worden wäre. `persistConsent` (Controller, `applicationScope`)
ist ohnehin fire-and-forget; die Aufrufer (`MainActivity` / `SettingsFragment`)
setzen `ACRA.errorReporter.setEnabled(userGaveConsent)` aber **synchron und
in-memory**. Schlägt der Write fehl, laufen beide Zustände auseinander.
**Fehlerszenario:** Nutzer tippt Accept. ACRA wird für die laufende Session
aktiviert. Der DataStore-`edit` wirft (Volllauf, korrupte Datei, IO). Der
Fehler wird geloggt und verschluckt; `hasAsked`/`consent` bleiben ungesetzt.
Beim nächsten Kaltstart liest der Bootstrap (`CrashReportConsentStore.hasConsent`)
den alten/fehlenden Wert → ACRA startet **aus**, und `hasBeenAsked()` liefert
`false` → der Dialog erscheint erneut. Der Nutzer hat zugestimmt, sieht die
Zustimmung nächste Session aber nicht wieder — und wird nirgends über das
Fehlschlagen informiert (der „enabled"-Toast erschien trotzdem).
**Abgrenzung:** Das Swallow-to-`false` von #2 betrifft den **Read**-Pfad; hier
ist es der **Write**-Pfad. Gegenrichtung zu #6: dort wird ein *falscher* Wert
*erfolgreich* geschrieben (`hasAsked=true`), hier wird *gar nichts* geschrieben.
Das Impl-Sad-Path-Verhalten ist bewusst getestet
(`… when edit fails - does not crash and persists nothing`) — der Fund betrifft
also nicht das „nicht crashen", sondern das fehlende Feedback + die
Session/Store-Divergenz.
**Bewertung:** Best-Effort-Telemetrie-Consent; seltener DataStore-Write-Fehler.
Niedrigste Handlungsdringlichkeit — aber der einzige im unabhängigen 5. Lauf
neu aufgetauchte Punkt, deshalb hier festgehalten.

---

## 🟡 Low (Nachtrag 6. Lauf)

### #12 — Apply-Sequenz in MainActivity und SettingsFragment dupliziert · `MainActivity.kt:546` · `SettingsFragment.kt:466`

**Kategorie:** simplification / reuse · **PLAUSIBLE**
**Behauptung:** Das Tripel `crashReportConsentController.persistConsent(x)` →
`ACRA.errorReporter.setEnabled(x)` → `Timber.i(…)` steht identisch in beiden
Consent-Aufrufern. Der eingeführte `CrashReportConsentController` zentralisiert
die **Persistenz** (`persistConsent` auf dem App-lifetime-Scope), nicht aber das
**Anwenden** der Entscheidung — der ACRA-In-Memory-Toggle plus Log bleibt in
`MainActivity.checkAndShowCrashReportConsent` (`:546`) und im
`SettingsFragment`-Crash-Report-Handler (`:466`) doppelt ausgeschrieben.
**Fehlerszenario:** Eine künftige Änderung am Anwenden einer getroffenen
Zustimmung (z. B. ein `CrashReportLimiter.resetAllLimits()` beim Opt-in, oder
den ACRA-Toggle bewusst **nach** erfolgreicher Persistenz zu ordnen) muss an
zwei Stellen gepflegt werden und kann driften — genau die Klasse von Bug, die
der Controller vermeiden sollte.
**Abgrenzung:** #5 betrifft den redundanten `lifecycleScope.launch`-Wrapper an
*einer* Stelle; #12 ist die Duplizierung *zwischen* MainActivity und
SettingsFragment. **Bewertung:** Wartbarkeit, kein Laufzeitfehler. Niedrige
Priorität. Optionaler Fix: eine `applyConsent(x)`-Methode auf dem Controller
(oder einem UI-Helper), die Persistenz + ACRA-Toggle + Log kapselt; der
In-Memory-ACRA-Seiteneffekt bleibt dabei bewusst app-seitig.

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
