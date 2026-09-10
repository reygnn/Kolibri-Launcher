# RECONCILE_HOME_LAYOUT_SPEC — Layout gegen Ground-Truth abgleichen, fail-closed (Sig + Contract)

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`. Konsumiert `ICON_HOME_MODEL_SPEC`
> (Modell + IHM-INV-*), wiederverwendet die Dissolve-Semantik aus
> `REMOVE_FROM_FOLDER_SPEC` (RFF-INV-1/-2) und das Fehler-Envelope aus
> `INSTALLED_APPS_LOAD_SPEC` (`AppLoadResult`).
>
> **Fokus:** `ReconcileHomeLayoutUseCase` + die reine Policy `HomeLayoutReconciler.reconcile(...)`.
> Der Abgleich des persistierten `HomeLayout` gegen die real installierten Apps und
> gegen die Modell-Invarianten — inkl. **Herstellung** von IHM-INV-7 (App-Unizität),
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
> Übernommen: **fail-closed** (R-INV — ein leeres/fehlerhaftes Enumerations-Ergebnis
> prunt nie), **Beobachtbarkeit** (kein stiller Verlust — ein typisierter Report statt
> `Unit`, gegen die „tote Recovery-Apparatur" aus `INSTALLED_APPS_LOAD_SPEC` §1.2), und
> **„a caught failure stays a failure"** (`AppLoadResult` wird als Envelope konsumiert,
> nicht zu `emptyList` kollabiert).

---

## §0 Was Reconcile leisten muss

Vier Klassen von Drift zwischen persistiertem Layout und Wahrheit:

1. **Tote Referenzen** — `ComponentKey`s, deren Paket deinstalliert/nicht mehr startbar
   ist. Müssen aus `items`, allen Folder-`members` und `dock` raus.
2. **Duplikate** — dieselbe `ComponentKey` mehrfach (Legacy-Backup, Merge von zwei
   Geräten). IHM-INV-7 verlangt: höchstens einmal. **Hier** wird die Invariante
   hergestellt.
3. **Struktur-Folgeschäden des Prunens** — ein Folder fällt unter 2 Mitglieder;
   Seiten werden leer.
4. **Import-Übermaß** — ein Dock über Kapazität (`> columns`).

> **RHL-INV-1 — Fail-closed.** Reconcile prunt **ausschließlich** gegen eine echt
> geladene App-Menge. Kommt die Enumeration als `AppLoadResult.Error` (oder leer, weil
> fehlgeschlagen), wird **nichts** mutiert und **nichts** gespeichert — Ergebnis
> `Skipped(reason)`. Ein transienter PackageManager-Fehler darf nie einen Home-Screen
> leeren (R-INV, `INSTALLED_APPS_LOAD_SPEC` §1.1).

---

## §1 Typen (`:domain`)

```kotlin
// Reine Policy-Ausgabe.
sealed interface ReconcileOutcome {
    data class Changed(val layout: HomeLayout, val report: ReconcileReport) : ReconcileOutcome
    data object Unchanged : ReconcileOutcome
}

// Beobachtbarkeit: was hat der Pass getan? Kein stiller Verlust.
data class ReconcileReport(
    val prunedApps: Int,          // tote Referenzen entfernt
    val dedupedApps: Int,         // überzählige Vorkommen entfernt (IHM-INV-7)
    val dissolvedFolders: Int,    // 2→1 Member ⇒ Survivor promotet
    val removedEmptyFolders: Int, // 0 Member ⇒ Folder entfernt
    val trimmedPages: Int,        // leere Endseiten entfernt
    val dockTrimmed: Int,         // Dock-Übermaß gekappt
)

