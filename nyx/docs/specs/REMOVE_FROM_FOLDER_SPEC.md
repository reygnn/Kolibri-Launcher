# REMOVE_FROM_FOLDER_SPEC — Extraktion aus Folder + Auto-Dissolve (Sig + Contract)

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als Zwilling zu `MOVE_ITEM_SPEC`.
> Konsumiert das Modell aus `ICON_HOME_MODEL_SPEC` und wiederverwendet `MoveResult.Reason`
> aus `MOVE_ITEM_SPEC`.
>
> **Fokus:** einen App-Member aus einem Folder ziehen (`RemoveFromFolderUseCase`) und
> die daraus folgende **Auto-Dissolve**-Regel — der Fall, den `MOVE_ITEM_SPEC` §8
> bewusst hierher verschoben hat. Wieder reine Transition + dünne IO-Hülle.
>
> **Nicht im Fokus (§8):** Gesten-Layer (Folder öffnen, Member-Drag — `androidTest`),
> Folder-Erzeugung-aus-Extraktion (App-aus-Folder auf App), Folder-Merge, Umsortieren
> *innerhalb* eines Folders.
>
> **Status:** ENTWURF v1. §7-Entscheidungen getroffen (enge v1-Semantik, konsistent
> mit `MOVE_ITEM_SPEC` §7-D2), Review-Runde 1 ausstehend.
>
> **Verhältnis:** identischer Policy/IO-Split wie `MOVE_ITEM_SPEC` §0. Die eine neue
> Idee ist die Folder-Wohlgeformtheit (RFF-INV-1) und ihre atomare Durchsetzung
> (RFF-INV-2).

---

## §0 Die eine neue Invariante: Folder ≥ 2

Ein Folder mit einem Mitglied *ist* keine Folder — es ist eine App. Also:

> **RFF-INV-1 — Folder-Wohlgeformtheit.** Ein persistierter `HomeItem.Folder` hat
> **immer ≥ 2 Mitglieder**. Geboren wird er mit genau 2 (`MOVE_ITEM_SPEC`
> `FolderCreated`); fällt er durch eine Extraktion auf 1, **löst er sich in derselben
> Transition auf** (RFF-INV-2). Ein 1-Member-Folder existiert nie zwischen zwei
> Aufrufen.

Das ist der Grund, warum Extraktion nicht „nur ein Member weniger" ist, sondern zwei
disjunkte Ausgänge hat: **schrumpfen** (Folder bleibt ≥ 2) oder **auflösen**
(Folder war 2, wird 0 sichtbare Folder + 2 Top-Level-Apps).

---

## §1 Typen (`:domain`)

`DropTarget` (Ziel der extrahierten App) ist aus `MOVE_ITEM_SPEC` §2 wiederverwendet.

```kotlin
sealed interface FolderEditResult {
    val layout: HomeLayout?               // null ⇔ Zustand unverändert

    // Folder war ≥3 → schrumpft; extrahierte App wird Top-Level-Item an target.
    data class Extracted(
        override val layout: HomeLayout,
        val app: ItemId,                  // neue Top-Level-ItemId der extrahierten App
    ) : FolderEditResult

    // Folder war genau 2 → aufgelöst.
    data class FolderDissolved(
        override val layout: HomeLayout,
        val extracted: ItemId,            // gezogener Member, jetzt an target
        val survivor: ItemId,             // Rest-Member, an die ALTE Folder-Zelle promotet
    ) : FolderEditResult

    data object NoOp : FolderEditResult { override val layout: HomeLayout? get() = null }
    data class Rejected(val reason: MoveResult.Reason) : FolderEditResult {  // reuse §MOVE_ITEM §2
        override val layout: HomeLayout? get() = null
    }
}
```

---

## §2 Die Extraktions-Matrix

Ziel-Regeln (leer/off-grid/dock voll/belegt) sind **identisch** zu `MOVE_ITEM_SPEC`
§3 — bewusst, damit Extraktion und Bewegung sich gleich anfühlen.

