# REACTIVE_APPLIST_SPEC.md — Anzeige rein reaktiv, Enumeration nur bei echter Änderung

**Status: UMGESETZT (2026-08-09, Branch `refactor/reactive-applist`).** Gebaut in der
sicheren G3-Reihenfolge, je Commit `:domain:test :data:test :app:test checkConventions
checkRule13` grün: Commit 1 = `customNamesFlow` + `usageFlow` (verhaltensneutral);
2a-1/2a-2/2a-3 = `applyNames`-Overlay an Site 1 (getInstalledApps-Familie) / Site 2
(Drawer+Favoriten auf `rawAppsFlow`) / Site 3 (Recents), No-Op solange die Enumeration
noch backt; 2b-i = Flip `processResolveInfoList → displayName = originalName`; 2b-ii =
`triggerCustomNameUpdate` entfernt (Rename ohne Enumeration, Gewinn 1); Commit 3 =
`usageFlow` in den Drawer-combine + `refreshAppsUseCase()` an beiden Launch-Stellen
entfernt (Start ohne Enumeration, Gewinn 2); Commit 4 = Cleanup/Doku. `appsUpdateTrigger`
trägt jetzt nur noch Paket-Events + Force-Reloads (Resume/Factory-Reset) — die Debounce
sitzt korrekt auf der Sturm-Quelle. `RECONCILE_C2_SPEC` / Option-B endgültig überflüssig.
Der Rest dieses Dokuments ist der (unveränderte) Entwurf, der zu dieser Umsetzung führte.

<details><summary>Ursprünglicher Entwurfs-Status (Spec-Review-Runden 1+2 + finale Verifikation)</summary>

**ENTWURF (2026-08-09), Spec-Review-Runden 1+2 eingearbeitet — Richtung SOUND,
Inventur vollständig, alle Findings gefixt.** Runde 1: R1 (Blocker, vollständige
displayName-Consumer-Inventur §3), R2 (Swipe-Launch entkoppelt), R3–R5 (Invarianten/
Recent-Fallback/DataStore). Runde 2: die zwei Quell-Familien entwirrt
(`getInstalledApps()` pre-Veto vs. `rawAppsFlow` post-Veto keep-last-good) — **Drawer/
Favoriten bleiben auf `rawAppsFlow`** (sonst kehrt der Transient-Empty-Flicker zurück);
die Name-Anwendung ist EIN Helfer an ~3 Quell-Grenzen, nicht „ein zentraler Flow"; Umfang
ehrlich ~14–18 Dateien. Selbst-Audit danach: zwei Rest-Widersprüche gefixt (RAL-INV-4 +
§9 trugen noch die pre-Runde-2-Formulierung) und eine sicherere inkrementelle Migration
(§7, `applyNames` idempotent → Consumer einzeln, dann winziger Flip-Commit) ergänzt.
**Finale Verifikations-Runde:** ein echter Major, den alle drei vorherigen Pässe
übersahen — die **Sortier-Kopplung** (`processResolveInfoList:261` sortiert nach
`displayName`; nach dem Flip verlören CustomNames/Settings die Custom-Name-Reihenfolge)
→ `applyNames` re-sortiert (§0/§2a/§6/§7); plus vier Minor/Nit (RAL-3 gelöst, §2b-Dedup-
Mechanismus korrigiert, `triggerCustomNameUpdate`-Aufrufer 4/3-Pfade, RAL-INV-4-Gloss).
Ziel: jede unnötige PackageManager-Enumeration
und jeden dadurch verursachten Lag beseitigen, indem **Name, Sortierung, Ausblenden
und Favorit als reaktive `combine`-Eingänge** modelliert werden — statt die
Enumeration als Universal-Hammer für jede Änderung zu missbrauchen. Erst reviewen
(mehrere Multi-Agent-Runden), dann bauen; nicht auf `main` mergen, bevor der Rewrite
sie überstanden hat.

</details>

Schwester-Dokumente: `RECONCILE_SPEC.md` (das Verifikations-Veto — bleibt), 
`DEBOUNCE_SPEC.md` / `RECONCILE_C2_SPEC.md` (werden durch diesen Spec **überflüssig**,
siehe §9), `app/src/test/CLAUDE.md`.

