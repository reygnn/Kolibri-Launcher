# RECONCILE_HOME_LAYOUT_SPEC — Structural layout repair, store-side fail-closed (Sig + Contract)

> ⚠️ **Teilweise überholt** (Branch `feature/lazy-slot-validation`): der **Prune** gegen die
> Enumeration ist entfernt (no-prune / Windows-Verknüpfungs-Modell, root `TODO.md` "✅ UMGESETZT").
> `HomeLayoutReconciler` macht nur noch **strukturelle** Reparatur (dedup / folder-dissolve /
> page-trim); `ReconcileHomeLayoutUseCase` enumeriert/gatet nicht mehr (kein `AppPresence`/
> `InstallSessionInspector`/`DeletionGatePass`, kein Partial-Snapshot-Gate). Die §2-Passes 2/3/5
> gelten weiter; der Prune-Pass (Pass 1) und das F7-Gate sind Historie.

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`. Konsumiert `ICON_HOME_MODEL_SPEC`
> (Modell + IHM-INV-*) und wiederverwendet die Dissolve-Semantik aus
> `REMOVE_FROM_FOLDER_SPEC` (RFF-INV-1/-2). (Das Fehler-Envelope `AppLoadResult` aus
> `INSTALLED_APPS_LOAD_SPEC` wird **nicht mehr** konsumiert — see banner: no enumeration.)
>
> **Fokus:** `ReconcileHomeLayoutUseCase` + die reine Policy `HomeLayoutReconciler.reconcile(...)`.
> STRUCTURAL-ONLY repair of the persisted `HomeLayout` against the model invariants
> (no app enumeration any more) — inkl. **Herstellung** von IHM-INV-7 (App-Unizität),
> die die anderen Specs nur *erhalten*.
>
> **Nicht im Fokus (§8):** Trigger-Verdrahtung (`PackageUpdateReceiver`, Cold-Start-
> Aufholung, Post-Import-Hook — `:app`/`:data`), Work-Profile-Verfügbarkeit (App
> *versteckt* statt deinstalliert), Shortcut-/Widget-Reconcile, Kollisions-Reparatur
> aus manuell zerschossenem Import.
>
> **Status:** ENTWURF v1. §7-Entscheidungen getroffen, Review-Runde 1 ausstehend.
>
> **Verhältnis zum großen Kolibri:** direkte Adaption der `RECONCILE_SPEC`-Familie.
> Übernommen: **fail-closed** (R-INV — now STORE-side only: a transient store fault never
> mutates/persists; enumeration is gone with the prune, see banner), and
> **Beobachtbarkeit** (kein stiller Verlust — ein typisierter Report statt `Unit`, gegen
> die „tote Recovery-Apparatur" aus `INSTALLED_APPS_LOAD_SPEC` §1.2). The former
> **„a caught failure stays a failure"** point (`AppLoadResult` consumed as an envelope
> rather than collapsed to `emptyList`) no longer applies — reconcile enumerates nothing.

---

## §0 Was Reconcile leisten muss

Structural drift classes in a persisted layout (from import/edit — no truth comparison):

1. ~~**Tote Referenzen**~~ — **REMOVED** (no-prune / Windows-shortcut model, see banner):
   `ComponentKey`s of an uninstalled/no-longer-launchable package are **kept**, not
   dropped. Reconcile enumerates nothing; such a dead tile is surfaced and removed lazily
   in the UI.
2. **Duplikate** — dieselbe `ComponentKey` mehrfach (Legacy-Backup, Merge von zwei
   Geräten). IHM-INV-7 verlangt: höchstens einmal. **Hier** wird die Invariante
   hergestellt.
3. **Struktur-Folgeschäden** (aus Import/Edit, nicht mehr aus Prune) — ein Folder fällt
   unter 2 Mitglieder; Seiten werden leer.
4. ~~**Import-Übermaß**~~ — **REMOVED** (structural-only, see banner): dock capacity
   (`> columns`) is no longer capped here; the regridder owns it against the real device
   grid.

> **RHL-INV-1 — Fail-closed (STORE only).** Reconcile enumerates **nothing** and prunes
> nothing, so there is no enumeration to fail-close against. The only fail-closed gate is
> store-side: if the atomic read-modify-write (`layoutRepository.update{}`) throws a
> transient DataStore `IOException`, **nothing** is mutated and **nothing** is persisted —
> result `Skipped(SkipReason.STORE_FAILED)`. A transient store fault must never empty a
> home screen (R-INV, `INSTALLED_APPS_LOAD_SPEC` §1.1).

---

## §1 Typen (`:domain`)

```kotlin
// Reine Policy-Ausgabe.
sealed interface ReconcileOutcome {
    data class Changed(val layout: HomeLayout, val report: ReconcileReport) : ReconcileOutcome
    data object Unchanged : ReconcileOutcome
}

