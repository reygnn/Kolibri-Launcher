# Unity-Launcher (Kolibri + Nyx) — geteilte TODO / Roadmap

Cross-cutting-Themen, die **beide** Launcher betreffen — typischerweise die geteilten
Module (`:core`, `:common-data`, `:common-ui`, `:common-android`,
`:feature-crashreporting`) oder eine Konvention, die für Kolibri *und* Nyx gilt.
Launcher-spezifisches bleibt in `kolibri/TODO.md` bzw. `nyx/TODO.md`; diese Datei ist die
gemeinsame Ebene darüber.

---

## Rule-9-Nuance: `silentError` vs. `reportToAcra` an fail-safe-to-keep-Grenzen (2026-09-24)

> ⚠️ **Obsolet** (Branch `feature/lazy-slot-validation`): die hier beschriebenen fail-safe-to-keep-Seams
> (`PackageManagerPresence`/`PackageManagerInstallSessions`) sind mit dem Auto-Prune gelöscht — siehe
> „✅ UMGESETZT" weiter unten. Bleibt als Referenz für die `silentError`-vs-`reportToAcra`-Regel selbst.

**Gilt für beide Launcher — der Code lebt im geteilten `:common-data`.**

**Merke (die Regel):** `TimberWrapper.silentError` **wirft in DEBUG** (`crashInDebug`) — es ist der
Kanal für *gefangene Programmierfehler*, die im Dev-Build laut auffallen sollen. Für eine
**fail-safe-to-keep-Grenze**, deren Vertrag »gib in **jedem** Build den sicheren Default zurück«
lautet (System-API-Boundary, environmental, self-healing), ist es das falsche Werkzeug: der
DEBUG-Throw bricht genau diesen Vertrag — der Default wird nie erreicht, der Throw entkommt und
wird oben nur mislabeled/abgebrochen weitergereicht. Dort gehört `reportToAcra` hin
(RELEASE-Signal via ACRA, **kein** DEBUG-Throw).

Kurzformel:

- **erwarteter, environmental-transienter, upstream-behandelter Fehler → `reportToAcra`**
- **echter Programmierfehler, der laut werden soll → `silentError`**

**Der konkrete Fund (AUDIT-1 F7 Medium-Review, Branch `fix/f7-partial-enumeration-guard`):** die
drei fail-safe-to-keep-Catches der geteilten Presence/Session-Seams —
`PackageManagerPresence.isComponentPresent` / `isPackagePresent` (→ `true`) und
`PackageManagerInstallSessions.activeSessionPackages` (→ `null`) — nutzten `silentError`. In einem
DEBUG-On-Device-Build gab ein transienter PackageManager-/PackageInstaller-Fehler damit **nicht**
den fail-safe-Default zurück, sondern warf; der Throw entkam der Methode und wurde upstream nur
gefangen (Nyx: fehletikettiert als `SkipReason.STORE_FAILED`; Kolibri: `runCleanup` bricht den
betroffenen Store ab). Fail-safe in der Richtung (kein Datenverlust, ein übersprungener Pass statt
eines zusätzlichen Prunes), aber es machte den dokumentierten Vertrag der Seams (»resolves to
present/null«) **und** die Reconcile-KDoc (»the gate arms … swallow their own platform errors,
rethrowing only cancellation«) in DEBUG falsch. **Fix:** alle drei Sites auf `reportToAcra`
umgestellt — der Vertrag hält jetzt in jedem Build, RELEASE behält das ACRA-Signal, beide Launcher
sind gleich betroffen (ein geteiltes Impl).

**Querverweise:**

- Nyx: `ReconcileHomeLayoutUseCase`s `STORE_FAILED`-Catch (fix 4 in `nyx/TODO.md`,
  F7-Gate-Sektion) traf dieselbe Wahl aus demselben Grund — der Auslöser, an dem die Inkonsistenz
  auffiel.
- Kolibri: die kanonische Rule-9-Historie (report-by-intent, `silentError`/`reportToAcra`/
  `silentDeath`-Kontrakt) steht in `kolibri/CLAUDE.md` Rule 9 und `kolibri/TODO.md` §2/§23; diese
  Notiz ist die praktische Ergänzung *„an einer fail-safe-Grenze ist es reportToAcra, nicht
  silentError"*.