---

## 0. Das Problem an der Wurzel

Die halbe ideale Architektur existiert bereits: `GetDrawerAppsUseCase` **kombiniert
reaktiv**
```
combine(rawAppsFlow, sortOrderFlow, hiddenAppsFlow) { apps, sort, hidden -> … }
```
— Ausblenden und Sortier-Reihenfolge lösen **keinen** Reload aus, sie werden im
combine re-derived. Das ist das Ideal. **Aber zwei Eingänge sind nicht reaktiv
verdrahtet** und erzwingen deshalb volle Enumerationen:

| Kopplung | Beleg | Folge |
|---|---|---|
| **Custom-Name in die Enumeration gebacken** | `InstalledAppsRepositoryImpl.processResolveInfoList:235` (`getDisplayNameForPackage` → `AppInfo.displayName`) | Rename **muss** neu enumerieren, um den Namen neu einzubacken → der 250-ms-Nit |
| **Nutzung als Sync-Call, kein Flow** | `GetDrawerAppsUseCase:68` (`sortAppsByTimeWeightedUsage(...)`), `AppUsageRepository` hat **keinen** Flow | App-Start feuert den combine nicht selbst → man erzwingt einen Reload (`onAppClicked` → `refreshAppsUseCase`) nur zum Neu-Sortieren |
| **Sortierung nach `displayName` in der Enumeration** (Verifikations-Major) | `processResolveInfoList:261` (`sortedBy { displayName.lowercase() }`) | hängt am Custom-Name → nach dem Flip sortiert die Enumeration nach Original-Name; Consumer **ohne eigene Sortierung** (CustomNames-/Settings-VM) verlieren die Custom-Name-Reihenfolge. Fix: `applyNames` re-sortiert (§2a/§6) |

Beide sind **DataStore-backed** und tragen bereits `AppInfo.originalName` **und**
`displayName` — die Entkopplung ist also sauber möglich, ohne neue APIs.

---

## 1. Zielbild

> **Die installierte Menge (`rawAppsFlow`) ändert sich NUR bei Install/Uninstall
> (+ Cold-Start/Resume). Alles andere — Label, Sortierung, Ausblenden, Favorit — ist
> ein reaktiver `combine`-Eingang, der in die Anzeige gerechnet wird. Die
> PackageManager-Enumeration ist der EINZIGE Enumerations-Trigger und hängt an genau
> einer Quelle: Paket-Broadcasts (debounced).**

- **Rename** → `customNamesFlow` emittiert → combine re-derived → **sofort, keine
  Enumeration.**
- **App-Start** → `usageFlow` emittiert → combine re-sortiert → **sofort, keine
  Enumeration.**
- **Ausblenden / Sortier-Order** → schon heute reaktiv (unverändert).
- **Install/Uninstall** → `getInstalledApps()` reloadet (debounced), das **Veto**
  reconciled. Die einzige Enumeration.

---

## 2. Die zwei Entkopplungen (der ganze Kern)

### 2a. Custom-Name → reaktiver Eingang
- **Enumeration liefert Original-Label:** `processResolveInfoList` setzt
  `displayName = originalName` (der `getDisplayNameForPackage`-Aufruf entfällt dort;
  `InstalledAppsRepositoryImpl` braucht `CustomNamesRepository` danach nicht mehr).
- **Neuer Flow:** `CustomNamesRepository.customNamesFlow: Flow<Map<String, String>>`
  (`settingsDataStore.data.map { … }.distinctUntilChanged()`), pkg → Custom-Name.
  **R5:** der Store ist geteilt (`CustomNamesRepositoryImpl:94`), also emittiert
  `.data` bei jedem Settings-Write auch hier eine (gleiche) Map — `distinctUntilChanged`
  verhindert, dass der combine bei jedem App-Launch unnötig neu feuert.