// Beobachtbarkeit: was hat der Pass getan? Kein stiller Verlust.
// REMOVED (no-prune / structural-only, see banner): `prunedApps` and `dockTrimmed`
// no longer exist — reconcile enumerates nothing and runs no dock-trim, so both
// counts were always zero and were dropped. Live type = the four fields below
// (matches ReconcileResult.kt).
data class ReconcileReport(
    val dedupedApps: Int,         // überzählige Vorkommen entfernt (IHM-INV-7)
    val dissolvedFolders: Int,    // 2→1 Member ⇒ Survivor promotet
    val removedEmptyFolders: Int, // 0 Member ⇒ Folder entfernt
    val trimmedPages: Int,        // leere Endseiten entfernt
)

// Use-Case-Ausgabe (Envelope inkl. fail-closed).
sealed interface ReconcileResult {
    data class Reconciled(val report: ReconcileReport) : ReconcileResult
    data object Unchanged : ReconcileResult
    data class Skipped(val reason: SkipReason) : ReconcileResult  // SkipReason.STORE_FAILED only (store-side fail-closed)
}
```

---

## §2 Reihenfolge der Operationen (bestimmt Korrektheit + Idempotenz)

1. ~~**Prune**~~ — **REMOVED** (no-prune / structural-only, see banner): reconcile no
   longer enumerates apps, so no dead-reference prune runs. Number kept so the banner's
   "Passes 2/3/5 still apply" reference stays stable.
2. **Dedup** — verbleibende Duplikate **pro Scope** (RHL-INV-4) auf ein Vorkommen
   reduzieren: Top-Level (`items` ∪ `dock`) Dock > Grid, jeder Folder für sich.
   Cross-Scope-Duplikate bleiben (App darf Kachel *und* Folder-Member sein).
3. **Folder-Reparatur** — Folder mit **1** Member → **Dissolve** (Survivor an die
   Folder-Zelle/den Slot promoten, Folder-`ItemId` retired; RFF-INV-2); Folder mit
   **0** Membern → entfernen, Zelle frei.
   *Scoped IHM-INV-7 + Idempotenz (RHL-INV-2):* Ist der Survivor-Key **bereits top-level**
   (ein Grid/Dock-Überlebender aus Schritt 2 **oder** ein in diesem Pass zuvor promoteter
   Survivor), wird **nicht** promotet — der redundante Member wird verworfen und der Folder
   nur entfernt (zählt als `dedupedApps`, nicht als `dissolvedFolders`). Sonst entstünde ein
   zweites Top-Level-Vorkommen, das ein erneuter Lauf wegdeduplizieren würde (nicht
   idempotent). Cross-Scope-Koexistenz (Kachel **und** Folder-Member) bleibt erlaubt; die
   Unterdrückung greift nur, wenn der 1-Member-Folder ohnehin verschwinden muss.
4. ~~**Dock kappen**~~ — **REMOVED** (structural-only, see banner): no dock-trim pass
   runs; the removed `dockTrimmed` count was always zero. Number kept for the banner's
   pass-reference.
5. **Endseiten trimmen** — hinten liegende **leere** Seiten entfernen, mindestens **1**
   Seite behalten. **Innere** Leerseiten bleiben (bewusste Leerseite = User-Absicht).

`Changed` ⇔ irgendein Schritt hat mutiert; sonst `Unchanged`.

> **RHL-INV-2 — Idempotenz (Fixpunkt).** `reconcile(reconcile(L)) == reconcile(L)`;
> der zweite Lauf über ein bereits sauberes Layout liefert `Unchanged`. Schritt 3 führt
> keine neuen Duplikate/Kollisionen ein (Survivor ist bereits unizitätskonform und erbt
> die schon belegte Folder-Position), Schritt 5 verschiebt nichts.

---

## §3 Dedup-Präzedenz (per Scope)

Dedup ist **pro Scope** (scoped IHM-INV-7). Innerhalb eines Scopes gewinnt das
Vorkommen an der **absichtlichsten Position**; die übrigen dieses Scopes fallen weg.
Über Scope-Grenzen hinweg wird **nicht** dedupliziert. Deterministisch, unabhängig von
Listen-Reihenfolge:

> **RHL-INV-4 — Per-Scope-Präzedenz.**
> - **Top-Level-Scope** (`items` ∪ `dock`): ein Überlebender, Präzedenz **Dock > Grid**.
>   1. **Dock** (kleinster Slot-Index zuerst)
>   2. **Top-Level-Grid** (`page` ↑, `y` ↑, `x` ↑)
> - **Folder-Scope** (jeder Folder für sich): nur **innerhalb** eines Folders wird auf
>   das erste Vorkommen dedupliziert. Ein Member wird **nicht** entfernt, weil dieselbe
>   `ComponentKey` auch top-level oder in einem anderen Folder liegt — das sind eigene
>   Scopes.
>
> Das höchstplatzierte Vorkommen **je Scope** bleibt. Eine `ComponentKey` darf also als
> Top-Level-Kachel **und** in mehreren Foldern gleichzeitig überleben. Dies **ersetzt**
> die frühere globale Formulierung „Dock > Grid > Folder".

---

## §4 Sigs

```kotlin
object HomeLayoutReconciler {                 // pure policy (RHL-INV-6)
    fun reconcile(
        layout: HomeLayout,
        newId: () -> ItemId,                  // only for survivor promotion on dissolve
    ): ReconcileOutcome                        // structural-only: NO installed-set any more
}

