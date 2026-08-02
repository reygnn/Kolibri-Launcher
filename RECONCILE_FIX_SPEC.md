# RECONCILE_FIX_SPEC.md

**Fix-Spec für das R-INV-Loch im Verifikations-Veto (`RECONCILE_SPEC.md` §3, as-built).**

Der Multi-Agent-Code-Review des Reconcile-Rewrites (Range `33a09a89..7bd7a77e`, 12 Agents,
5 bestätigte Funde) hat einen realen Datenverlust-Pfad aufgedeckt, den der Veto **nicht**
schließt — er reintroduziert eine engere Variante genau der Klasse, die er beseitigen sollte.
Dieses Dokument beschreibt das Problem und legt den Fix fest. Es ist **Plan, noch kein Code**:
erst Doc-Review bis grün, dann Umsetzung nach §7.

**Status: UMGESETZT** (F-C, atomar) — alle drei Modul-Suiten + `checkConventions` + `checkRule13`
nachweislich grün vor dem Merge. Regressionstests §6.1–§6.7 + L1 vorhanden.

*(Historie:)* Entwurf, **Revision 3** — zwei Multi-Agent-Doc-Reviews durchlaufen. Rev 2 arbeitete die
5 Funde aus Runde 1 ein (Swipe-In-Edit-Wert-Guard §2/§5, Test-Blast-Radius §3/§6, atomare
Reihenfolge §7). Rev 3 arbeitet die 6 Funde aus Runde 2 ein — **kein** Soundness-Loch (Kern-Design
F-C und Swipe-Guard bestätigt), nur Präzision/Test-Plan: §6.1 pinnt jetzt den **assertThrows**-
Vertrag (nicht nur „Store unverändert"), §6.6/§6.7 fordern **neue** Edit-Fail- und Swipe-Clobber-
Tests statt nicht-existente, §7 „Stub erweitert, nicht gelöscht", §3 um KDoc-Cross-Refs ergänzt.
Nach drei Multi-Agent-Doc-Reviews **grün** (Runde 3: 0 Pipeline-Funde, ein letzter KDoc-Nit —
`FavoritesRepositoryImpl.kt:72–89` semantisch statt nur umbenennen — eingearbeitet). Kern-Design
(F-C) und Swipe-Wert-Guard über zwei Runden ohne Soundness-Fund bestätigt.

Zwei offene Gabelungen, die **der Mensch vor der Umsetzung** entscheidet: `SPEC-DECISION F-1`
(F-C vs F-B, Empfehlung F-C) und `F-2` (atomar vs. add-alongside, Empfehlung atomar); siehe §8.

**Umsetzungs-Disziplin (bindend):** beim Übergang in den Code wird **erst gemergt, wenn alles
nachweislich grün ist** (alle drei Modul-Suiten + Linter) — kein Merge auf rotem/ungetestetem
Stand. Siehe §7.4.

---

## §0 Das Problem

### §0.1 Der Kern in einem Satz

> Der Veto berechnet die **Lösch-Kandidaten** aus einem *anderen* Read als dem, aus dem der
> Cleanup tatsächlich **löscht**. R-INV hält nur, wenn der Veto-Read eine **Obermenge** des
> Cleanup-Reads ist — und nichts erzwingt das.

Heutiger Ablauf in `ObserveInstalledAppsUseCase` (§3-as-built):

```
validComponents = realApps.componentNames
effectiveValidComponents = validComponents + vetoedComponents(...)   // Read A: Zuweisungen via .first()/getAllCustomNames
runCleanup { favoritesRepository.cleanupFavoriteComponents(effectiveValidComponents) }
                                                       //  ^ Read B: eigener frischer edit{}-Read, intersect
```

`vetoedComponents`/`vetoedPackages` (Read A) bilden die Kandidaten aus dem Zuweisungs-Bestand.
`cleanupX(effectiveValid)` (Read B) liest den **Live-Store frisch im `edit{}`** und entfernt
alles, was nicht in `effectiveValid` steht. Wenn Read A **weniger** Zuweisungen sieht als Read B,
wird die fehlende Zuweisung nie Kandidat → nie an `PackagePresence` übergeben → von Read B trotzdem
gelöscht. Der Fail-Safe sitzt **nur in `PackagePresence`** und nützt nichts für ein Ziel, das
`PackagePresence` nie erreicht.

### §0.2 Drei Wege, wie Read A kleiner wird als Read B (alle am Code bestätigt)

- **M1 — Fail-open (Favorites/Hidden/Swipe).** Read A geht über `favoriteComponentsFlow.first()`
  → `safeReadFlow`, das `IOException` mit `emit(emptyPreferences())` recovered
  (`DataStoreReadFlow.kt:31-39`). Ein transienter Read-Fehler zur Veto-Zeit ⇒ `assigned = {}` ⇒
  keine Kandidaten ⇒ `effectiveValidComponents = validComponents`. `cleanupFavoriteComponents`
  liest im eigenen `edit{}` den echten Bestand (`FavoritesRepositoryImpl.kt:236`) und löscht gegen
  die partielle Liste. **Read A fällt offen (leer), Read B fällt geschlossen (frisch) — die
  Asymmetrie IST der Bug.**

- **M2 — Swallow-to-empty (Custom Names).** Read A: `getAllCustomNames()` fängt *jedes*
  `Throwable` → `emptyMap()` (`CustomNamesRepositoryImpl.kt:217-233`, selbst verifiziert). Ein
  Read-Hiccup ⇒ keine Kandidaten ⇒ `reconcileCustomNames`… nein: `cleanupCustomNames`
  (`:266-289`) liest im eigenen `edit{}` und entfernt jede Custom-Name, deren Paket ∉
  `validPackages` — ohne `isPackagePresent`. Anders als der Flow-Pfad wirft `getAllCustomNames`
  **nie**, also läuft die Verarbeitung ungebremst in den listen-vertrauenden Delete.

- **H1 — Stale Replay / TOCTOU (high).** Read A nutzt `.first()` auf `WhileSubscribed(replay=1)`-
  Hot-Flows ohne `replayExpiration`; `.first()` liefert den zurückgehaltenen Replay-Wert vor dem
  frischen Re-Read. Die **Swipe-Flows haben keinen Dauer-Subscriber** (on-demand via
  `HandleSwipeActionUseCase`), ihr Replay hinkt dem Live-Store also routinemäßig nach. Der Codebase
  dokumentiert exakt diese `.first()`-Falle auf genau diesen Flows bereits in
  `BackupDataAssembler.kt:204-211` (AUDIT-9 #2) und vermeidet dort das bare `.first()` — der Veto
  führt es wieder ein. Zusätzlich: ein TOCTOU-Fenster über die N `isComponentPresent`-IO-Roundtrips
  zwischen Veto-Reads und Cleanup-Edits.

### §0.3 Auslösebedingung & Schweregrad

Der Verlust braucht die **Konjunktion**: (1) eine partielle installierte-Apps-Ladung (der
ursprünglich seltene Fall, den R-INV schützt) **und** (2) einen transienten Assignment-Read-Fehler
/ stale Replay / gleichzeitigen Write. Seltener als der Originalbug — aber es besiegt exakt die
Invariante, die der Rewrite verspricht, und der reinste Fall (M2) braucht **keine** Concurrency.

### §0.4 Was R-INV wirklich verlangt (und der Veto nicht liefert)

R-INV: *„Eine Zuweisung wird nur gelöscht, wenn ihr Ziel per `PackagePresence` verifiziert-abwesend
ist."* Das hält nur, wenn **jede** Zuweisung, die der Delete löschen könnte, vorher presence-geprüft
wurde. Der heutige Veto prüft aber nur die Kandidaten aus Read A; Read B kann eine echte Obermenge
sein. → R-INV verletzt.

---

## §1 Zielinvariante (verschärft): R-INV-2

> **R-INV-2:** Kandidaten-Read und Delete-Read sind **derselbe** Read, und der Delete entfernt
> **ausschließlich** Schlüssel, die zuvor einzeln durch `PackagePresence` als abwesend bestätigt
> wurden. Kann dieser Read nicht sauber gelesen werden, wird **nichts** gelöscht (fail-**closed**).

Zwei Zusätze zu R-INV:
1. **Eine Read-Autorität.** Es gibt keinen zweiten, unabhängigen „intersect gegen die Liste"-Read
   mehr. Der Delete operiert über exakt die Menge, die presence-geprüft wurde.
2. **Fail-closed statt fail-open.** Ist der Zuweisungs-Read unlesbar, überspringt der Reconcile
   diesen Store (löscht nichts), statt mit leerem Veto weiterzumachen.

---

## §2 Die Lösung

**Design F-C (empfohlen): pro-Store-Reconcile-Primitiv mit Presence-Prädikat, im Repository.**

Ersetze die vier listen-vertrauenden `cleanupX(installed)`-Methoden durch presence-gegatete
`reconcileX(installed, isStillPresent)`-Methoden. Der Store-Besitzer (das Repo) liest **einmal
fail-closed**, bildet die Orphans, prüft jeden per Prädikat und entfernt nur die verifiziert-
abwesenden — alles über *dieselbe* Read-Autorität.

Generischer Mechanismus (identisch für alle vier Stores, an die Store-Struktur angepasst):

```
1. current = <direkter, FAIL-CLOSED Read des Live-Stores>      // IOException propagiert, KEIN safeReadFlow/Swallow
2. orphans = current - installedSet                            // Kandidaten: zugewiesen, aber nicht in der Ladung
3. if (orphans.isEmpty()) return                               // Steady State: keine Arbeit, keine IO
4. verifiedAbsent = orphans.filterNot { isStillPresent(it) }   // presence-gegatet; Fail-Safe: present -> behalten
5. if (verifiedAbsent.isNotEmpty()) dataStore.edit { /* WERT-scoped entfernen, s.u. */ }
```

**Schritt 5 ist wert-scoped, nicht schlüssel-blind (kritisch, §5).** Das `edit{}` **re-liest** den
aktuellen Store und entfernt nur, was *jetzt noch* einem verifiziert-abwesenden Wert entspricht —
nie „was zu Schritt 1 im Slot stand". Für die Mengen-Stores (Favorites/Hidden) heißt das
`current_im_edit − verifiedAbsent`; für die Map (Custom Names) `remove(key)` je verifiziert-
abwesendem Paket (Key ≙ Wert). Für **Swipe** siehe die eigene Notiz unten — dort ist der Wert-Guard
nicht automatisch, weil der Key der Slot ist.

Jeder gelöschte Schlüssel war in `verifiedAbsent` → einzeln presence-geprüft, **und** sein Wert galt
noch im Moment des Deletes. R-INV-2 hält. Es existiert kein „intersect gegen die Liste" mehr.

**Eigenschaften:**
- **Eine Read-Autorität:** Schritt 1 (Orphan-Basis) und Schritt 5 (Delete) sind derselbe Store;
  gelöscht wird die in Schritt 1 bestimmte, presence-gefilterte Schlüsselmenge — nicht „was gerade
  nicht in der Liste steht".
- **Fail-closed:** Schritt 1 nutzt einen Read, der Fehler **propagiert** (nicht `safeReadFlow`,
  nicht `getAllCustomNames`). Wirft er, fängt das umgebende `runCleanup` (Store übersprungen,
  nichts gelöscht) — das ist das gewünschte Verhalten. `reconcileX` hat daher **keinen** eigenen
  Throwable-Swallow (nur `CancellationException` propagiert ohnehin).
- **Steady-State-Kosten unverändert:** `orphans` ist im Normalfall leer (Schritt 3) → keine
  `isStillPresent`-Aufrufe, kein `edit`. Genau wie beim heutigen Veto.
- **Repo bleibt `PackagePresence`-agnostisch:** es bekommt ein `suspend (String) -> Boolean`, kennt
  `PackagePresence` nicht.

**Der Use-Case** verliert die `vetoedComponents`/`vetoedPackages`-Helfer und die
`effectiveValid…`-Berechnung komplett und ruft nur noch:

```
val validComponents = realApps.map { it.componentName }
val validPackages   = realApps.map { it.packageName }
runCleanup("favorites")        { favoritesRepository.reconcileFavoriteComponents(validComponents) { packagePresence.isComponentPresent(it) } }
runCleanup("swipe actions")    { swipeActionsRepository.reconcileSwipeActions(validComponents)    { packagePresence.isComponentPresent(it) } }
runCleanup("hidden components"){ hiddenAppsRepository.reconcileHiddenComponents(validComponents)  { packagePresence.isComponentPresent(it) } }
runCleanup("custom names")     { customNamesRepository.reconcileCustomNames(validPackages)        { packagePresence.isPackagePresent(it) } }
```

Der Use-Case liest **keine** Zuweisungen mehr selbst → M1/M2/H1 verschwinden an der Wurzel, weil
die Divergenz zwischen zwei Reads nicht mehr existiert.

**Store-spezifische Notiz Swipe (der einzige Slot-Store — Vorsicht).** Swipe hat zwei Slots
(`swipeLeftApp`/`swipeRightApp`), keine Menge; der DataStore-**Key ist der Slot**, nicht der
Ziel-Wert (die Component). Das heutige `cleanupSwipeActions` liest den Slot-Wert **im `edit{}`** und
löscht atomar — es gibt heute kein TOCTOU-Fenster. F-C muss den Wert-Read für die Presence-Prüfung
**aus** der Transaktion ziehen (Prädikat ist `suspend`), also entsteht ein Read→Check→Edit-Fenster.
Deshalb **MUSS** `reconcileSwipeActions` im `edit{}` den Slot-Wert **erneut lesen** und den Slot nur
leeren, wenn `preferences[slot]` *dort noch* einer verifiziert-abwesenden Component gleicht:

```
edit { for slot in [LEFT, RIGHT]:
         val nowInSlot = preferences[slot]
         if (nowInSlot != null && nowInSlot in verifiedAbsentSet) preferences.remove(slot) }
```

Ein blindes `remove(LEFT)`, nur weil LEFT zu Schritt 1 abwesend war, würde eine im Fenster
gesetzte Neuzuweisung `LEFT = Y` (Y die ganze Zeit installiert) **überschreiben** — Y nie
presence-geprüft, R-INV verletzt (der Slot-spezifische Fund des Doc-Reviews). Der Wert-Guard stellt
die Atomizität wieder her, die der heutige In-Edit-Read schon hat.

Die Alternative (Design F-B: Orchestrierung im Use-Case mit `readX()`-+-`removeX(keys)`-Primitiven
statt Prädikat-im-Repo) steht in §8 als `SPEC-DECISION F-1`.

---

## §3 Betroffene Dateien & Deltas

| Datei | Delta |
|---|---|
| `domain/.../domain/usecase/ObserveInstalledAppsUseCase.kt` | `vetoedComponents`/`vetoedPackages` + `effectiveValid…` entfernen; die 4 `runCleanup`-Calls auf `reconcileX(valid) { presence }` umstellen; `first`-Import evtl. weg. `packagePresence` bleibt injiziert. |
| `domain/.../repository/FavoritesRepository.kt` | `cleanupFavoriteComponents(List)` → `reconcileFavoriteComponents(List, suspend (String)->Boolean)` |
| `domain/.../repository/SwipeActionsRepository.kt` | analog `reconcileSwipeActions(...)` |
| `domain/.../repository/HiddenAppsRepository.kt` | analog `reconcileHiddenComponents(...)` |
| `domain/.../repository/CustomNamesRepository.kt` | `cleanupCustomNames(List)` → `reconcileCustomNames(List, suspend (String)->Boolean)` |
| `data/.../FavoritesRepositoryImpl.kt` (+ Swipe/Hidden/CustomNames-Impls) | Impl nach dem §2-Mechanismus: fail-closed `dataStore.data.first()`, Orphan-Compute, Prädikat-Filter, gezielter Remove im `edit{}`; **kein** Throwable-Swallow. |
| `domain/src/testFixtures/.../fakes/Fake*Repository.kt` (4×) | `reconcileX` über den In-Memory-State (gleiche Semantik: Orphans − present). |
| `domain/src/testFixtures/.../*RepositoryContract.kt` (4×) | `cleanup*`-Contract-Methoden auf `reconcile*` mit Prädikat umstellen (siehe Test-Plan §6). |
| `app/src/test/.../ObserveInstalledAppsUseCaseTest.kt` — `TestFakeFavoritesRepository` (lokales Stub, ~:415) | Interface-Implementierer: `cleanupFavoriteComponents` → `reconcileFavoriteComponents`. **Zusätzlich** braucht das Stub jetzt echte prädikat-gegatete Entfernung (heute reiner Call-Tracker: `cleanupCallCount`/`lastCleanupComponentNames`, mutiert den Flow nie) — sonst kann der M3-Favorites-Fall (§6.3) „Orphan entfernt" nicht prüfen. `throwOnCleanup` bleibt (der Isolations-Test braucht es). |
| `domain/src/test/.../ToggleFavoriteUseCaseTest.kt` (~:208) | anonymer `FavoritesRepository`-Implementierer: `override cleanupFavoriteComponents` → `reconcileFavoriteComponents`. |
| `data/src/test/.../FavoritesRepositoryImplTest.kt` (~:129/:380/:404) + `CustomNamesRepositoryImplTest.kt` (~:243) | direkte Impl-Aufrufe `cleanup* → reconcile*` (Prädikat mitgeben). **Achtung:** `FavoritesRepositoryImplTest` „when DataStore edit fails - keeps current state" (~:387) pinnt den **heutigen Swallow**; §2 invertiert das (kein Swallow, propagiert) → dieser Test muss auf „wirft" umgestellt werden (Test-Plan §6.6). |
| **Neue** Impl-Edit-Fail-Tests (§6.6) | Swipe/Hidden/CustomNames haben heute **keinen** Cleanup-Edit-Fail-Test; je einen **neu** schreiben (assertThrows). `SwipeActionsRepositoryImplTest.kt` **existiert nicht** und wird neu angelegt (heute nur Contract-Test). |
| KDoc-Cross-Refs zum umbenannten `cleanupFavoriteComponents` | `CustomNamesRepository.kt:20`, `HiddenAppsRepository.kt:18`, `SwipeActionsRepository.kt:38` (`[FavoritesRepository.cleanupFavoriteComponents]`-@links) auf `reconcileFavoriteComponents` nachziehen (sonst tote KDoc-Links). |
| `FavoritesRepositoryImpl.kt:72–89` (Klassen-KDoc „Cleanup Mechanism" + „Error Handling") | **Nicht nur Umbenennung — semantisch neu schreiben:** die Zeilen `:73` „Taking intersection with currently installed components", `:76` „Failing gracefully on errors (keeps current state)" und `:89` „Cleanup failures preserve current state rather than clearing favorites" beschreiben das **alte** Verhalten, das F-C **umkehrt** (Orphans − presence-verifiziert-abwesend; fail-**closed** Read, propagiert, kein Swallow). KDoc-Block entsprechend anpassen. |

`PackagePresence`/`PackagePresenceImpl`/`FakePackagePresence` bleiben **unverändert** — sie waren
korrekt; der Bug lag ausschließlich in der Read-Divergenz eine Ebene darüber.

---

## §4 Fail-closed-Detail

- Der fail-closed Read ist `dataStore.data.first()` **direkt im `reconcileX`-Impl**, das
  `IOException` propagieren lässt (im Gegensatz zu `safeReadFlow` und `getAllCustomNames`).
- `getAllCustomNames()` bleibt für Backup/Export wie es ist (dort ist Swallow-to-empty korrekt);
  es wird nur **nicht mehr** als Reconcile-Kandidatenquelle benutzt.
- Wirft der Read (oder der spätere `edit`-Write) `IOException`, fängt `runCleanup` (Rule-9-konform,
  `silentError`, per-Store isoliert seit `0ca40ece`) → dieser Store wird diese Runde übersprungen,
  `updateApps` + `emit(Success)` laufen weiter. **Nichts wird gelöscht** — genau der fail-closed
  Ausgang.

---

## §5 Restfenster (TOCTOU) — warum es sicher ist

Zwischen Schritt 1 (Read) und Schritt 5 (Delete) läuft die Presence-Prüfung (suspend IO). Ein
gleichzeitiger Write kann in diesem Fenster passieren. Der Delete ist deshalb **wert-scoped im
`edit{}`** (§2 Schritt 5): er re-liest den aktuellen Store und entfernt nur, was *jetzt noch* einem
verifiziert-abwesenden Wert entspricht.

**Mengen-Stores (Favorites/Hidden) und Map-Store (Custom Names) — unbedingt sicher.** Der Delete ist
`current_im_edit − verifiedAbsent` bzw. `remove(key)` je abwesendem Paket. Eine gleichzeitig
**hinzugefügte** Zuweisung ist strukturell nicht in `verifiedAbsent` → unangetastet. Und einen
Schlüssel für eine *abwesende* App neu zu setzen ist praktisch unmöglich (man kann keine
deinstallierte App als Favorit/Hidden/Custom-Name setzen). Kein „setze auf berechnete Menge".

**Swipe-Store (Slot-keyed) — braucht den expliziten Wert-Guard, sonst NICHT sicher.** Hier ist der
Key der Slot, nicht der Wert; „entferne den verifiziert-abwesenden Slot" würde ohne Guard zu
„entferne, was gerade im Slot steht" degenerieren. Der gefährliche Fall ist **nicht** die
Neuzuweisung *derselben* abwesenden App, sondern die Neuzuweisung des Slots an eine **andere, die
ganze Zeit installierte** App Y im Fenster: `remove(LEFT)` würde Y clobbern, obwohl Y ∉
`verifiedAbsent` und nie presence-geprüft. Der In-Edit-Wert-Guard aus §2 (re-lies `preferences[slot]`,
leere nur bei Wert-Gleichheit mit einer verifiziert-abwesenden Component) schließt das — er stellt
exakt die Atomizität wieder her, die der heutige In-Edit-Read von `cleanupSwipeActions` schon hat.

Mit dem Wert-Guard ist das Restfenster für **alle** Stores harmlos — im Gegensatz zum heutigen
„intersect gegen die Liste", der eine ganze Live-Store-Momentaufnahme gegen eine möglicherweise
veraltete Kandidatensicht verrechnet.

---

## §6 Test-Plan

Regression-Guards für die drei Funde plus die zwei Test-Lücken:

1. **M1/M2 fail-closed (der Kern-Regressionstest):** Am Impl-Level: bei einem `reconcileX`, dessen
   Zuweisungs-Read `IOException` wirft, MUSS `reconcileX` die Exception **propagieren** (assertThrows)
   — **nicht** nur „Store unverändert" prüfen. Grund: ein fail-**open** Read (z. B. Wiederverwendung
   von `favoriteComponentsFlow.first()` über `safeReadFlow`) liefert bei Read-Fehler `emptySet()` →
   `orphans` leer → nichts gelöscht → Store *ebenfalls* unverändert. „Store unverändert" grün-lightet
   also genau die M1-Regression, die §2 verbietet; nur assertThrows unterscheidet fail-closed von
   fail-open. Dass der Wurf oben von `runCleanup` gefangen wird (Store übersprungen, nichts gelöscht),
   ist §4 — hier wird der **Read-Propagations-Vertrag** gepinnt, nicht der Ausgang.
2. **Presence-Veto positiv (verschärft):** Orphan, dessen `isStillPresent` `true` liefert, bleibt;
   Orphan mit `false` wird entfernt — pro Store, inkl. **Favorites** (schließt M3).
3. **M3 — Favorites-Veto-Zweig:** ein droppte-aber-vorhandenes Ziel, das **einem Favoriten**
   zugewiesen ist, überlebt eine partielle Ladung (der heute nie asserted Zweig).
4. **L1 — Event-Mapping:** je ein Test, der den emittierten `PackageEvent` für ein echtes
   `PACKAGE_ADDED` (`Added`) und ein echtes `PACKAGE_REMOVED` (`Removed`) prüft.
5. **Contract-Tripel** (`Fake*` + `*Impl`): `reconcileX(installed, predicate)` — mit `{ true }`
   wird kein Orphan entfernt, mit `{ false }` werden Orphans entfernt, leere Ladung /
   nicht-Orphan-Einträge bleiben. (Der fail-closed Read-Fehler ist Impl-only, da der Fake keinen
   Read-Fehler hat — dokumentiert wie die bestehende „Failure-Branch nur Impl"-Regel.)
6. **Edit-Fail wirft (Impl, alle vier Stores):** Heute schluckt jeder `cleanup*`-Impl einen
   `edit`-Fehler (`catch(Throwable) → silentError`, „keeps current state") — §2/§4 entfernen den
   Swallow, der Fehler propagiert. Nur **Favorites** hat dafür heute einen Test
   (`FavoritesRepositoryImplTest` „when DataStore edit fails - keeps current state", ~:387) — der
   wird auf **wirft** invertiert. Für **Swipe/Hidden/CustomNames existiert KEIN** solcher Test
   (Swipe hat gar **kein** Impl-Test-File) → je einen **neu hinzufügen**, der pinnt, dass
   `reconcileX` bei Edit-Fehler propagiert. (Alle vier in §3 aufgeführt.)
7. **Swipe-Wert-Guard (§2/§5) — der Clobber-Test, verpflichtend:** ein Test, bei dem das
   Presence-Prädikat (das zwischen Read und Edit läuft) im selben Store `setSwipeAction(LEFT, Y)`
   ausführt (Y die ganze Zeit installiert) und dann `false` für die abwesende X zurückgibt; gepinnt
   wird, dass `reconcileSwipeActions` den LEFT-Slot **behält** (Y nicht clobbert). Deterministisch,
   weil `FakeDataStore` Writes über einen Mutex serialisiert — der Prädikat-Write ist persistiert,
   bevor das Reconcile-`edit{}` den Slot re-liest. Der reine „Slot-Wert ≠ verifiziert-abwesend"-Zweig
   ist **kein** ausreichender Ersatz: ihn besteht auch eine blinde `remove(slot)`-Impl (ohne
   Fenster-Write gibt es nichts zu unterscheiden).

**Feasibility Fail-closed (§6.1):** der Fake kann keinen Read-Fehler simulieren; der assertThrows-
Kern-Test läuft daher am Impl-Level (echter DataStore, dessen Read `IOException` wirft). Eine
optionale Use-Case-Level-Variante braucht ein Fake, dessen `reconcileX` den Wurf durchreicht.

Der bestehende Kern-Veto-Test (`partial load vetoes a still-present app…`) wird auf die neue
`reconcileX`-Signatur portiert und um den **Favorites**-Fall (M3) erweitert — was voraussetzt, dass
das lokale `TestFakeFavoritesRepository` echte Entfernung bekommt (§3).

---

## §7 Umsetzungs-Reihenfolge

**Wichtig — kein grün-kompilierender Zwischenschritt bei reiner Signatur-Ersetzung.** Interface und
`ObserveInstalledAppsUseCase` liegen beide in `:domain`-main; §3 *ersetzt* `cleanupX` durch
`reconcileX` (kein Nebeneinander). Sobald das Interface umgestellt ist, brechen `:domain` (Use-Case-
Aufrufe) und `:data` (Impl-`override`s) **bis** Use-Case + alle Impls + alle Test-Implementierer
mitgezogen sind. Der Kern ist daher **ein atomarer Schritt**, nicht drei FF-mergebare Checkpoints.
Zusätzlich ist der Umschalt-Schritt ohnehin die **Verhaltensänderung** (fail-closed statt fail-open),
also nicht „verhaltensneutral".

1. **Ein Commit/Branch (atomar, kompiliert erst am Ende grün):** Interfaces + 4 Impls (§2) + 4
   Fakes + 4 Contracts + `ObserveInstalledAppsUseCase` (Veto-Helfer raus) + **alle** Test-Call-
   Sites/-Implementierer aus §3: das lokale `TestFakeFavoritesRepository` (Stub um echte
   prädikat-gegatete Entfernung **erweitert, nicht gelöscht** — `lastCleanupComponentNames`/
   `cleanupCallCount`/`throwOnCleanup` bleiben, die Konsumenten `:139`/`:155` + M3 brauchen es), die
   anonymen Implementierer, die direkten Impl-Aufrufe, und die KDoc-Cross-Refs (§3). Erst wenn alles
   zusammensteht, kompiliert der Baum.
2. Regressionstests §6.1–§6.7 ergänzen (Kern-fail-closed, Presence-Veto inkl. Favorites/M3,
   Event-Mapping/L1, invertierter Edit-Fail-Test, Swipe-Wert-Guard).
3. `RECONCILE_SPEC.md` §3/Umsetzung nachziehen (R-INV → R-INV-2, „eine Read-Autorität, fail-closed").
4. **Verifikation vor dem Merge (nicht verhandelbar):** alle drei Modul-Suiten +
   `checkConventions` + `checkRule13` **nachweislich grün** — erst dann FF-Merge. Kein Merge auf
   einem roten/ungetesteten Stand.

*(Alternative Reihenfolge, falls kleinere Commits gewünscht: `reconcileX` **zusätzlich** zu
`cleanupX` einführen, Caller migrieren, dann `cleanupX` löschen — das erlaubt grün-kompilierende
Zwischenstände, widerspricht aber der „ersetzen"-Formulierung in §3 und verdoppelt kurzzeitig die
Methoden. `SPEC-DECISION F-2`, s. §8.)*

---

## §8 Entscheidungen (getroffen)

- **`SPEC-DECISION F-1` — ENTSCHIEDEN: F-C** (Prädikat-im-Repo). Read+Delete beim Store-Besitzer;
  die Read-Divergenz wird strukturell unmöglich.
- **`SPEC-DECISION F-2` — ENTSCHIEDEN: atomar** (ein Commit, §7). Kompiliert erst am Ende grün;
  Merge nur bei nachweislich grünen Suiten + Linter.

Ursprüngliche Optionen zur Nachvollziehbarkeit:

- **`SPEC-DECISION F-1` (wo lebt die Reconcile-Logik).**
  - **F-C (empfohlen, §2):** Prädikat-im-Repo. `reconcileX(installed, isStillPresent)`, das Repo
    liest+prüft+löscht. Eine Methode pro Store, Read+Delete beim Store-Besitzer (beste
    Konsistenz), Repo bleibt PackagePresence-agnostisch.
  - **F-B (Alternative):** Orchestrierung-im-Use-Case. Repos bekommen `readX(): Set` (fail-closed)
    + `removeX(keys: Set)` (gezielt); der Use-Case bildet Orphans, prüft via `PackagePresence`,
    ruft `removeX`. Zwei Methoden pro Store, dünnere Repos, Reconcile-Logik zentral im Use-Case
    (leichter am Stück zu testen), aber ein separater Read/Remove statt einer Read-Autorität → das
    TOCTOU-Argument (§5) muss dort genauso greifen (Remove löscht nur konkrete verifiziert-abwesende
    Schlüssel — tut es). **Achtung:** F-B's `removeSwipe(...)` braucht denselben In-Edit-Wert-Guard
    wie §2/§5 (Slot-Wert re-lesen, nur bei Wert-Gleichheit leeren) — die Slot-vs-Wert-Falle gilt
    unabhängig davon, wo die Orchestrierung lebt.
  - Empfehlung: **F-C**, weil Read und Delete beim selben Store bleiben (die Divergenz, die den Bug
    verursachte, wird strukturell unmöglich) und weil die Store-Strukturen ohnehin verschieden sind
    (Set vs. Zwei-Slot vs. Map), also wenig DRY-Gewinn durch zentrale Orchestrierung.

- **`SPEC-DECISION F-2` (Umsetzungs-Form, §7).** Atomarer Ein-Commit-Umbau (empfohlen: klar, aber
  ein großer Commit, erst am Ende grün) **vs.** `reconcileX` zusätzlich einführen → Caller migrieren
  → `cleanupX` löschen (grün-kompilierende Zwischenstände, aber kurzzeitige Methoden-Verdopplung und
  Widerspruch zur „ersetzen"-Formulierung in §3). Empfehlung: **atomar**, da der Umbau ohnehin klein
  und in sich geschlossen ist und die Verhaltensänderung nicht sinnvoll teilbar ist.

---

## Anhang: was ich in `RECONCILE_SPEC.md` §3 falsch entschied

Beim Bau von Schritt 3 (Commit `7bd7a77e`) wählte ich bewusst „Approach a" (Veto im Use-Case,
Kandidaten aus Zuweisungs-Reads, `cleanup*` unverändert) gegenüber „Approach b" (Veto ins Repo mit
Prädikat). Die Begründung war Churn-Minimierung. Der Review zeigt, dass genau diese Vereinfachung
die Read-Divergenz einführte: der Use-Case-Read und der `cleanup*`-Read sind zwei verschiedene
Quellen. Der Fix ist im Kern „Approach b" (= F-C). Die Lehre: wenn Kandidaten-Auswahl und die
tatsächliche Mutation aus getrennten Reads stammen, muss man beweisen, dass der eine eine Obermenge
des anderen ist — sonst ist die Gate-Invariante nur scheinbar erfüllt.