- **Name-Anwendung als reiner Helfer:** `fun applyNames(apps, names) = apps.map {
  it.copy(displayName = names[it.packageName] ?: it.originalName) }
  .sortedBy { it.displayName.lowercase() }` — die **Logik** an **einer** Stelle, aber
  **an jeder Quell-Grenze angewandt** (`SPEC-DECISION RAL-1`), weil die Consumer über
  **zwei** Quell-Familien laufen (`getInstalledApps()` vs. `rawAppsFlow`) plus Recents
  Point-Read. Also 2–3 Anwendungs-Sites, EIN Helfer (R2). **Das `.sortedBy`
  (Verifikations-Major) ist zwingend:** `processResolveInfoList:261` sortiert heute
  nach `displayName` (= Custom-Name); nach dem Flip auf `originalName` würde die
  Enumeration nach Original-Name sortieren, und CustomNames-/Settings-VM re-sortieren
  **nicht** → umbenannte Apps stünden dauerhaft an der Original-Position. `applyNames`
  re-sortiert nach dem finalen `displayName` und stellt so die heutige Sortier-
  Nachbedingung an **jeder** Site wieder her (Drawer/Favoriten/Hidden/Swipe/Onboarding
  überschreiben sie mit ihrer eigenen Sortierung — harmlos).
- **`triggerCustomNameUpdate()` entfällt** (Rename triggert keinen Reload mehr; der
  DataStore-Write treibt `customNamesFlow`).

### 2b. Nutzung → reaktiver Eingang
- **Neuer Flow:** `AppUsageRepository.usageFlow: Flow<Unit>` als Änderungs-Signal.
  **R5-Präzisierung:** Usage liegt im **geteilten** `settingsDataStore`
  (`AppUsageRepositoryImpl:71`, Keys `KEY_USAGE_PREFIX` neben favorites/hidden/sort);
  `DataStore.data` emittiert bei **jedem** Write darauf. `usageFlow` feuert also auch
  bei Rename/Hide/Favorit/Sort mit — funktional harmlos (idempotentes Re-Sort, **keine**
  Enumeration). **Korrektur (Verifikation):** der Drawer-Pfad hat **kein** terminales
  `stateIn`/`distinctUntilChanged` (`GetDrawerAppsUseCase` endet in `.flowOn` →
  `AppManagementDelegate:80 .asLiveData()`); der redundante Tick ist harmlos, weil der
  RecyclerView-**Adapter-DiffUtil** einen No-Op-Diff erzeugt, nicht durch Flow-Dedup.
  Optional den `usageFlow` auf die Usage-Keys eingrenzen, um die Ticks zu reduzieren.
- **combine-Eingang:** `GetDrawerAppsUseCase` nimmt `usageFlow` als weiteren Eingang;
  der bestehende `sortAppsByTimeWeightedUsage(...)`-Aufruf bleibt (combine-Transform
  ist `suspend`), feuert aber jetzt reaktiv bei Start neu.
- **`refreshAppsUseCase()` entfällt an BEIDEN Launch-Stellen** (Spec-Review R2):
  `AppManagementDelegate.onAppClicked` **und** `HandleSwipeActionUseCase:47` (der
  Swipe-Launch — strukturell identisch, sonst enumeriert jeder Swipe-Start weiter →
  RAL-INV-1/2 verletzt). `recordAppLaunchUseCase` bleibt an beiden (es treibt
  `usageFlow` → reaktives Re-Sort). `refreshAppsUseCase` bleibt für echte
  Force-Reloads (Resume) verfügbar.

---

## 3. Wo die Name-Anwendung lebt (`SPEC-DECISION RAL-1`) — VOLLSTÄNDIGE Inventur

**Kritisch (Spec-Review R1, Blocker):** die installierte Menge fließt in **mehr**
Consumer als naiv gedacht. Wer `displayName` liest, MUSS nach 2a durch die
Name-Anwendung — sonst zeigen diese Screens still den Original-Namen, und die
CustomNames-Erkennung bricht **strukturell**. Vollständige Liste der `displayName`-
Consumer (über `rawAppsFlow` **und** über `getInstalledApps()` via
`GetInstalledAppsUseCase` / `GetOnboardingAppsUseCase`):

