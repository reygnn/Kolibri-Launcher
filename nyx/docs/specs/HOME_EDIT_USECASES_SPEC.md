# HOME_EDIT_USECASES_SPEC — Place / Remove / RenameFolder (Sigs + Contract)

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`. Bündelt die drei kleinen
> mutierenden Use-Cases, die zu schlank für je einen eigenen Spec sind. Konsumiert
> `ICON_HOME_MODEL_SPEC` und wiederverwendet die Move-Matrix aus `MOVE_ITEM_SPEC`.
>
> **Fokus:** `PlaceItemUseCase` (App aus dem Drawer aufs Home), `RemoveItemUseCase`
> (Item vom Home nehmen), `RenameFolderUseCase`. Gleicher Policy/IO-Split wie die
> großen Use-Cases; alles rein/JVM-testbar.
>
> **Nicht im Fokus:** Gesten-Layer, Umsortieren im Folder, Drawer selbst (bestehende
> `GetDrawerAppsUseCase`-Kette aus dem großen Kolibri).
>
> **Status:** ENTWURF v1. §-Entscheidungen getroffen, Review-Runde 1 ausstehend.

---

## §1 Gemeinsames Ergebnis für die zwei trivialen

`RemoveItem` und `RenameFolder` ändern entweder etwas oder nicht — sie brauchen kein
reiches `MoveResult`:

```kotlin
sealed interface LayoutEdit {
    val layout: HomeLayout?                 // null ⇔ unverändert ⇒ kein save
    data class Changed(override val layout: HomeLayout) : LayoutEdit
    data object NoOp : LayoutEdit { override val layout: HomeLayout? get() = null }
}
```

`PlaceItem` dagegen kann eine Folder erzeugen → es gibt `MoveResult` (`MOVE_ITEM_SPEC`
§2) zurück.

---

## §2 `PlaceItemUseCase` — der einzige nicht-triviale

Eine App aus dem Drawer auf ein `DropTarget` setzen. Der Haken ist **IHM-INV-7
(App-Unizität)**: der Drawer zeigt *alle* Apps, auch schon platzierte. Eine App zweimal
aufs Home zu legen ist verboten.

> **HEU-INV-1 — Place ist unizitätssicher via Delegation.** Ist `app` **bereits**
> irgendwo im Layout, ist `place` **kein** Neu-Einfügen, sondern ein `move` des
> vorhandenen Items an das Ziel (delegiert an `MOVE_ITEM_SPEC`). Nur wenn `app` noch
> nicht platziert ist, entsteht ein neues `HomeItem.App` (neue `ItemId`). So kann nie
> ein Duplikat entstehen.

Ist die App neu, folgt der Ausgang exakt der Move-Matrix (`MOVE_ITEM_SPEC` §3):

| App-Status | Ziel | Ergebnis |
|---|---|---|
| bereits platziert | beliebig | wie `move(vorhandene ItemId, target)` — volle Matrix |
| neu | leere Zelle / freier Dock-Slot | `Moved` (neues App-Item, neue `ItemId`) |
| neu | belegt mit App | `FolderCreated` `[Ziel-App, neue App]` |
| neu | belegt mit Folder | `AddedToFolder` |
| neu | belegt (Dock) / off-grid / Dock voll | `Rejected(...)` wie Move |

```kotlin
object HomeLayoutTransition {                 // dieselbe reine Fassade
    fun place(
        layout: HomeLayout,
        app: ComponentKey,
        target: DropTarget,
        newId: () -> ItemId,                  // App-Item und/oder Folder
    ): MoveResult
}

class PlaceItemUseCase @Inject constructor(
    private val repo: HomeLayoutRepository,
    private val idFactory: ItemIdFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(app: ComponentKey, target: DropTarget): MoveResult =
        withContext(dispatcher) {
            val current = repo.layout().first()
            val result = HomeLayoutTransition.place(current, app, target, idFactory::next)
            result.layout?.let { repo.save(it) }
            result
        }
}
```

---

## §3 `RemoveItemUseCase`

Ein Top-Level-Item vom Home nehmen. **Apps gehen nicht verloren** — der Drawer zeigt
ohnehin alle installierten Apps; „entfernen" heißt nur „nicht mehr auf dem Home".

> **HEU-INV-2 — Folder-Entfernen verliert keine App.** Wird ein `Folder` entfernt,
> verschwindet nur das Folder-Item; seine Member sind Apps und bleiben im Drawer
> erreichbar. Kein Auto-Dissolve, kein Promoten (das ist Schrumpf-Semantik,
> `REMOVE_FROM_FOLDER_SPEC`, nicht Entfernen).

- Zelle/Slot wird frei (erhält IHM-INV-3/-4).
- `id` unbekannt → Programmierfehler → `silentError` → `NoOp` (Rule 11).

```kotlin
object HomeLayoutTransition { fun remove(layout: HomeLayout, id: ItemId): LayoutEdit }