- Enforcement: der Intent-Gate-Linter (`kolibri/tools/check-intent-gate.awk`) fängt **bare**
  `Timber.e(`, aber **nicht** ein falsch gewähltes `silentError` an einer fail-safe-Grenze — das
  bleibt eine Review-/Konventions-Frage, daher dieser Eintrag.

---

## Drift-Prävention App-Verwaltung: geteilter Gate-Helfer + Cross-Launcher-Parity-Test (offen, 2026-09-24)

> ⚠️ **Obsolet** (Branch `feature/lazy-slot-validation`): (a) der geteilte `DeletionGatePass` und (b) der
> Cross-Launcher-Parity-Test sind gelöscht — es gibt kein Delete-Gate mehr, gegen dessen Drift sie
> schützen müssten. Siehe „✅ UMGESETZT" weiter unten. Bleibt als Referenz für die Duplikations-Analyse.

**Ausgangslage (gemessen 2026-09-24, LOC ohne Tests).** Die App-Verwaltung (Enumeration → Laden
→ In-RAM-State → Delete-Gate → Reconcile der User-Zuweisungen) ist zu **~50 %** geteilt: die
**Engine** (`:core`-Ports/Modelle + `:common-data/installedapps`, ~1150 LOC) läuft in beiden
Launchern als *ein* Impl (100 % geteilt), die **Policy-Schicht** (~1400 LOC) ist zu 0 % geteilt.
Letzteres ist fundamental — nyx verwaltet ein räumliches `HomeLayout` (Positionen/Folder/Dock),
kolibri vier flache Key-Sets (favorites/hidden/swipe/customnames) —, aber es bedeutet, dass das
Delete-Gate **zweimal orchestriert** ist (`nyx ReconcileHomeLayoutUseCase` inline vs. kolibri
`ObserveInstalledAppsUseCase` + `PassSessionGate` über vier Store-Reconciles). Diese Duplikation
ist der Haupt-Drift-Vektor: Session-Arm, `null→keep`-Fail-safe, malformed-key-Kurzschluss und
Presence-vor-Session-Reihenfolge stehen doppelt, und **nichts erzwingt Gleichheit** — Patch 12
(Session-Arm nach kolibri nachziehen) und die schiefe Test-Parität waren genau das. Der
wahrscheinlichste nächste Auslöser ist der in beiden `ACCEPTED_LIMITATIONS.md` genannte
keep-last-good-**count-floor**: landet er nur in einem Launcher, öffnet sich das akzeptierte
Restore-Restrisiko wieder einseitig.

Zwei Hebel dagegen (a reduziert die Duplikation, b macht Rest-Drift laut):

### a) ✅ Erledigt (2026-09-24, Branch `refactor/shared-deletion-gate`)

Umgesetzt als `core/DeletionGatePass` — eine per-Pass-Instanz, die `AppPresence` + `InstallSessionInspector`
kapselt und `keepComponent(ComponentKey)` / `keepPackage(String)` anbietet (Presence zuerst,
Session-Set **einmal** pro Pass lazy gelesen, `null→keep`). nyx' inline `sessionsRead`-Schleife und
kolibris `PassSessionGate` + beide Bridges (`isFlatComponentPresentOrRestoring` /
`isPackagePresentOrRestoring`) sind entfernt; beide Reconciles rufen jetzt denselben Helfer. Kolibri
behält nur einen 3-Zeilen-Bridge `DeletionGatePass.keepFlatComponent` (flat-String → parse →
`keepComponent`, malformed → prune), weil seine Store-Keys flache Strings sind. Getrennt bleiben
(fundamental): Kandidaten-Finden (`referencedKeys` vs Store-Orphans), Modell-Anwendung (atomares
`update{}` vs subtract-`edit{}`), das snapshot→RMW-Fenster. Direkt gepinnt durch
`core/DeletionGatePassTest` (8 Fälle: present→reads==0, absent+session→keep, absent+session-less→prune,
undetermined→keep, beide Grains, batching reads==1). Der Fail-safe-Kern (inkl. eines künftigen
count-floors) lebt jetzt an **einer** Stelle. Gate grün.

<details><summary>Ursprünglicher Plan (Referenz)</summary>

