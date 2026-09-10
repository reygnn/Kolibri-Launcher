# MOVE_ITEM_SPEC — Drop-Semantik als reine Transition (Sig + Contract)

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als erster ausmodellierter
> Use-Case des Icon-Launchers. Konsumiert das Modell + die Invarianten aus
> `ICON_HOME_MODEL_SPEC`.
>
> **Fokus:** die Drop-Semantik des Home-Grids — `MoveItemUseCase` und die reine
> Zustandsfunktion `HomeLayoutTransition.move(...)` darunter. Das ist der Use-Case
> mit dem meisten Verhalten (Drop-auf-App ⇒ Folder), und er ist zu 100 % ohne
> Android/Coroutine als Wahrheitstabelle testbar.
>
> **Nicht im Fokus (§8):** der Gesten-/Touch-Layer (Drag-Preview, Auto-Scroll,
> Touch-Priorität — `androidTest`), `RemoveFromFolderUseCase` inkl. Auto-Dissolve,
> Folder-Merge, Folder-in-Dock, Page-Compaction. Alle referenziert, keiner hier
> ausmodelliert.
>
> **Status:** ENTWURF v1. Scope-Entscheidungen in §7 sind getroffen (bewusst enge
> v1-Semantik), Review-Runde 1 ausstehend.
>
> **Verhältnis zum großen Kolibri:** dieselbe Trennung wie `APPLIST_SORT_SPLIT_SPEC`
> („Policy dem reinen Consumer, nicht dem IO-Helfer") und dasselbe Rückgabe-Muster
> wie die Use-Case-Messages dort — ein **sealed Ergebnis-Identifier** (`MoveResult`),
> den `:app` per Mapper zu Toast/Announce/Animation übersetzt, statt einer nackten
> `HomeLayout`.

---

## §0 Warum das ein eigener Spec ist

`MoveItemUseCase` hat zwei getrennte Verantwortungen, die man auseinanderhalten muss,
sonst wird er unprüfbar:

1. **Policy** — „gegeben ein `HomeLayout`, ein bewegtes `ItemId` und ein
   `DropTarget`: was ist das Folge-Layout und was ist passiert?" Reine Funktion,
   keine Seiteneffekte.
2. **IO** — den aktuellen Zustand lesen, die Policy anwenden, das Ergebnis
   persistieren, auf dem richtigen Dispatcher.

> **MIU-INV-1 — Policy ist rein.** `HomeLayoutTransition.move(...)` hängt an keinem
> Repository, keinem Dispatcher, keinem Android-Typ und keiner Uhr/RNG (IDs kommen
> als injizierte Factory, §4). Sie ist eine totale Funktion (§5) und damit als
> Wahrheitstabelle testbar (§6.1). Der Use-Case ist die dünne IO-Hülle und wird
> separat, mit MockK-Repo, getestet (§6.2).

Das spiegelt exakt den Split, mit dem der große Kolibri die Sortierung aus dem
Namens-Helfer gezogen hat (`APPLIST_SORT_SPLIT_SPEC`, RAL-4).

---

## §1 Zielbild

- Eine reine `move`-Funktion mit einer **vollständigen, kollisionsfreien**
  Drop-Matrix (§3).
- Ein Use-Case, der **liest → transformiert → nur bei echter Änderung speichert** und
  das `MoveResult` unverändert nach oben durchreicht.
- Bewusst **enge v1-Semantik**: genau eine Richtung Folder-Erzeugung (App auf App /
  App auf Folder), alles Zweideutige wird sauber `Rejected` statt heuristisch geraten.

---

## §2 Typen (`:domain`)

```kotlin
// Wohin gedroppt wurde. DockSlot ist seitenlos (kein CellPos), vgl. HomeLayout.dock.
sealed interface DropTarget {
    data class Cell(val pos: CellPos) : DropTarget
    data class DockSlot(val index: Int) : DropTarget
}

// Was passiert ist — sealed Identifier, :app mappt zu UI-Feedback (kein String hier).
sealed interface MoveResult {
    val layout: HomeLayout?          // null ⇔ Zustand unverändert (NoOp/Rejected)

    data class Moved(override val layout: HomeLayout) : MoveResult
    data class FolderCreated(override val layout: HomeLayout, val folder: ItemId) : MoveResult
    data class AddedToFolder(override val layout: HomeLayout, val folder: ItemId) : MoveResult

    data object NoOp : MoveResult { override val layout: HomeLayout? get() = null }
    data class Rejected(val reason: Reason) : MoveResult {
        override val layout: HomeLayout? get() = null
    }

    enum class Reason { TARGET_OCCUPIED_INCOMPATIBLE, OFF_GRID, DOCK_FULL }
}
```

---

## §3 Die Drop-Matrix (die eigentliche Spezifikation)

Quelle ist ein **Top-Level**-Item (aus `items` oder `dock`); Items *innerhalb* eines
Folders sind nicht Quelle dieses Use-Cases (§8, `RemoveFromFolderUseCase`).

### §3.1 Ziel = `Cell`

| Quelle | Zielzelle | Ergebnis |
|---|---|---|
| App | leer | `Moved` — Quelle in Zielzelle, alte Zelle frei |
| App | = eigene aktuelle Zelle | `NoOp` |
| App | belegt mit App | `FolderCreated` — neuer Folder `[Ziel-App, Quell-App]`, in Zielzelle; beide alten Zellen frei; genau **eine** neue `ItemId` |
| App | belegt mit Folder | `AddedToFolder` — Quell-App ans Folder-Ende angehängt; Quell-Zelle frei; Folder bleibt an seiner Zelle (fügt unter IHM-INV-7 immer echt hinzu) |
| Folder | leer | `Moved` |
| Folder | = eigene aktuelle Zelle | `NoOp` |
| Folder | belegt mit App | `Rejected(TARGET_OCCUPIED_INCOMPATIBLE)` — Folder-auf-App ist in v1 kein Merge-Trigger (§7-D2) |
| Folder | belegt mit Folder | `Rejected(TARGET_OCCUPIED_INCOMPATIBLE)` — Merge ist v2 (§8) |
| — | `page == layout.pages` (genau eine über der letzten) | Seite anhängen, dann wie oben |
| — | `page > layout.pages` oder `x/y` außerhalb `grid` | `Rejected(OFF_GRID)` |

### §3.2 Ziel = `DockSlot`

| Quelle | Dock-Slot | Ergebnis |
|---|---|---|
| App oder Folder | leer, Dock hat Platz | `Moved` (Item wird Dock-Item) |
| App oder Folder | Dock voll (`dock.size == columns`, §7-D3) | `Rejected(DOCK_FULL)` |
| App oder Folder | belegt | `Rejected(TARGET_OCCUPIED_INCOMPATIBLE)` — keine Folder-Erzeugung im Dock in v1 (§7-D2) |

> Ein Item, das aus dem Dock aufs Grid (oder umgekehrt) wandert, ist derselbe
> `move`-Aufruf — die Quelle wird über `ItemId` gefunden, egal ob sie in `items` oder
> `dock` lag.

---

## §4 Sigs

```kotlin
// :domain/model — reine Policy. IDs als Factory injiziert ⇒ deterministisch testbar.
object HomeLayoutTransition {
    fun move(
        layout: HomeLayout,
        moving: ItemId,
        target: DropTarget,
        newFolderId: () -> ItemId,   // nur bei FolderCreated aufgerufen
    ): MoveResult
}

// :domain/usecase — dünne IO-Hülle (Rule 1: geht durchs Repository).
class MoveItemUseCase @Inject constructor(
    private val repo: HomeLayoutRepository,
    private val idFactory: ItemIdFactory,       // Impl in :data (UUID); Fake in Tests
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(moving: ItemId, target: DropTarget): MoveResult =
        withContext(dispatcher) {
            val current = repo.layout().first()
            val result = HomeLayoutTransition.move(current, moving, target, idFactory::next)
            result.layout?.let { repo.save(it) }   // nur bei echter Änderung
            result
        }
}

fun interface ItemIdFactory { fun next(): ItemId }
```

---

## §5 Invarianten (`MIU-INV-*`)

- **MIU-INV-1 — Policy ist rein** (§0).
- **MIU-INV-2 — Totale Funktion.** Für jedes `(layout, moving, target)` liefert `move`
  genau ein `MoveResult` und **wirft nie** für UI-erreichbare Eingaben. Ein
  unbekanntes `moving` ist kein UI-Fall, sondern ein Programmierfehler → `silentError`
  (laut im DEBUG, `NoOp` im RELEASE), **nicht** `Rejected` (Rule 11: keinen
  can't-throw wegfangen und zu Nutzer-Feedback umdeuten).
- **MIU-INV-3 — Zustands-Erhalt bei Nicht-Änderung.** `NoOp` und `Rejected` geben
  `layout == null` zurück; der Use-Case **speichert dann nicht** (kein leerer
  DataStore-Write, kein Flow-Tick).
- **MIU-INV-4 — Keine Waisen-Zelle.** Eine erfolgreiche Bewegung räumt die
  Quell-Zelle/den Quell-Slot atomar; nach `move` verweist keine zwei Positionen auf
  dieselbe `ItemId` (erhält IHM-INV-4) und keine belegte Zelle überlappt (erhält
  IHM-INV-3).
- **MIU-INV-5 — ID-Stabilität.** `move` ändert die `ItemId` der Quelle nie.
  `FolderCreated` prägt **genau eine** neue `ItemId` (via `newFolderId`);
  `AddedToFolder` prägt keine.
- **MIU-INV-6 — Folder-Titel bleibt Framework-frei.** Ein neu erzeugter Folder hat
  `title == ""`; die lokalisierte Default-Beschriftung ist reine UI (`:app`), damit
  `:domain` string-/`@StringRes`-frei bleibt (IHM-INV-1-Geist).
- **MIU-INV-7 — Determinismus.** Gleiche Eingabe + gleiche `newFolderId`-Folge ⇒
  bit-gleiches `MoveResult`. Keine Iteration über ungeordnete Mengen (Member-Reihung
  ist Liste, nicht Set).

---

## §6 Test-Inventar

### §6.1 Reine Matrix-Tests (JVM, kein Mock)

Die §3-Tabelle **ist** der Testplan — eine parametrisierte Wahrheitstabelle über
`{Quelle: App, Folder} × {Ziel-Zustand} × {Cell, DockSlot}`. Zusätzlich die
Rand-Zeilen: Self-Drop → `NoOp`; `page == pages` → Seite angehängt; `OFF_GRID`;
`DOCK_FULL`; unbekanntes `moving` → `silentError`-Pfad (MIU-INV-2).
`newFolderId` ist ein Stub, der `ItemId("folder-test")` liefert (MIU-INV-7).

### §6.2 Use-Case-Tests (JVM, MockK-Repo)

- liest `repo.layout().first()` genau einmal, speichert **nur** bei `layout != null`
  (MIU-INV-3) — verify `repo.save(...)` wird bei `NoOp`/`Rejected` **nie** gerufen;
- reicht das `MoveResult` unverändert durch;
- läuft auf dem injizierten Dispatcher.

> **Dispatcher-Konvention (Projekt):** ein Dispatcher über `MainDispatcherRule`, kein
> separater `TestScope`/`StandardTestDispatcher` — siehe `TESTING_CONVENTIONS.kt` im
> Test-Root. `repo` ist MockK, `idFactory` ein Fake.

### §6.3 Nicht hier

Kein neues Repository ⇒ **kein** neues Contract-Triple. `HomeLayoutRepository`s
Triple wohnt in `ICON_HOME_MODEL_SPEC` §4. Der Gesten-Layer → `androidTest` (§8).

---

## §7 Entscheidungen (getroffen — v1, Review-Runde 1 ausstehend)

- **§7-D1 — Folder-Mitglieder-Reihung: Ziel zuerst.** Neuer Folder ist
  `[Ziel-App, Quell-App]` — die App, auf der man landet, steht vorn. Deterministisch
  (MIU-INV-7), keine „wer war zuerst da"-Heuristik.
- **§7-D2 — Nur eine Richtung Folder-Erzeugung.** App→App und App→Folder erzeugen/
  füllen; Folder→App, Folder→Folder und alles im Dock → `Rejected`. Hält die Matrix
  eindeutig; Merge & Dock-Folder sind bewusst v2.
  > **Entschieden (Runde 2, `ICON_HOME_MODEL_SPEC` IHM-INV-7 „App-Unizität"):** eine
  > top-level App ist nie zugleich in einem Folder, daher ist `AddedToFolder` einer
  > bereits enthaltenen App ein **unerreichbarer** Zustand → `silentError` (Rule 11),
  > **kein** `NoOp` und **kein** `Rejected`. `AddedToFolder` fügt immer echt hinzu.
- **§7-D3 — Dock-Kapazität = `grid.columns`.** (Beantwortet `ICON_HOME_MODEL_SPEC`
  §10-Punkt 3 vorläufig.) Volles Dock → `Rejected(DOCK_FULL)`.
- **§7-D4 — Keine Auto-Compaction.** `move` kann eine Seite anhängen, entfernt aber
  nie eine leer gewordene. Leere Seiten aufräumen ist ein separater, expliziter Pass
  (fail-closed, analog Reconcile), nicht Teil einer Bewegung — sonst wäre `move` nicht
  mehr rein-lokal begründbar.

---

## §8 Außerhalb des Scopes (v2+)

- **Gesten-/Touch-Layer** — Drag-Preview, Kanten-Auto-Scroll, Touch-Priorität,
  reale Grid-Measure. Nur auf Device wahr → `:app/androidTest`.
- **`RemoveFromFolderUseCase` + Auto-Dissolve** — voll ausmodelliert in
  `REMOVE_FROM_FOLDER_SPEC` (RFF-INV-1/-2). Item aus Folder aufs Grid ziehen; Folder
  mit dann 1 Mitglied löst sich zur nackten App auf.
- **Folder-Merge** (Folder→Folder) und **Dock-Folder** — reservierte `MoveResult`-
  Erweiterung, in v1 `Rejected`.
- **Page-Compaction** — der §7-D4-Aufräum-Pass.

---

## Review-Log

- **v1 (ENTWURF)** — Erst-Ausformulierung nach „weiter". Policy/IO-Split gesetzt,
  Drop-Matrix §3 vollständig, enge v1-Semantik (§7-D1..D4) als Entscheidungen
  eingetragen, Merge/Dock-Folder/Auto-Dissolve nach v2 verschoben.
- **v1.1** — `AddedToFolder`-Duplikatfrage durch `ICON_HOME_MODEL_SPEC` IHM-INV-7
  (App-Unizität) geschlossen: Duplikat-Add ist unerreichbar → `silentError`. §7-D2 +
  Matrix §3.1 entsprechend präzisiert.
