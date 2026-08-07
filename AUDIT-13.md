# AUDIT-13 — Die „non-negotiable" Dispatcher-Regel widerspricht dem eigenen Referenzdokument (quellenverifiziert)

> **Erzeugt** 2026-08-06 gegen `main` @ `db2840b9` (Version 0.99.148 / code 168),
> als Nebenfund bei der Untersuchung eines roten CI-Laufs.
> **Fokus:** eine einzige Aussage — CLAUDE.md erklärt
> `runTest(mainDispatcherRule.dispatcher)` für „non-negotiable" und begründet das
> mit einem Flakiness-Mechanismus, den es in diesem Projekt nicht gibt.
> **Methode:** Auszählung über alle Test-Sourcesets, danach **drei empirische
> Proben** als Wegwerf-Tests gegen die reale Toolchain (der erste Probenentwurf
> war selbst falsch — siehe „Methodische Notiz"), zuletzt Abgleich mit
> `TESTING_CONVENTIONS.kt`.
>
> **Kein Code-Defekt.** Es ist ein Dokumentationsdefekt. Nichts an der Testsuite
> ist kaputt, nichts muss repariert werden. Offen ist eine redaktionelle
> Entscheidung.

---

## Der Widerspruch

Zwei Dokumente desselben Projekts sagen Gegenteiliges über dieselbe Sache.

**`CLAUDE.md:736-753`** — Abschnitt „The dispatcher rule (non-negotiable)":

```
@Test fun whatever() = runTest(mainDispatcherRule.dispatcher) { … }
```
> Plain `runTest { }` creates its own `TestCoroutineScheduler`, so
> `advanceUntilIdle()` / `advanceTimeBy()` will not drive code that runs on
> `Dispatchers.Main`. Tests become flaky.

**`TESTING_CONVENTIONS.kt:44-46`** — Regel 3 der maßgeblichen technischen
Referenz:

> 3. **Top-level runTest**: Use `= runTest { }`, not `= testScope.runTest { }`.
>    The runTest block shares the scheduler with MainDispatcherRule's dispatcher.

Die Referenz schreibt genau die Form vor, die CLAUDE.md verbietet — und nennt
als Begründung genau den Mechanismus, dessen Nichtexistenz CLAUDE.md behauptet.

Der Code folgt der Referenz.

---

## Zahlen

Über alle Test-Sourcesets (`app/src/test`, `app/src/testDebug`, `data/src/test`,
`domain/src/test`, `domain/src/testFixtures`):

| | |
|---|---|
| `runTest { }` (plain) | **1424** |
| `runTest(…)` (parametrisiert) | **128** |
| davon `runTest(mainDispatcherRule.testDispatcher)` | 106 |
| davon `runTest(testDispatcher)` | 22 |
| Dateien, die `MainDispatcherRule` deklarieren | 55 |
| `MainDispatcherRule`-Definitionen im Projekt | 1 (`domain/src/testFixtures/…/rule/`) |

**Die „non-negotiable" Regel wird in rund 8 % der Fälle befolgt.** Die Suite ist
dabei durchgehend grün und nicht flaky: 2432 Tests, 0 Failures (app 1208 /
data 859 / domain 365).

---

## Empirische Verifikation

Ich wollte die Behauptung nicht aus Bibliothekswissen widerlegen, sondern
messen. Drei Wegwerf-Tests gegen die reale Toolchain, alle drei **grün**,
danach gelöscht.

**Probe A — Identität des Schedulers.** Innerhalb eines plain `runTest { }`:

```kotlin
assertSame(mainDispatcherRule.testDispatcher.scheduler, testScheduler)
```

Grün. `runTest` baut **keinen** eigenen Scheduler, sondern übernimmt den, der
über `Dispatchers.setMain` installiert ist.

**Probe B — das Verhalten, um das es der Regel eigentlich geht.** Virtuelle Zeit
im plain `runTest { }` gegen eine Coroutine auf `Dispatchers.Main`:

```kotlin
var done = false
CoroutineScope(Dispatchers.Main).launch { delay(1_000); done = true }
advanceTimeBy(1_001)
assertTrue(done)
```

Grün. `advanceTimeBy` erreicht die Main-dispatchte Coroutine. Bei getrennten
Schedulern bliebe `done` false — das ist exakt der Fall, den CLAUDE.md
beschreibt und den es hier nicht gibt.

**Probe C — hält das auch mit `StandardTestDispatcher`?** Relevant für die
Frage, ob die Regel wenigstens als Vorsichtsmaßnahme gegen einen späteren
Wechsel taugt:

```kotlin
val std = StandardTestDispatcher()
Dispatchers.setMain(std)
runTest { assertSame(std.scheduler, testScheduler) }
```

Grün. Das Sharing hängt an `setMain(TestDispatcher)`, nicht am Dispatcher-Typ.
**Damit trägt auch das „als Defensive behalten"-Argument nicht.**

### Methodische Notiz

Der erste Probenentwurf war falsch und schlug fehl:

```kotlin
(Dispatchers.Main as TestDispatcher).scheduler   // ClassCastException
assertSame(mainDispatcherRule.testDispatcher, Dispatchers.Main)  // AssertionError
```

Nach `setMain` ist `Dispatchers.Main` **nicht** der übergebene Dispatcher,
sondern ein interner `TestMainDispatcher`-Wrapper, der an ihn delegiert. Die
Korrektur führt über `.scheduler` statt über Identität des Dispatchers. Der
Fehlschlag ist hier dokumentiert, weil er zeigt, warum die Behauptung gemessen
und nicht aus dem Gedächtnis beantwortet gehört.

---

## Das Codebeispiel in CLAUDE.md kompiliert nicht

`CLAUDE.md:744` schreibt:

```kotlin
@Test fun whatever() = runTest(mainDispatcherRule.dispatcher) { … }
```

`MainDispatcherRule` exponiert aber `testDispatcher`, nicht `dispatcher`:

```kotlin
class MainDispatcherRule : TestWatcher() {
    val testDispatcher = UnconfinedTestDispatcher()
```

Es gibt keinen Alias. **Das kanonische Beispiel der Regel würde nicht
übersetzen.** Die 106 Stellen, die die Regel tatsächlich befolgen, schreiben
korrekt `.testDispatcher` — sie stammen also aus dem Code, nicht aus diesem
Dokument. Ein stärkeres Indiz dafür, dass die Regel nie aus CLAUDE.md heraus
angewandt wurde, gibt es kaum.

---

## Woher der Irrtum stammt

Der historische Vorfall ist real und in `TESTING_CONVENTIONS.kt:10-16`
protokolliert — nur war er ein anderer:

> - MainDispatcherRule created an UnconfinedTestDispatcher
> - @Before created a separate StandardTestDispatcher
> - advanceUntilIdle() only advanced one of them
> - Result: 100% test failure

Der Defekt war **zwei manuell erzeugte Dispatcher-Instanzen nebeneinander**,
nicht `runTest` gegen die Rule. CLAUDE.md hat diesen Vorfall zu „plain `runTest`
baut seinen eigenen Scheduler" verkürzt — eine Verallgemeinerung, die den
eigentlichen Mechanismus (nie eine zweite Dispatcher-Instanz erzeugen, Regel 1
und 2 der Referenz) durch eine falsche Ursache ersetzt.

Die Konsequenz ist nicht harmlos: Wer der falschen Ursache folgt, hält
`runTest(dispatcher)` für den Schutz und übersieht, dass ein zweiter
`StandardTestDispatcher()` im `@Before` den Test **trotzdem** zerlegt.

---

## Bewertung

| | |
|---|---|
| Schweregrad | `low` — kein Laufzeit- oder Testdefekt |
| Klasse | Dokumentationsdefekt mit falscher Kausalaussage |
| Blast Radius | die 1424 Aufrufstellen wären bei wörtlicher Durchsetzung alle „Verstöße" |
| Risiko bei Nichtstun | ein Beitragender folgt der falschen Ursache und schützt sich gegen das falsche Problem |

---

## Optionen

**A — CLAUDE.md an die Referenz angleichen (empfohlen).** Den Abschnitt auf das
korrigieren, was gilt und was tatsächlich schützt: *eine* Dispatcher-Quelle
(`mainDispatcherRule.testDispatcher`), niemals eine zweite Instanz, plain
`runTest { }` ist korrekt und teilt den Scheduler. Verweis auf
`TESTING_CONVENTIONS.kt` statt Neuformulierung. Der Widerspruch verschwindet,
die echte Lehre bleibt erhalten.

**B — Regel beibehalten und den Bestand angleichen.** 1424 Aufrufstellen
umschreiben, für einen Schutz, den Probe C als gegenstandslos ausweist. Nicht
empfohlen.

**C — Nichts tun.** Der Widerspruch bleibt; die nächste Person, die beide
Dokumente liest, verliert Zeit an derselben Stelle wie diese Untersuchung.

Mindestens zu korrigieren ist in jedem Fall das nicht übersetzende
`.dispatcher` in `CLAUDE.md:744`.

---

## Kein Linter

Bewusst **kein** Gate gebaut. Eine Regel, die 1424 von 1552 Aufrufstellen
flaggt, ist kein Gate, sondern ein Rewrite-Auftrag — und sie würde die falsche
Eigenschaft erzwingen. Das prüfbare Kriterium wäre ein anderes: *„eine zweite
Dispatcher-Instanz in einer Datei, die bereits `MainDispatcherRule`
deklariert"*. Das ist der Defekt, der die Testsuite historisch zerlegt hat, es
ist eine präzise Form im Sinne der übrigen Checks, und der Bestand ist heute
sauber. Kandidat für eine spätere Sitzung, nicht Teil dieses Audits.

---

## Re-Evaluierungs-Trigger

- **kotlinx-coroutines-test wird angehoben.** Das Scheduler-Sharing ist
  Verhalten der Bibliothek, kein Sprachgarantie. Probe A und B sind billig
  genug, um sie nach jedem Upgrade der Test-Coroutines einmal laufen zu lassen.
- **`MainDispatcherRule` wird geändert** (anderer Dispatcher-Typ, eigener
  Scheduler im Konstruktor). Ein explizit übergebener Scheduler wäre eine
  andere Ausgangslage als Probe C.
- **Ein Test wird doch flaky** in einer Weise, die nach Zeitsteuerung riecht.
  Dann zuerst nach einer zweiten Dispatcher-Instanz suchen — nicht nach plain
  `runTest`.

---

# Anhang — CI-Änderungen sind nicht vorab testbar (`workflow_dispatch`)

> Andere Defektklasse als der Hauptbefund, hier festgehalten, weil sie aus
> derselben Untersuchung stammt: der rote CI-Lauf, der zu diesem Audit führte.
> Kein Testthema, sondern Werkzeug-Ergonomie.

## Der Befund

`.github/workflows/android.yml` kennt genau zwei Trigger:

```yaml
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
```

Ein Push auf einen Feature-Branch löst **nichts** aus. Und weil dieses Projekt
solo ohne Pull Requests arbeitet (Branches gehen per Fast-Forward direkt auf
`main`), greift auch der zweite Trigger praktisch nie.

**Folge: eine Änderung am Workflow lässt sich erst validieren, nachdem sie auf
`main` liegt.** Der Merge ist der Test. Geht die Änderung schief, ist `main`
bereits rot, und die Korrektur braucht denselben Weg noch einmal.

## Warum das gerade jetzt konkret ist

Commit `d476dd0e` hebt vier Action-Majors an — `checkout` v4→v7, `setup-java`
v4→v5, `upload-artifact` v4→v7, `codecov-action` v4→v7. Die Input-Kompatibilität
ist gegen die `action.yml` der jeweiligen Ziel-Version geprüft (jeder
übergebene Input existiert dort), aber **die Runtime hat noch kein einziges Mal
laufen dürfen**: seit dem Push steht GitHub Actions auf `major_outage`, kein
Job wird von einem Hosted Runner angenommen. Der Lauf zu `db2840b9` ist ohne
einen einzigen ausgeführten Step gescheitert.

Es gibt derzeit also keine Möglichkeit, die Bumps zu prüfen, ohne auf GitHub zu
warten — und selbst danach nur, indem man ihnen auf `main` beim Laufen zusieht.

## Vorschlag

```yaml
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
  workflow_dispatch:
```

Danach ist ein Lauf auf beliebigem Ref manuell auslösbar:

```bash
gh workflow run android.yml --ref chore/mein-ci-branch
```

**Einschränkung, die den Nutzen zeitlich verschiebt:** GitHub blendet
`workflow_dispatch` erst ein, wenn der Trigger auf dem **Default-Branch**
liegt. Ein heute hinzugefügter Trigger hilft also nicht mehr für die aktuellen,
unvalidierten Bumps — er wirkt ab der nächsten CI-Änderung. Das ist kein
Argument dagegen, nur gegen die Erwartung, das aktuelle Problem damit zu lösen.

## Bewertung

| | |
|---|---|
| Schweregrad | `low` — Ergonomie, kein Defekt |
| Kosten | eine Zeile |
| Nutzen | CI-Änderungen vor dem Merge prüfbar, statt `main` als Testumgebung zu benutzen |
| Wirksam ab | der nächsten Änderung nach dem Landen auf `main` |

Bewusst **nicht** ungefragt eingebaut, als die Action-Bumps entstanden — eine
Trigger-Erweiterung verändert, wer den Workflow unter welchen Umständen starten
kann, und gehörte damit nicht in einen Commit, der etwas anderes tut.

---

# Anhang — Flows mit `replay` im Repo

> Reine Bestandsaufnahme, kein Befund. Auf Nachfrage erhoben: welche Flows im
> Produktionscode (`*/src/main`) einen Replay-Cache tragen. Zwei Klassen:
> explizit über einen `replay`-Parameter konfiguriert, und implizit (jeder
> `StateFlow`/`stateIn` repliziert per Definition seinen aktuellen Wert,
> `replay = 1`).

## Explizit `replay = N` konfiguriert

| Flow | Ort | replay | Anmerkung |
|---|---|---|---|
| `ErrorEventBus._events` | `app/…/ui/util/ErrorEventBus.kt:7` | **5** | `+ extraBufferCapacity = 10`; puffert Fehler-Events, die vor Existenz einer Activity gepostet werden (`BaseActivity.kt:32`) |
| `refreshTrigger` | `domain/…/usecase/ObserveTimeBasedEventsUseCase.kt:20` | **1** | `MutableSharedFlow<Unit>(replay = 1)` |
| `shareInOrRaw(…)`-Tail | `data/…/DataStoreReadFlow.kt:48-54` | **1** | Default-Param `replay: Int = 1`, durchgereicht an `shareIn(…, replay = replay)`; gemeinsamer Hot-Share aller `SharedDataStoreFlowRepository`-Abkömmlinge |
| `provideAppsUpdateTrigger` | `app/…/di/AppUpdateModule.kt:18` | **0** | Kein Cache — `replay = 0, extraBufferCapacity = 1`; reiner Event-Trigger, hier nur der Vollständigkeit halber |

## Implizit `replay = 1` (StateFlow / stateIn)

Jeder `StateFlow` hält seinen aktuellen Wert vor und gibt ihn an jeden
Neu-Kollektor aus — das ist ein Replay-Cache der Größe 1. Ebenso jedes
`.stateIn(…)`.

**`.stateIn(…)`-Hot-Shares** (`WhileSubscribed(FLOW_SHARING_TIMEOUT_MS)`):

- `data/…/InstalledAppsRepositoryImpl.kt:122`
- `app/…/ui/main/delegate/LayoutDelegate.kt` — 5 Stellen (`:51,63,75,87,99`)
- `app/…/ui/main/delegate/WallpaperDelegate.kt:218`
- `app/…/ui/main/LauncherViewModel.kt:246`

**`shareIn(…)`-Hot-Shares** (`WhileSubscribed`, über `SharedDataStoreFlowRepository`):

- `data/…/FavoritesRepositoryImpl.kt:125`
- `data/…/FavoritesOrderRepositoryImpl.kt:175`
- `data/…/FabPositionRepositoryImpl.kt:66`

**`MutableStateFlow`-Felder** (jeweils `replay = 1` inhärent) — u.a.:
`SystemWallpaperColorsSignal`, `InstalledAppsStateRepositoryImpl._rawAppsFlow`,
`ThemingDelegate` (2), `ClockDelegate` (5), `AppManagementDelegate` (2),
`WallpaperDelegate` (3), sowie die UI-State-/Suchfeld-Flows sämtlicher
ViewModels und Activities (`CustomNames`, `Onboarding`, `Settings`,
`HiddenApps`, `SwipeActions`, `Backup`, `UsageExport`, `FavoritesSort` …).

## Bewusst OHNE replay

- `AppUpdateSignal._events` (`domain/…/core/AppUpdateSignal.kt:24`) —
  `extraBufferCapacity = 1`, **kein** replay. Ein lifecycle-unabhängiger
  Producer (BroadcastReceiver) darf einem später eintreffenden Kollektor kein
  veraltetes Package-Event nachliefern; der Buffer verhindert nur den Drop bei
  fehlendem Subscriber (siehe CLAUDE.md, „Event-bus SharedFlows must be
  buffered").
- `BaseViewModel`-Event-Kanal — bewusst ein `Channel`, **nicht** `replay = 1`
  (Kommentar `BaseViewModel.kt:33-39`): One-time-UI-Events dürfen sich nicht
  wiederholen.
- **Swipe-Actions (links/rechts)** — hatten früher ebenfalls einen Replay-Flow
  (`swipeLeftAppFlow`/`swipeRightAppFlow`, `shareIn(replay=1, WhileSubscribed(5000))`);
  entfernt, weil er einen echten Bug verursachte. Erklärung unten.

## Historischer Fehler — der Replay-Cache der Swipe-Actions

Die Swipe-Ziele wurden bis Anfang August 2026 über einen heißen
`shareIn(replay = 1, WhileSubscribed(5000))`-Flow gelesen. Das erzeugte einen
sichtbaren Bug: **nach dem Zuweisen einer neuen App an einen Swipe-Slot startete
der erste Swipe noch die alte App, erst der zweite die neue.**

Ursache (Commit `f1a1464c`): `HandleSwipeActionUseCase` las das Ziel per
`.first()` auf dem geteilten Replay-Flow. Der Home-Screen hält aber **keinen
dauerhaften Subscriber**, und die Zuweisung passiert in einer *separaten*
Activity. Der Write erreichte zwar den DataStore, wurde vom SharedFlow ohne
Subscriber jedoch nie beobachtet → der Replay-Cache blieb **alt**. Der erste
Swipe bekam synchron den veralteten Replay-Wert; genau diese neue Subscription
triggerte dann den DataStore-Read, der den Cache auffrischte — deshalb lieferte
erst der *zweite* Swipe die richtige App.

Das ist die generische Falle eines `WhileSubscribed`-Replay-Caches: ohne warmen
Subscriber liefert er nicht den aktuellen Zustand, sondern den letzten
*beobachteten* — für einen **Entscheidungs-Pfad** (welche App starten?) falsch,
denn der braucht die aktuelle Wahrheit, nicht einen zwischengespeicherten
UI-Wert.

Fix (`f1a1464c`): autoritative Frisch-Lesung. Neue Methode
`getSwipeActionComponent(slot)` liest direkt aus dem Store
(`dataStore.data.first()`) statt aus dem UI-Replay-Cache — dasselbe Prinzip wie
bei den Favoriten-Fresh-Reads (`FavoritesRepositoryImpl.kt:238`). `IOException`
kollabiert non-destruktiv zu `null` (lieber kein Launch als der falsche),
Cancellation propagiert. Nachfolge-Commit `534f5a2b` entfernte die dann toten
`swipeLeftAppFlow`/`swipeRightAppFlow` samt `SharedDataStoreFlowRepository`-Basis
ganz — der Konstruktor kollabierte auf `@Inject constructor(dataStore)`. Die
Restspur im Code ist der KDoc-Kommentar in `SwipeActionsRepositoryImpl.kt:27-29`.

## Noch offene Flows mit demselben Fehlerpotenzial

Frage: welcher der **verbliebenen** Flows kann denselben Fehler provozieren wie
der entfernte Swipe-Flow? Das Swipe-Muster braucht drei Zutaten gleichzeitig:

1. eine `.first()`-**Punktlesung** (kein kontinuierliches Collect)
2. auf einem **hot-shared** Flow mit Replay-Cache
   (`shareIn/stateIn(replay=1, WhileSubscribed(5000), replayExpiration=∞)`)
3. aus einem Kontext **ohne garantiert warmen Subscriber** (separate Activity /
   Hintergrund-Write) — nur dann ist der Cache stale statt frisch.

Kalte `dataStore.data.first()`-Lesungen erfüllen (2) nicht (frischer Disk-Read)
und sind damit ungefährlich. Damit bleiben genau **zwei** Consumer mit exakt dem
Swipe-Muster übrig — beide auf den **Favoriten-Hot-Shares**, deren Konfiguration
mit dem entfernten Swipe-Flow identisch ist:

| Ort | Flow | Warum gefährdet |
|---|---|---|
| `SettingsFragment.showSortFavoritesFragment()` (`:786`, `:803`) | `favoriteComponentsFlow.first()` + `favoriteComponentsOrderFlow.first()` | Settings ist eine **separate Activity** → MainActivity STOPPED → kein warmer Subscriber auf dem Home-Hot-Share → Replay-Cache eingefroren; die Sortier-Auswahl arbeitet auf einem stale Favoriten-Set/-Order |
| `OnboardingViewModel` EDIT_FAVORITES (`:114`) | `getFavoriteComponentsUseCase()` → `favoriteComponentsFlow.first()` | ebenfalls separate Activity → gleiche kalte Lesung des Hot-Shares → falsche Vorauswahl der aktuellen Favoriten |

**Auslöser** (analog Swipe): ein Favoriten-Write **ohne warmen Home-Subscriber** —
klarster Fall ein Backup-**Restore** (schreibt direkt in den DataStore, während
der User im Backup-Screen ist). Danach Settings→„Favoriten sortieren" bzw.
Onboarding→Favoriten-Edit → Snapshot auf dem **alten** Set/Order, bis Home
erneut subscribed und den Cache auffrischt.

**Bewusst NICHT gefährdet:**

- `ToggleFavoriteUseCase.kt:44` / `isFavoriteComponent` (`FavoritesRepositoryImpl.kt:214`)
  lesen zwar den Hot-Flow, laufen aber aus `AppManagementDelegate` im **Home**-Scope
  (warmer Subscriber lebt); der eigentliche Write nutzt ohnehin frisches
  `dataStore.data.first()` (`:266`).
- Die `stateIn`-Flows (InstalledApps `:122`, LayoutDelegate ×5, WallpaperDelegate
  `:218`, LauncherViewModel `:246`) und `FabPositionRepositoryImpl` (`:66`) werden
  **kontinuierlich kollektiert**, nie per `.first()` auf einem Entscheidungspfad.
- Alle `settingsRepository.*Flow.first()` und `hiddenAppsFlow.first()` sind
  **kalte** Flows (bestätigt in `BackupDataAssembler.kt:82`: „COLD flows whose
  `.first()` is already a fresh disk read").

**Bereits abgedichtet, aber unvollständig:** Die impl-internen Schreib- und
Backup-Export-Pfade der Favoriten wurden schon auf autoritative Fresh-Reads
umgestellt (`FavoritesRepositoryImpl.kt:241,266`,
`FavoritesOrderRepositoryImpl.kt:298`, `BackupDataAssembler.kt:250-253`). Die
Sanierung hat aber genau die zwei **fremden UI-Consumer** oben übersehen —
dieselbe Klasse Bug wie Swipe, nur noch nicht gezogen. Fix wäre dieselbe
Bewegung: eine autoritative `getFavoriteComponents()` / `getFavoritesOrder()`
Repo-Methode (fresh `dataStore.data.first()`), von diesen beiden Aufrufern statt
des Hot-Flows genutzt.

## Plan — saubere Umsetzung des Fix

**Branch:** `fix/favorites-stale-replay` (mehrere Dateien, als Einheit
revertierbar → eigener Branch, kein `main`).

### Ausgangslage (verifiziert — keine Arbeit nötig)

Der Fix braucht **keine neue Repo-API und keine neuen Contract-Tests**. Die
autoritativen Methoden existieren bereits (für den Backup-Pfad angelegt):

- `FavoritesRepository.getFavoriteComponentsSnapshot(): Set<String>`
- `FavoritesOrderRepository.getFavoriteComponentsOrderSnapshot(): List<String>`

Beide sind über das Contract-Triple abgesichert (Fake + Impl + je ein
`reflects the LATEST …, never a previous one`-Test in
`FavoritesRepositoryContract.kt:103` / `FavoritesOrderRepositoryContract.kt:255`).
Die Fakes implementieren sie (`FakeFavoritesRepository.kt:59`,
`FakeFavoritesOrderRepository.kt:48`).

**Semantik-Abgleich (entscheidend für „behavior-preserving"):** In RELEASE liefert
`favoriteComponentsFlow.first()` bei `IOException` bereits `empty` (via
`sharedReadFlow`/`DataStoreReadFlow`-`.catch`) und propagiert alles andere +
`CancellationException`. `getFavoriteComponentsSnapshot()` tut **exakt dasselbe**
(`empty` bei `IOException` mit `Timber.w`, `throw` bei `CancellationException`,
Propagation sonst) — Unterschied nur: es umgeht den Replay-Cache. Der Tausch
ändert also **kein** Fehlerverhalten, nur die Staleness.

### Kein Flow-Abbau (Unterschied zum Swipe-Flow)

Anders als beim Swipe-Flow **bleiben beide Favoriten-Flows nötig** und werden
**nicht** entfernt. Der Swipe-Slot wurde nur je punktuell entschieden
(Launch-Zeitpunkt), nie dauerhaft angezeigt → nach dem Fresh-Read-Umstieg blieb
kein Kollektor → der Flow war tot und entfernbar. Die Favoriten dagegen werden
**permanent gerendert**: `GetFavoriteAppsUseCase.favoriteApps` (`:66-95`)
`combine`t `favoriteComponentsFlow` **und** `favoriteComponentsOrderFlow`
laufend — das ist der warme Subscriber und der Existenzgrund des
`WhileSubscribed`-Hot-Share. Weitere verbleibende Consumer:
`ToggleFavoriteUseCase:44`, `isFavoriteComponent` (`FavoritesRepositoryImpl:214`),
`BackupDataAssembler:253` (Fallback). Der Fix ist damit **additiv** (Snapshot für
die punktuellen Fremd-Lesungen), keine Streichung.

### Änderungen

**1. `domain/…/usecase/GetFavoriteComponentsUseCase.kt`** — Body von
`favoritesRepository.favoriteComponentsFlow.first()` auf
`favoritesRepository.getFavoriteComponentsSnapshot()` umstellen. KDoc (Englisch)
präzisieren: autoritative Frisch-Lesung, umgeht den Replay-Cache, aus
Nicht-Home-Kontexten (Onboarding-Edit) aufrufbar. — Einziger Aufrufer ist
`OnboardingViewModel:114`; dessen Quelle bleibt **unverändert** (ruft weiter die
Use-Case-Signatur, `suspend () -> Set<String>`).

**2. `app/…/ui/settings/SettingsFragment.kt` `showSortFavoritesFragment()`** —
beide Lesungen tauschen:
- `:786` `favoritesRepository.favoriteComponentsFlow.first()` →
  `favoritesRepository.getFavoriteComponentsSnapshot()`
- `:803` `favoritesOrderRepository.favoriteComponentsOrderFlow.first()` →
  `favoritesOrderRepository.getFavoriteComponentsOrderSnapshot()`

Die bestehenden `catch (CancellationException) { throw e }` / `catch (Throwable)`
Arme **unverändert lassen** (beide Aufrufe bleiben `suspend`; die Datei ist schon
auf `cancel_files` → keine Whitelist-/Linter-Änderung, minimaler Diff).

**3. Interface-KDoc beider Snapshot-Methoden** (`FavoritesRepository.kt`,
`FavoritesOrderRepository.kt`) — „Used by the backup export" erweitern auf „Used
by any point-read from a context without a warm Home subscriber: backup export,
Settings sort-favorites, Onboarding edit-favorites". Reine Doku.

### Tests

**Pflicht — `domain/…/GetFavoriteComponentsUseCaseTest.kt` anpassen** (sonst rot):
beide Tests stubben aktuell `every { favoritesRepository.favoriteComponentsFlow }`
→ auf `coEvery { favoritesRepository.getFavoriteComponentsSnapshot() }` umstellen
(`suspend` → `coEvery`). `returns set`-Test: Snapshot gibt das Set zurück.
`propagates exception`-Test: Snapshot `throws RuntimeException` (Nicht-IO) →
propagiert weiter. Das pinnt den Onboarding-Pfad auf die autoritative Lesung.

**Settings-Regression — zwei Optionen:**
- *(minimal)* Der Repo-Contract deckt die Snapshot-Korrektheit bereits ab; der
  Tausch selbst ist der Fix. Kein neuer Test.
- *(Rule-10-sauber, empfohlen wenn ein JVM-Pin gewünscht ist)* Die Snapshot-Lesung
  + Filter/Sort-Entscheidung aus dem Fragment in `SettingsViewModel` als
  `suspend fun` heben (Rückgabe z.B. ein sealed Outcome `Empty` / `NoFavorites` /
  `Ordered(list)`), unit-getestet mit Mock-Repos, die verifizieren, dass
  `getFavoriteComponentsSnapshot()`/`…OrderSnapshot()` aufgerufen werden **und die
  Hot-Flows nicht**. Fragment wird dünner Glue. Führt einen neuen `suspend`-Frame
  in `SettingsViewModel` ein → danach `./gradlew scanCancelCandidates` laufen und
  triagen.

Empfehlung: erst der minimale Tausch (klein, korrekt, risikoarm); der Rule-10-Lift
optional als zweiter Commit, falls ein gepinnter Settings-JVM-Regressionstest
gewünscht ist.

### Verifikation

```bash
./gradlew :domain:test :data:test :app:test
./gradlew checkConventions checkRule13
# nur falls die Settings-Logik gehoben wurde:
./gradlew scanCancelCandidates
```

### Außerhalb des Scopes (bewusst nicht mitgezogen)

- **Empty-on-IO-Vorauswahl im Editor.** Dass eine I/O-Lesefehler-Vorauswahl leer
  ist (und ein anschließendes Speichern die Favoriten leeren *könnte*), ist
  **Bestandsverhalten** — der Flow tut das heute genauso. Der Fix regressiert es
  nicht. Fail-closed für den Editor (Snapshot-Variante, die wirft / ein
  `Result` liefert, statt leer) wäre eine **eigene** Entscheidung im Sinne von
  Rule 11 („a caught failure must stay a failure") — nicht in diesen Commit bündeln.
- Keine DataStore-Schemaänderung, keine Migration, keine neuen Strings. Purge-,
  Parity- und OOM-Linter unberührt.

### Abschluss

Nach dem Merge diesen AUDIT-Abschnitt auf „behoben in `<commit>`" aktualisieren.