| Consumer | Weg | Warum Name nötig |
|---|---|---|
| `GetDrawerAppsUseCase` | `rawAppsFlow`-combine | Anzeige + Suche |
| `GetFavoriteAppsUseCase` | `rawAppsFlow`-combine | Anzeige |
| `GetRecentAppsUseCase` | `getCurrentApps()` Point-Read | Anzeige (siehe R4) |
| `HandleSwipeActionUseCase` | `getCurrentApps()` Point-Read | **nur Launch** → braucht **kein** Label |
| `CustomNamesViewModel` (`:116/:121`) | `GetInstalledAppsUseCase` | **`filter { it.originalName != it.displayName }`** — ohne Custom-Name im `displayName` wird die „Apps mit Custom-Name"-Sektion **dauerhaft leer** (`CustomNamesAdapter:43`); `CustomNamesActivity:221` (`setText(app.displayName)`) füllt den Rename-Dialog mit dem Original |
| `HiddenAppsViewModel` | `GetInstalledAppsUseCase` | Anzeige + Suche |
| `SwipeActionsViewModel` (`:68`) | `GetInstalledAppsUseCase` | Anzeige + Suche (`displayName`-Filter) |
| `OnboardingViewModel` + `GetOnboardingAppsUseCase:33` | `getInstalledApps()` | Anzeige + `displayName.isNotBlank()`-Filter |
| `SettingsViewModel` | `GetInstalledAppsUseCase` | Anzeige |

**Konsequenz — ZWEI Quell-Familien, EIN Helfer, mehrere Sites (Spec-Review R2):** die
Consumer laufen über **zwei verschiedene** Quellen, die man NICHT verwechseln darf:
- **`getInstalledApps(): Flow<AppLoad>`** (die rohe, **pre-Veto** Enumeration) →
  `GetInstalledAppsUseCase` / `GetOnboardingAppsUseCase` → CustomNames-, Hidden-,
  Swipe-, Settings-, Onboarding-VMs.
- **`InstalledAppsStateRepository.rawAppsFlow`** (der **post-Veto** keep-last-good
  Holder, geschrieben von `ObserveInstalledAppsUseCase.updateApps`) → **Drawer +
  Favoriten** (`GetDrawerAppsUseCase:32`, `GetFavoriteAppsUseCase:67`).
- **`getCurrentApps()`** Point-Read (mit `lastSuccessfulAppList`-Fallback) → Recent (R4).

`SPEC-DECISION RAL-1`: **die Name-Anwendungs-LOGIK ist EIN reiner Helfer
(`applyNames(apps, names)`), aber er wird an JEDER dieser Quell-Grenzen angewandt** —
kein „ein zentraler Flow, den alle konsumieren" (das ist physikalisch unmöglich, da die
Quellen verschiedene Interfaces sind). Konkret ~3 Sites:
- **`GetInstalledAppsUseCase`** `combine(getInstalledApps().map{unwrap}, customNamesFlow)
  { a, n -> applyNames(a, n) }` (als `stateIn`/`shareIn` geteilt) → deckt die
  getInstalledApps-Familie ab; `GetOnboardingAppsUseCase` analog.
- **`GetDrawerAppsUseCase` / `GetFavoriteAppsUseCase`** nehmen `customNamesFlow` in
  **ihren eigenen `rawAppsFlow`-combine** auf und rufen `applyNames` im Transform.
  **Kritisch (R2-Blocker): Drawer/Favoriten bleiben auf `rawAppsFlow`** — sie dürfen
  NICHT auf die getInstalledApps-basierte Ableitung umgehängt werden, sonst wandern
  Drawer/Home vom Veto-Holder auf den rohen Loader (der `Failed → emptyList` kollabiert)
  und der Transient-Empty-Drawer-Flicker (den der Holder gerade verhindert, RAL-INV-4/
  P1) kehrt zurück.
- **Recent** wendet `applyNames` auf seinen `getCurrentApps()`-Point-Read an (R4).

**Erkennungs-Invariante bleibt gültig:** nach der Anwendung trägt `displayName` wieder
den Custom-Name → `originalName != displayName` erkennt umbenannte Apps korrekt
(Enumeration setzt `displayName = originalName`, der combine überschreibt für
umbenannte). Diese Invariante ist im Schritt-2-Test explizit zu prüfen.

