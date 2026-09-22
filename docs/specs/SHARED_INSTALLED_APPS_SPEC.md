# SHARED_INSTALLED_APPS_SPEC — Kolibris In-Memory-App-Store als geteiltes Klasse-A-Subsystem

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als Detail-Spec unter dem
> Dach von `MONOREPO_MERGE_SPEC` (§3.5-Erweiterung). Behandelt genau das eine
> Subsystem, das die Merge-Spec bisher nur als *Contract-Konvergenz* (Klasse C)
> führt, obwohl es die reifste, am dichtesten getestete Einheit beider Launcher
> ist: **das Laden und In-RAM-Halten der Liste installierter Apps.**
>
> **Fokus:** Kolibris battle-tested Zwei-Stufen-Motor (reaktiver `Flow<AppLoad>`
> + `InstalledAppsStateRepository` mit `lastSuccessfulAppList`-Fallback +
> debounced Broadcast-Reload) von „jeder Launcher hat seine eigene Kopie" auf
> „ein geteiltes Klasse-A-Modul, beide Apps konsumieren es" heben — und die
> reale Divergenz der beiden Implementierungen benennen und auflösen.
>
> **Nicht im Fokus:** die Produkt-Overlays *über* der rohen Liste (Favoriten,
> Hidden, CustomNames, Sortierung, Reconcile) — die bleiben Klasse B pro App und
> docken über die vorhandenen `Get*AppsUseCase` an. Ebenso die Icon-Auflösung
> (Nyx `IconRef`/`:data`, Kolibri separater Pfad) — icon-los ist Vertrag beider
> Modelle und wird hier nicht angefasst.
>
> **Status:** ENTWURF v1.0. Review-Runde 1 ausstehend. Die Enumeration-API
> (`PackageManager` vs. `LauncherApps`) ist bewusst als offener Punkt geführt
> (§9), damit der Rest des Schnitts nicht darauf wartet.
>
> **Verhältnis zum Dach:** dieselbe Grundhaltung wie `MONOREPO_MERGE_SPEC` —
> „Architektur erben, nicht die Produkt-Philosophie". Geteilt wird der *Motor*,
> nicht das Home-Modell (MRG-INV-8). Diese Spec schlägt vor, den Motor in die
> §3.5-Teilmenge aufzunehmen; heute steht er dort nicht.

---

## §0 Warum das hier eine eigene Spec verdient