// Use-Case-Ausgabe (Envelope inkl. fail-closed).
sealed interface ReconcileResult {
    data class Reconciled(val report: ReconcileReport) : ReconcileResult
    data object Unchanged : ReconcileResult
    data class Skipped(val reason: AppLoadResult.ErrorReason) : ReconcileResult  // §INSTALLED_APPS_LOAD
}
```

---

## §2 Reihenfolge der Operationen (bestimmt Korrektheit + Idempotenz)

1. **Prune** — jede `ComponentKey ∉ installed` aus `dock`, `items`, Folder-`members`.
2. **Dedup** — verbleibende Duplikate nach RHL-INV-4-Präzedenz auf ein Vorkommen
   reduzieren.
3. **Folder-Reparatur** — Folder mit **1** Member → **Dissolve** (Survivor an die
   Folder-Zelle/den Slot promoten, Folder-`ItemId` retired; RFF-INV-2); Folder mit
   **0** Membern → entfernen, Zelle frei.
4. **Dock kappen** — `dock` auf `grid.columns` kürzen (Import-Sicherheit; erste
   `columns` behalten).
5. **Endseiten trimmen** — hinten liegende **leere** Seiten entfernen, mindestens **1**
   Seite behalten. **Innere** Leerseiten bleiben (bewusste Leerseite = User-Absicht).

`Changed` ⇔ irgendein Schritt hat mutiert; sonst `Unchanged`.

> **RHL-INV-2 — Idempotenz (Fixpunkt).** `reconcile(reconcile(L)) == reconcile(L)`;
> der zweite Lauf über ein bereits sauberes Layout liefert `Unchanged`. Schritt 3 führt
> keine neuen Duplikate/Kollisionen ein (Survivor ist bereits unizitätskonform und erbt
> die schon belegte Folder-Position), Schritt 5 verschiebt nichts.

---

## §3 Dedup-Präzedenz

Erscheint eine `ComponentKey` mehrfach, gewinnt das Vorkommen an der **absichtlichsten
Position**; die übrigen fallen weg. Deterministisch, unabhängig von Listen-Reihenfolge:

> **RHL-INV-4 — Präzedenz Dock > Grid > Folder.**
> 1. **Dock** (kleinster Slot-Index zuerst)
> 2. **Top-Level-Grid** (`page` ↑, `y` ↑, `x` ↑)
> 3. **Folder-Member** (Grid-Rang des Folders, dann Member-Index)
>
> Das höchstplatzierte Vorkommen bleibt, alle anderen werden entfernt. Dies **ersetzt**
> die lose Formulierung „letztes Vorkommen gewinnt" in `ICON_HOME_MODEL_SPEC` IHM-INV-7.

---

## §4 Sigs

```kotlin
object HomeLayoutReconciler {                 // reine Policy (RHL-INV-6)
    fun reconcile(
        layout: HomeLayout,
        installed: Set<ComponentKey>,         // Ground-Truth; Aufrufer garantiert echt geladen
        newId: () -> ItemId,                  // nur für Survivor-Promotion bei Dissolve
    ): ReconcileOutcome
}