Heute steckt „fehlender Key → nur Kandidat → keep, wenn Presence **oder** Session anschlägt (beide
fail-safe), sonst prune" in beiden Policies als eigener Code. Ziel: die **reine, Android-freie**
Gate-Logik einmal in `:core` (neben `AppPresence`/`InstallSessionInspector`), sodass die
Policy-Schicht nur noch zwei launcher-eigene Enden liefert:

- **Input:** die Menge der vom Store referenzierten Keys (nyx: `HomeLayout.referencedKeys()`;
  kolibri: die vier Store-Key-Sets) + die frische Enumeration.
- **Shared:** Kandidaten = referenziert ∧ nicht enumeriert; pro Kandidat `AppPresence` (grain-korrekt)
  ODER die **einmal pro Pass** gelesene `InstallSessionInspector`-Menge; `null→keep`; malformed →
  absent. Rückgabe: „diese Keys prunen".
- **Output:** jeder Launcher wendet das Prune-Set auf sein eigenes Modell an (nyx im atomaren
  `update{}`, kolibri per subtract-only `edit{}` je Store).

Damit lebt der Fail-safe-Kern (inkl. eines künftigen count-floors) an **einer** Stelle; die
Policy-Schicht schrumpft auf „liefere Keys / wende Ergebnis an". `PassSessionGate` und die inline
`sessionsRead`-Mechanik in nyx werden durch den Helfer ersetzt. Akzeptanzkriterium: nach der
Extraktion referenzieren **beide** Reconcile-Eingänge denselben Helfer; kein zweites `null→keep`
oder `session-read-once` mehr im Repo-/UseCase-Code.

</details>

### b) ✅ Erledigt (2026-09-24, Branch `refactor/shared-deletion-gate`)

Umgesetzt als abstrakter `core/testFixtures/DeletionGateParityContract` (vier Component-Grain-Szenarien:
present→keep, absent+session→keep, absent+session-less→**prune**, undetermined→keep), implementiert
von **beiden** Launchern: `NyxDeletionGateParityTest` (Kandidat als Home-Tile → `ReconcileHomeLayoutUseCase`
→ überlebt im Layout?) und `KolibriDeletionGateParityTest` (Kandidat im Hidden-Store →
`ObserveInstalledAppsUseCase` → überlebt im Store?). Eine Szenario-Tabelle, zwei Adapter, gegen die
**echten** Use-Cases. Nicht-vacuous, weil beide Richtungen assertet werden (der prune-Fall ist
`assertFalse`). Ergänzt die per-Launcher-Suites (`DeletionGatePassTest` pinnt den Kern, dies pinnt die
Wiring-Parität). Ein Launcher, der einen Store nicht mehr durchs Gate routet oder die Fail-safe-Richtung
kippt, macht seine Adapter-Seite rot. Nur Component-Grain (kolibris Package-Grain für custom names hat
kein nyx-Analog → von `DeletionGatePassTest` + kolibri-Suite abgedeckt). Gate grün.

<details><summary>Ursprünglicher Plan (Referenz)</summary>

Heute testen nyx und kolibri ihr Gate **unabhängig** — eine einseitige Semantik-Änderung wird nicht
rot. Ziel: **eine** Tabelle von Gate-Szenarien (present / absent+session / absent+no-session /
undetermined / malformed / partial-load / store-read-fail), die gegen **beide** Reconcile-Eingänge
läuft und identisches keep/prune-Verhalten (plus `reads==1`/`reads==0`) assertet. Zwei Formen
möglich:

- **Nach (a):** ein JVM-Contract-Test direkt gegen den geteilten Helfer (deckt den Kern ab) +
  je ein dünner Adapter-Test pro Launcher (liefert-Keys / wendet-an).
- **Ohne (a):** ein parametrisierter Test, der dieselbe Szenario-Liste einmal durch
  `ReconcileHomeLayoutUseCase` und einmal durch `ObserveInstalledAppsUseCase` schickt und die
  Ergebnisse vergleicht.

Akzeptanzkriterium: eine bewusst eingebaute Divergenz (z. B. `null→keep` in nur einem Launcher auf
`null→prune` kippen) macht den Parity-Test rot. Ergänzt die bestehenden per-Launcher-Suites, ersetzt
sie nicht.

