# RECONCILE_C2_SPEC.md — Sauberer C2b (Repository-lokalisiertes Display-Targeting)

> ## ⛔ VERWORFEN (2026-08-09) — nach vollständigem Bau + Code-Review
>
> C2b wurde vollständig gebaut (Branch `feature/c2b-display-targeting`, 4 Commits,
> Multi-Agent-Code-Review bestanden, ein Major gefixt) und danach **verworfen** —
> der Branch wurde gelöscht, **nichts davon ist auf `main`**.
>
> **Grund (der Denkfehler im Design):** C2bs Effizienz-Prämisse — bei einem
> Install/Uninstall die teure Voll-Enumeration sparen — hält **nicht**, weil der
> für die Selbstheilung nötige **debounced Backstop-Reload** (§2, vierter
> merge-Zweig) genau diese Enumeration weiterhin fährt. Das Fast-Delta **ersetzt**
> die Enumeration also nicht, es kommt **obendrauf**:
> - **1 Event:** main = 1 Enumeration; C2b = 1 Enumeration (Backstop) **+ 1 Delta**
>   (reconcile×4 + Re-Render). → C2b macht *mehr*, nicht weniger.
> - **Sturm (N Events):** main = 1 (coalesced) Enumeration; C2b = 1 (coalesced)
>   Backstop **+ N Deltas**. → deutlich mehr Downstream-Arbeit.
>
> C2b spart also **nie** eine Enumeration; der einzige reale Gewinn ist **Latenz**
> (~250 ms schnelleres Erscheinen bei *einem* Event) plus der Custom-Name-Nit-Fix —
> beides marginal und billiger anders zu haben (der Custom-Name-Nit ist auf `main`
> mit *einer* Zeile behebbar: Custom-Name-Trigger un-debounced). Kosten: mehr Arbeit
> (v.a. im Sturm), die ganze Fold-/Event-Komplexität + Wartungslast. **Netto ein
> Minus gegenüber Debounce-only-`main`.**
>
> **Falls der Effizienz-Gewinn je wirklich gewollt ist**, bräuchte es eine *andere*
> Variante: den **per-Event-Backstop weglassen** (nur Delta; Anzeige-Selbstheilung
> über den ohnehin bei jedem Resume/Cold-Start laufenden Reconcile, Persistenz bleibt
> über das Veto sicher). Dann spart ein Einzel-Event die Enumeration real — aber mit
> schwächerer Anzeige-Selbstheilung (eine spurious-`Removed`-Zeile bliebe bis zum
> nächsten Resume falsch). Eigenes Projekt, eigener Review. Der Rest dieses Dokuments
> bleibt als **Design-Record** der verworfenen Variante.