Das **Veto/Reconcile** (`ObserveInstalledAppsUseCase`) **liest** `getInstalledApps()`
(`:46`) und **schreibt** `rawAppsFlow` via `updateApps` (`:131`) — beides mit
Original-Label. Es reconciled über `componentName`/`packageName`, **nie** `displayName`
— also von der Name-Anwendung unberührt. `HandleSwipeActionUseCase` braucht für den
*Launch* kein Label und bleibt Point-Read.

**R4 (Recent behält seinen Last-known-good-Fallback):** `GetRecentAppsUseCase` nutzt
heute `getCurrentApps()`, das im transient-leeren Reload-Fenster auf
`lastSuccessfulAppList` zurückfällt (`InstalledAppsStateRepositoryImpl:80-85`). Es darf
**nicht** naiv auf ein `.first()` eines raw-Flows umgestellt werden (kein
`ifEmpty`-Guard → Recent zeigt im Fenster null). → Recent **behält den
`getCurrentApps()`-Point-Read** und wendet `applyNames` mit einem Snapshot
(`getAllCustomNames()`, existiert schon suspend) obendrauf an. Damit ist Recents Umstellung ebenfalls nicht Teil des „verhaltensneutral"-Anspruchs
ohne diese Fallback-Erhaltung.

---

## 4. Prämissen

- **P1 — Veto bleibt unverändert** (`RECONCILE_SPEC`). Es läuft nur noch, wenn sich
  die installierte Menge *wirklich* ändert (Paket-Events + Cold-Start), nicht mehr
  bei Rename/Start.
- **P2 — Nur Standard-APIs:** `combine`, `StateFlow`, DataStore-`Flow`, `debounce`,
  `flatMapLatest`. Keine erfundene API. Alles bereits im Einsatz.
- **P3 — Das reaktive Muster existiert schon** (`hidden`/`sortOrder`); dieser Spec
  erweitert es auf `customNames` + `usage`, statt neue Struktur zu erfinden.
- **P4 — `AppInfo` bleibt unverändert** (`originalName` + `displayName` sind schon
  da). Die Enumeration setzt `displayName = originalName`; der combine überschreibt.

---

## 5. Invarianten (RAL-INV-*)

- **RAL-INV-1:** Die PackageManager-Enumeration wird **ausschließlich** durch
  Paket-Broadcasts (debounced) + Priming/Cold-Start ausgelöst — nie durch Rename,
  Start, Ausblenden, Sortier-Order oder Favorit.
- **RAL-INV-2:** Rename und Start sind **reine reaktive Re-Derivationen** (combine),
  sofort, ohne Enumeration, ohne Debounce.
- **RAL-INV-3 (R3-präzisiert):** `appsUpdateTrigger` trägt nach dem Rewrite
  Paket-Events **plus explizite Force-Reloads** (Resume via
  `AppManagementDelegate.refreshInstalledApps:217`, Factory-Reset via
  `FactoryResetUseCase`) — aber **keine** bewussten Einzelaktionen (Rename/Start)
  mehr. Debounce darauf trifft die Sturm-Quelle; die verbleibenden Force-Reloads sind
  seltene, gewollte Voll-Reloads (kein Nutzer-Lag).
- **RAL-INV-4:** Das Veto läuft auf jeder `getInstalledApps()`-Emission (Paket-Event,
  Cold-Start, + seltene Force-Reloads Resume/Factory-Reset) und **schreibt** danach
  `rawAppsFlow` (`updateApps`), per-Ziel
  `PackagePresence`-gegated — Korrektheit unverändert. (Es reagiert NICHT auf
  `rawAppsFlow`, es speist ihn.)
- **RAL-INV-5:** Der `getInstalledApps(): Flow<AppLoad>`-Vertrag bleibt; nur die
  *Trigger-Menge* schrumpft und der `displayName` ist jetzt Original (Namen kommen
  im Display-combine dazu).

---

## 6. Sigs (Zielzustand)

