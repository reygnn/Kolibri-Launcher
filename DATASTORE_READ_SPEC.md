# DATASTORE_READ_SPEC — Ein autoritativer Lesepfad pro DataStore-State (Plan + Sigs)

> **Erzeugt** 2026-08-08 gegen `main` @ `0966cef5` (unmittelbar nach dem
> AUDIT-13-Fix), auf einem Branch `refactor/datastore-read-contract`.
> **Fokus:** die drei **DataStore-gestützten Hot-Share-Repositories**
> (`FavoritesRepositoryImpl`, `FavoritesOrderRepositoryImpl`,
> `FabPositionRepositoryImpl`) und ihre gemeinsame Basis
> `SharedDataStoreFlowRepository`. **Nicht** im Fokus: teure Upstreams
> (`InstalledAppsRepositoryImpl`), `SettingsRepositoryImpl` (eigene, breitere
> Recovery), die Wallpaper-Pipeline.
> **Verhältnis zu AUDIT-13:** AUDIT-13 hat den *Symptom-Bug* geschlossen (zwei
> UI-Consumer lesen jetzt `getXSnapshot()`, plus `check-stale-replay-read.awk`
> als Gate). Diese Spec fixt nichts Rotes nach — sie beseitigt die *Ursache*,
> die den Bug erst möglich machte und bis heute vier divergierende Lese-Policies
> je Repo koexistieren lässt.
>
> **Kein akuter Defekt.** Reine Struktur-Konsolidierung mit **einer**
> echten Korrektur-Zugabe (Belang C, der Editor-Preselect-Pfad). Als Einheit
> revertierbar → eigener Branch.

---

## 1. Das Problem: vier Lese-Policies je Repo, plus doppelter Lesepfad

`FavoritesRepositoryImpl` liest denselben Key (`favorites_components_set`) heute
über **vier** verschiedene Konsistenz-/Fehler-Politiken:

| Aufrufstelle | Quelle | Fehler bei `IOException` | Zweck |
|---|---|---|---|
| `isFavoriteComponent` (`:217`) | `favoriteComponentsFlow.first()` (Replay-Cache) | empty, `silentError`, **wirft in DEBUG** | Entscheidung, „stale-replay ok" (warmer Home-Subscriber) |
| `getFavoriteComponentsSnapshot` (`:239`) | `dataStore.data.first()` (frisch) | empty, `Timber.w`, **wirft NICHT in DEBUG** | Punkt-Lesung aus fremdem Kontext |
| `reconcileFavoriteComponents` (`:261`) | `dataStore.data.first()` (frisch) | **propagiert** (fail-closed) | destruktive Lesung (löschen) |
| Writes (`add/remove`, `:154/:188`) | Lesung *innerhalb* `edit{}` | propagiert (Transaktion) | Race-freies read-modify-write |

Das ist nicht Zufall, sondern akkumulierte Kompensation: jede Zeile ist ein
eigener Bugfix (AUDIT-7 = `safeReadFlow`, RECONCILE_FIX_SPEC = fail-closed,
Backup-Export = Snapshot, AUDIT-13 = die zwei UI-Consumer). Dasselbe Muster,
das den ACRA-Rewrite ausgelöst hat: das ursprüngliche Design hat zwei Belange
(beobachten vs. entscheiden) vermengt, und darüber hat sich Maschinerie
gestapelt.

**Der doppelte Lesepfad.** Jedes der drei Repos hält heute **zwei** Lesewege
zum selben Key nebeneinander — den Hot-Flow und die Snapshot-Methode —, die je
ihr eigenes Parsing und ihre eigene Fehler-Policy tragen. Bei
`FavoritesOrderRepositoryImpl` teilen sie zwar `parseOrderString`, aber die
Fehler-Envelopes (`safeReadFlow` mit `silentError`/DEBUG-throw vs. Snapshot mit
`Timber.w`/kein-throw) sind hand-dupliziert und driften genau dort, wo es
zählt. Die Contract-Tests pinnen die *Frische* (`reflects the LATEST …`), aber
nicht die *Deckungsgleichheit der Fehler-Policy* der beiden Wege.

---

## 2. These / Zielbild

**Beobachten wird aus derselben autoritativen Lesung abgeleitet wie
Entscheiden — es gibt keinen Replay-Cache über DataStore-State.**

Konkret: die drei Repos geben ihre `shareIn(replay = 1, WhileSubscribed)`-
Hülle auf. Der `xFlow` wird **kalt** (direkt `dataStore.data` → `transform`),
`getXSnapshot()` ist `dataStore.data.first()` → **derselbe** `transform`. Damit:

- **Flow und Snapshot sind ein Lesepfad.** Kein doppeltes Parsing, keine zwei
  Fehler-Envelopes, keine Drift-Fläche.
- **Die Stale-Replay-Klasse verschwindet strukturell**, nicht per Marker: ohne
  Replay-Cache kann ein `.first()` nichts Veraltetes zurückgeben. `isFavorite`
  und `ToggleFavoriteUseCase` werden automatisch korrekt, ohne „stale-replay
  ok"-Begründung.