`MONOREPO_MERGE_SPEC §2` klassifiziert rund um die App-Liste sauber:
`ComponentKey`, `AppUpdateSignal`, `PackageEvent`, `UsageScore/UsageTimestamp`
als Klasse A (schon in `:core`), den `AppLoadResult`-Fehler-Envelope als
Klasse C (kanonisieren). Der **Halter selbst** — der `StateFlow`, der die Liste
über die Prozess-Lebenszeit im RAM hält, samt Empty-Schutz und debounced
Reload — fehlt in der §3.5-Wunschliste. Das ist genau die Einheit, die deine
Absicht („Kolibris battle-tested Code in Nyx wiederverwenden") am direktesten
bedient und heute doppelt existiert.

---

## §1 Ist-Zustand — zwei divergierende Kopien

### Kolibri (der volle Motor, `kolibri/`)
- `domain/repository/InstalledAppsRepository.kt` — `fun getInstalledApps(): Flow<AppLoad>` (heißer Strom) + `suspend fun triggerAppsUpdate()`.
- `domain/repository/InstalledAppsStateRepository.kt` — der kanonische In-RAM-Halter: `val rawAppsFlow: StateFlow<List<AppInfo>>`, `updateApps(...)`, `getCurrentApps()`.
- `data/InstalledAppsRepositoryImpl.kt` — Loader: `reloadTriggers → flatMapLatest → loadAppsFromPackageManager → stateIn(WhileSubscribed)`, `PackageManager.queryIntentActivities`, `debounce` gegen Broadcast-Stürme.
- `data/InstalledAppsStateRepositoryImpl.kt` — `MutableStateFlow` + `@Volatile lastSuccessfulAppList` (Wert-basierter Fallback gegen Empty-Flackern).
- `data/PackageUpdateReceiver.kt` + `data/InstalledAppsRepositoryEntryPoint.kt` — Broadcast → `AppUpdateSignal`.
- `domain/usecase/{ObserveInstalledApps,RefreshApps,GetDrawerApps,GetFavoriteApps,GetRecentApps}UseCase.kt` — Reconcile + Overlays.
- **Tests:** `InstalledAppsStateRepositoryContract` + `Fake*`/`ReactiveFake*`, `InstalledAppsRepositoryImplTest`. `InstalledAppsRepository` selbst trägt „NO CONTRACT TEST (ADR)" (System-API-Wrapper).

### Nyx (die schlanke Kopie, `nyx/`)
- `domain/home/repository/InstalledAppsRepository.kt` — nur `suspend fun loadInstalledApps(): AppLoadResult`. Kein heißer Strom, kein State-Halter.
- `data/home/InstalledAppsRepositoryImpl.kt` — `LauncherApps.getActivityList(null, Process.myUserHandle())`, Pull-on-open.
- Freshness: `LauncherApps.Callback` (pflegt nur Icons + Reconcile, **schiebt nichts in die Drawer-Liste**); Liste wird erst bei nächstem Öffnen neu abgefragt.
- Trägt bereits `NO CONTRACT TEST (ADR)`.

---

## §2 Die realen Divergenzen (das eigentliche Herz)

| Achse | Kolibri | Nyx | Auflösung |
|---|---|---|---|
| **App-Modell** | `AppInfo(originalName, displayName, packageName, className, isFavorite)` — reich, precomputed `key`/`displayNameLower`/`normalizedClassName` | `LauncherApp(key: ComponentKey, label, customName?)` — schlank, icon-los | Geteiltes neutrales `AppInfo` in `:core` **ohne `isFavorite`** (schon in `MONOREPO_MERGE_SPEC §7` entschieden). Nyx' `LauncherApp` wird dessen Projektion/Alias; `displayName = customName ?: label` bleibt als Overlay pro App (§3). |
| **Ladevertrag** | `Flow<AppLoad>` (hot) + State-Halter | `loadInstalledApps(): AppLoadResult` (one-shot) | Kolibris hot-`Flow<AppLoad>` + `InstalledAppsStateRepository` **gewinnen** (der Motor). Nyx gibt Pull-on-open auf. |
| **Ergebnis-Envelope** | `AppLoad.{Loaded(apps), Failed(cause)}` | `AppLoadResult.{Loaded, Error(Reason)}`, `Reason ∈ {ENUMERATION_FAILED, ENUMERATION_EMPTY}` | Ein kanonischer `AppLoad` in `:core` (Klasse C, `MONOREPO_MERGE_SPEC §2`). Empty-Policy → §9. |
| **Empty-Behandlung** | `Loaded(emptyList())` ist legitim | leere Enumeration = `Error(ENUMERATION_EMPTY)` (fail-closed für Reconcile) | Offener Punkt §9: Empty als Wert vs. als Fehler. Vorschlag: Loader bleibt Wert-ehrlich (`Loaded(empty)`), „empty ⇒ verdächtig" ist Policy am Reconcile-Rand pro App, nicht im geteilten Loader. |
| **Freshness** | `PackageUpdateReceiver` → `AppUpdateSignal` (in `:core`) → `RefreshAppsUseCase` → `triggerAppsUpdate` → debounced reload | `LauncherApps.Callback` (nur Icons/Reconcile) | Auf Kolibris Broadcast→`AppUpdateSignal`-Pipeline vereinheitlichen. Der `AppUpdateSignal` liegt bereits geteilt in `:core`. |
| **Enumeration** | `PackageManager.queryIntentActivities` + `loadLabel` | `LauncherApps.getActivityList` (primary-user-aware) | **Offen (§9).** Hinter einen `AppEnumerator`-Port (§3) gelegt, damit die Wahl den Rest des Schnitts nicht blockiert. |
| **CustomName** | via `ObserveInstalledAppsUseCase` (Reconcile gegen CustomNames-Store) auf `displayName` gemappt | direkt im `LauncherApp.customName` gefaltet | Geteilter Halter hält die **rohe** Liste (`rawAppsFlow`); CustomName/Hidden/Favorit sind Overlays pro App (Klasse B) — deckt sich mit Kolibris heutigem `rawAppsFlow`-Design. |

---

## §3 Zielbild + Adaptions-Naht

Ein geteiltes Zwei-Stufen-Subsystem, konsumiert von beiden Apps:

- **`:core` (pure-Kotlin Contracts):** neutrales `AppInfo`, `AppLoad`,
  `InstalledAppsRepository` (`getInstalledApps(): Flow<AppLoad>` + `triggerAppsUpdate()`),
  `InstalledAppsStateRepository` (`rawAppsFlow`/`updateApps`/`getCurrentApps`),
  `ObserveInstalledAppsUseCase`/`RefreshAppsUseCase` in ihrer produktneutralen
  Form, plus der `AppEnumerator`-Port (siehe unten).
- **`:common-data` (Android):** die beiden `*Impl` (Loader-`StateFlow` +
  Zustand-Halter mit `lastSuccessfulAppList`), `PackageUpdateReceiver`,
  Entry-Point. Nutzt `AppUpdateSignal`/`PackageEvent` aus `:core`.

> **Naht — der `AppEnumerator`-Port (MRG-INV-4).** Die eine produktvariante
> Stelle ist *wie* enumeriert wird (PackageManager vs. LauncherApps). Sie kommt
> hinter ein schmales Interface im geteilten Modul:
> ```kotlin
> interface AppEnumerator { suspend fun enumerate(): List<AppInfo> }  // wirft; der Halter fängt → AppLoad
> ```
> Solange §9 offen ist, kann jede App ihren eigenen `AppEnumerator` binden; der
> Motor (Halten, Debounce, Fallback, Reload, Broadcast-Anbindung) ist geteilt.
> Bei Entscheidung in §9 kollabiert das auf **eine** geteilte Impl.

Overlays (Favorit/Hidden/CustomName/Sort/Reconcile) bleiben Klasse B und docken
wie heute über die `Get*AppsUseCase` der jeweiligen App an — der Halter liefert
nur `rawAppsFlow`.

---

## §4 Modulschnitt (konsistent mit `MONOREPO_MERGE_SPEC §4.1`)

- Contracts + Modelle + neutrale UseCases → `:core` (`com.github.reygnn.launcher.core`).
- `*Impl` + Receiver + EntryPoint → `:common-data`.
- Kolibris `InstalledAppsStateRepositoryContract` + `Fake`/`ReactiveFake` →
  `:core` `testFixtures` (die Contract-Triple-Regel, `MONOREPO_MERGE_SPEC §7`,
  gilt im geteilten Modul unverändert).
- `InstalledAppsRepository` behält seinen `NO CONTRACT TEST (ADR)`-Marker
  (System-API-Wrapper) — beide Apps führen ihn heute schon.

---

## §5 Migrationspfad (hängt an `MONOREPO_MERGE_SPEC` Phase 1)

Voraussetzung: neutrales `AppInfo` + `AppLoad` liegen in `:core` (Phase 1 des
Dach-Specs). Jede Stufe hält MRG-INV-2 (beide Apps grün).

1. **`AppLoad` + neutrales `AppInfo` kanonisieren** (Klasse C). Kolibri zeigt
   drauf; Nyx' `AppLoadResult`/`LauncherApp` werden Alias/Projektion. Empty-Policy
   (§9) hier entscheiden.
2. **Halter nach `:common-data`.** Kolibris `InstalledApps(State)RepositoryImpl`
   + Receiver + EntryPoint umziehen, Namespace-Rename. Kolibri konsumiert die
   geteilte Fassung, seine Kopien entfallen.
3. **Nyx andockt.** Nyx bindet einen `AppEnumerator` (zunächst sein
   `LauncherApps`-Pfad), konsumiert `rawAppsFlow` statt Pull-on-open, gibt den
   `LauncherApps.Callback`-Drawer-Refresh zugunsten der Broadcast-Pipeline auf.
   Nyx' `Get*/Reconcile`-UseCases lesen ab jetzt den geteilten Halter.
4. **Enumeration vereinheitlichen** (nachdem §9 entschieden ist): ein geteilter
   `AppEnumerator`, der Port-Doppelweg kollabiert.

---

## §7 Invarianten

> **SIA-INV-1 — Ein Halter, eine Quelle.** Die im RAM residente App-Liste hat
> genau *einen* Ort (`InstalledAppsStateRepository` in `:common-data`), nicht
> eine Kopie pro App. (Spezialfall von MRG-INV-1.)

> **SIA-INV-2 — Ein gefangener Ladefehler bleibt Fehler.** Der Loader kollabiert
> nie zu `Loaded(emptyList())`; er liefert `AppLoad.Failed`. `CancellationException`
> propagiert immer. (Erbt `AppLoad`s Vertrag; deckt sich mit Nyx RHL-INV-1 /
> Kolibri IAL-INV-1.)

> **SIA-INV-3 — Der Halter hält roh.** `rawAppsFlow` trägt die unveränderte
> enumerierte Liste. Favorit/Hidden/CustomName/Sortierung sind Overlays pro App,
> nie im geteilten Modell gespeichert. (Spezialfall von MRG-INV-3; `AppInfo`
> trägt kein `isFavorite`, MRG-INV-9.)

> **SIA-INV-4 — Produktvarianz nur über den Port.** Der Motor kennt nie einen
> App-Namen oder ein Home-Modell; die einzige Varianz ist der injizierte
> `AppEnumerator`. (Spezialfall von MRG-INV-4.)

> **SIA-INV-5 — Kein Empty-Flackern.** `getCurrentApps()` fällt bei transientem
> Leer-Stand auf `lastSuccessfulAppList` zurück. Der Fallback ist Wert-basiert,
> kein zweiter Cache mit eigener Invalidierung.

---

## §8 Nicht im Fokus / Risiken

- **Icons.** Icon-Auflösung bleibt pro App (`IconRef`/`:data`); `AppInfo` ist
  icon-los in beiden Welten — hier keine Änderung.
- **Overlays.** Reconcile/Favoriten/Hidden/CustomNames/Sort sind Klasse B und
  Sache der jeweiligen `*_SPEC` (`HOME_CURATION_SPEC`, `APP_USAGE_SPEC`).
- **Multi-User.** Single-User bleibt (MRG-INV-9); `Process.myUserHandle()` bzw.
  primary-user ist die einzige Semantik. Work-Profiles out-of-scope.
- **Risiko Debounce/`WhileSubscribed`-Timing.** Nyx' bisheriges Pull-on-open
  hatte keine Sharing-Semantik; beim Umstieg auf `stateIn(WhileSubscribed)` die
  Timeout-Konstante gegen Nyx' Drawer-Lebenszyklus prüfen.

---

## §9 Offene Punkte (für Review-Runde 1)

1. **Enumeration-API kanonisch: `PackageManager` oder `LauncherApps`?**
   LauncherApps ist moderner/primary-user-aware und liefert
   `LauncherActivityInfo` direkt; PackageManager ist Kolibris battle-tested
   Pfad. Bis entschieden: `AppEnumerator`-Port, jede App bindet den eigenen.
2. **Empty-Policy.** Empty als legitimer Wert (Kolibri) oder als Fehler
   (Nyx `ENUMERATION_EMPTY`)? Vorschlag: Loader Wert-ehrlich, „empty ⇒
   verdächtig" als Reconcile-Policy pro App — dann braucht der geteilte
   `AppLoad` keinen `Reason`-Enum.
3. **CustomName-Overlay-Ort.** Kolibri mappt im `ObserveInstalledAppsUseCase`;
   Nyx faltet in `LauncherApp`. Bestätigen, dass der geteilte Halter roh bleibt
   (SIA-INV-3) und CustomName ausschließlich Overlay ist.

---

## Review-Log

| Runde | Datum | Reviewer | Ergebnis |
|---|---|---|---|
| v1.0 | `<offen>` | `<offen>` | Erstentwurf: Motor als Klasse-A-Kandidat vorgeschlagen (§3.5-Erweiterung des Dach-Specs); Divergenzen klassifiziert (§2); `AppEnumerator`-Port als Naht; Enumeration-API + Empty-Policy als §9 offen |
| 1 | `<offen>` | `<offen>` | ausstehend |