</details>

**Reihenfolge (erledigt):** (a) zuerst gemacht → (b) war dann fast trivial (der Contract gegen den
Helfer war schon `DeletionGatePassTest`; (b) fügte die Wiring-Parität über beide echten Use-Cases
hinzu). Beide auf Branch `refactor/shared-deletion-gate`, Gate grün. Der Haupt-Drift-Vektor
(dupliziertes Delete-Gate) ist damit strukturell geschlossen **und** durch einen Cross-Launcher-Test
abgesichert.

---

## Design-Frage (Reflexion, offen): Auto-Prune vs. feste Slots + lazy-Validierung

**Ausgangsbeobachtung.** Der gesamte F7-/Delete-Gate-/Drift-Prävention-Apparat existiert nur, *weil*
beide Launcher gespeicherte User-Zuweisungen (favorites / hidden / swipe / custom names / home-layout)
**automatisch prunen** — per Snapshot-Diff gegen die frische Enumeration. Genau dieser Auto-Prune
erzeugt die stille-Datenverlust-Klasse, gegen die das Gate dann fail-safe verteidigen muss. Anders
gesagt: das Problem ist zu einem guten Teil **selbst induziert** durch die Entscheidung, eine
*offene* Referenzmenge automatisch zu bereinigen.

**Die Alternative.** Für manche Stores ginge auch das Gegenmodell: **feste / user-kuratierte Referenz +
lazy Validierung am Verwendungspunkt, aber nie stilles Auto-Löschen.** Eine Referenz auf eine
verschwundene App bleibt dann sichtbar stehen und wird erst beim *Benutzen* behandelt (z. B. Toast
„nicht mehr installiert" statt Absturz), aufgeräumt nur durch User-Aktion oder Reinstall. Damit
verschwindet die Prune-Verlust-Klasse **komplett** — es gibt nichts still zu verlieren, also braucht
es dort auch kein Gate.

**Der Trade-off (ehrlich, kein Freibier).**

- *Auto-Prune* (heute): keine Karteileichen, self-healing beim nächsten Load — **aber** braucht das
  Fail-safe-Gate + fail-closed Reads + count-floor-Erwägung, sonst Datenverlust.
- *Feste Slots + lazy*: keine Gate-Komplexität, immun gegen die Verlust-Klasse — **aber** tote
  Einträge sammeln sich an (veraltetes Label, Toast beim Tippen), heilen nur manuell.

Es ist **pro Store** abzuwägen, nicht pauschal:

- **Slot-artige Stores** (Swipe-Left/-Right sind schon einzelne Slots) sind die natürlichen
  Kandidaten fürs lazy-Modell: ein toter Swipe-Slot könnte beim Auslösen validiert werden statt per
  Reconcile geprunt — das spräche das Gate an dieser Stelle komplett frei.
- **Offene Mengen** (home-layout, favorites, hidden, custom names) profitieren stärker vom
  Auto-Cleanup (sonst wächst der Müll unbegrenzt), also lohnt dort das Gate eher.

**Warum das hier steht (kein Task).** Nichts davon ist ein Bug oder eine ToDo-Umsetzung — es ist die
Linse, durch die die nächste Entscheidung laufen sollte: **bevor ein neuer auto-geprunter Store
dazukommt**, erst fragen „muss der überhaupt auto-prunen, oder reicht Slot + lazy?". Reframed auch den
count-floor (ACCEPTED_LIMITATIONS-Re-Eval): ein Sanity-Floor ist ein *Pflaster auf dem Auto-Prune* —
die tiefere Frage ist, ob der Auto-Prune am jeweiligen Store überhaupt gerechtfertigt ist.

### ✅ UMGESETZT (Branch `feature/lazy-slot-validation`): Auto-Prune ganz raus → lazy, user-bestätigtes Entfernen

Die radikale Option ist umgesetzt (windows-style). Die kuratierten Stores (**home-layout/nyx,
favorites/kolibri, custom names, hidden apps, swipe slots**) werden **nie mehr automatisch geprunt**.
Eine Referenz auf eine verschwundene App bleibt stehen; sichtbare Stores markieren sie als „missing"
und bieten „App nicht gefunden. Entfernen?" an (Windows-Verknüpfung-Modell), unsichtbare lassen sie
einfach liegen. Der Membership-Check ist ein In-Memory `key in currentApps` — **kein IPC, kein Gate**.

**Was umgesetzt wurde (staged, ein Commit pro Stufe):**

- **A — kolibri no-prune:** die vier `reconcile*`-Methoden (favorites/swipe/hidden/custom-names) samt
  Interface/Impl/Fake/Contract/Impl-Tests gelöscht; `ObserveInstalledAppsUseCase` reconcilet nicht
  mehr (kein `DeletionGatePass`, kein `AppPresence`/`InstallSessionInspector`), nur noch load →
  keep-last-good → state → emit.
- **B — kolibri lazy-UI:** `GetFavoriteAppsUseCase` hält einen Favoriten mit deinstallierter App als
  synthetischen „missing"-Eintrag (Best-Effort-Label: Custom-Name, sonst Package) statt ihn zu
  droppen; `HomeFavoritesAdapter` graut die Zeile aus und bietet auf Tap „App nicht gefunden.
  Entfernen?"; Swipe-Slot validiert lazy beim Auslösen (Toast + „nicht installiert" im Settings-Chip).