- `SharedDataStoreFlowRepository` **entfällt** (wird gelöscht — §4/§6; kein
  Rest-Helfer, die drei In-Scope-Repos bauen ihren Kalt-Flow direkt über
  `readFlowFailOpen`).

### Warum der Hot-Share hier ~nichts kauft

Der einzige Existenzgrund von `WhileSubscribed(5000)` ist, mehrfache
gleichzeitige Kollektoren nicht je einen Disk-Read auslösen zu lassen. Aber:

1. **DataStore teilt selbst.** `SingleProcessDataStore` hält die dekodierten
   Preferences in-memory (`inMemoryCache`) und bedient neue `.data`-Kollektoren
   version-gated **ohne Disk-I/O**, solange keine Schreibgeneration dazwischen
   liegt — verifiziert gegen die gepinnten `androidx.datastore 1.2.1`-Sourcen
   (`SingleProcessCoordinator.updateNotifications` ist leer, File-Watching ist
   MultiProcess-only). Präzise formuliert: **eine geteilte Disk-Lesung pro
   Schreibgeneration**, von allen Kollektoren wiederverwendet — nicht „eine
   Lesung überhaupt". `shareIn` dedupliziert darüber nur noch den
   `.map(transform)` — bei Favoriten ein `Set`-Lookup, bei Order ein JSON-Parse
   von typischerweise <30 Einträgen. Der Replay-Cache erspart zusätzlich den drei
   transienten `.first()`-Punktlesungen (`isFavoriteComponent`, ToggleFavorite-
   Size-Check, Backup-Order-Import) den Transform-Rerun; ohne ihn läuft jede als
   frischer **In-Memory**-Transform neu (kein Disk) — für den Order-Parse etwas
   schwerer als ein Set-Lookup, aber vernachlässigbar. Also **~nichts**, präzise:
   ein In-Memory-Transform-Rerun je Punktlesung, nicht „gar nichts".
2. **Es gibt real einen einzigen warmen Kollektor:** `GetFavoriteAppsUseCase`
   `combine`t beide Flows dauerhaft (`:66`). Ein Dedup über *einen* Kollektor
   ist gegenstandslos.
3. Für einen Launcher ist der State **entscheidungsrelevant** (welche App, in
   welcher Reihenfolge). Ein Replay-Cache mit „letzter *beobachteter* Wert"-
   Semantik ist damit invertierter Wert: messbares Staleness-Risiko gegen einen
   nahe-null Performance-Gewinn.

`FabPositionRepositoryImpl` ist der Reinfall des Arguments: rein
kontinuierlich gerendert (`GetFabPositionUseCase` → `WallpaperDelegate.stateIn`
→ `LauncherViewModel.fabPosition`), **keine** `.first()`-Punktlesung, **keine**
Snapshot-Methode. Der Hot-Share dort schützt gegen ein Problem, das es nicht
hat.

---

## 3. Belange

### Belang A — Hot-Share abbauen (`SharedDataStoreFlowRepository` auflösen)

Die drei Repos erben nicht mehr von `SharedDataStoreFlowRepository`. Der `xFlow`
wird kalt aus `dataStore.data` + `transform` gebaut; der `@Inject`-Konstruktor
kollabiert auf `@Inject constructor(dataStore)` (kein `externalScope`, kein
`sharingStrategy`, kein Dual-Konstruktor mehr). Das ist exakt die Bewegung, die
`SwipeActionsRepositoryImpl` beim Swipe-Fix schon vollzogen hat (Konstruktor
kollabierte auf `@Inject constructor(dataStore)`, AUDIT-13-Anhang).

**Behalten (bewusst NICHT angefasst):** die `.stateIn(WhileSubscribed)`-Shares
auf *teuren* Upstreams — `InstalledAppsRepositoryImpl:122` (PackageManager-
Enumeration). Die sind gerechtfertigt (teure Quelle) und ungefährlich (nur
kontinuierlich kollektiert, nie per `.first()` auf einem Entscheidungspfad,
AUDIT-13). Der Hot-Share bleibt dort, wo der Upstream ihn verdient.

### Belang B — Fehler-Modell auf zwei benannte Envelopes konsolidieren

Statt vier hand-geschriebener catch-Blöcke: genau **zwei** Politiken, benannt
und in `DataStoreReadFlow.kt` zentralisiert, die Wahl je Aufrufstelle explizit.

- **fail-open** (Anzeige, Backup-Export): `IOException` → Default (empty),
  Cancellation propagiert, sonst propagiert. Das ist das heutige
  `safeReadFlow`-Verhalten, plus die Snapshot-Frische.
- **fail-closed** (destruktive Lesung, z.B. `reconcile`): `IOException`
  propagiert, damit der Aufrufer nichts löscht. Das heutige reconcile-Verhalten.

Die DEBUG-throw-Divergenz zwischen Flow (`silentError`, wirft in DEBUG) und
Snapshot (`Timber.w`, wirft nicht) wird **eingezogen**: ein `IOException` beim
Lesen ist ein echter I/O-Fehler, kein Programmierfehler — er darf in DEBUG
**nicht** werfen (sonst crasht ein DEBUG-Backup an einem Store-Schluckauf). Also
gilt fail-open einheitlich `Timber.w`, nicht `silentError`. (Rule 9 bleibt
gewahrt: `silentError` ist für Programmierfehler; ein Disk-`IOException` ist
keiner.)