```kotlin
// CustomNamesRepository — NEU
val customNamesFlow: Flow<Map<String, String>>   // settingsDataStore.data.map{…}.distinctUntilChanged()
// entfällt: suspend fun triggerCustomNameUpdate()

// AppUsageRepository — NEU
val usageFlow: Flow<Unit>                         // settingsDataStore.data (Änderungs-Signal; R5)

// InstalledAppsRepositoryImpl.processResolveInfoList
//   displayName = originalName   (kein getDisplayNameForPackage mehr)

// EIN reiner Helfer (die Logik), an JEDER Quell-Grenze angewandt (SPEC-DECISION RAL-1, R2):
fun applyNames(apps: List<AppInfo>, names: Map<String, String>): List<AppInfo> =
    apps.map { it.copy(displayName = names[it.packageName] ?: it.originalName) }
        .sortedBy { it.displayName.lowercase() }   // Sortier-Kopplung: heutige Nachbedingung von processResolveInfoList:261

// Site 1 — getInstalledApps-Familie (pre-Veto): GetInstalledAppsUseCase, geteilt via stateIn/shareIn
//   combine(getInstalledApps().map{unwrap}, customNamesFlow) { a, n -> applyNames(a, n) }
//   → CustomNamesVM, HiddenAppsVM, SwipeActionsVM, SettingsVM; GetOnboardingAppsUseCase analog.

// Site 2 — Drawer/Favoriten BLEIBEN auf rawAppsFlow (post-Veto keep-last-good, R2-Blocker):
//   GetDrawerAppsUseCase   = combine(rawAppsFlow, customNamesFlow, sortOrderFlow, hiddenAppsFlow, usageFlow) {
//       raw, names, sort, hidden, _ -> … applyNames(raw, names) … sort … }
//   GetFavoriteAppsUseCase = combine(rawAppsFlow, customNamesFlow, …) { raw, names, … -> applyNames(raw, names) … }

// Site 3 — Recent: getCurrentApps()-Point-Read (Fallback erhalten) + applyNames obendrauf (R4).

// refreshAppsUseCase()-Zeile entfällt an BEIDEN Launch-Stellen (R2):
//   AppManagementDelegate.onAppClicked  UND  HandleSwipeActionUseCase:47
// triggerCustomNameUpdate()-Aufrufe entfallen (Rename treibt customNamesFlow via DataStore)
```

---

## 7. Migrationsreihenfolge (je ein revertierbarer Commit)

1. **`customNamesFlow` + `usageFlow` hinzufügen** (Repos + Contract-Triples, Rule 2),
   **ohne** sie zu konsumieren. Verhaltensneutral.
2. **Name-Anwendung entkoppeln — als EIN Commit, ALLE Consumer zusammen** (R1):
   `processResolveInfoList` → `displayName = originalName`; den `applyNames`-Helfer an
   **allen ~3 Quell-Grenzen** einziehen (§3/§6, R2): Site 1 = `GetInstalledAppsUseCase`-
   combine (deckt CustomNames-/Hidden-/Swipe-/Settings-VMs + Onboarding); Site 2 =
   Drawer- **und** Favoriten-combine **auf `rawAppsFlow` bleibend** + `customNamesFlow`;
   Site 3 = Recents Point-Read. `triggerCustomNameUpdate`-Aufrufe entfernen → **Rename
   ohne Enumeration** (Gewinn 1). **Nur dann verhaltensneutral**, wenn ALLE Consumer im
   selben Commit umziehen **und** Drawer/Favoriten auf `rawAppsFlow` bleiben (sonst:
   Custom-Namen verschwinden von nicht-migrierten Screens / CustomNames-Sektion leer =
   R1-Blocker, oder Drawer wandert vom Veto-Holder weg = R2-Blocker/Transient-Empty).
   **Pflicht-Tests:** (a) die `originalName != displayName`-Erkennung nach der
   Anwendung; (b) Drawer/Favoriten weiterhin über `rawAppsFlow` (keep-last-good
   erhalten); (c) **die Sortierung** — CustomNames/Settings zeigen umbenannte Apps
   nach dem Flip weiter an der Custom-Name-Position (dank `applyNames`-`.sortedBy`),
   nicht an der Original-Position (Verifikations-Major).

   > **Sicherere Reihenfolge (empfohlen statt eines ~10-Datei-Atomic-Commits, G3):**
   > `applyNames` ist **idempotent, solange die Enumeration den Namen noch einbackt** —
   > `applyNames(apps, names)` setzt `displayName = names[pkg] ?: originalName`, was bei
   > schon-eingebackenem `displayName` derselbe Wert ist. Deshalb:
   > - **2a:** `customNamesFlow` + `applyNames` an **allen** Sites einziehen, **während**
   >   `processResolveInfoList` den Namen noch einbackt → jeder Consumer einzeln, jeder
   >   Commit grün (No-Op-Overlay).
   > - **2b:** in **einem winzigen** Commit `processResolveInfoList` → `displayName =
   >   originalName` flippen **und** `triggerCustomNameUpdate` entfernen. Jetzt kommt der
   >   Name aus dem combine — und weil ALLE Sites ihn schon anwenden, bricht kein Screen.
   >
   > So ist der gefährliche Moment (der Flip) **ein** kleiner Commit statt eines großen,
   > und die Consumer-Migration ist inkrementell + einzeln reviewbar.