**Status: ENTWURF v3 (2026-08-09), Spec-Review-Runde 1 eingearbeitet.** Konkretisiert
**C2b** (Display-Targeting) in der **Repository-lokalisierten** Form. Review-Fixes:
Veto-Modell korrigiert (§3 — das Veto läuft auf jedem `Loaded`, auch Fast-Path-
Deltas; kein „null Persistenz"), Backstop erhalten (§2/§4/§7/§8 — Events speisen
Delta **und** debounced Reload), Add = replace-by-package gegen Update-Duplikate,
ADR-Marker korrigiert. Präzisierung nach Auslieferung von Debounce-only: der
Debounce sitzt in C2b **nur** auf der Sturm-Quelle (`appUpdateSignal.events`),
`appsUpdateTrigger` wird sofort → C2b fixt den Debounce-only-Custom-Name-Nit
automatisch (§2, §8-Schritt-3, C2b-INV-6). Der harte Punkt (der Fold, C2b-1) ist per
Wegwerf-Spike (4/4, inkl. Money-Test) **entschärft**: `flatMapLatest` + gehaltener
Zustand trägt latest-wins ohne Generations-Guard; Rest = Thread-Sicherheit von
`current` (§4 knot #2, C2b-1). Löst das frühere C0-Options-Fundament ab:
**C2a** (Cleanup-Targeting) ist **verworfen** (das Veto machte den Cleanup schon
billig), **Debounce** ist ausgelagert nach `DEBOUNCE_SPEC.md` (zugleich Commit 1
von C2b). Dies ist Option **(iii)** der Entscheidung `SPEC-DECISION C-0`. Erst
reviewen, dann bauen; nicht auf `main` mergen, bevor mehrere Multi-Agent-Reviews
bestanden sind.

Schwester-Dokumente: `DEBOUNCE_SPEC.md` (Option (i) / C2b-Commit-1),
`RECONCILE_SPEC.md` (Verifikations-Veto = as-built Korrektheit; §2–§6 =
Design-Record der ursprünglich nicht-gebauten event-targeted Variante),
`INSTALLED_APPS_LOAD_SPEC.md` (Belang C = Zeiger hierher).

---

## 0. Framing — C2b ist Optimierung, nicht Korrektheit

Der Reconcile-**Korrektheits**-Defekt (`RECONCILE_SPEC §0.2`: eine partielle
Ladung löscht noch installierte Zuweisungen) ist **gelöst** — durch das
**Verifikations-Veto** (`7bd7a77e`, §9.3), nicht durch Event-Targeting. C2b
gewinnt daher **nur Effizienz/Latenz**: es vermeidet die teure Voll-Enumeration +
`loadLabel`-für-alle bei einem **einzelnen** Install/Uninstall. Der typisierte
`PackageEvent`(`Added`/`Removed`)-Bus (`de021520`) ist der bewusst geparkte Hook.

**Ehrliche Kosten/Nutzen-Grenze.** Paket-Events sind selten (nutzergetrieben).
Der Sturm-Fall (System-Update) ist **allein durch Debounce** (`DEBOUNCE_SPEC`)
gezähmt. C2b liefert *zusätzlich* die Latenz-/Kosten-Ersparnis bei jedem
Einzel-Event. Ob dieser Zusatz die Komplexität wert ist, ist die eigentliche
Frage — nicht die Machbarkeit (die ist gut, siehe §1).

---

## 1. Zielbild — Hybrid, im Repository gekapselt

**Kern-Erkenntnis:** das Repository emittiert weiterhin **fertige** Listen
(`getInstalledApps(): Flow<AppLoad>` **unverändert**), nur **billig berechnet**.

- **Backstop:** debounced Voll-Reload + Veto — **unverändert** (selbstheilend:
  Cold-Start, verpasste Events, `RECONCILE_SPEC §0.3` Aufgabe 2).
- **Fast-Path im Repo:** `Removed(pkg)` → `Loaded(liste − pkg)`; `Added(pkg)` →
  scoped Load + einsortieren — **ohne** Voll-Enumeration.

**Warum das den Blast-Radius kollabiert:** weil der Vertrag `getInstalledApps()`
gleich bleibt, ist **alles downstream unberührt** — der Use-Case (+ das Veto!),
der `InstalledAppsStateRepository`-Holder, Drawer, Favoriten, Backup. Der
**Single-Writer bleibt trivial erhalten**: der Holder bekommt weiter fertige
Listen; die Delta-Logik lebt eine Ebene tiefer, im Flow selbst serialisiert. Der
gesamte C2b lebt in **einer** Prod-Datei (`InstalledAppsRepositoryImpl`) plus
einer kleinen Delegate-/DI-Anpassung.

---

## 2. Architektur — der zustandsbehaftete Fold im Repo

Heute (zustandslos):
`appsUpdateTrigger.onStart{emit}.flatMapLatest{load}.stateIn(…)`.

C2b: den Upstream durch einen **zustandsbehafteten Fold** über drei gemergte
Quellen ersetzen, der die aktuelle Liste hält und `AppLoad` emittiert:

```kotlin
private sealed interface ListCommand {
    object Reload : ListCommand                         // FULL
    data class Remove(val pkg: String) : ListCommand    // DELTA
    data class Add(val pkg: String) : ListCommand       // DELTA
}

merge(
    flowOf(ListCommand.Reload),                                    // Priming — sofort
    appsUpdateTrigger.map { ListCommand.Reload },                  // custom-name edit, app-click refresh (:130) — SOFORT (nicht-Sturm)
    appUpdateSignal.events.map { it.toListCommand() },             // FAST delta — sofort
    appUpdateSignal.events.debounce(T).map { ListCommand.Reload }, // BACKSTOP — Paket-Broadcasts (die Sturm-Quelle), debounced
)
    .foldIntoList()   // hält List<AppInfo>, wendet Command an → emittiert AppLoad
    .catch { … AppLoad.Failed(e) … }                              // wie heute
    .stateIn(scope, WhileSubscribed(FLOW_SHARING_TIMEOUT_MS), AppLoad.Loaded(emptyList()))
```

**Command-Semantik:**
- **`Reload`** → volle Enumeration (heutige `loadAppsFromPackageManager`) → neue
  Liste **ersetzt** den Fold-Zustand. Fehler → `AppLoad.Failed` (unverändert).
- **`Remove(pkg)`** → `zustand.filterNot { it.packageName == pkg }` →
  `Loaded(neu)`. **Keine** Enumeration.
- **`Add(pkg)`** → scoped `queryIntentActivities(intent.setPackage(pkg))` →
  `processResolveInfoList` (bestehend) → **replace-by-package**: erst **alle**
  bestehenden Einträge mit `packageName == pkg` entfernen, dann die frisch gequerten
  einsortieren (nach `displayName.lowercase()`) → `Loaded(neu)`. Replace-statt-additiv
  ist **Pflicht**: `PACKAGE_ADDED` ist auch die Add-Hälfte eines In-Place-Updates
  (`EXTRA_REPLACING=true`) und wird von `PackageUpdateReceiver` ebenfalls zu
  `PackageEvent.Added` gemappt — additiv gäbe es bei jedem Update Duplikat- und
  Stale-Label-Zeilen. Ein Paket kann zudem **mehrere** Launcher-Activities hosten →
  mehrere Zeilen gleichen `packageName`; sowohl `Remove` als auch die Replace-Vorstufe
  von `Add` treffen **alle** (`filterNot { it.packageName == pkg }`).

Der vierte `merge`-Zweig ist der **Self-Healing-Backstop**: jedes Paket-Event plant
(debounced) zusätzlich zum sofortigen Delta einen autoritativen `Reload`. Die Wire
Broadcast→Reload darf also **nicht** ersatzlos verschwinden (siehe §7/§8 — der
Consumer wandert aus dem Delegate in den Repo, verliert die Reload-Wirkung aber
nicht).

**Debounce sitzt in C2b nur auf der Sturm-Quelle — das ist der Unterschied zum
ausgelieferten Debounce-only.** Heute (DEBOUNCE_SPEC, `main`) debounct der Repo den
**ganzen** `appsUpdateTrigger`, weil dort *alle* Quellen zusammenlaufen — inkl. der
Paket-Broadcasts (die stürmen). Preis: der einzelne Custom-Name-Edit
(`CustomNamesRepositoryImpl.triggerCustomNameUpdate`) und der App-Klick-Refresh
(`:130`) zahlen die 250 ms mit, obwohl sie nie stürmen (der akzeptierte
Debounce-only-Nit). In C2b wandert die **Sturm-Quelle** auf `appUpdateSignal.events`
(vierter Zweig, dort debounced), und `appsUpdateTrigger` trägt danach **nur noch**
diese Einzelaktionen → er wird **un-debounced/sofort** (zweiter Zweig). Damit **fixt
C2b den Debounce-only-Nit automatisch**, statt ihn zu erben: nur was stürmt wird
debounced, bewusste Einzelaktionen sind sofort.

---

## 3. Wie Löschung wirklich passiert: das Veto läuft auf JEDEM Loaded

Präzisierung gegenüber einer naiven Lesart („der Fast-Path fasst nur die Anzeige
an, nie Persistenz"): das ist **falsch** und wäre ein gefährliches Modell. Es gibt
genau **ein** Veto, in `ObserveInstalledAppsUseCase.invoke()`, und es läuft auf
**jedem** nicht-leeren `AppLoad.Loaded` — **auch** auf einem vom Fast-Path erzeugten
`Removed(pkg)` → `Loaded(liste − pkg)`. Daraus präzise:

- **Echte Uninstalls werden am Fast-Path-Zeitpunkt gelöscht**, nicht „dem Backstop
  überlassen": das kleinere `Loaded` erreicht sofort den Reconcile, `pkg` fehlt →
  Kandidat → `PackagePresence`-Gate bestätigt Abwesenheit → Favorite/Swipe/Hidden/
  Custom-Name werden gelöscht. Gewollt und korrekt (per-Ziel gegated).
- **Der `PackagePresence`-Gate ist NICHT überflüssig — er ist genau die
  Absicherung.** Ein *spurious* `Removed(pkg)` (pkg doch installiert) erzeugt ein
  `Loaded(liste − pkg)`; das Veto sieht `pkg` als Kandidat — und der Gate **rettet**
  die Persistenz, weil er `pkg` als vorhanden meldet und die Löschung **vetoed**. Der
  Fast-Path darf diesen Gate deshalb **niemals umgehen**; er nutzt ihn.
- **Anzeige ≠ Löschung** (`RECONCILE_SPEC §1`) bleibt erhalten: der Fast-Path
  *selbst* enthält **keine** Löschlogik; die Persistenz-Wirkung entsteht
  ausschließlich über das **unveränderte** downstream-Veto, das jedes `Loaded`
  (Fast-Path wie Reload) gleich behandelt.

→ Der Clou ist also **nicht** „null Persistenz-Wirkung", sondern: **C2b braucht
keine neue Löschlogik und kein neues Gate — das bestehende Veto greift auf
Fast-Path-Deltas genauso sicher wie auf Voll-Reloads.** Das ist die tragende
Sicherheits-Aussage; alles hängt daran, dass C2b das Veto unverändert lässt.

---

## 4. Der (kleinere) harte Knoten

Der Fold ersetzt `flatMapLatest`. Zu erhalten bzw. zu klären:

1. **Latest-wins beim `Reload`:** eine laufende Voll-Enumeration muss von einem
   neueren `Reload` gecancelt werden — das gab `flatMapLatest` gratis. Im Fold
   braucht es Struktur (`SPEC-DECISION C2b-1`: z.B. den `Reload`-Zweig weiter über
   `flatMapLatest` führen und Deltas separat foldern, statt eines einzigen
   Operators).
2. **Ordnung `Reload` vs Delta — GELÖST durch die Fold-Struktur (Spike-bewiesen).**
   Weil **alle** Commands (Reload aus jeder Quelle + Deltas) durch **einen** gemergten
   Stream und **ein** `flatMapLatest` laufen, canceled jeder neue Command den
   laufenden inneren Flow. Ein langsamer, veralteter `Reload` (der `pkg` noch
   enthielte) wird von einem später eintreffenden `Remove(pkg)` **gecancelt**, bevor
   er den gehaltenen Zustand schreibt — er kann `pkg` also nicht wieder einfügen. Im
   Wegwerf-Spike (2026-08-09, Money-Test „remove during a slow reload wins", 4/4)
   bewiesen. **Kein Generations-Guard nötig** (die früher hier vermutete Härtung
   entfällt). Einzige Voraussetzung: der Single-Stream-Funnel (kein zweiter,
   nebenläufiger Schreiber). Produktions-Detail: den gehaltenen Zustand thread-sicher
   machen (`SPEC-DECISION C2b-1`).
3. **`WhileSubscribed`-Teardown:** Re-Subscribe re-initialisiert den Fold → erstes
   Command ist der Priming-`Reload` (volle Wahrheit). Unkritisch.
4. **Delta gegen leeren Zustand:** ein `Remove`/`Add` vor dem ersten `Reload`
   (Zustand leer) → `SPEC-DECISION C2b-2` (no-op vs. `Reload`-Fallback).

---

## 5. Prämissen

- **P1 — Backstop bleibt.** Debounced Voll-Reload + Veto ersetzt nichts, ergänzt.
  R-INV + Cold-Start-Aufholung wortwörtlich erhalten.
- **P2 — Payload nutzen, nicht erfinden.** `PackageEvent` + typisierter
  `AppUpdateSignal` existieren (`de021520`). C2b baut den Consumer.
- **P3 — Anzeige ≠ Löschung** bleibt entkoppelt (§3).
- **P4 — Debounce ist Voraussetzung** (`DEBOUNCE_SPEC`, Commit 1).
- **P5 — `getInstalledApps()`-Vertrag unverändert** → Downstream unberührt,
  Single-Writer trivial.

---

## 6. Invarianten (C2b-INV-*)

- **C2b-INV-1:** `getInstalledApps(): Flow<AppLoad>`-Vertrag unverändert.
- **C2b-INV-2:** Der Fast-Path enthält **keine** eigene Löschlogik und umgeht das
  `PackagePresence`-Veto **nie**. Jede Persistenz-Löschung entsteht ausschließlich
  über das unveränderte Veto in `ObserveInstalledAppsUseCase`, das auf jedem `Loaded`
  (Fast-Path-Delta wie Voll-Reload) greift. (§3 — die tragende Invariante.)
- **C2b-INV-3:** Jedes Paket-Event plant (debounced) einen Backstop-`Reload` — der
  Event-Consumer im Repo speist Delta **und** Reload (§2, vierter merge-Zweig). So
  korrigiert nach jedem Event/Burst eine autoritative Enumeration die Fast-Path-
  Anzeige zur Wahrheit. Die Wire Broadcast→Reload darf **nie** ersatzlos entfernt
  werden.
- **C2b-INV-4:** `Added` löst nie eine Löschung aus.
- **C2b-INV-5:** Verpasstes/gedropptes/spurious Event → **kein** permanenter Drift:
  der event-getriebene debounced Reload (INV-3) plus die Cold-Start-Aufholung
  (`RECONCILE_SPEC §0.3`) konvergieren Anzeige **und** Persistenz zur Wahrheit.
- **C2b-INV-6:** Debounce sitzt **nur** auf der Sturm-Quelle (`appUpdateSignal.events`,
  Paket-Broadcasts). `appsUpdateTrigger` (Custom-Name-Edit, App-Klick — bewusste
  Einzelaktionen) ist **sofort**, nie debounced. So zahlt keine Einzelaktion die
  Sturm-Latenz (fixt den Debounce-only-Nit).

---

## 7. Sigs (Zielzustand)

- **`InstalledAppsRepositoryImpl`** (der Kern): `AppUpdateSignal` in den
  Konstruktor injizieren (Hilt-provided, `:domain/core`; in `:data` via
  `InstalledAppsRepositoryEntryPoint` schon erreichbar — jetzt direkt injizieren).
  Interner `ListCommand` + `PackageEvent.toListCommand()`. Fold statt
  `flatMapLatest`.
- **Scoped Load:** `queryIntentActivities(Intent(ACTION_MAIN).addCategory(LAUNCHER)
  .setPackage(pkg), …)` → `processResolveInfoList` (bestehend).
- **Listen-Helfer:** `replacePackage(current, pkg, newForPkg)` =
  `current.filterNot { it.packageName == pkg } + newForPkg`, dann sortiert nach
  `displayName.lowercase()` — **extrahierbar + pure → unit-testbar**. `Remove` nutzt
  nur den `filterNot`-Teil.
- **`AppManagementDelegate`:** das `appUpdateSignal.events.collect { refreshAppsUseCase() }`
  (Zeile 268) entfernen, **aber die Reload-Wirkung nicht verlieren**: der Repo
  konsumiert `appUpdateSignal.events` jetzt selbst und speist daraus BEIDES — den
  sofortigen Delta-Command UND (debounced) den Backstop-`Reload` (§2, merge-Zweige
  3+4). Der App-Klick-Refresh (`:130`) und andere `appsUpdateTrigger`-Quellen bleiben
  unberührt.
- **`AppConstants`:** Debounce-Fenster T (`DEBOUNCE_SPEC D-1`).

---

## 8. Migrationsreihenfolge (je ein revertierbarer Commit)

1. **Debounce** (= `DEBOUNCE_SPEC`). Standalone, für sich wertvoll — zähmt den
   Sturm schon allein.
2. **Fold-Gerüst, nur `Reload`.** `AppUpdateSignal` injizieren, `flatMapLatest`
   durch den Fold ersetzen, aber **nur** `Reload`-Commands verarbeiten →
   **verhaltensneutral** zu (Debounce-)heute. Reine Umstrukturierung, grün
   verifizieren, bevor Targeting dazukommt.
3. **`Remove(pkg)`-Fast-Path** (Anzeige-Filter). Den Event-Consumer aus dem Delegate
   (`:268`) in den Repo-Fold verlegen und dort **beide** Zweige speisen: sofortiger
   Delta **+** debounced Backstop-`Reload` (die Wire darf nicht ersatzlos
   verschwinden, sonst planen Paket-Events keinen Reload mehr → Self-Healing-Lücke,
   Spec-Review-Finding 1). Kern-Test: `Removed` → `pkg` sofort raus aus `Loaded`
   **ohne** `queryIntentActivities`-Aufruf (Zähler == 0 auf dem Delta), UND ein
   debounced Reload feuert danach. **Hier zieht der Debounce um:** da die Sturm-Quelle
   (Paket-Broadcasts) jetzt auf `appUpdateSignal.events` liegt (dort debounced), wird
   `appsUpdateTrigger` **un-debounced/sofort** — er trägt nur noch Custom-Name-Edit +
   App-Klick. Damit ist der Debounce-only-Nit weg. Regressions-Test: ein
   `triggerCustomNameUpdate` → Reload **ohne** 250 ms-Verzögerung.
4. **`Add(pkg)`-Fast-Path** (scoped Load + **replace-by-package** einsortieren). Test:
   `Added` → `pkg` in `Loaded`, korrekt sortiert, ohne Voll-Enumeration; **zwei**
   `Added(pkg)` bzw. ein Update (`EXTRA_REPLACING`) → **keine** Duplikat-Zeilen.

Nach 1–2 ist der Sturm gezähmt und die Struktur da; 3–4 sind der eigentliche
C2b-Gewinn. Jeder Schritt mergefähig.

---

## 9. Test-Auswirkung

- Repo steht auf **`NO IMPL CONTRACT TEST (ADR)`** (nicht `NO CONTRACT TEST`) — die
  Fake-Contract-Tests (`FakeInstalledAppsRepositoryContractTest`,
  `ReactiveFake…`: `getInstalledApps` gleiche Flow-Instanz, Initial-Emit ohne
  Trigger) bleiben **Pflicht**; der Fold-Umbau (`flatMapLatest` ersetzen,
  `AppUpdateSignal` in den Konstruktor) muss sie grün halten. Nur der Impl-Contract-
  Test entfällt; die folgenden Impl-Tests kommen dazu.
- **Fast-Path-Listen-Logik** (`replacePackage` / `filterNot`) rausziehen → **pure
  Unit-Tests** (kein Robolectric).
- **Fold-Verhalten** (Reload-vs-Delta-Ordnung, Debounce) → virtual-time Impl-Test
  (`MainDispatcherRule`/`testScheduler`).
- **Kern-Regressionstest 1 (Fast-Path):** `Removed(pkg)` entfernt `pkg` sofort aus
  der Anzeige, während ein `queryIntentActivities`-**Zähler == 0** bleibt (keine
  Enumeration auf dem Delta); der event-getriebene debounced Reload feuert danach.
- **Kern-Regressionstest 2 (Veto auf Delta, §3):** ein Fast-Path
  `Removed(pkg)`-`Loaded` läuft durch das Veto — **echter** Uninstall → pkg-Zuweisung
  gelöscht (per-Ziel gegated); **spurious** `Removed` (pkg per `PackagePresence` noch
  da) → Zuweisung **bleibt** (Gate vetoed).
- **Add-Idempotenz:** zwei `Added(pkg)` bzw. ein Update (`EXTRA_REPLACING`) → **keine**
  Duplikat-Zeilen (replace-by-package).

---

## 10. Offene Entscheidungen

- **`SPEC-DECISION C2b-1` — GELÖST durch Wegwerf-Spike (2026-08-09).** Der Fold ist
  `flatMapLatest` über **einen** gemergten Command-Stream (Reload + Deltas) mit einem
  gehaltenen `current`, das **nur bei Vollendung** des inneren Flows geschrieben wird.
  Bewiesen (4/4 Spike-Tests, inkl. Money-Test): ein neuer Command canceled den
  laufenden inneren Flow, ein langsamer/veralteter `Reload` überschreibt ein späteres
  `Remove` nicht → **kein Generations-Guard** (§4 knot #2 entfällt). Zwei Rest-Punkte
  für die Umsetzung: **(i)** `current` muss thread-sicher sein
  (`@Volatile`/`MutableStateFlow`/`AtomicReference`) — der Prod-`stateIn`-Scope läuft
  auf `Dispatchers.IO` (Multi-Thread); der Spike lief single-thread und hat die
  Sichtbarkeit nicht abgedeckt. **(ii)** ein `Add` kann mitten im scoped-Load von
  einem Folgecommand gecancelt werden (suspend) → das Paket erscheint dann erst über
  den Backstop-Reload; Latenz-Kante, keine Korrektheit (`Remove` ist pur, nie
  betroffen). Beides ist bekannt und akzeptabel, muss aber in der Umsetzung
  angefasst/dokumentiert werden.
- **`SPEC-DECISION C2b-2`:** Delta-vor-erstem-`Reload` (no-op vs. `Reload`-Fallback).
- **`SPEC-DECISION D-1`:** Debounce-Fenster T (erbt `DEBOUNCE_SPEC`).
- **`SPEC-DECISION C-2`:** Bus-Konsolidierung — `appsUpdateTrigger` (`Unit`) bleibt
  Reload-Trigger, `AppUpdateSignal` (`PackageEvent`) trägt Deltas. Empfehlung:
  **getrennt lassen** (zwei klare Rollen), keine Zusammenführung nötig.

---

## 11. Was es NICHT ist

- Kein Korrektheits-Fix (das war das Veto, `RECONCILE_SPEC`).
- Keine **neue** Löschlogik und kein neues Gate im Fast-Path — die Persistenz-
  Löschung läuft über das bestehende, unveränderte Veto, das jedes `Loaded` (auch
  Fast-Path-Deltas) gated; der Fast-Path umgeht es nie (§3).
- Kein Vertragsbruch (`getInstalledApps()` unverändert).
- Nicht C2a (verworfen — Veto machte Cleanup-Targeting überflüssig).