| Folder-Größe *vorher* | Ziel | Ergebnis |
|---|---|---|
| ≥ 3 | leere Zelle / leerer Dock-Slot mit Platz | `Extracted` — Folder schrumpft (bleibt ≥ 2), App an Ziel; **eine** neue `ItemId` |
| = 2 | leere Zelle / leerer Dock-Slot mit Platz | `FolderDissolved` — extrahierte App an Ziel, **Rest-Member an die alte Folder-Zelle/den alten Slot**, Folder-`ItemId` retired; **zwei** neue `ItemId` |
| beliebig | belegte Zelle/Slot | `Rejected(TARGET_OCCUPIED_INCOMPATIBLE)` — Folder-Erzeugung-aus-Extraktion ist v2 (§8, §7-D1) |
| beliebig | `page > pages` / außerhalb `grid` | `Rejected(OFF_GRID)` |
| beliebig | Dock voll (`dock.size == columns`) | `Rejected(DOCK_FULL)` |
| `member ∉ folder` oder `folder` ist kein Folder | — | Programmierfehler → `silentError` → `NoOp` (RFF-INV-4) |

> Lag der Folder **im Dock**, nimmt der Survivor bei Dissolve den Dock-Slot des
> Folders ein (nicht eine Grid-Zelle). „Alte Folder-Zelle/-Slot" meint die Position,
> die der Folder selbst innehatte.

---

## §3 Sigs

```kotlin
object HomeLayoutTransition {              // dieselbe reine Fassade wie MOVE_ITEM_SPEC §4
    fun removeFromFolder(
        layout: HomeLayout,
        folder: ItemId,
        member: ComponentKey,
        target: DropTarget,
        newId: () -> ItemId,               // 1× bei Extracted, 2× bei FolderDissolved
    ): FolderEditResult
}

class RemoveFromFolderUseCase @Inject constructor(
    private val repo: HomeLayoutRepository,
    private val idFactory: ItemIdFactory,  // aus MOVE_ITEM_SPEC §4
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(
        folder: ItemId,
        member: ComponentKey,
        target: DropTarget,
    ): FolderEditResult = withContext(dispatcher) {
        val current = repo.layout().first()
        val result = HomeLayoutTransition.removeFromFolder(
            current, folder, member, target, idFactory::next,
        )
        result.layout?.let { repo.save(it) }   // nur bei echter Änderung (RFF-INV-3)
        result
    }
}
```

---

## §4 Invarianten (`RFF-INV-*`)

- **RFF-INV-1 — Folder ≥ 2** (§0).
- **RFF-INV-2 — Dissolve ist automatisch & atomar.** Fällt ein Folder durch die
  Transition auf 1 Member, wird der Survivor **im selben Ergebnis** zur Top-Level-App
  an der alten Folder-Position promotet und die Folder-`ItemId` retired. Es gibt kein
  Zwischenlayout mit einem 1-Member-Folder.
- **RFF-INV-3 — Rein, total, „nur bei Änderung speichern".** Erbt MIU-INV-1/-2/-3
  wörtlich (reine Policy, totale Funktion, `layout == null` ⇒ kein `save`).
- **RFF-INV-4 — Präconditions sind Programmierfehler, keine Rejects.** `member ∉
  folder` bzw. `folder` referenziert keinen Folder → `silentError` (laut im DEBUG,
  `NoOp` im RELEASE), **nicht** `Rejected` (Rule 11).
- **RFF-INV-5 — ID-Buchhaltung.** `Extracted` prägt genau **eine** neue `ItemId`;
  `FolderDissolved` prägt genau **zwei** (extracted + survivor) und retired die
  Folder-`ItemId`. Kein bestehendes Item ändert seine `ItemId`.
- **RFF-INV-6 — Erhalt der Modell-Invarianten.** Nach der Transition gelten IHM-INV-3
  (keine Kollision/Off-Grid) und IHM-INV-4 (eindeutige, stabile IDs) unverändert.

---

## §5 Test-Inventar

### §5.1 Reine Matrix-Tests (JVM, kein Mock)

Die §2-Tabelle ist der Testplan. Pflicht-Zeilen zusätzlich zur Matrix:

- **Schrumpf-Pfad** (Folder 3→2): Member-Reihenfolge der verbleibenden erhalten.
- **Dissolve-Pfad** (Folder 2→0): Survivor landet exakt auf der alten Folder-Position;
  Folder-`ItemId` taucht danach nirgends mehr auf (RFF-INV-2/-5).
- **Dissolve mit Folder im Dock**: Survivor nimmt den Dock-Slot.
- `member ∉ folder` → `silentError`-Pfad (RFF-INV-4).
- `newId`-Stub liefert deterministische IDs; Aufrufzähler = 1 (Extracted) bzw. 2
  (Dissolved) verifiziert (RFF-INV-5).

