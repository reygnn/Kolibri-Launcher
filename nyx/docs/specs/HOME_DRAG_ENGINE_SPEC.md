# HOME_DRAG_ENGINE_SPEC

Eigene, touch-getrackte Drag-Engine für den internen Home-Drag — als Ersatz für
das Android-View-Drag-&-Drop (`View.startDragAndDrop`). Vorbild ist Launcher3
(`DragController` / `DragLayer` / `DragView`). **Status: Phasen 1+2 umgesetzt (§9),
Phase 3 (Kür) offen.** Code: `home/drag/` (`DragLayer`, `DragController`,
`DropZone`) + `MainActivity` (Zonen-Registrierung, `resolveGridCell`).

---

## §0 Motivation

Der heutige Drag nutzt das OS-Drag-&-Drop: `view.startDragAndDrop(...)`, plus
`OnDragListener` auf `home_root` (Grid-Catch-all), `dock` und `remove_bar`. Das
**OS** führt die Geste und liefert `ACTION_DRAG_LOCATION`/`ACTION_DROP` an das
**Fenster unter dem Finger**. Daraus folgen die bekannten Grenzen:

- **Statusbar-Fenster klaut die obere Zone.** Die Statusbar ist ein eigenes
  System-Fenster über der App. Sobald der Finger dort hineinkommt, bekommt die
  App `ACTION_DRAG_EXITED`, und ein Drop landet nicht in der App. Die
  Remove-Zone ist deshalb **nicht bis zur Display-Oberkante droppbar** (siehe
  auch die Edge-to-Edge-Optik: rot bis oben, aber interaktiv nur unter der
  Statusbar).
- **Kein Zugriff auf die rohen Move-Koordinaten** über System-/Fremdflächen →
  keine eigenen Cues (Spring-loaded-Scale, Edge-Autoscroll zwischen Seiten,
  eigene Lift-/Snap-Animationen).
- **Drop-Ziel = View-Z-Order + verstreute Listener** statt einer klaren,
  priorisierten Liste (heute: `home_root`-Catch-all + Geometrie in
  `resolveGridCell`, dazu Sonder-Listener für Dock und Remove-Bar).

Ziel: eine Engine, die die Geste **nie ans System abgibt**, alle Move/Up-Events
selbst erhält und Drop-Ziele über **Rechteck-Hit-Test gegen eine priorisierte
Liste** auflöst — damit ist jeder Punkt droppbar, inkl. der oberen Kante.

---

## §1 Scope

**Im Scope** — der interne Drag:
- Grid-Icon verschieben (`DragPayload.Existing`).
- Dock-Icon verschieben.
- Drawer-App auf die Home legen (`DragPayload.NewApp`).
- Löschen über die Remove-Zone.

**Nicht im Scope:**
- Cross-App-Drag (echtes System-DnD zwischen Apps) — bleibt beim OS-Mechanismus,
  falls je gebraucht.
- Widgets.
- **Die Domäne bleibt unangetastet:** `DropTarget`, `HomeLayoutTransition`,
  `MoveItemUseCase`/`PlaceItemUseCase`/`RemoveItemUseCase`,
  `HomeLayoutRegridder`. Die Engine liefert am Ende nur `(payload, target)` und
  ruft dieselben ViewModel-Methoden wie heute. Nur der **UI-Drag-Mechanismus**
  und die **Drop-Ziel-Auflösung** werden ersetzt.

---

## §2 Komponenten

- **`DragLayer`** — bildschirmfüllende ViewGroup, die die Geste besitzt und die
  `DragView` zeichnet. Kandidat: `home_root` (bereits `GestureFrameLayout`,
  bildschirmfüllend) übernimmt die Rolle, oder ein dedizierter Layer *über*
  allem (auch über Dock und — bei Bedarf — hinter der ausgeblendeten Statusbar).
- **`DragController`** — Zustandsmaschine: `startDrag(payload, source, touch)`,
  `onMove(x, y)`, `drop(x, y)`, `cancel()`. Hält die registrierte, geordnete
  Liste der `DropTarget`s. Kein Android-Framework-DnD.
- **`DragView`** — das mitgezogene Icon (Bitmap/Shadow des Quell-Views), folgt
  dem Finger via `translationX/Y`; Lift-Scale/Elevation beim Start.
- **`DropTarget`** (Interface):
  - `hitRect(): Rect` — in `DragLayer`-Koordinaten.
  - `acceptDrop(payload): Boolean`.
  - `onDragEnter()/onDragExit()` — Highlight.
  - `onDrop(payload, x, y)`.
- **`DragSource`** — liefert beim Start das `DragPayload` und den Quell-View
  (für Shadow + Rück-Animation bei Cancel).