- **C — nyx no-prune:** `HomeLayoutReconciler` verliert Pass 1 (Prune) + den `installed`-Parameter
  (nur noch dedup/folder-repair/page-trim, erreichbar via Import); `ReconcileHomeLayoutUseCase` ist ein
  reiner Struktur-Pass (kein Enumerate/Gate/`snapshot`-fail-closed, nur `STORE_FAILED`).
- **D — nyx missing-Tiles:** `HomeCell.App.missing` + installed-Set aus dem reaktiven Loader
  (`HomeViewModel.installedKeys`); Tile grau + Platzhalter, Tap → „App nicht gefunden. Entfernen?"
  → `viewModel.remove(id)`.
- **E — tote Infra:** `core/{AppPresence,InstallSessionInspector,DeletionGatePass}` +
  `DeletionGateParityContract` + `common-data/PackageManager{Presence,InstallSessions}` + Tests + DI-Binds
  gelöscht. Die gesamte F7-/Drift-Prävention-Maschinerie ist damit weg.

Damit sind die Abschnitte oben (Rule-9-Nuance an den fail-safe-Seams, Drift-Prävention (a)/(b), der
count-floor) **gegenstandslos** — die dort beschriebenen `AppPresence`/`InstallSessionInspector`/
`DeletionGatePass`-Nähte existieren nicht mehr. Die ACCEPTED_LIMITATIONS-Restore-Restrisiko-Einträge
beider Launcher sind ebenfalls moot (es gibt keinen Auto-Prune mehr, der still verlieren könnte).

**Verbleibende bewusste Grenze (neu):** tote Einträge sammeln sich an, bis der User sie entfernt —
veraltetes Label/Icon (missing-Platzhalter), Toast beim Tippen. Das ist der akzeptierte Trade-off der
Option (kein stiller Verlust dafür manuelle Bereinigung). Missing-Folder-*Member* in nyx bekommen
(noch) keine eigene missing-Markierung — sie sind über ihren Folder erreichbar; nur top-level Tiles +
Dock werden ausgegraut.

**Offener Review-Punkt (nyx, Perf-Haltung):** Die missing-Erkennung in nyx abonniert den reaktiven
Installed-Apps-Loader `WhileSubscribed`, solange Home sichtbar ist (`HomeViewModel.installedKeys`) —
ein bewusster Bruch mit nyx' bisheriger *pull-on-open*-Enumerations-Haltung (Enumeration nur beim
Drawer-Öffnen, nicht dauerhaft warm). Das Feature verlangt kontinuierliches Wissen über den
Installations-Status (damit ein Tile beim Uninstall live ausgraut), daher die stehende Subscription
während Home sichtbar ist. **Abwägung:** live-Ausgrauen vs. immer-warme Enumeration. Falls die
Perf-Haltung höher gewichtet wird, ist die Alternative „installed-Set nur auf Paket-Events (via
`PackageEventCoordinator`/`AppUpdateSignal`) + beim ersten Home-Show refreshen" statt der
kontinuierlichen `WhileSubscribed`-Subscription — dann graut ein Tile erst beim nächsten Paket-Event
aus statt sofort. **Noch nicht entschieden.**