### §5.2 Use-Case-Tests (JVM, MockK-Repo)

- liest `repo.layout().first()` einmal, `repo.save(...)` **nur** bei `layout != null`;
- reicht `FolderEditResult` unverändert durch; läuft auf injiziertem Dispatcher.

> **Dispatcher-Konvention (Projekt):** ein Dispatcher via `MainDispatcherRule`, kein
> separater `TestScope`/`StandardTestDispatcher` — `TESTING_CONVENTIONS.kt` im
> Test-Root. `repo` MockK, `idFactory` Fake.

### §5.3 Nicht hier

Kein neues Repository ⇒ kein Contract-Triple. Folder-öffnen/Member-Drag → `androidTest`
(§8).

---

## §6 Cross-Spec-Fund: Duplikat-Handling — ENTSCHIEDEN (Runde 2)

Beim Ausmodellieren aufgefallen: `MOVE_ITEM_SPEC` §3.1 `AddedToFolder` sagte nicht,
was passiert, wenn die Quell-App schon Mitglied des Ziel-Folders ist. Das setzte die
Frage voraus, ob dieselbe `ComponentKey` mehrfach im Layout vorkommen darf.

> **Entschieden:** Variante **(A) App-Unizität** — ratifiziert als
> `ICON_HOME_MODEL_SPEC` **IHM-INV-7**. Jede `ComponentKey` erscheint höchstens einmal
> über `items` ∪ alle Folder ∪ `dock`.

**Konsequenz — schärfer als ursprünglich vermutet:** unter IHM-INV-7 ist eine
top-level App nie zugleich in einem Folder. Damit ist „Add einer schon enthaltenen
App" kein normaler `NoOp`, sondern ein **unerreichbarer** Zustand → Programmierfehler
→ `silentError` (Rule 11). Für diesen Spec heißt das: eine Extraktion entfernt genau
ein tatsächlich vorhandenes Vorkommen; die extrahierte App wird top-level und bleibt
damit unizitätskonform (sie war vorher nur im Folder). Kein zusätzlicher Dedupe-Pfad
nötig.

Die Invariante wird am Rand etabliert (Import/Reconcile: fail-closed dedupe) und von
`MIU-*`/`RFF-*` erhalten.

---

## §7 Entscheidungen (getroffen — v1, Review-Runde 1 ausstehend)

- **§7-D1 — Extraktions-Ziel muss frei sein.** App-aus-Folder auf eine belegte
  Zelle/Slot → `Rejected`, keine neue Folder-Erzeugung. Spiegelt `MOVE_ITEM_SPEC`
  §7-D2 (eine Richtung, nichts Zweideutiges raten).
- **§7-D2 — Survivor erbt die Folder-Position**, nicht eine Nachbarzelle —
  vorhersagbar und ohne Suche nach „nächster freier Zelle".
- **§7-D3 — Dissolve-Schwelle < 2.** Direkt aus RFF-INV-1.
- **§7-D4 — Umsortieren innerhalb eines Folders ist ein anderer Use-Case** (v2), nicht
  dieser.

---

## §8 Außerhalb des Scopes (v2+)

- **Gesten-Layer** — Folder-Sheet öffnen, Member-Drag mit Preview → `:app/androidTest`.
- **Folder-Erzeugung aus Extraktion** (App-aus-Folder auf App) — reserviert, in v1
  `Rejected`.
- **Folder-Merge / Umsortieren im Folder** — eigene Use-Cases.

---

## Review-Log

- **v1 (ENTWURF)** — Erst-Ausformulierung nach „weiter". RFF-INV-1/-2 (Wohlgeformtheit
  + Auto-Dissolve) als Kern, Matrix §2 an `MOVE_ITEM_SPEC` §3 ausgerichtet, enge
  v1-Semantik (§7). Cross-Spec-Fund §6 (Duplikat-Handling) als **offen** notiert, nicht
  still geschlossen.
- **v1.1 (Runde 2)** — §6 entschieden: (A) App-Unizität → `ICON_HOME_MODEL_SPEC`
  IHM-INV-7. Duplikat-Add unerreichbar → `silentError`; kein zusätzlicher
  Dedupe-Pfad in diesem Spec nötig.
