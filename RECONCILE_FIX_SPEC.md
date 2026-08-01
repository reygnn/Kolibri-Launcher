# RECONCILE_FIX_SPEC.md

**Fix-Spec für das R-INV-Loch im Verifikations-Veto (`RECONCILE_SPEC.md` §3, as-built).**

Der Multi-Agent-Code-Review des Reconcile-Rewrites (Range `33a09a89..7bd7a77e`, 12 Agents,
5 bestätigte Funde) hat einen realen Datenverlust-Pfad aufgedeckt, den der Veto **nicht**
schließt — er reintroduziert eine engere Variante genau der Klasse, die er beseitigen sollte.
Dieses Dokument beschreibt das Problem und legt den Fix fest. Es ist **Plan, noch kein Code**:
erst Doc-Review bis grün, dann Umsetzung nach §7.

**Status:** Entwurf zur Review. Eine offene Gabelung (`SPEC-DECISION F-1`) ist vor der Umsetzung
zu entscheiden; siehe §8.

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
5. if (verifiedAbsent.isNotEmpty()) dataStore.edit { entferne genau verifiedAbsent }
```

Jeder gelöschte Schlüssel war in `verifiedAbsent` → einzeln presence-geprüft. R-INV-2 hält. Es
existiert kein „intersect gegen die Liste" mehr.

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

**Store-spezifische Notiz Swipe:** Swipe hat zwei Slots (`swipeLeftApp`/`swipeRightApp`), keine
Menge. `reconcileSwipeActions` liest beide Slots fail-closed, bildet Orphans aus den belegten
Slots, presence-prüft, und leert nur verifiziert-abwesende Slots.

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
gleichzeitiger Write kann in diesem Fenster passieren. Warum das R-INV-2 nicht bricht:
- Gelöscht wird **ausschließlich** die in Schritt 4 bestimmte `verifiedAbsent`-Menge (konkrete,
  verifiziert-abwesende Schlüssel). Eine gleichzeitig **hinzugefügte** Zuweisung ist nicht in dieser
  Menge → unangetastet.
- Ein Schlüssel in `verifiedAbsent` wurde als *abwesende App* bestätigt; dass der Nutzer im ms-
  Fenster genau für diese abwesende App eine Zuweisung setzt, ist praktisch unmöglich (man kann
  keine deinstallierte App als Favorit/Swipe setzen).
- Der Delete ist ein gezieltes `- verifiedAbsent` innerhalb `edit{}` (transaktional), kein
  „setze auf berechnete Menge", also kann er keinen fremden gleichzeitigen Write überschreiben.

Damit ist das Restfenster harmlos — im Gegensatz zum heutigen „intersect gegen die Liste", der eine
ganze Live-Store-Momentaufnahme gegen eine möglicherweise veraltete Kandidatensicht verrechnet.

---

## §6 Test-Plan

Regression-Guards für die drei Funde plus die zwei Test-Lücken:

1. **M1/M2 fail-closed (der Kern-Regressionstest):** Bei einem Reconcile, dessen Zuweisungs-Read
   `IOException` wirft, während die Ladung partiell ist (Ziel-App fehlt), darf **nichts** gelöscht
   werden. Am Impl-Level (DataStore-Read wirft) oder Use-Case-Level (Fake-Repo, dessen `reconcileX`
   den fail-closed Read simuliert werfen lässt) — der Store bleibt unverändert, R-INV-2 hält.
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

Der bestehende Kern-Veto-Test (`partial load vetoes a still-present app…`) wird auf die neue
`reconcileX`-Signatur portiert und um den Favorites-Fall (M3) erweitert.

---

## §7 Umsetzungs-Reihenfolge

1. Interfaces + Fakes + Contract-Umstellung `cleanup* → reconcile*` (mechanisch, kompiliert).
2. Ein Impl nach §2 (z. B. Favorites), Contract grün, dann die anderen drei.
3. `ObserveInstalledAppsUseCase` auf `reconcileX` umstellen, Veto-Helfer entfernen.
4. Regressionstests §6.1–§6.4 ergänzen.
5. `RECONCILE_SPEC.md` §3/Umsetzung nachziehen (R-INV → R-INV-2, „eine Read-Autorität, fail-closed").
6. Verifikation: alle drei Modul-Suiten + `checkConventions` + `checkRule13` grün.

Jeder Schritt eigener Branch + FF-Merge + Checkpoint, wie in der Serie üblich. Kein Schritt lässt
das System in einem schlechteren Zustand als heute (Schritt 1-2 sind verhaltensneutral, bis der
Use-Case in Schritt 3 umschaltet).

---

## §8 Offene Entscheidung

- **`SPEC-DECISION F-1` (wo lebt die Reconcile-Logik).**
  - **F-C (empfohlen, §2):** Prädikat-im-Repo. `reconcileX(installed, isStillPresent)`, das Repo
    liest+prüft+löscht. Eine Methode pro Store, Read+Delete beim Store-Besitzer (beste
    Konsistenz), Repo bleibt PackagePresence-agnostisch.
  - **F-B (Alternative):** Orchestrierung-im-Use-Case. Repos bekommen `readX(): Set` (fail-closed)
    + `removeX(keys: Set)` (gezielt); der Use-Case bildet Orphans, prüft via `PackagePresence`,
    ruft `removeX`. Zwei Methoden pro Store, dünnere Repos, Reconcile-Logik zentral im Use-Case
    (leichter am Stück zu testen), aber ein separater Read/Remove statt einer Read-Autorität → das
    TOCTOU-Argument (§5) muss dort genauso greifen (Remove löscht nur konkrete verifiziert-abwesende
    Schlüssel — tut es).
  - Empfehlung: **F-C**, weil Read und Delete beim selben Store bleiben (die Divergenz, die den Bug
    verursachte, wird strukturell unmöglich) und weil die Store-Strukturen ohnehin verschieden sind
    (Set vs. Zwei-Slot vs. Map), also wenig DRY-Gewinn durch zentrale Orchestrierung.

---

## Anhang: was ich in `RECONCILE_SPEC.md` §3 falsch entschied

Beim Bau von Schritt 3 (Commit `7bd7a77e`) wählte ich bewusst „Approach a" (Veto im Use-Case,
Kandidaten aus Zuweisungs-Reads, `cleanup*` unverändert) gegenüber „Approach b" (Veto ins Repo mit
Prädikat). Die Begründung war Churn-Minimierung. Der Review zeigt, dass genau diese Vereinfachung
die Read-Divergenz einführte: der Use-Case-Read und der `cleanup*`-Read sind zwei verschiedene
Quellen. Der Fix ist im Kern „Approach b" (= F-C). Die Lehre: wenn Kandidaten-Auswahl und die
tatsächliche Mutation aus getrennten Reads stammen, muss man beweisen, dass der eine eine Obermenge
des anderen ist — sonst ist die Gate-Invariante nur scheinbar erfüllt.