class ReconcileHomeLayoutUseCase @Inject constructor(
    private val layoutRepository: HomeLayoutRepository,
    private val idFactory: ItemIdFactory,      // from MOVE_ITEM_SPEC §4
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {                                             // NO InstalledAppsRepository any more (no-prune)
    suspend operator fun invoke(): ReconcileResult = withContext(dispatcher) {
        try {
            var result: ReconcileResult = ReconcileResult.Unchanged
            layoutRepository.update { current ->               // atomic read-modify-write
                when (val out = HomeLayoutReconciler.reconcile(current, idFactory::next)) {
                    ReconcileOutcome.Unchanged -> null          // no write
                    is ReconcileOutcome.Changed -> {
                        result = ReconcileResult.Reconciled(out.report)
                        out.layout                              // persisted by the RMW
                    }
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // STORE-side fail-closed (RHL-INV-1): a transient DataStore IOException from
            // the atomic RMW skips the pass with zero mutation instead of degrading to an
            // empty layout. invoke() stays TOTAL — only CancellationException escapes.
            ReconcileResult.Skipped(SkipReason.STORE_FAILED)
        }
    }
}
```

---

## §5 Invarianten (`RHL-INV-*`)

- **RHL-INV-1 — Fail-closed** (§0).
- **RHL-INV-2 — Idempotenz** (§2).
- **RHL-INV-3 — Stellt die Modell-Invarianten her und erhält sie.** Output erfüllt
  IHM-INV-7 (Dedup), RFF-INV-1 (kein <2-Member-Folder), IHM-INV-3/-4 (keine Kollision/
  Off-Grid, eindeutige IDs). The structural passes introduce no collision (they only
  drop redundant items or promote a survivor into its own folder cell); repairing
  import *collisions* is v2 (§7-D5).
- **RHL-INV-4 — Dedup-Präzedenz** (§3).
- **RHL-INV-5 — Verlust ist begründet & beobachtbar.** Reconcile removes only redundant
  duplicates and empty structure; it **never** drops a unique app reference (and never
  prunes an uninstalled one — no-prune, see banner). Jede Mutation zählt im
  `ReconcileReport` — no silent loss (die Lektion aus `INSTALLED_APPS_LOAD_SPEC` §1.2:
  keine tote/maskierte Recovery, Fehler/Änderungen sichtbar machen).
- **RHL-INV-6 — Reine Policy.** `HomeLayoutReconciler.reconcile` hängt an keinem Repo/
  Dispatcher/Android-Typ/keiner Uhr/RNG (IDs als Factory). The policy takes only the
  `HomeLayout` (no installed-set any more); the store-side fail-closed gate lives in the
  **use case** (the atomic RMW), not in the policy.

---

## §6 Test-Inventar

### §6.1 Reine Reconciler-Tests (JVM, kein Mock)

- ~~**Prune:**~~ **REMOVED** (no-prune, see banner): no such test exists — reconcile never
  drops an uninstalled reference.
- **Dedup-Präzedenz:** dieselbe Key in Dock + Grid + Folder → nur das Dock-Vorkommen
  bleibt; Grid + zwei Folder → Grid gewinnt; deterministisch bei permutierten
  Eingabelisten (RHL-INV-4).
- **Folder 2→1** (a member removed via dedup/import) → Dissolve, Survivor an Folder-Zelle,
  Folder-ID retired; **Folder →0** → entfernt, Zelle frei.
- **Endseiten:** hintere Leerseite weg, ≥1 Seite bleibt; **innere** Leerseite bleibt.
- ~~**Dock-Übermaß:**~~ **REMOVED** (structural-only, see banner): no such test exists —
  dock capacity is not capped here (the regridder owns it).
- **Idempotenz:** zweiter Lauf → `Unchanged` (RHL-INV-2).
- **Report-Zählwerte** stimmen mit den Mutationen überein.
- `newId`-Stub deterministisch; Aufrufzahl = Anzahl Dissolves.

### §6.2 Use-Case-Tests (JVM, MockK)

- **STORE fault** (`layoutRepository.update{}` throws a transient `IOException`) →
  `Skipped(SkipReason.STORE_FAILED)`, zero mutation, `invoke()` stays total — only
  `CancellationException` escapes (RHL-INV-1 — der wichtigste Test).
- Policy `Changed` → the RMW persists the new layout **once**, `Reconciled(report)`
  durchgereicht.
- Policy `Unchanged` → RMW returns `null` (**no** write).
- läuft auf injiziertem Dispatcher.

> **Dispatcher-Konvention (Projekt):** ein Dispatcher via `MainDispatcherRule`, kein
> separater `TestScope`/`StandardTestDispatcher` — `TESTING_CONVENTIONS.kt` im Test-Root.

### §6.3 Nicht hier

Kein neues Repository ⇒ kein Contract-Triple. The only repo reconcile touches now is
`HomeLayoutRepository` (its triple already exists; `InstalledAppsRepository` is no longer
a dependency — no-prune, see banner). Trigger-Verdrahtung → §8.

---

## §7 Entscheidungen (getroffen — v1, Review-Runde 1 ausstehend)

- ~~**§7-D1 — Ground-Truth als `AppLoadResult`-Envelope, nicht als `List`.**~~ **SUPERSEDED**
  (no-prune, see banner): reconcile no longer enumerates apps, so there is no ground-truth
  envelope to consume. Fail-closed is now store-side only (RHL-INV-1); the "distinguish
  error from truly-empty" concern moved to `INSTALLED_APPS_LOAD_SPEC`'s own consumers.
- **§7-D2 — Dedup precedence, per scope: Dock > Grid** (RHL-INV-4), deterministic rather
  than order-dependent. (The original global "Dock > Grid > Folder" was refined to the
  per-scope rule in §3 — folders are independent scopes.)
- **§7-D3 — Folder-Reparatur = RFF-Dissolve wiederverwenden**, nicht neu erfinden;
  0-Member-Folder werden entfernt.
- **§7-D4 — Compaction nur an den Endseiten**, ≥1 Seite, innere Leerseiten bleiben.
  (Löst den in `MOVE_ITEM_SPEC` §7-D4 hierher verschobenen Aufräum-Pass ein.)
- **§7-D5 — Kollisions-Reparatur aus zerschossenem Import ist v2.** Reconcile assumes cell
  collisions do not arise from its own structural passes (dedup/dissolve/trim only add
  free space or promote a survivor into an already-occupied folder cell); an import with
  two items on one cell is not repaired in v1. Dock capacity is likewise not capped here —
  the regridder owns it against the real grid. Bewusst eng.

---

## §8 Außerhalb des Scopes (v2+)

- **Trigger-Verdrahtung** — `PackageUpdateReceiver` → Reconcile, Cold-Start-Aufholung,
  Post-Import-Hook. Wiring in `:app`/`:data`, angelehnt an `RECONCILE_SPEC` §3/§4.
- **Verfügbarkeits-Transitionen** — App *versteckt* (Work-Profile pausiert) vs.
  deinstalliert. Reconcile prunes neither any more (no-prune, see banner); presence is
  surfaced and resolved lazily in the UI, not by reconcile.
- **Kollisions-Reparatur** aus manuell/legacy zerschossenem Layout (§7-D5).
- **Shortcut-/Widget-Reconcile** — sobald diese Item-Typen existieren.

---

## Review-Log

- **v1 (ENTWURF)** — Erst-Ausformulierung nach „weiter". Fail-closed (RHL-INV-1) als
  Kern, Operationsreihenfolge §2 mit Idempotenz-Begründung, Dedup-Präzedenz §3 ersetzt
  die lose IHM-INV-7-Formulierung. Enge v1-Linie: Kollisions-Reparatur (§7-D5) und
  Trigger-Wiring (§8) verschoben.