<details><summary>Ursprüngliche Option-Notiz (Referenz)</summary>

**Was dadurch WEGFÄLLT** (~1.300–1.700 LOC prod+test, praktisch die gesamte F7-Arbeit + der Alt-
Reconcile-Prune):

- `core/DeletionGatePass` (+Test), `core/AppPresence`, `core/InstallSessionInspector` — danach
  konsumentenlos, komplett tot.
- `common-data/PackageManagerPresence` (+Test), `common-data/PackageManagerInstallSessions` (+Test).
- nyx: die Gate-Logik in `ReconcileHomeLayoutUseCase`, der `snapshot()`-fail-closed-Read, `STORE_FAILED`,
  der Prune-Pass in `HomeLayoutReconciler` (Struktur-Pässe dedup/regrid/folder-dissolve bleiben).
- kolibri: die vier `reconcile*Components`-Methoden, der Reconcile-Block in `ObserveInstalledAppsUseCase`,
  `snapshotFailClosed`.
- Die Cross-Launcher-Parity-Arbeit (a/b) + DI-Bindings + die ACCEPTED_LIMITATIONS-Restore-Einträge +
  die count-floor-Idee — alles moot.

**Was NEU dazukommt — asymmetrisch pro Store:**

- **Sichtbar (home/nyx, favorites/kolibri):** ein „missing"-UI-Zustand (Tile/Row vergraut + Badge) +
  „Entfernen?"-Affordance an der toten Referenz. Remove-Pfad existiert schon
  (`removeFavoriteComponent` etc.). Aufwand liegt in der **View-Schicht beider Launcher** (dünne
  Logik, aber UI-Arbeit — schlechter JVM-testbar als die heutige Domain-Logik: der Aufwand
  verschiebt sich von „viel getestete Domain" zu „wenig getestete UI").
- **Unsichtbar (custom names, hidden):** **gar nichts** — ein Custom-Name / Hidden-Eintrag für eine
  fehlende App tut nichts. Nur die Reconcile-Methode löschen. Bei **Hidden ist no-prune strikt
  besser** (Verstecken überlebt Uninstall→Reinstall). Optional später ein „Aufräumen"-Screen.

**Load-bearing Mechanik gegen die Restore-Flut:** nur im UI **markieren**, und erst bei
**User-Interaktion** mit dem toten Item fragen — **nie** proaktiv scannen+prompten. Damit gibt es
keinen Reconcile-Pass mehr, der während Restore/Settling danebengreifen kann → die gesamte Timing-/
Restore-Verlust-Klasse verschwindet *by design*, nicht per Fail-safe.

**Was BLEIBT:** der Drawer hat keinen persistierten State (zeigt Live-Enumeration ∖ hidden) → nichts
zu verlieren; der triviale `isEmpty()`-Guard + last-good im `InstalledAppsStateRepository` gegen
transienten Anzeige-Flicker bleibt.

**Vorgeschlagene Reihenfolge (falls je umgesetzt):**
1. Billige Wins zuerst: `reconcileCustomNames` + `reconcileHiddenComponents` **löschen** (reine
   Entfernung, für hidden strikt besser). Gate bleibt grün (die zugehörigen Tests mit weg).
2. Sichtbare Stores: „missing"-State + „Entfernen?" in nyx-Home und kolibri-Favorites bauen, dann
   `reconcileFavoriteComponents` + den Home-Layout-Prune-Pass entfernen.
3. Zuletzt die nun tote Infra löschen: `DeletionGatePass`/`AppPresence`/`InstallSessionInspector` +
   die beiden `PackageManager*`-Impls + DI + Doku-Einträge.

**Offene Abwägung (der eigentliche Call):** lohnt „zero-touch-Sauberkeit" (heute: nie Karteileichen,
self-healing) den Preis von ~1.500 Zeilen Korrektheits-Maschinerie + einem unbewiesenen Verlust-Vektor?
Für kleine, user-kuratierte Mengen (8 Home-Slots, paar Favoriten) spricht viel für die Option; dagegen
spricht die UI-Arbeit und der Verlust der guten Domain-Testbarkeit. **Bewusst offen gelassen.**

</details>