class RemoveItemUseCase @Inject constructor(
    private val repo: HomeLayoutRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(id: ItemId): LayoutEdit = withContext(dispatcher) {
        val current = repo.layout().first()
        HomeLayoutTransition.remove(current, id)
            .also { it.layout?.let(repo::save) }
    }
}
```

---

## §4 `RenameFolderUseCase`

```kotlin
object HomeLayoutTransition {
    fun renameFolder(layout: HomeLayout, folder: ItemId, title: String): LayoutEdit
}

class RenameFolderUseCase @Inject constructor(
    private val repo: HomeLayoutRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(folder: ItemId, title: String): LayoutEdit =
        withContext(dispatcher) {
            val current = repo.layout().first()
            HomeLayoutTransition.renameFolder(current, folder, title)
                .also { it.layout?.let(repo::save) }
        }
}
```

- `title == alter Titel` → `NoOp` (kein save).
- Leerer Titel **erlaubt** — die UI zeigt dann die lokalisierte Default-Beschriftung
  (MIU-INV-6-Geist: `:domain` bleibt string-/`@StringRes`-frei).
- `folder` ist kein Folder / unbekannt → `silentError` → `NoOp`.

---

## §5 Invarianten-Sammelband

- **HEU-INV-1** — Place unizitätssicher (§2).
- **HEU-INV-2** — Folder-Entfernen verliert keine App (§3).
- **HEU-INV-3** — Alle drei erben die Familien-Disziplin: reine Policy, totale Funktion,
  „nur bei echter Änderung speichern", Preconditions sind `silentError` (nicht
  `Rejected`), IHM-INV-3/-4/-7 bleiben erhalten.

---

## §6 Test-Inventar (JVM, kein Device)

- **Place, App neu:** Move-Matrix-Zeilen (leer/App/Folder/reject) mit frischer `ItemId`.
- **Place, App bereits platziert:** identisches Ergebnis wie der entsprechende
  `move`-Aufruf; **kein** Duplikat entsteht (HEU-INV-1) — der Schlüsseltest.
- **Remove App vs. Folder:** Zelle frei; Folder-Remove lässt keine Member-Waise zurück
  (HEU-INV-2); unbekannte `id` → `silentError`-Pfad.
- **Rename:** Titel gesetzt; gleicher Titel → `NoOp` (kein save); leerer Titel erlaubt;
  Nicht-Folder → `silentError`.
- **Use-Case-Ebene (MockK):** liest einmal, speichert nur bei `layout != null`, läuft
  auf injiziertem Dispatcher.

> **Dispatcher-Konvention:** ein Dispatcher via `MainDispatcherRule`, kein separater
> `TestScope`/`StandardTestDispatcher` — `TESTING_CONVENTIONS.kt` im Test-Root.

Kein neues Repository ⇒ kein Contract-Triple.

---

## §7 Entscheidungen (getroffen — v1)

- **§7-D1 — Place delegiert bei bereits platzierter App an Move**, statt zu
  `Rejected`/`NoOp`. Intuitiver (Drag aus Drawer „holt" die App ans Ziel) und die
  einzige unizitätssichere Variante.
- **§7-D2 — Remove ist kein Dissolve.** Folder-Remove entfernt das Folder-Item ganz;
  Member bleiben im Drawer. Schrumpf/Auto-Dissolve ist ausschließlich
  `REMOVE_FROM_FOLDER_SPEC`.
- **§7-D3 — Leerer Folder-Titel erlaubt**, Default-Label ist UI.

---

## Review-Log

- **v1 (ENTWURF)** — Erst-Ausformulierung nach „weiter". Drei kleine Use-Cases
  gebündelt; einzig `PlaceItem` trägt neue Logik (HEU-INV-1, Unizitäts-Delegation), der
  Rest ist Familien-Disziplin.