class ReconcileHomeLayoutUseCase @Inject constructor(
    private val layoutRepo: HomeLayoutRepository,
    private val apps: InstalledAppsRepository, // liefert AppLoadResult, NICHT bare List
    private val idFactory: ItemIdFactory,      // aus MOVE_ITEM_SPEC §4
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): ReconcileResult = withContext(dispatcher) {
        when (val load = apps.load()) {                       // AppLoadResult
            is AppLoadResult.Error ->
                ReconcileResult.Skipped(load.reason)          // FAIL-CLOSED (RHL-INV-1)
            is AppLoadResult.Loaded -> {
                val current = layoutRepo.layout().first()
                when (val out = HomeLayoutReconciler.reconcile(
                    current, load.installedKeys, idFactory::next,
                )) {
                    ReconcileOutcome.Unchanged -> ReconcileResult.Unchanged
                    is ReconcileOutcome.Changed -> {
                        layoutRepo.save(out.layout)           // nur bei echter Änderung
                        ReconcileResult.Reconciled(out.report)
                    }
                }
            }
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
  Off-Grid, eindeutige IDs). Prunen führt keine Kollision ein (es entfernt nur);
  Import-*Kollisionen* zu reparieren ist v2 (§7-D5).
- **RHL-INV-4 — Dedup-Präzedenz** (§3).
- **RHL-INV-5 — Verlust ist begründet & beobachtbar.** Entfernt **nie** eine noch
  installierte, unizitätskonforme App. Jede Mutation zählt im `ReconcileReport` — kein
  stiller Prune (die Lektion aus `INSTALLED_APPS_LOAD_SPEC` §1.2: keine tote/maskierte
  Recovery, Fehler/Änderungen sichtbar machen).
- **RHL-INV-6 — Reine Policy.** `HomeLayoutReconciler.reconcile` hängt an keinem Repo/
  Dispatcher/Android-Typ/keiner Uhr/RNG (IDs als Factory). Der fail-closed-Gate liegt im
  **Use-Case**, nicht in der Policy — die Policy sieht nur eine echte `installed`-Menge.

---

## §6 Test-Inventar

### §6.1 Reine Reconciler-Tests (JVM, kein Mock)

- **Prune:** entfernt nur `∉ installed`; lässt Installierte unangetastet (RHL-INV-5).
- **Dedup-Präzedenz:** dieselbe Key in Dock + Grid + Folder → nur das Dock-Vorkommen
  bleibt; Grid + zwei Folder → Grid gewinnt; deterministisch bei permutierten
  Eingabelisten (RHL-INV-4).
- **Folder 2→1** (ein Member geprunt) → Dissolve, Survivor an Folder-Zelle, Folder-ID
  retired; **Folder →0** → entfernt, Zelle frei.
- **Endseiten:** hintere Leerseite weg, ≥1 Seite bleibt; **innere** Leerseite bleibt.
- **Dock-Übermaß:** `> columns` → auf `columns` gekappt.
- **Idempotenz:** zweiter Lauf → `Unchanged` (RHL-INV-2).
- **Report-Zählwerte** stimmen mit den Mutationen überein.
- `newId`-Stub deterministisch; Aufrufzahl = Anzahl Dissolves.

### §6.2 Use-Case-Tests (JVM, MockK)

- `AppLoadResult.Error` → `Skipped(reason)`, `reconcile` **nie** gerufen, `save` **nie**
  gerufen (RHL-INV-1 — der wichtigste Test).
- `Loaded` + Policy `Changed` → `save` **einmal**, `Reconciled(report)` durchgereicht.
- `Loaded` + Policy `Unchanged` → **kein** `save`.
- läuft auf injiziertem Dispatcher.

> **Dispatcher-Konvention (Projekt):** ein Dispatcher via `MainDispatcherRule`, kein
> separater `TestScope`/`StandardTestDispatcher` — `TESTING_CONVENTIONS.kt` im Test-Root.

### §6.3 Nicht hier

Kein neues Repository ⇒ kein Contract-Triple. `HomeLayoutRepository`/
`InstalledAppsRepository` haben ihre Triples. Trigger-Verdrahtung → §8.

---

## §7 Entscheidungen (getroffen — v1, Review-Runde 1 ausstehend)

- **§7-D1 — Ground-Truth als `AppLoadResult`-Envelope, nicht als `List`.** Der ganze
  fail-closed-Mechanismus hängt daran, „Fehler" von „echt leer" unterscheiden zu können.
  Nutzt direkt den Fix aus `INSTALLED_APPS_LOAD_SPEC` (Fehler nicht zu `emptyList`
  kollabieren).
- **§7-D2 — Dedup-Präzedenz Dock > Grid > Folder** (RHL-INV-4), deterministisch statt
  order-abhängig.
- **§7-D3 — Folder-Reparatur = RFF-Dissolve wiederverwenden**, nicht neu erfinden;
  0-Member-Folder werden entfernt.
- **§7-D4 — Compaction nur an den Endseiten**, ≥1 Seite, innere Leerseiten bleiben.
  (Löst den in `MOVE_ITEM_SPEC` §7-D4 hierher verschobenen Aufräum-Pass ein.)
- **§7-D5 — Kollisions-Reparatur aus zerschossenem Import ist v2.** Reconcile geht davon
  aus, dass Kollisionen nur aus Prune-Freiraum *verschwinden*, nicht entstehen; ein
  Import mit zwei Items auf einer Zelle wird in v1 nicht repariert (nur Dock-Übermaß, weil
  billig). Bewusst eng.

---

## §8 Außerhalb des Scopes (v2+)

- **Trigger-Verdrahtung** — `PackageUpdateReceiver` → Reconcile, Cold-Start-Aufholung,
  Post-Import-Hook. Wiring in `:app`/`:data`, angelehnt an `RECONCILE_SPEC` §3/§4.
- **Verfügbarkeits-Transitionen** — App *versteckt* (Work-Profile pausiert) vs.
  deinstalliert; v1 behandelt nur „nicht mehr startbar".
- **Kollisions-Reparatur** aus manuell/legacy zerschossenem Layout (§7-D5).
- **Shortcut-/Widget-Reconcile** — sobald diese Item-Typen existieren.

---

## Review-Log

- **v1 (ENTWURF)** — Erst-Ausformulierung nach „weiter". Fail-closed (RHL-INV-1) als
  Kern, Operationsreihenfolge §2 mit Idempotenz-Begründung, Dedup-Präzedenz §3 ersetzt
  die lose IHM-INV-7-Formulierung. Enge v1-Linie: Kollisions-Reparatur (§7-D5) und
  Trigger-Wiring (§8) verschoben.