Wiederverwendet: `DragPayload` (`Existing`/`NewApp`), `DropTarget.Cell`/
`.DockSlot`/`.DockItem`, die Zell-Geometrie aus `resolveGridCell`.

---

## §3 Touch-Flow

1. **Long-press** auf einem Icon (Grid/Dock/Drawer) ruft `DragController.startDrag`.
   Der `DragLayer` übernimmt ab hier den Touch
   (`onInterceptTouchEvent → true`), die `DragView` erscheint unter dem Finger.
2. Weil der Touch beim `ACTION_DOWN`/Long-press bereits im **App-Fenster**
   gefangen ist (Touch-Capture), erhält die App **alle** folgenden `MOVE` bis
   `UP` — auch über Statusbar/Navigationsleiste. **DRG-INV-1.**
3. `onMove(x, y)`: `DragView` folgt; `findDropTarget(x, y)` bestimmt das Ziel,
   `onDragEnter/Exit` schalten die Highlights (z. B. Remove-Zone rot-aktiv).
4. `ACTION_UP` → `drop(x, y)`: getroffenes Ziel `onDrop`, sonst `cancel()` →
   `DragView` animiert zur Quelle zurück (**DRG-INV-3**, kein Verlust).

---

## §4 Drop-Ziel-Auflösung

`findDropTarget(x, y)` iteriert die Ziele in **Prioritätsreihenfolge** und nimmt
das erste mit `hitRect.contains(x, y) && acceptDrop(payload)`. **DRG-INV-2** —
entscheidet der Hit-Test, nicht die View-Z-Order.

Reihenfolge (oben gewinnt):
1. **RemoveZone** — `hitRect` reicht bis `y = 0` (Oberkante). `acceptDrop` nur
   für `DragPayload.Existing`.
2. **Dock** — `hitRect` = Dock-Bereich. **Mitte-vs-Rand wie das Grid**
   (`GRID_INSERT_EDGE_FRACTION`, gemeinsame Konstante): liegt der Finger über den
   zentralen ~60% eines Dock-Icons → `DockItem(index)` (auf das Icon landen: Ordner
   anlegen / hinzufügen / `MovedBetweenFolders`); der äußere ~20%-Rand → `DockSlot(index)`
   (zwischen Icons einfügen). So verhalten sich Dock-Folder identisch zu Grid-Foldern.
3. **Grid (aktuelle Seite)** — Rest der Fläche; Zelle aus (x, y) per Geometrie
   (die heutige `resolveGridCell`-Rechnung als `GridDropTarget`).

Kein Treffer → `cancel()`.

---

## §5 Remove-Zone bis zur Oberkante (der eigentliche Auslöser)

Da die Geste nie ans System geht (**DRG-INV-1**), gehört der obere Bereich —
auch hinter der Statusbar — zum `DragLayer`. Damit reicht das `hitRect` der
RemoveZone bis `y = 0`; ein Drop dort löscht zuverlässig, das Highlight bleibt
bis zur Kante aktiv. Die heutige Beobachtung „Zone endet gefühlt unter der
Notification-Bar / Icon fällt oben ins Grid zurück" entfällt.

Optik (orthogonal): rot bis oben wie gehabt. Ob die **System-Icons** während des
Drags ausgeblendet werden (Immersive), ist eine reine Darstellungsfrage und für
die Funktion **nicht** mehr nötig — der Hit-Test funktioniert unabhängig davon.

---

## §6 Integration mit der Domäne

Beim Drop mappt die Engine `target` auf die bestehenden ViewModel-Aufrufe —
identisch zu heute:

| Target        | Payload `Existing(id)` | Payload `NewApp(key)` |
|---------------|------------------------|-----------------------|
| `Cell`        | `viewModel.move(id, Cell)`   | `viewModel.place(key, Cell)`   |
| `DockSlot`    | `viewModel.move(id, DockSlot)` | `viewModel.place(key, DockSlot)` |
| `DockItem`    | `viewModel.move(id, DockItem)` | `viewModel.place(key, DockItem)` |
| `RemoveZone`  | `viewModel.remove(id)` | (kein `acceptDrop`)   |

`HomeLayoutTransition` + Use Cases bleiben unverändert (**DRG-INV-4**). Die
reine Move/Place-Logik (Ordner anlegen/hinzufügen/ablehnen) ist weiterhin die
Wahrheit.

---

## §7 Koexistenz mit der Gesten-Schicht

`GestureFrameLayout`/`GestureDispatchCore` (Swipe-up → Drawer, Long-press auf
leer → Settings) muss mit dem Drag koexistieren:

- **Long-press auf ein Icon** → `DragController.startDrag` (nicht Settings). Die
  Icons sind `isLongClickable`; der Gesten-Core unterdrückt seinen eigenen
  Long-press über solchen Kindern schon heute (Hit-Test in
  `hasOwnTouchPipelineDescendantAt`).
- **Long-press auf leere Fläche** → Settings (Gesten-Schicht).
- **Swipe-up** → Drawer (Gesten-Schicht), solange kein Drag läuft.

Offen: die genaue Touch-Ownership-Übergabe — der `DragLayer` fängt erst **nach**
dem Long-press-Trigger; bis dahin läuft der Touch durch `GestureDispatchCore`.
Sauber zu definieren, damit sich Drag-Start und Swipe/Long-press nicht in die
Quere kommen (Kandidat: der Long-press-Callback des Icons startet den Drag und
setzt `DragLayer` in den „fangenden" Zustand).

---

## §8 Optional: Animationen / Spring-loaded

Erst mit eigener Engine möglich, weil die App die Frames kontrolliert:
- **Lift** der `DragView` beim Start (Scale/Elevation).
- **Snap** zur Zielzelle beim Drop, **Rück-Animation** bei Cancel.
- **Spring-loaded**: Grid beim Drag leicht herunterskalieren (~0.96) als
  „Arrange"-Cue (Launcher3).
- **Edge-Autoscroll**: Drag an den linken/rechten Pager-Rand blättert die Seite.

Alles Kür, nicht Teil des Kern-Umbaus.

---

## §9 Migration (phasenweise)

- **Phase 1 — Engine einführen.** ✅ **umgesetzt.** `DragLayer` (ersetzt das
  gemeinsame `GestureFrameLayout` als Home-Root und fährt `GestureDispatchCore`
  weiter, solange kein Drag läuft) + `DragController` + Snapshot-`DragView`.
  `DropZone`-Interface (so genannt, um vom Domänen-`DropTarget` unterscheidbar zu
  bleiben); RemoveZone/Dock/Grid als `DropZone`s registriert. Icon-Long-press
  ruft die Engine. **Anders als skizziert nicht „parallel", sondern gleich mit
  Phase 2 kombiniert** — kein Bedarf für eine Übergangsphase mit beiden Systemen.
- **Phase 2 — OS-DnD entfernen.** ✅ **umgesetzt (mit Phase 1).**
  `startDragAndDrop` und die `home_root`/`dock`/`remove_bar`-`OnDragListener`
  (plus `handleDockDrag`) sind raus. Die reine (x,y)→`CellPos`-Geometrie ist in
  `gridCellAt` (`HomeGridGeometry.kt`) extrahiert und JVM-getestet
  (`HomeGridGeometryTest`, 8 Fälle); `MainActivity.resolveGridCell` ist nur noch
  View-Glue (Seiten-View + Offset → lokale Koordinaten → `gridCellAt`).
  **Bewusste Abweichung:** kein separates `GridDropTarget`-Klasse — die Grid-
  `DropZone` bleibt ein dünner Inline-Aufruf von `resolveGridCell`; eine eigene
  Klasse wäre nur Indirektion.
- **Phase 3 — Kür.** ⏳ **offen.** Lift-Scale beim Aufnehmen, Snap-/Zurück-Flug-
  Animation, Spring-loaded, Edge-Autoscroll, optional Statusbar-Ausblenden.

**Tests:**
- (x,y)→`CellPos`-Geometrie — ✅ JVM (`HomeGridGeometryTest`, 8 Fälle).
- `DragController`-Zustandsmaschine (start → move → drop/cancel) **und**
  `findZone`-Priorität + Hit-Test — ✅ `DragControllerTest` (9 Fälle, Robolectric
  nur für `Rect`/`View`). Möglich durch die `DragViewHost`-Abstraktion, gegen die
  der Controller getestet wird.
- Instrumentiert nur, was echtes Touch/Fenster-Verhalten braucht (Drop an der
  Oberkante, Touch-Capture über der Statusbar) — value bar, nicht cost bar.
  On-device (A17) manuell verifiziert.

---

## §10 Invarianten (Entwurf)

- **DRG-INV-1** — Die Drag-Geste wird nach dem Long-press **nie** an ein
  System-/Fremdfenster abgegeben (Touch-Capture im App-Fenster). Daraus folgt
  die Droppbarkeit an der Display-Oberkante.
- **DRG-INV-2** — Das Drop-Ziel wird durch Rechteck-Hit-Test gegen eine
  **priorisierte Liste** bestimmt, nicht durch View-Z-Order.
- **DRG-INV-3** — Verlustfrei: ein Drop ohne Treffer animiert zur Quelle zurück,
  kein Item geht verloren.
- **DRG-INV-4** — Die Domänen-Transitionen (`HomeLayoutTransition`, Use Cases,
  `HomeLayoutRegridder`) bleiben unverändert; die Engine liefert nur
  `(payload, target)`.

---

## §11 Offene Punkte

1. ~~**DragLayer-Rolle:** übernimmt `home_root` (`GestureFrameLayout`) den Layer,
   oder ein dedizierter Layer über allem?~~ — **gelöst:** `home_root` *ist* der
   `DragLayer` (ersetzt `GestureFrameLayout`), full-screen inkl. Dock.
2. ~~**Touch-Ownership** `DragController` ↔ `GestureDispatchCore` beim Long-press~~
   — **gelöst:** ein `dispatchTouchEvent` — idle → Core (Swipe/Long-press),
   dragging → Kurzschluss zum `DragController` (+ `ACTION_CANCEL` an die Kinder).
   Gesten während des Drags implizit aus.
3. **Abbruch-Fälle** (teilweise): `ACTION_CANCEL` → `onCancel()` räumt sofort
   sauber ab; Rotation mitten im Drag = Activity-Recreate (Drag verfällt, ok).
   **Offen:** Rück-Flug-**Animation** (Kür, §8) und explizites **Multi-Touch /
   Zweiter-Finger**-Handling.
4. **Edge-Autoscroll** zwischen Pager-Seiten — ja/nein, und wie mit ViewPager2
   (dessen eigenes Touch-Handling ist dann inaktiv, weil der DragLayer fängt).
   (Kür, §8.)
5. **Statusbar-Icons** während des Drags ausblenden — reine Optik, separat
   entscheidbar.
6. ~~**Aufwand/Nutzen:** lohnt der Umbau?~~ — **entschieden: ja, umgesetzt**
   (Phasen 1+2). Die Oberkanten-Droppbarkeit allein hat den Umbau getragen; die
   Kür (§8) ist die optionale Zugabe.

---

## Review-Log

- **v1 (ENTWURF)** — Skizze auf Wunsch, als Alternative zum pragmatischen
  „Statusbar während des Drags ausblenden". Noch nicht ratifiziert; kein Code.
  Heutige Anker, die der Umbau ersetzt: `MainActivity.startDrag` /
  `resolveGridCell` / `setupRemoveBar` / `handleDockDrag`, die
  `home_root`/`dock`/`remove_bar`-`OnDragListener`, `HomePagerAdapter`,
  `DockAdapter`, `AppDrawerFragment` (Drawer-Drag).
- **v2 (umgesetzt: Phasen 1+2)** — Engine live: `DragLayer` (ersetzt
  `GestureFrameLayout` als Home-Root), `DragController`, `DropZone` (Remove >
  Dock > Grid). OS-DnD komplett entfernt. On-device (A17) verifiziert:
  Remove-Zone bis zur Oberkante droppbar (DRG-INV-1 bestätigt), Grid/Dock/
  Drawer-Drops, Ordner, Gesten-Koexistenz. Zwei Fixes nach erstem Test: Drag-View
  mit bekannter Quell-Größe zentrieren (sonst Ecke am Finger); Drag-View bis zum
  Commit-Re-Render halten (sonst Zurückspringen zur alten Zelle beim asynchronen
  Move). Offen: Phase 3 (Kür) und die JVM-Tests (§9) — Phase 1/2 sind
  UI/Touch-lastig und wurden bewusst on-device statt unit-getestet.
- **v3 (Aufräumen)** — Phase-2-Rest erledigt: die reine Drop-Geometrie ist als
  `gridCellAt` (`HomeGridGeometry.kt`) aus `MainActivity.resolveGridCell`
  extrahiert und JVM-getestet (`HomeGridGeometryTest`, 8 Fälle). Kein
  Verhaltenswechsel. Ein eigener `GridDropTarget`-Typ wurde bewusst weggelassen.
- **v4 (Tests nachgeholt)** — `DragController`-Zustandsmaschine + `findZone`
  (Priorität, `accepts`-Gate, Enter/Exit, Drop-lässt-View, Cancel-räumt-ab) mit
  `DragControllerTest` (9 Fälle) gepinnt. Dafür `DragViewHost`-Schnittstelle
  extrahiert (Dependency-Inversion), gegen die der Controller ohne echte View
  testbar ist; `DragLayer` implementiert sie. Kein Verhaltenswechsel. Damit sind
  die §9-Tests bis auf die (bewusst on-device belassenen) Touch/Fenster-Fälle
  abgedeckt.