3. **Nutzung entkoppeln:** `usageFlow` in den Drawer-combine; `refreshAppsUseCase()` an
   **beiden** Launch-Stellen entfernen (`onAppClicked` **und**
   `HandleSwipeActionUseCase:47`, R2) → **Start (Tap + Swipe) ohne Enumeration**
   (Gewinn 2).
4. **Aufräumen:** `appsUpdateTrigger` trägt jetzt nur noch Paket-Events + Force-Reloads
   (RAL-INV-3, R3-präzisiert) → Debounce darauf korrekt platziert; `RECONCILE_C2_SPEC`/
   Option-B sind endgültig überflüssig (§9).

Nach 2 ist der Custom-Name-Nit weg, nach 3 die Start-Enumeration. Jeder Schritt
mergefähig; die frühen Schritte sind verhaltensneutral, die Gewinne fallen an den
klar benannten Punkten.

---

## 8. Test-Auswirkung

- **Contract-Tests** für die zwei neuen Flows (`customNamesFlow`, `usageFlow`) — Teil
  des Contract-Triples pro Repo (Rule 2). Die **Entfernung** von
  `triggerCustomNameUpdate` aus `CustomNamesRepository` berührt Interface + Fake +
  Contract (inkl. der zwei Contract-Test-Methoden `CustomNamesRepositoryContract:256/262`).
  **Korrektur (Verifikation): vier Aufruf-Stellen über drei Pfade**, nicht nur Rename:
  `setCustomNameForPackage:125` + `removeCustomNameForPackage:139` (Rename),
  `setCustomNamesInBatch:256` (Backup-Import), `purgeRepository:302` (Reset) — alle vier
  fallen weg. Die Anzeige-Refreshes für Import + Reset bleiben abgedeckt, weil
  `settingsDataStore.data` bei diesen Writes ohnehin re-emittiert → `customNamesFlow`
  (Import/Reset ändern die Map real, also greift `distinctUntilChanged` nicht).
- **`usageFlow` betrifft nur den Drawer-Usage-Sort** — Favoriten (user-gepinnt bzw.
  alphabetischer Top-N-Fallback) und Recent (Point-Read, liest Usage bei jedem Aufruf
  via `getRecentlyLaunchedPackages`/`getAllCustomNames`) brauchen ihn **nicht**.
- **Name-Anwendung (Kern-Regressionstest gegen den Nit):** ein `customNamesFlow`-Change
  → `displayName` ändert sich an jeder Site **ohne** `queryIntentActivities`-Aufruf
  (Zähler == 0). Plus: die `originalName != displayName`-Erkennung greift nach
  `applyNames` (R1-Pflichttest).
- **Drawer/Favoriten:** weiterhin über `rawAppsFlow` (keep-last-good erhalten, R2); ein
  `usageFlow`-Emit → Re-Sort **ohne** Enumeration.
- **`GetDrawerAppsUseCase`/`GetFavoriteAppsUseCase`** haben schon Tests (combine); die
  neuen Eingänge (`customNamesFlow`, `usageFlow`) dort ergänzen. Neue `.catch`-Arme
  gehorchen dem `checkConventions` Flow.catch-rethrow-Gate.

---

## 9. Was dieser Spec ÜBERFLÜSSIG macht

