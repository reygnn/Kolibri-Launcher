# Design specs

Living design documents for the icon-launcher's domain and its one pixel-bearing
subsystem. Unlike the sibling `Kolibri-Launcher`, whose specs were blueprints for
rewrites that already shipped, **these are greenfield** — written before the code, and
meant to *become* the authoritative rationale + invariants the production code delegates
to once it exists (a code comment saying "`MOVE_ITEM_SPEC` §7-D2" will resolve here).

Same two conventions as the big Kolibri, inherited deliberately:

- **Reference by bare name, never a path** — `MOVE_ITEM_SPEC`, not
  `docs/specs/MOVE_ITEM_SPEC.md`. The folder can be reorganised without touching code or
  cross-references.
- **Rejected / superseded alternatives live in [`../history/`](../history/), not here.**
  A spec on disk is the *current* position; the graveyard is separate.

Every spec carries a **status** (all `ENTWURF` until the code lands), an **invariant
family** (see the table below), and a **Review-Log** at the foot recording each round.

---

## Reading order

The model spec is the root; the three mutating use-cases and the loader all import its
types and invariants. Read top-down:

1. [`ICON_HOME_MODEL_SPEC`](ICON_HOME_MODEL_SPEC.md) — das Fundament (Modell, Module,
   `IHM-INV-*`). Alles andere hängt hier dran.
2. [`MOVE_ITEM_SPEC`](MOVE_ITEM_SPEC.md) — die Drop-Semantik (Bewegen + Folder erzeugen).
3. [`REMOVE_FROM_FOLDER_SPEC`](REMOVE_FROM_FOLDER_SPEC.md) — Extraktion + Auto-Dissolve.
4. [`RECONCILE_HOME_LAYOUT_SPEC`](RECONCILE_HOME_LAYOUT_SPEC.md) — Abgleich gegen die
   Wahrheit, fail-closed; **stellt** IHM-INV-7 her.
5. [`ICON_LOADER_SPEC`](ICON_LOADER_SPEC.md) — der einzige Android-/Pixel-Teil.

---

## Specs

- [`ICON_HOME_MODEL_SPEC`](ICON_HOME_MODEL_SPEC.md) — Domänenmodell & Modulschnitt für
  den Icon-Launcher (grüne Wiese). `HomeLayout`-Graph statt flacher Favoriten-Liste;
  „Architektur erben, nicht die Produkt-Philosophie". Status ENTWURF v3.
- [`MOVE_ITEM_SPEC`](MOVE_ITEM_SPEC.md) — Drop-Semantik als reine Transition (Sig +
  Contract). Die §3-Matrix *ist* der Testplan; Drop-auf-App ⇒ Folder. Status ENTWURF v1.1.
- [`REMOVE_FROM_FOLDER_SPEC`](REMOVE_FROM_FOLDER_SPEC.md) — Extraktion aus Folder +
  Auto-Dissolve. Folder ≥ 2, sonst löst er sich in derselben Transition auf. Status
  ENTWURF v1.1.
- [`RECONCILE_HOME_LAYOUT_SPEC`](RECONCILE_HOME_LAYOUT_SPEC.md) — Layout gegen
  Ground-Truth abgleichen, **fail-closed** (ein fehlgeschlagenes Enumerations-Ergebnis
  prunt nie). Idempotent; stellt App-Unizität her. Status ENTWURF v1.
- [`ICON_LOADER_SPEC`](ICON_LOADER_SPEC.md) — Hand-rolled Icon-Cache: Byte-LRU + Disk,
  Policy JVM-rein. Drei-Schichten-Split, Request-Coalescing, Paket-Kohärenz. Status
  ENTWURF v1.
- [`HOME_EDIT_USECASES_SPEC`](HOME_EDIT_USECASES_SPEC.md) — die drei kleinen Use-Cases
  (Place / Remove / RenameFolder) gebündelt; einzig `PlaceItem` trägt neue Logik
  (`HEU-INV-1`, Unizitäts-Delegation). Status ENTWURF v1.

---

## Invarianten-Familien

Die Specs verweisen quer über Invarianten-IDs. Präfix → Heimat-Spec:

| Präfix | Familie | Heimat |
|---|---|---|
| `IHM-INV-*` | Home-Modell (Pixel-Freiheit, Grid-/ID-/App-Unizität) | `ICON_HOME_MODEL_SPEC` |
| `MIU-INV-*` | Move-Item (Policy rein, total, ID-Stabilität) | `MOVE_ITEM_SPEC` |
| `RFF-INV-*` | Remove-from-Folder (Wohlgeformtheit ≥2, Auto-Dissolve) | `REMOVE_FROM_FOLDER_SPEC` |
| `RHL-INV-*` | Reconcile (fail-closed, Idempotenz, Dedup-Präzedenz) | `RECONCILE_HOME_LAYOUT_SPEC` |
| `ICL-INV-*` | Icon-Loader (Byte-Budget, Kohärenz, Coalescing) | `ICON_LOADER_SPEC` |
| `HEU-INV-*` | Home-Edit (Place-Unizität, Remove-ohne-Verlust) | `HOME_EDIT_USECASES_SPEC` |

Geerbt aus dem großen Kolibri und hier nur referenziert (nicht neu definiert): die
`R-INV`-Familie (`RECONCILE_SPEC`, fail-closed Reconcile) und das `AppLoadResult`-
Fehler-Envelope (`INSTALLED_APPS_LOAD_SPEC`, „a caught failure stays a failure").

---

## Querschnitt-Konventionen (gelten für alle sechs)

- **Policy/IO-Split** — jede mutierende Operation ist eine *reine* Transition/Policy
  (kein Repo, kein Dispatcher, kein Android, keine Uhr/RNG; IDs als injizierte Factory)
  unter einer dünnen `suspend`-Use-Case-Hülle. Testbar zu JVM-Geschwindigkeit.
- **Rule 10 (value bar, not cost bar)** — was auf der JVM wahr sein kann, wird dort
  gepinnt; Robolectric/`androidTest` nur, wo ein Gerät die Wahrheit trägt (Compositing,
  Drag&Drop-Geste, `LauncherApps`, `onTrimMemory`).
- **Sealed Ergebnis-Identifier** — Use-Cases geben `MoveResult`/`FolderEditResult`/
  `ReconcileResult` zurück, nicht nackte `HomeLayout`s; `:app` mappt zu UI-Feedback.
  Kein String/`@StringRes` in `:domain`.
- **Dispatcher** — ein Dispatcher via `MainDispatcherRule`, **kein** separater
  `TestScope`/`StandardTestDispatcher`. Siehe `TESTING_CONVENTIONS.kt` im Test-Root.
- **„Nur bei echter Änderung speichern"** — `NoOp`/`Rejected`/`Unchanged` ⇒ kein
  DataStore-Write, kein Flow-Tick.

---

## Offene Querschnitt-Punkte

Zentral geführt in `ICON_HOME_MODEL_SPEC` §10; hier der Überblick:

- Grid fix vs. nutzer-konfigurierbar (Modell trägt beides).
- Dock-Kapazität als Modell-Invariante heben (vorläufig `= columns`,
  `MOVE_ITEM_SPEC` §7-D3).
- Produkt-/Paketname der App — **entschieden: Nyx Launcher** (`com.github.reygnn.nyx_launcher`).
- Icon-Loader-Tuning (`ICON_LOADER_SPEC` §10): Budget-Startwerte, Disk-Obergrenze,
  webp-Qualität, themed-Icons-in-v1.