### Belang C — der eine echte Korrektur-Zusatz: Editor-Preselect fail-closed

AUDIT-13 hat bewusst ausgeklammert: ein I/O-Lesefehler beim Vorbelegen des
Favoriten-Editors (Onboarding EDIT_FAVORITES) liefert heute **empty**, und ein
anschließendes Speichern **leert die Favoriten**. Das ist Bestandsverhalten
(der Flow tut es genauso), aber es ist die eine Stelle, an der ein fail-open-
Default *destruktiv* wirkt — ein leeres Preselect ist von „User hat keine
Favoriten" nicht unterscheidbar.

Diese Spec zieht genau diese Zeile: der Editor-Preselect-Read ist
**fail-closed / unterscheidbar**. Ein Lesefehler darf nicht als „keine
Favoriten" maskieren und ein Save die echten Favoriten überschreiben lassen.
Umsetzung minimal-invasiv über ein kleines sealed Result **nur** an diesem
einen Pfad (siehe §4, `readFavoritesForEdit`), nicht flächendeckend — dieselbe
Begründung wie bei `ConsentReadResult` (CLAUDE.md, Rule 11: „a caught failure
must stay a failure"): der sealed Typ kommt genau dahin, wo ein falscher
Default *handelt*, und nirgends sonst.

**Die Korrektheit liegt im Save-Gate, nicht in der Read-Signatur** (Review-Fund
#1). Ein `Unavailable`, auf das niemand verzweigt, ist wirkungslos: heute leitet
`OnboardingViewModel.onDoneClicked` (`:150-153`) `selectedComponents.value`
(init `emptySet()`, `:44`) **bedingungslos** an `CompleteOnboardingUseCase` →
`saveFavoriteComponents` (full-replace, `FavoritesRepositoryImpl:229`); der
`loadInitialData`-catch loggt heute nur und lässt den leeren Default stehen. Der
Fix ist daher **zweiteilig und beides ist Pflicht**:

1. **Read** → `readFavoritesForEdit(): FavoritesEditRead`
   (`Loaded(Set)` / `Unavailable`).
2. **Save-Gate im ViewModel als TRI-STATE, Default gesperrt** (Review-Runde 2,
   Fund 2.1 — ein Boolean reicht NICHT). `loadInitialData` läuft async mit echtem
   Suspend-Point; ein Zwei-Wert-Flag mit Default `true` ließe ein „Done vor dem
   Auflösen des Reads" durchrutschen und den leeren Full-Replace **trotzdem**
   feuern (In-Flight-Race). Deshalb ein expliziter Zustand:

   ```kotlin
   sealed interface PreselectState {
       data object NotLoaded : PreselectState   // Default — blocks EDIT save
       data object Loaded : PreselectState
       data object Unavailable : PreselectState
   }
   ```

   **Save-Erlaubnis in `onDoneClicked`:**
   - `mode == INITIAL_SETUP` → **immer speichern** (leere Auswahl ist beim
     Erststart legitim; das Gate gilt NICHT für First-Run).
   - `mode == EDIT_FAVORITES` → speichern **nur** bei `Loaded`; bei `NotLoaded`
     (Read noch nicht fertig) **und** `Unavailable` (Read fehlgeschlagen) den
     Save **hart abbrechen** (`CompleteOnboardingUseCase` gar nicht aufrufen)
     und `ShowError` emittieren.

   Der Zustand geht von `NotLoaded` **nur** in `Loaded` (erfolgreicher EDIT-Read)
   bzw. `Unavailable` (fehlgeschlagener EDIT-Read) über; für `INITIAL_SETUP` ist
   er belanglos (die Save-Erlaubnis hängt dort nicht am Zustand). Damit ist der
   destruktive Default eliminiert: im EDIT-Modus ist Speichern gesperrt, bis der
   Read beweisbar `Loaded` gemeldet hat.

3. **Load-Zeit-Fehleranzeige** (Fund 2.3): `loadInitialData` muss `Unavailable`
   **sofort** sichtbar machen (`ShowError` + eigener „Laden fehlgeschlagen"-
   Zustand), nicht erst am Save — sonst öffnet der Editor still leer und ist von
   „keine Favoriten" nicht unterscheidbar. Der bereits existierende, hartcodierte
   `OnboardingEvent.ShowError("Could not load favorites.")` (`:118`) wird
   wiederverwendet → **keine neuen Strings**, Parity-Gate unberührt.

Ohne Schritt 2+3 ist DSR-INV-4 nicht erfüllt und der Wipe besteht fort. **Pflicht-Test:**
Done getippt, während der EDIT-Read noch offen ist (`NotLoaded`) → `saveFavoriteComponents`
wird **nicht** aufgerufen.

---

## 4. Sigs (Zielzustand)

### `FavoritesRepository` (domain)

```kotlin
interface FavoritesRepository : Purgeable {
    // Observe — COLD (from dataStore.data), for continuous rendering.
    val favoriteComponentsFlow: Flow<Set<String>>

    // Decide — authoritative point-read, the SAME read+transform as the flow.
    // Fail-open (IOException → empty). Cancellation propagates.
    suspend fun getFavoriteComponentsSnapshot(): Set<String>

    // NEW (Belang C) — fail-closed preselect for the editor: a read error is
    // NOT masked as an empty selection.
    suspend fun readFavoritesForEdit(): FavoritesEditRead   // Loaded(Set) | Unavailable

    suspend fun isFavoriteComponent(componentName: String?): Boolean
    suspend fun toggleFavoriteComponent(componentName: String): Boolean
    suspend fun addFavoriteComponent(componentName: String): Boolean
    suspend fun removeFavoriteComponent(componentName: String): Boolean
    suspend fun saveFavoriteComponents(componentNames: List<String>)
    suspend fun reconcileFavoriteComponents(
        installedComponentNames: List<String>,
        isStillPresent: suspend (String) -> Boolean,
    )   // unverändert: fail-closed
}

sealed interface FavoritesEditRead {
    data class Loaded(val components: Set<String>) : FavoritesEditRead
    data object Unavailable : FavoritesEditRead
}
```

`FavoritesOrderRepository` analog: `favoriteComponentsOrderFlow` kalt,
`getFavoriteComponentsOrderSnapshot()` teilt `parseOrderString` mit dem Flow.
Der Onboarding-Editor liest die Order gar nicht (er sortiert nach `displayName`);
die order-basierte Punktlesung gehört zur **Settings-Sort**-Oberfläche.

**Order-Destruktivität — bewusstes Fail-open, Exemption argumentiert**
(Runde-3-Fund S1). Die Order **ist** prinzipiell destruktiv über den Write-Pfad:
`getFavoriteComponentsOrderSnapshot()` fail-open → leere Order →
`sortAppsWithGivenOrder` → rein alphabetisch → `FavoritesSortViewModel` →
Reset/Sort/Drag → `saveOrder` überschreibt die echte Custom-Order — dieselbe
Read→Save-Klasse wie Belang C, eine Ebene milder. Trotzdem **kein `readForEdit`**
für die Order, weil die Exemption trägt:
1. **Trigger fast unmöglich.** Set und Order liegen in **einer** in-memory-
   gecachten DataStore-Datei. Scheitert der Order-Read mit `IOException`,
   scheitert der Set-Read auch → `SettingsViewModel.prepareFavoritesForSorting`
   fail-opent das Set auf leer → `NoFavorites` → der Sort-Dialog **öffnet gar
   nicht**, die leere Order wird nie gespeichert.
2. **Order-only + wiederherstellbar** (Drag stellt sie neu her), nicht der Verlust
   der Favoriten-Mitgliedschaft.
3. **User-action-gated & Bestandsverhalten** — kein Regress durch diesen Refactor.

Fail-closed für die Order wäre eine **eigene** Entscheidung (Rule 11) und wird
hier bewusst nicht gebündelt; die Behauptung „nicht destruktiv" (frühere
Fassung) war falsch und ist durch diese argumentierte Exemption ersetzt.

`FabPositionRepository`: `fabPositionFlow` kalt, **keine** Snapshot-Methode
(hatte nie eine) — reiner Render-Flow. Simpelster Migrationsfall.

### `DataStoreReadFlow.kt` (data, zentralisierte Envelopes)

```kotlin
// Observe: cold flow, fail-open on IOException. Recovers to emptyPreferences()
// so the SAME transform produces the fallback — no hand-passed empty value.
internal fun <T> DataStore<Preferences>.readFlowFailOpen(
    label: String, transform: suspend (Preferences) -> T,
): Flow<T> = data
    .catch { e ->
        if (e is IOException) { Timber.w(e, label); emit(emptyPreferences()) } else throw e
    }
    .map(transform)

// Decide, fail-open: a point-read with the SAME policy AND the SAME fallback
// source — transform(emptyPreferences()), never a separately passed default
// (DSR-INV-1; Review-Fund #5).
internal suspend fun <T> DataStore<Preferences>.snapshotFailOpen(
    label: String, transform: suspend (Preferences) -> T,
): T = try { transform(data.first()) }
       catch (e: CancellationException) { throw e }
       catch (e: IOException) { Timber.w(e, label); transform(emptyPreferences()) }

// Decide, fail-closed: IOException propagates (destructive reads, e.g. reconcile).
internal suspend fun <T> DataStore<Preferences>.snapshotFailClosed(
    transform: suspend (Preferences) -> T,
): T = transform(data.first())   // no IOException catch; cancellation propagates anyway
```

Es sind **drei generische Envelope-Funktionen** plus **eine repo-spezifische
vierte Form** für den unterscheidbaren Editor-Read (Runde-3-Fund S2 — DSR-INV-3
zählt die *Politiken* fail-open/fail-closed, nicht die Funktions-Signaturen).
`readFavoritesForEdit` ist explizit auszuschreiben, mit
**`CancellationException`-first-Arm**, damit §9s „propagiert Cancellation per
Konstruktion" gegründet ist:

```kotlin
// Repo-spezifisch (FavoritesRepositoryImpl): distinguishable editor read.
override suspend fun readFavoritesForEdit(): FavoritesEditRead =
    try { FavoritesEditRead.Loaded(dataStore.data.first()[FAVORITES] ?: emptySet()) }
    catch (e: CancellationException) { throw e }   // rethrow FIRST — no swallow
    catch (e: IOException) { Timber.w(e, "..."); FavoritesEditRead.Unavailable }
```

**Achtung Cancellation-Gate:** `FavoritesRepositoryImpl.kt` steht **nicht** auf
`cancel_files`; ein breiter `catch (Exception)` in diesem Suspend-Frame würde vom
Linter also nicht erfasst. Der `CancellationException`-first-Arm oben ist Pflicht,
und `scanCancelCandidates` (§9) ist der einzige Backstop — nach dem Belang-C-Commit
laufen lassen und triagen (ggf. Datei auf `cancel_files` heben).

`shareInOrRaw` und `SharedDataStoreFlowRepository` werden **gelöscht** (nur die

`shareInOrRaw` und `SharedDataStoreFlowRepository` werden **gelöscht** (nur die
drei In-Scope-Repos hingen daran). **`safeReadFlow` bleibt** dagegen erhalten
(Review-Fund #2): es hat zwei **direkte** Aufrufer außerhalb des Scopes —
`HiddenAppsRepositoryImpl:100` und `WallpaperRepositoryImpl:77` (Letzteres per
§10 einer eigenen Spec vorbehalten). Beide hängen ihr eigenes `.map{}` an,
während `readFlowFailOpen` den `transform` einbäckt — ein Rewrite, kein Rename.
`readFlowFailOpen` kommt daher **additiv** neben `safeReadFlow` und wird nur von
den drei In-Scope-Repos genutzt; `safeReadFlow` wird nicht angefasst. (DSR-INV-3
ist entsprechend auf die drei In-Scope-Repos begrenzt, siehe §5.)

**`getFavoriteComponentsSnapshot()` bleibt** (Review-Fund #1): zwei lebende
Nicht-Onboarding-Aufrufer — `SettingsViewModel:110`, `BackupDataAssembler:83` —
dürfen es nicht als verwaisten Rest wegräumen. `readFavoritesForEdit` kommt
**zusätzlich** dazu, nur für den Onboarding-Editor. `GetFavoriteComponentsUseCase`
(einziger Aufrufer = Onboarding) wird auf den fail-closed-Pfad umgestellt oder
durch einen direkten `readFavoritesForEdit()`-Aufruf ersetzt (Detail im
Belang-C-Commit; siehe §11).

---

## 5. Invarianten (DSR-INV-*)

- **DSR-INV-1 — Ein autoritativer Lesepfad je Key-Set.** `xFlow` und
  `getXSnapshot()` teilen **denselben `transform` und dieselbe
  fail-open-Semantik** (IOException→`transform(emptyPreferences())`, `Timber.w`).
  Es sind zwei syntaktische Träger (`Flow.catch` vs. `try/catch`), aber eine
  Policy — kein zweites Parsing, kein zweiter Default, keine zweite
  Fehler-Behandlung pro Repo.
- **DSR-INV-2 — Kein Replay-Cache über DataStore-User-State.** Keine der drei
  Repos nutzt `shareIn(replay ≥ 1, WhileSubscribed)`. (Teure Nicht-DataStore-
  Upstreams sind ausgenommen und bleiben.)
- **DSR-INV-3 — Genau zwei Fehler-*Politiken*, explizit gewählt** — **für die
  drei In-Scope-Repos.** Jede Lesung ist `failOpen` **oder** `failClosed`; kein
  ad-hoc catch-Block je Aufrufstelle. (Die Politiken werden von drei generischen
  Envelope-Funktionen + der repo-spezifischen `readFavoritesForEdit`-Form
  getragen, §4 — die Zahl der Funktionen ist nicht die Zahl der Politiken.)
  Out-of-scope-Repos mit eigenem `safeReadFlow`/`safeData` sind nicht erfasst,
  §10.
- **DSR-INV-4 — Eine Lesung, deren Ergebnis selbst als neuer Wert persistiert
  wird, ist fail-closed / unterscheidbar.** Editor-Preselect → Save darf aus
  einem Lesefehler keinen destruktiven Default machen (Belang C). **Ausgenommen:**
  der Toggle-Pfad — `isFavoriteComponent` (fail-open) steuert nur ein
  read-modify-write *innerhalb* `edit{}`; ein Lesefehler dort ist ein gutartiger
  No-op, nie destruktiv, weil der maßgebliche Read in der Transaktion sitzt.
- **DSR-INV-5 — Cancellation propagiert immer.** Auf jedem Pfad, unverändert.
- **DSR-INV-6 — Save-Gate-Write-Ordering (Belang C, Runde-3-Fund S3).** Im
  EDIT-`Loaded`-Zweig wird `selectedComponents` **vor** dem `PreselectState`-Flip
  auf `Loaded` gesetzt, und zwischen den beiden Zuweisungen liegt **kein
  Suspension-Point**. Beide laufen auf dem Main-Dispatcher → nur dann sind sie
  gegenüber einem nebenläufigen `onDoneClicked` atomar. Ein späteres Einschieben
  eines `suspend`-Calls dazwischen öffnet die Wipe-Race erneut — genau die
  diff-unsichtbare Klasse, gegen die auch die `cancel_files`-Prüfung existiert.
  Diese Invariante ist beim Belang-C-Commit im ViewModel-Test zu pinnen.

---

## 6. Migrationsreihenfolge (je ein Commit, revertierbar)

1. **`FabPositionRepositoryImpl` zuerst** — kein Snapshot, keine Punktlesung im
   Produktionscode → reiner Kalt-Flow-Flip auf `readFlowFailOpen`. Kleinster,
   risikoärmster Schritt; validiert den Basis-Umbau (Envelope-Aufbau,
   Konstruktor-Kollaps) isoliert. **Mitzuziehen in genau diesem Commit**
   (Review-Fund #3/#4, korrigiert in Runde 2 Fund 2.4):
   - `FabPositionRepositoryImplContractTest` + `createForTesting`-Entfernung auf
     den Ein-Argument-`@Inject constructor(dataStore)` (§7), samt Nachziehen der
     Contract-Test-KDocs, die noch `createForTesting`/`externalScope` nennen.
   - `readFlowFailOpen` liefert Fabs Fail-open-Default über
     `transform(emptyPreferences())` → `FabPosition.DEFAULT` (§4); **kein**
     `snapshotFailOpen` bei Fab (Fab hat keine Snapshot-Methode — Fund #5 betrifft
     Fab daher NICHT).
   - `fabPositionFlow` aus `hot_flows` in `check-stale-replay-read.sh` entfernen
     (§8) — reine **Config-Truthfulness**: Fab hat keine *gescannte* Punktlesung
     (seine `.first()`-Reads liegen in `testFixtures`, außerhalb des Scans), der
     Name stand nur vorsorglich in der Liste. Kommentar bei
     `check-stale-replay-read.sh:48` im selben Commit mitziehen.
2. **`FavoritesOrderRepositoryImpl`** — Kalt-Flow + Snapshot auf einen Pfad
   ziehen (`parseOrderString` wird die einzige Parse-Stelle). `removeComponentFromOrder`
   bleibt transaktional (`edit{}`), unangetastet.
3. **`FavoritesRepositoryImpl`** — meiste Consumer; zuletzt. Danach Belang C
   (`readFavoritesForEdit` + Onboarding-Pfad).
4. **`SharedDataStoreFlowRepository` löschen** + `DataStoreReadFlow`-Envelopes
   finalisieren.

---

## 7. Contract-Test-Auswirkung

Die Churn ist **erheblich** — §7 hatte sie ursprünglich um ~eine Größenordnung
unterschätzt (Review-Fund #4). Realistisch:

- **Konstruktor-Kollaps → ~60 Konstruktionsstellen umschreiben.** Der Wegfall
  der `(externalScope, sharingStrategy)`-Parameter und der `createForTesting`-
  Fabriken (`FavoritesOrderRepositoryImpl:159`, `FabPositionRepositoryImpl:51`)
  trifft `FavoritesRepositoryImplTest` (~32 Tests), `FavoritesOrderRepositoryImplTest`
  (~24 Tests) und **alle drei** `*ImplContractTest`s — jede Konstruktionsstelle
  geht auf den Ein-Argument-`@Inject constructor(dataStore)`. Die
  `createForTesting`-Fabriken werden entfernt. **Auch die Contract-Test-KDocs
  mitziehen**, die noch `createForTesting`/`externalScope`/eine ShareInTest
  nennen (u.a. `FabPositionRepositoryImplContractTest.kt:11-19`,
  `FabPositionRepositoryContract.kt:31-35`) — sonst bleiben sie lügende
  Kommentare.
- **Zwei `*ShareInTest`-Dateien werden GELÖSCHT, nicht migriert.**
  `FavoritesRepositoryImplShareInTest` war in §7 als „`externalScope = null`-
  Kaltpfad" **fehlbeschriftet** — er nutzt einen echten Non-null-Scope und
  behauptet Hot-Share-Semantik (ein Upstream, `replay = 1`, beweisbar-stale
  Replay bei `:392-424`). Diese Semantik wird abgebaut → die Datei prüft
  gelöschtes Verhalten und **entfällt ganz** (ebenso das bisher ungenannte
  Order-Pendant). Der `externalScope`-Parameter fällt vollständig weg, also
  überlebt keine `externalScope = null`-Konstruktion; nur das **kalte
  Leseverhalten**, das dieser Zweig früher selektierte, lebt in den
  `*ImplContractTest`s weiter.
- **`BackupDataAssemblerImportOrderStaleCacheTest` neu schreiben.** Er pinnt
  exakt die Replay-Lag-Gefahr, die dieser Refactor strukturell **beseitigt** →
  kein Port, sondern Neu-Autorierung (der Stale-Zustand ist nicht mehr
  herstellbar).
- **Bleiben, gewinnen an Bedeutung:** die `reflects the LATEST …, never a
  previous one`-Tests (`FavoritesRepositoryContract:103`,
  `FavoritesOrderRepositoryContract:255`) — sie pinnen jetzt einen echten
  frischen Read, nicht mehr die Umgehung eines Caches.
- **Neu:** ein Fake+Impl-Contract-Test für `readFavoritesForEdit`
  (`Loaded` bei lesbarem Store, `Unavailable` bei `IOException` — Letzteres
  impl-only, analog `CrashReportConsentRepositoryImplTest`), plus ein
  ViewModel-Test, der das **Save-Gate** pinnt (`Unavailable` → `onDoneClicked`
  ruft `CompleteOnboardingUseCase` **nicht** und emittiert `ShowError`).
- **CLAUDE.md-Notiz nachziehen:** der Testhinweis „Repository takes a `shareIn`
  external-scope parameter? … `externalScope = null`" wird für diese drei Repos
  gegenstandslos.
- **Purge unberührt** (Keys/`safePurge` unverändert) → Purge-Completeness-Gate
  bleibt grün.

---

## 8. Der Stale-Replay-Gate danach

**Korrektur (Review-Fund #3): der Gate wird NICHT von selbst grün.** Er ist
namens-, nicht hotness-getrieben: `tools/check-stale-replay-read.sh:49-53`
hardcodet `hot_flows=(favoriteComponentsFlow favoriteComponentsOrderFlow
fabPositionFlow)`, und die awk flaggt jedes `<name>.(first|firstOrNull)(` — ohne
jede `shareIn`-Wahrnehmung. Würde man (wie ursprünglich in §8 formuliert) die
`stale-replay ok`-Marker entfernen, aber die Reads als
`favoriteComponentsFlow.first()` stehen lassen, ergäbe das **drei unmarkierte
Hits → `checkConventions` ROT.** Zwei Konsequenzen:

1. **Die Gate-Config-Änderung ist Teil der Refactor-Commits, nicht Beiwerk.**
   Da die drei Flows kalt werden und Punkt-Lesungen auf `getXSnapshot()` gehen,
   werden die Flow-Namen aus `hot_flows` (und ggf. `stale_files`) **entfernt**.
   Erst dann sind die zu streichenden Marker
   (`FavoritesRepositoryImpl:214`, `ToggleFavoriteUseCase:44`,
   `BackupDataAssembler:252`) folgenlos streichbar.
2. **Kein „Regressions-Lock gegen Rückfall in den Hot-Share".** Der frühere
   §8-Satz war falsch: die awk erkennt Reads *nach Namen*, nie die
   `shareIn`-Konstruktion — ein wieder eingeführter Hot-Share würde von ihr
   nicht bemerkt. DSR-INV-2 gegen Rückfall abzusichern wäre eine **separate**
   Prüfung (Kandidat: „`shareIn(replay ≥ 1)` in einem `*RepositoryImpl` mit
   DataStore-Backing") — nicht Teil dieses Refactors, hier nur als Notiz.

Nach dem Umbau ist der Gate also entweder **entleert** (leere `hot_flows` →
nichts zu prüfen) oder auf verbliebene echte Hot-Flows (z.B. InstalledApps, das
nie punktgelesen wird) neu gezielt. Empfehlung: entleeren und als Notiz
markieren, dass der Ersatz-Lock (shareIn-Konstruktion) noch aussteht.

---

## 9. Verifikation

```bash
./gradlew :domain:test :data:test :app:test
./gradlew checkConventions checkRule13
# Belang C führt einen neuen suspend-Frame (readFavoritesForEdit + Onboarding) ein:
./gradlew scanCancelCandidates
```

Erwartung: Suite grün, `checkConventions` grün (Stale-Replay-Gate findet nichts,
Purge/Parity/OOM unberührt), `scanCancelCandidates` triagiert — der neue Pfad
propagiert Cancellation per Konstruktion (Envelopes), sollte also nichts
Fixbares zeigen.

---

## 10. Außerhalb des Scopes (bewusst nicht mitgezogen)

- **`InstalledAppsRepositoryImpl`** `stateIn` — teurer Upstream, legitimer
  Hot-Share, nie punktgelesen. Bleibt.
- **`SettingsRepositoryImpl`** — eigene, breitere Read-Recovery (`safeData`,
  „doomsday"-Tests, schluckt auch `ClassCastException`); ruft **nicht**
  `safeReadFlow` → gar nicht betroffen (Review hat das explizit widerlegt).
- **`HiddenAppsRepositoryImpl:100` und `WallpaperRepositoryImpl:77`** — die zwei
  **direkten** `safeReadFlow`-Aufrufer. `safeReadFlow` bleibt für sie
  **unverändert** erhalten (§4); dieser Refactor fasst sie nicht an. Wallpaper
  ist ohnehin der P2-Kandidat mit eigener Spec.
- Keine DataStore-Schemaänderung, keine Migration, keine neuen Strings (das
  Save-Gate nutzt den existierenden `ShowError`-String, §3 Belang C).
- **`@ApplicationScope`-Provider bleibt (wird nicht dead).** Nach Wegfall des
  `externalScope`-Params der drei Repos hat der Provider weiterhin einen
  Consumer (`ConsentController`) — nicht entfernen (Runde-3-Completeness-Fund).

---

## 11. Entscheidungen

1. **Hot-Share abbauen (Belang A) — ENTSCHIEDEN (2026-08-08): abbauen.**
   Der Kalt-Flow+Snapshot-Weg ist der eigentliche Klasseneliminierer; die
   Konsolidierung allein (Envelopes vereinheitlichen, Hot-Share behalten) ließe
   DSR-INV-2 offen und den Gate dauerhaft „scharf". Die Perf-Annahme (§2: kein
   realer Regress, da DataStore selbst teilt und nur ein warmer Kollektor
   existiert) wird als tragfähig akzeptiert — **keine** vorgeschaltete
   Mikro-Messung.
2. **Belang C jetzt oder separat?** Empfehlung: **jetzt**, aber als **eigener,
   letzter Commit** auf demselben Branch — es ist die einzige Verhaltensänderung
   (nicht nur Struktur) und verdient eine eigene, klar benannte Revert-Einheit.
   Der Commit umfasst **beide** Hälften (Read `readFavoritesForEdit` **und**
   Save-Gate im `OnboardingViewModel`, §3 Belang C) sowie die Umstellung/
   Ablösung von `GetFavoriteComponentsUseCase`; `getFavoriteComponentsSnapshot()`
   bleibt für seine zwei anderen Aufrufer erhalten.

---

## Review-Log

- **2026-08-08 — Multi-Agent-Spec-Review (5 Dimensionen + adversarialer Verify,
  10 Agents).** Verdict **GO-WITH-CHANGES**. Tragende Behauptung (DataStore-
  In-Memory-Sharing → Hot-Share kauft nichts) gegen `androidx.datastore 1.2.1`-
  Sourcen **BESTÄTIGT**, keine Messung nötig. Fünf materielle Funde eingearbeitet:
  (#1) Belang C braucht ein Save-Gate im ViewModel, nicht nur die Read-Signatur
  (§3); (#2) `safeReadFlow` bleibt additiv erhalten — direkte Aufrufer HiddenApps
  + Wallpaper (§4/§10); (#3) Stale-Replay-Gate ist namens-getrieben, Config-
  Änderung ist Commit-Teil (§8); (#4) Test-Churn realistisch enumeriert, beide
  `*ShareInTest` gelöscht (§7); (#5) `snapshotFailOpen` rechnet Fallback aus
  `transform(emptyPreferences())` (§4). Verworfen: `silentError→Timber.w` breche
  DEBUG-Tests (widerlegt via `preventCrashForTesting`).
- **2026-08-08 — Multi-Agent-Spec-Review Runde 2 (Verifikation der 5 Fixes +
  neue Widersprüche + adversarialer Save-Gate-Trace, 10 Agents).** Alle 5 Fixes
  **CLOSED**. Ein neuer **MAJOR** (2.1): das boolean Save-Gate hatte ein
  In-Flight-Race (Default-true → „Done vor Read-Auflösung" wipe't Favoriten) →
  auf **Tri-State `NotLoaded/Loaded/Unavailable`, Default gesperrt** umgestellt,
  `INITIAL_SETUP` ausgenommen, Load-Zeit-Fehleranzeige ergänzt (§3 Belang C).
  First-Run-Trap-Verdacht **widerlegt**. MINOR 2.4: §6 schrieb `snapshotFailOpen`
  fälschlich dem Fab zu → auf `readFlowFailOpen` korrigiert, #5 aus „trifft Fab"
  entfernt. Diverse Nits (§2/§7/§8) eingearbeitet. **Verdict: GO für Commit 1
  (Fab)**; 2.1 blockt den Belang-C-Commit, ist aber jetzt in der Spec gelöst.
- **2026-08-08 — Multi-Agent-Spec-Review Runde 3 (Tri-State-Audit, Steelman,
  Kalt-Frischlesung, Completeness-Kritiker, 11 Agents).** Verdict
  **GO-WITH-CHANGES**, kein Code-Defekt, alle Sicherheits-Eigenschaften intakt.
  These **hält** (Steelman: Abbau kostet ~nichts, präzisiert auf „In-Memory-
  Transform-Rerun je Punktlesung"). Tri-State-Gate **SOUND** (DEBUG-Crash-
  Verdacht widerlegt via `launchSafe`). Ein neuer materieller Fund **S1**
  (minor): die Order **ist** über `FavoritesSortViewModel`→`saveOrder` doch
  destruktiv — „nicht destruktiv"-Behauptung durch eine argumentierte Exemption
  ersetzt (§4). Zwei Härtungen: **S2** vierter Envelope `readFavoritesForEdit`
  mit `CancellationException`-first ausgeschrieben + `cancel_files`-Hinweis (§4/§9);
  **S3** neue **DSR-INV-6** (Save-Gate-Write-Ordering). Nits gefegt (invalid-Kotlin
  `PreselectState`, deutsche §4-Kommentare→Englisch, `@ApplicationScope` bleibt,
  Terminologie „zwei Politiken"). **Drei Runden konvergiert → Spec-Review-Phase
  abgeschlossen; Code darf beginnen. Merge-Sperre gilt weiter für den Rewrite
  (Code): kein Merge, bevor der echte Diff mehrere Multi-Agent-Reviews bestanden
  hat.**

---

## 12. Abschluss

Nach dem Merge diese Spec auf „umgesetzt in `<commits>`" aktualisieren und den
AUDIT-13-Anhang „Flows mit `replay` im Repo" um den Hinweis ergänzen, dass die
drei DataStore-Hot-Shares aufgelöst wurden (Tabelle dort wird sonst stale).