- **Der Custom-Name-Nit** (DEBOUNCE_SPEC v2 / Option B) verschwindet **komplett** —
  Rename enumeriert nie, es gibt kein Debounce-Platzierungs-Problem mehr. Der Branch
  `fix/debounce-storm-source-only` wird damit unnötig.
- **C2b** (`RECONCILE_C2_SPEC`, verworfen) ist erst recht sinnlos — die Delta-
  Optimierung wollte die Enumeration bei Paket-Events sparen; hier passiert die
  Enumeration ohnehin nur noch bei *echten* (seltenen, debounced) Paket-Events, eine
  volle Enumeration dort ist billig genug.
- **Der Debounce (v1, geliefert auf `main`)** wird **korrekt** as-is: `appsUpdateTrigger`
  trägt dann die Sturm-Quelle (Paket-Broadcasts) **plus die seltenen Force-Reloads**
  (Resume/Factory-Reset) — keine bewussten Einzelaktionen mehr (RAL-INV-3). Debounce
  darauf lagt also nichts Nutzer-Sichtbares. Nichts umzuverdrahten.

---

## 10. Ehrlich: was NICHT auf null geht (irreduzibel, keine „Drawbacks")

- Ein **echter Install** braucht *etwas* Arbeit, um das neue Label zu finden (mind.
  eine scoped Query oder die nächste Voll-Enumeration). Reale Arbeit für reale
  Änderung — nicht unnötig.
- **Cold-Start/Resume-Reconcile** (Selbstheilung für Events während toter Prozess)
  bleibt nötig — Korrektheits-Mechanismus (Veto), keine Limitation.
- Die zeit-gewichtete Sortierung driftet zwischen Starts leicht (nutzt
  `currentTimeMillis` ohne Clock) — bestehende akzeptierte Näherung, **unverändert**
  (reaktives Re-Sort bei Start verbessert sie sogar tendenziell).

Umfang (Spec-Review R1/Fan-out ehrlich nachgezogen): **moderate-plus, ~14–18
Dateien** — nicht die naiven ~8–10. Die Architektur ist mittelgroß, aber der
displayName-Consumer-Fan-out (§3: sechs Use-Cases + fünf Screens, plus die
Contract-Triples der zwei neuen Flows und die `checkConventions` Flow.catch/cancel-
Gates auf jedem neuen `combine`/`.catch`) verdoppelt die Touch-Liste. Weiter kein
Framework, keine neue Schicht, keine erfundene API — aber die §3-Auslassung war
genau das, was den Umfang klein *wirken* ließ.

---

## 11. Offene Entscheidungen

- **`SPEC-DECISION RAL-1` — GELÖST (Runde 2):** EIN reiner `applyNames`-Helfer, an ~3
  Quell-Grenzen angewandt (getInstalledApps-Familie via GetInstalledAppsUseCase;
  Drawer/Favoriten auf `rawAppsFlow`; Recents Point-Read) — nicht „ein zentraler Flow".
  Drawer/Favoriten dürfen NICHT vom Veto-Holder weg (§3/§6).
- **`SPEC-DECISION RAL-2`:** `usageFlow` als nacktes `Unit`-Signal (Trigger, Sort
  bleibt Suspend-Call) vs. als Daten-Flow (Sort wird pure Funktion). Empfehlung: das
  billigere **Unit-Signal**, solange die Suspend-Sort-im-combine bleibt.
- **`SPEC-DECISION RAL-3` — GELÖST:** `InstalledAppsRepositoryImpl` nutzt
  `CustomNamesRepository` **nur** für `getDisplayNameForPackage` (Impl:237) — genau der
  Aufruf, den 2a entfernt. Danach ist der Konstruktor-Param nachweislich ungenutzt →
  entfernen (deckungsgleich mit §2a).

---

## 12. Was dieser Spec NICHT ist

- Kein Korrektheits-Fix (das war/ist das Veto).
- Keine neue Reload-Maschinerie (im Gegenteil — er entfernt Reload-Trigger).
- Kein Delta/Fold (nicht C2b).
- Keine `AppInfo`- oder Vertrags-Änderung (`getInstalledApps` bleibt).
