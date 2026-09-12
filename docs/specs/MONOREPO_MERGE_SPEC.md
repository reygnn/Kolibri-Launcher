# MONOREPO_MERGE_SPEC — Nyx + Kolibri in einem Gradle-Build, geteilte Infra statt Copy-Paste

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als Architektur-Spec für die
> Zusammenführung von `Nyx-Launcher` und `Kolibri-Launcher` in ein einziges
> Gradle-Projekt (Option **1a** aus der Vorab-Analyse: Monorepo + dünne
> App-Module + geteilte Bibliotheks-Module).
>
> **Fokus:** der *Modulschnitt* und der *Migrationspfad*, auf dem Kolibris
> battle-tested Infrastruktur nach Nyx wandert, ohne komplexen Code zu
> duplizieren. Also: welche Pakete produktneutral sind und in geteilte Module
> gehören, wie die zwei divergierenden Produkt-Domänen nebeneinander leben, wie
> der Namespace vereinheitlicht wird, und in welcher Reihenfolge extrahiert wird,
> ohne dass je eine der beiden Apps rot wird.
>
> **Nicht im Fokus:** die konkrete Portierung *einzelner* Features (jedes bekommt
> bei Bedarf seinen eigenen `*_PORT_SPEC`), die Merge-Mechanik der Git-Historien
> (§8), und jede Produkt-Entscheidung, ob ein Kolibri-Feature in Nyx überhaupt
> *sinnvoll* ist — diese Spec liefert nur die Naht, an der es andocken kann.
>
> **Status:** ENTWURF v1.7. Der Modulschnitt in §4 ist entschieden (ein `:core`,
> `crashreporting` als eigenes Feature ab Start, ein Baseline-/Macrobench-Modul mit
> Flavors); §3.5 pinnt die Teil-Menge (inkl. Info-Elemente + App-Usage); §7 hält die
> Identitäts-Entscheidungen fest; §5 den Namespace-Stamm. Review-Runde 1 ausstehend.
>
> **Verhältnis zum großen Kolibri:** dieselbe Grundhaltung wie das Nyx-
> `docs/specs/README.md` — **„Architektur erben, nicht die Produkt-Philosophie"**.
> Nyx ist ein Grid-/Icon-Launcher, Kolibri ein flacher Favoriten-Launcher; ihre
> Produkt-Domänen sind mit Absicht verschieden. Geteilt wird deshalb *nur* die
> produktneutrale Infra, nicht das Home-Modell.

---

## §0 Warum überhaupt ein Monorepo

Die Ausgangslage macht die Entscheidung fast: beide Projekte sind vom selben
Autor, teilen den identischen 3-Modul-Schnitt (`:app` / `:domain` / `:data`),
laufen beide auf Hilt, haben beide ein **reines Kotlin-JVM `:domain`** (in
Kolibri wurden alle Android-Imports aus `src/main/` entfernt, Logging läuft über
`core/KolibriLog`), und benutzen denselben Version-Catalog-Stil. Nyx ist
strukturell ein Kolibri-Scaffold.

Der teure Teil — der „komplexe Code, den man nicht duplizieren will" — sind
nicht die Features selbst, sondern ihr **gemeinsames Fundament**. Stichprobe
`ui/backup` in Kolibri: das Feature zieht `domain.model`, `domain.usecase`,
`core` (`TimberWrapper`, `AppConstants`, `MainDispatcher`), `ui.base`
(`BaseViewModel`, `UiEvent`), `ui.flow`, `ui.util` mit rein. **97 von 144
`:app`-Dateien** hängen an `core`/`domain`. Feature-für-Feature-Copy-Paste würde
dieses Fundament jedes Mal mitschleppen → Divergenz + doppelte Wartung.

> **MRG-INV-1 — Geteiltes hat genau eine Quelle.** Jede battle-tested Einheit,
> die in beiden Apps läuft, existiert als *ein* Modul mit *einem* Namespace, nicht
> als zwei Kopien. Copy-Paste einer produktneutralen Klasse in beide Apps ist ein
> Konventionsbruch (prüfbar, §7).

---

## §1 Zielbild

- **Ein** Gradle-Build (`settings.gradle.kts`) mit **einem** Version-Catalog.
- Zwei dünne `application`-Module `:app-nyx` und `:app-kolibri`, die je nur ihre
  Produkt-Wiring + produktspezifische UI/Ressourcen tragen.
- Ein Ring produktneutraler Bibliotheks-Module (`:core`, `:common-ui`,
  `:common-data`, plus optionale `:feature-*`), von *beiden* Apps konsumiert.
- Die zwei Produkt-Domänen (`:domain-nyx` = Grid, `:domain-kolibri` = Favoriten)
  bleiben getrennt und teilen nur produktneutrale Contracts über `:core`.
- Kolibris Guardrail-Apparat (`tools/*.awk`, `check-conventions.sh`,
  `baselineprofile`, `macrobenchmark`, `MainDispatcherRule`-Testkonvention) greift
  nach der Migration über *beide* Apps.

> **MRG-INV-2 — Jede Migrationsphase lässt beide Apps grün.** Nach jeder in §6
> nummerierten Phase kompilieren `:app-nyx` und `:app-kolibri`, ihre Unit-Tests
> laufen, und `check-conventions.sh` ist grün. Es gibt keinen „Zwischenstand, den
> man nicht bauen kann".

---

## §2 Die Klassifizierung (das eigentliche Herz der Spec)

Jedes Kolibri-Paket fällt in genau eine von drei Klassen. Nur Klasse **A** wird
geteilt; **B** bleibt pro App; **C** ist schon parallel-konvergiert und braucht
nur Contract-Abgleich.

### Klasse A — produktneutral → geteiltes Modul

Läuft in *jedem* Launcher unabhängig vom Home-Modell. Wandert 1:1 (nur Namespace-
Rename, §5) in ein geteiltes Modul.

- **`domain/core/*`** (17 Dateien): `KolibriLog`, `TimberWrapper`, `AppConstants`,
  `Qualifiers`, `FlowExtensions`, `EnumExtensions`, `CoerceExtensions`,
  `ColorMath`, `ComponentKey`, `PackageEvent`, `AppUpdateSignal`, `UsageScore`,
  `UsageTimestamp`, `TextContent`, `CompositeLuminanceSignal`,
  `SystemWallpaperColorsSignal`, `OwnsSettingsStoreKeys`.
- **`domain/di/DispatcherModule.kt`** — Nyx hat das schon als eigene Kopie
  (`di/DispatcherModule.kt` + `di/Dispatchers.kt`); die wird durch die geteilte
  ersetzt.
- **`app/ui/base/*`**: `BaseActivity`, `BaseViewModel`, `BaseViewModelInterface`,
  `UiEvent`.
- **`app/ui/flow/FlowCollection.kt`**.
- **Produktneutrale `app/ui/util/*`**: `ToastSafe`, `ToastErrorTree`, `ErrorData`,
  `ErrorEventBus`, `Event`, `MonotonicClock`, `StrictModeUtil`, `TestMode`,
  `LaunchTrace`, `GestureThresholds`, `SwipeGestureAnalyzer`, `BottomSheetWindow`,
  `DefaultLauncherHelper`, `FilenameBuilder`, `DialogDrag`, `ViewFade`,
  `ViewTinting`, `TextOutline`, `ThemeColor`, `DomainMessageMappers` (Mapper-
  *Muster*, nicht die produktspezifischen Strings).
- **`crashreporting/*`** über alle drei Module (app 13 / domain 5 / data 2 Dateien):
  ACRA-Anbindung + Consent-Flow. Komplett produktneutral, hoher battle-tested-
  Gewinn, quasi null Kopplung an das Home-Modell → **bester Erst-Kandidat** nach
  `:core`.
- **`data/wallpaper/*` + `app/ui/home/wallpaper*`**: Compositing/Parallel-Decode.
  Produktneutral bis auf die Aufhängung ans Home-Rebuild (Adaptions-Naht, §3).

### Klasse B — produktspezifisch → bleibt pro App

Hängt am jeweiligen Home-Modell (Grid vs. flache Favoriten). **Nicht** 1:1
portierbar; bei Bedarf pro App neu ausmodelliert (eigener `*_SPEC`).

- Kolibri: `ui/favorites`, `ui/customnames`, `ui/hiddenapps`, `ui/swipeactions`,
  `ui/appdrawer` (Kolibri-Sortier-/Split-Semantik), `ui/colorcustomization`,
  `ui/layoutcustomization`, Hashtags — alles gebaut um die *flache Favoritenliste*.
- Kolibri `domain/domain/*`: `model` (30), `repository` (19), `usecase` (54),
  `service` (3) — Kolibris Produkt-Domäne.
- Nyx-Gegenstück: das gesamte `home/*` (Grid, `HomeLayout`, `DropTarget`, Folder,
  Icon-Loader) — Nyx' Produkt-Domäne.

> **MRG-INV-3 — Die Produkt-Domäne wird nie geteilt.** `:domain-nyx` und
> `:domain-kolibri` sind getrennte Module. Ein Typ darf nur dann nach `:core` /
> `:common-domain` wandern, wenn er *keinen* Bezug auf ein konkretes Home-Modell
> (Grid *oder* Favoriten) hat. Das ist die technische Fassung von „Architektur
> erben, nicht die Produkt-Philosophie".

### Klasse C — schon parallel konvergiert → nur Contract-Abgleich

Beide Repos haben unabhängig dieselben Contracts erfunden; hier ist die Arbeit,
die *eine* kanonische Fassung nach `:core`/`:common-domain` zu heben und beide
Seiten drauf zeigen zu lassen.

- **`AppLoadResult`-Fehler-Envelope** („a caught failure stays a failure") — in
  Nyx `home/model/AppLoadResult.kt`, in Kolibri via `INSTALLED_APPS_LOAD_SPEC`.
- **`ComponentKey`** — existiert in beiden (`core/ComponentKey.kt` bzw.
  `home/model/ComponentKey.kt`).
- **Reconcile-fail-closed-Familie** (`R-INV`) — Nyx `RECONCILE_HOME_LAYOUT_SPEC`,
  Kolibri `RECONCILE_SPEC`. Die *Invariante* ist geteilt, die *Implementierung*
  bleibt Klasse B (unterschiedliche Modelle).
- **`ItemIdFactory` / injizierte ID-Erzeugung**, **DataStore-Read-Muster**
  (`DATASTORE_READ_SPEC`), **Debounce** (`DEBOUNCE_SPEC`).

---

## §3 Adaptions-Nähte

Zwei Klasse-A-Subsysteme berühren das Produkt-Modell an genau einer Stelle. Statt
sie zu Klasse B abzustufen, wird die Berührung hinter ein schmales Interface im
geteilten Modul gelegt, das jede App implementiert.

- **Backup / Import-Export.** Das Plumbing (Datei-IO, `FilenameBuilder`,
  `ImportOptions`, `BackupPreview`, `ImportResult`, Dialog-Drag, Fortschritt) ist
  neutral; nur das *Schema* der serialisierten Nutzlast ist produktspezifisch. →
  `:feature-backup` definiert `interface BackupSchema<T>` (serialize/deserialize +
  Versionierung); `:app-nyx` liefert das `HomeLayout`-Schema, `:app-kolibri` das
  Favoriten-Schema. Nyx hat mit `home/usecase/ExportLayoutUseCase` +
  `ImportLayoutUseCase` bereits die passende Andockstelle.
- **Wallpaper-Rebuild.** Compositing/Decode ist neutral; der Trigger „Layout hat
  sich geändert, rebuild Scrim/Composite" hängt am Home-Rebuild. →
  `:feature-wallpaper` exponiert einen `WallpaperRebuildTrigger`-Port; jede App
  feuert ihn aus ihrem eigenen Layout-Flow.

> **MRG-INV-4 — Adaptions-Nähte sind Ports, keine `if (isNyx)`.** Ein
> Klasse-A-Modul kennt nie den Namen einer App oder eines konkreten Home-Modells.
> Produktvarianz kommt ausschließlich über injizierte Interfaces, die im
> App-Modul implementiert werden.

---

## §3.5 Bestätigte Teil-Menge (Wunschliste, Stand v1.1)

Diese Features sollen **definitiv** geteilt werden. Nach Prüfung des realen
Kolibri-Codes hier die belastbare Einordnung — inklusive einer Korrektur (Widgets)
und zweier Aufteilungen (Swipe, Drawer), die in der pauschalen Wunschliste
verborgen waren.

| Wunsch | Was real existiert | Geteilt (Klasse A) | Bleibt pro App (Klasse B) | Naht / Aufwand |
|---|---|---|---|---|
| **Wallpaper** | großes Subsystem: ~13 domain-UseCases + `WallpaperState/Backdrop/SurfaceMode`, `WallpaperRepositoryImpl`, `WallpaperFileManager`, `WallpaperRestorer`, `…LuminanceImpl`, Composite-Cache / `RenderScheduler` / `Flattener` / `MemoryReport`, `WallpaperFab`, `WallpaperDelegate` | Compositing, Parallel-Decode, Luminanz, Datei-IO, Scrim, die UseCases + Modelle | *wo* der Wallpaper im Layout sitzt + der Rebuild-Auslöser | `WallpaperRebuildTrigger`-Port (§3). **Aufwand hoch** — größte Einzel-Extraktion; hängt am Settings-Store *und* am Backup-Schema (s. u.) |
| **ACRA** | `crashreporting/*` über app 13 / domain 5 / data 2 Dateien: ACRA-Anbindung + Consent | **alles** | nur `applicationId` / Report-Metadaten | keine Naht. **Aufwand niedrig → zuerst extrahieren** (größter battle-tested-Gewinn, null Home-Kopplung) |
| **JSON Backup/Restore** | bestätigt JSON: `kotlinx.serialization.json` **+** `org.json`-Strict-Pass (forward-compat *und* validierte manuelle Edits). `BackupSerializer`, `BackupDataAssembler`, `BackupRepositoryImpl`, `Export/Import/PreviewBackupUseCase`, Fragment/VM/State | JSON-Engine + Versionierung, Assembler, Repo-Impl, die drei UseCases, die UI-Hülle, `FilenameBuilder` | **das Schema**: `BackupData`/`LauncherSettings` (favoriteComponents, hiddenComponents, customAppNames, swipeLeft/RightApp, favoritesAlignment, `WallpaperLayerBackup`) — reines Favoriten-Modell, mappt nicht auf `HomeLayout` | `BackupSchema<T>`-Port (§3). **Aufwand mittel.** Bonus: `usageexport` reitet dieselbe Datei-/Serializer-Schiene |
| **Swipe-Erkennung (Home)** | zwei Schichten | `SwipeGestureAnalyzer` + `GestureThresholds` — reine Touch-Delta-Mathematik, **null interne Imports → 1:1** | `ui/swipeactions/*` (Config-Screen) + Bindung „welche App startet Swipe-hoch" via `SwipeSlot` / `GetSwipeActionComponentUseCase` (hängt an `AppInfo`) | Nyx nutzt Analyzer + Slot-Konzept, verdrahtet eigene Komponenten-Auflösung. **Detektion niedrig, Bindung mittel** |
| **App-Drawer (evtl.)** | `ui/appdrawer/*` (6 Dateien) | neutrale Interaktions-Helfer: `AppSearchFilter`, `SearchQueryChangeTracker`, `KeyboardShowCoordinator`, `SwipeDownDismissLayout` | `AppDrawerAdapter`/`Fragment` — an `AppInfo`/`SortOrder`/`FavoritesAlignment`/flache Liste gebunden; Nyx hat eigenes grid-orientiertes `home/drawer` | Helfer teilen (**niedrig**); den Drawer selbst *nicht teilen*, höchstens neu bauen |
| **App-Usage (Tracking/Score/Sort/Export)** | `core/UsageScore` + `UsageTimestamp` (λ-Decay, rein), `AppUsageRepository`(+Impl, DataStore per Package-Name), neutrale Use-Cases (`Check`/`Reset`/`Toggle`/`recentPackages`), `SortOrder`, **kompletter** Usage-Export (`UsageExportRepository`, `UsageImportResult`, Payload `Map<String,List<Long>>`) | fast alles: Scoring, Store, Ranking, Export **end-to-end** (neutrales Payload, kein Produktmodell) — Kolibri nutzt **nicht** `UsageStatsManager`, trackt selbst | drei dünne Adapter-Use-Cases (`RecordAppLaunch`, `GetRecentApps`, `GetDrawerApps`) am App-Modell-Rand + die eine Methode `sortAppsByTimeWeightedUsage` → wird zu neutralem `rankByUsage` | Detail in `APP_USAGE_SPEC`. **Aufwand niedrig–mittel**; Naht = App-Modell ↔ Package-Key (wenige Zeilen pro App) |

> **MRG-INV-8 — Geteilt wird die Mechanik, nicht das Schema.** Für jedes Feature
> dieser Teil-Menge wandert die produktneutrale *Maschinerie* (Serializer,
> Compositor, Gesten-Analyzer, ACRA-Pipeline) in ein Klasse-A-Modul; das
> produktgebundene *Datenmodell* (`BackupData`, Swipe-Slot-Bindung,
> Wallpaper-Sitzplatz im Layout) bleibt in der App und dockt über einen §3-Port an.

> **Terminologie.** „Widgets" meint hier die **eingebauten Info-Elemente**
> (Uhr, Datum, Akku, Kalender-/Event-Indikator), die Kolibri selbst auf den
> Homescreen zeichnet — **nicht** vom `AppWidgetHost` gehostete Fremd-Widgets.
> Letztere existieren in keinem Projekt (kein `AppWidgetManager`, kein
> Manifest-Eintrag); falls sie je gewollt sind, ist das Neuentwicklung und gehört
> in einen eigenen `HOME_WIDGETS_SPEC`, nicht in diese Merge-Spec.

**Empfohlene Extraktions-Reihenfolge für diese Teil-Menge** (verfeinert §6):
ACRA → **Info-Elemente (Uhr/Datum/Akku/Event-Indikator)** → **App-Usage
(Scoring/Store/Export, `APP_USAGE_SPEC`)** → Backup-JSON-Engine
(`BackupSchema<T>`-Port) → Swipe-Analyzer → Wallpaper (zusammen mit dem
Backup-Schema planen, da `WallpaperLayerBackup` Teil der Nutzlast ist) →
Drawer-Helfer. Info-Elemente und App-Usage rangieren gleich hinter ACRA, weil beide
nahezu null Produktkopplung haben; App-Usage bringt zudem den Usage-Export
end-to-end geteilt mit (neutrales Payload).

> **Kopplungs-Warnung Wallpaper ↔ Backup.** Wallpaper-Layer sind Teil des
> Backup-Schemas (`WallpaperLayerBackup` in `BackupData`). Wer Wallpaper teilt,
> muss den Wallpaper-Anteil des `BackupSchema<T>` mitdenken — beide Extraktionen
> zusammen planen, nicht nacheinander blind.

---

## §4 Der Modulschnitt

Zwei Varianten. Starte mit **Minimal** (Rule 10: value bar, not cost bar);
splitte zu **Fein** erst, wenn ein `:feature-*` unabhängig getestet/gebaut werden
soll.

### §4.1 Minimal (empfohlener Start)

```
:core            pure-Kotlin JVM     — domain/core/*, DispatcherModule,
                                        produktneutrale Contracts (Klasse A+C)
:common-ui       android-library+Hilt — ui.base, ui.flow, neutrale ui.util
:common-data     android-library+Hilt — DataStore-Grundgerüst, Serializer-Basis,
                                        crashreporting-data + consent
:domain-nyx      pure-Kotlin JVM     — Nyx home/* (Grid)         [ex :domain]
:domain-kolibri  pure-Kotlin JVM     — Kolibri domain/domain/*   [ex :domain]
:data-nyx        android-library+Hilt
:data-kolibri    android-library+Hilt
:app-nyx         application         — Grid-UI + Ressourcen       [ex :app]
:app-kolibri     application         — Favoriten-UI + Ressourcen  [ex :app]
:baselineprofile / :macrobenchmark   — pro App-Variante parametrisiert
```

Abhängigkeiten (nur nach unten):
`:app-*` → `:common-ui`, `:common-data`, `:domain-*`, `:data-*`, `:core`
`:common-ui` → `:core`   ·   `:common-data` → `:core`
`:data-*` → `:domain-*` → `:core`

### §4.2 Fein (später, bei Bedarf — außer crashreporting)

`:core` bleibt; zusätzlich eigenständig testbare Features als Android-Library:
`:feature-backup`, `:feature-wallpaper`. Jede App dependt auf die, die sie will.

**Ausnahme `:feature-crashreporting`: von Anfang an ein eigenes Modul** (v1.7,
§9-Entscheid), nicht erst „bei Bedarf" — es ist eine kohärente vertikale Scheibe
(~20 Dateien), die beide Apps wortgleich nutzen. Es hängt an `:core` + `:common-ui`;
die App ruft nur den Initializer aus ihrer `@HiltAndroidApp`-Klasse.

> **MRG-INV-5 — Modulgrenzen folgen dem Wert, nicht der Ästhetik.** Ein neues
> `:feature-*`-Modul entsteht nur, wenn (a) es *beide* Apps nutzen oder (b) es
> isoliert schneller baut/testet. Ein Modul „weil sauber" ohne einen dieser Gründe
> wird abgelehnt.

---

## §5 Namespace-Vereinheitlichung

Geteilter Code kann nicht zwei Namespaces (`nyx_launcher` / `kolibri_launcher`)
gleichzeitig tragen. **Entschieden:** der neutrale Stamm ist
`com.github.reygnn.launcher` (v1.6). Regel:

- Geteilte Module ziehen auf diesen Stamm um: `com.github.reygnn.launcher.core`,
  `…launcher.common.ui`, `…launcher.common.data` (bzw. `…launcher.feature.*`).
- App-Module behalten ihren eigenen Namespace + eigene `R`-Klasse:
  `com.github.reygnn.nyx_launcher`, `com.github.reygnn.kolibri_launcher`.
- `applicationId` bleibt **pro App verschieden** (getrennte Store-Einträge, parallel
  installierbar).
- Ressourcen in Library-Modulen (`:common-ui`) mergen in die App → Kollisionsgefahr
  bei Namen. Konvention: geteilte Ressourcen prefixen (`common_…`, `crash_…`).

> **MRG-INV-6 — Ein Symbol, ein Namespace.** Kein Typ existiert unter zwei
> Paketnamen. Der Namespace-Rename ist ein *einmaliges* IDE-Refactoring pro
> extrahiertem Paket, kein wiederkehrender Handgriff.

---

## §6 Migrationspfad

Jede Phase ist für sich mergebar und hält MRG-INV-2 (beide Apps grün).

- **Phase 0 — Monorepo-Skelett.** Ein `settings.gradle.kts`, ein Version-Catalog
  (Union beider `libs.versions.toml`; Nyx 129 Zeilen, Kolibri 228 → unbenutzte
  Aliase schaden nicht). Beide Projekte als Unterbäume einhängen (§8). Bauen:
  beide Apps unverändert unter alten Modulnamen `:app-nyx`/`:app-kolibri` etc.
- **Phase 1 — `:core`.** Kolibris `domain/core/*` + `DispatcherModule` → `:core`,
  neutraler Namespace. Kolibri zeigt drauf. Nyx zeigt drauf und **löscht** seine
  Kopien (`di/Dispatchers.kt`, `di/DispatcherModule.kt`, ggf. `home/model/ComponentKey.kt`
  → auf `:core`-Fassung). Klasse-C-Contracts (`AppLoadResult`) hier kanonisieren.
- **Phase 2 — `:common-ui` + `:common-data`.** `ui.base`, `ui.flow`, neutrale
  `ui.util`; DataStore-Grundgerüst. Nyx' `BaseViewModel`-Äquivalente auf die
  geteilten umstellen.
- **Phase 3 — `:feature-crashreporting`** (eigenes Modul ab Start, §4.2/§9).
  Produktneutral, null Home-Kopplung → hier holt Nyx den größten battle-tested-
  Sprung mit dem kleinsten Risiko. ACRA + Consent-Flow inklusive.
- **Phase 4 — `:feature-backup` / `:feature-wallpaper`** über die Ports aus §3.
  Nyx implementiert `BackupSchema<HomeLayout>` an seinen vorhandenen
  Export/Import-Use-Cases.
- **Phase 5 — Guardrails vereinheitlichen.** `tools/*.awk`, `check-conventions.sh`,
  `baselineprofile`, `macrobenchmark` auf beide Apps ausrichten. Ab hier prüft der
  Konventions-Apparat auch Nyx.

> **MRG-INV-7 — Reihenfolge = Abhängigkeitstiefe, aufsteigend.** Zuerst das, woran
> alles hängt (`:core`), zuletzt die Blätter. Es wird nie ein Feature extrahiert,
> dessen Fundament noch nicht geteilt ist.

---

## §7 Querschnitt-Konventionen (aus beiden Repos geerbt)

- **Dispatcher** — ein Dispatcher via `MainDispatcherRule`, **kein** separater
  `TestScope`/`StandardTestDispatcher`. Gilt unverändert in *allen* geteilten
  Modulen; siehe `TESTING_CONVENTIONS.kt` im Test-Root. Bricht sonst die
  `tools/`-Checks.
- **Hilt-Aggregation** — Libraries deklarieren `@Module`/`@Provides`/`@Binds` und
  nutzen nur `ksp(hilt.compiler)`; das `hilt-android`-Plugin läuft **nur** in den
  `:app-*`-Modulen. (Kolibri macht das in `:domain` bereits genau so.)
- **`:domain-*` bleibt Android-frei** — reines Kotlin-JVM, Logging über
  `KolibriLog` aus `:core`, kein `Context`, kein `R`, kein `@StringRes`.
- **Sealed Ergebnis-Identifier** — Use-Cases geben `…Result` zurück, `:app` mappt
  zu UI-Feedback. Keine Strings in der Domäne.
- **„Nur bei echter Änderung speichern"** — `NoOp`/`Unchanged` ⇒ kein DataStore-
  Write, kein Flow-Tick.
- **Komponenten-Identität = Single-User.** Nyx entfernt sein `userSerial`-Feld
  (nur Platzhalter, nie ein Feature; `NYX_SINGLE_USER_CLEANUP`); beide Launcher
  teilen eine nutzerlose Identität. Work-Profile/geklonte Apps sind explizit
  out-of-scope (Status quo in beiden). Wird es je gewollt, ist es eine bewusste
  Schema-Migration.
- **Geteiltes `AppInfo` lebt neutral in `:core`, ohne `isFavorite`.** Kolibris
  battle-tested `AppInfo` wird übernommen, aber das denormalisierte
  `isFavorite`-Feld (flacher-Favoriten-Leak, heute für `BuildAppContextMenuUseCase`)
  entfällt; „ist auf dem Home" ist ein Pro-App-Overlay (Nyx aus `HomeLayout`,
  Kolibri aus `favoriteComponents`) über den `HomeCuration.isOnHome(key)`-Prädikat.
- **Kanonische Identität ist strukturiert; flach ist nur Projektion.** Der
  geteilte `ComponentKey(packageName, className)` bleibt ein strukturiertes
  `data class` (Nyx' Form); die flache `"package/class"`-Zeichenkette (`key.flat`)
  ist eine *abgeleitete* Form, die nur an der Persistenz-/Backup-Grenze auftritt,
  nie die Identität selbst. `.className` wird **einmal bei der Konstruktion**
  normalisiert (aus dem stets absoluten `LauncherActivityInfo.componentName`);
  Kolibris String-Validator (`isValid`, TODO §15) wandert zur `parse()`-Seite und
  bewacht genau den einen fehleranfälligen Übergang flach → strukturiert.
- **Store-Keying-Regel (pro Concern verschieden granular, bewusst):** Hidden
  persistiert `key.flat` (**Component-granular — verpflichtend**, sonst versteckt ein
  Paket mit mehreren Launcher-Activities alle Geschwister auf einmal; siehe KDoc
  `HiddenAppsRepository`). CustomNames und Usage persistieren `key.packageName`
  (**Package-granular**, gewollt: eine Umbenennung / ein Nutzungs-Score gilt fürs
  ganze Paket). Beides sind Projektionen desselben strukturierten `ComponentKey`.
- **Referenz per nacktem Namen** — `MONOREPO_MERGE_SPEC §4`, nie ein Pfad.
- **Verworfenes → `../history/`**, nicht in diese Spec.

> **MRG-INV-10 — Strukturierte Identität, projizierte Persistenz.** Kein geteilter
> Typ hält Komponenten-Identität als rohen String; die Identität ist immer das
> strukturierte `ComponentKey`. Persistenz und Backup benutzen `key.flat` bzw.
> `key.packageName` als Projektion — die Wahl der Projektion (Component- vs.
> Package-Granularität) gehört zum jeweiligen Store, nicht zur Identität. Wire-Format
> formt nie das Domänenmodell.
> Komponenten-Schlüssel trägt kein `userSerial`, und das geteilte `AppInfo` trägt
> kein `isFavorite` — keine Multi-User-Vorratshaltung und keine Home-Kuration
> sickern in `:core`. Home-Kuration (Favoriten/`HomeLayout`) bleibt pro App
> (MRG-INV-3); „auf dem Home" wird berechnet, nie im App-Modell gespeichert.

---

## §8 Nicht im Fokus / Risiken / akzeptierte Grenzen

- **Git-History-Merge.** `git subtree add` beider Repos in `apps/` → einmalig
  großer, aber nachvollziehbarer Diff. Alternative Submodule wird verworfen (mehr
  laufende Reibung). Details in einem separaten `REPO_MERGE_NOTES` (history).
- **Ressourcen-Kollisionen** zwischen App und Library-Modulen — abgefangen durch
  die Prefix-Konvention (§5), prüfbar per neuem `check-resource-prefix.awk`.
- **Version-Catalog-Union** zieht Kolibris größere Dep-Menge in den Build; für Nyx
  ungenutzte Aliase sind akzeptiert (kein Laufzeit-Impact).
- **Zwei Store-Identitäten** müssen erhalten bleiben (getrennte `applicationId`,
  getrennte Signing-Configs).
- **`values-de` / Strings** sind app-lokal; geteilte UI-Module bringen entweder
  eigene (geprefixte) Strings mit oder erhalten sie per Ressourcen-Referenz aus der
  App.
- Diese Spec entscheidet **nicht**, *welche* Klasse-B-Features Nyx bekommen soll —
  nur, dass sie über Klasse-A-Fundament + §3-Ports andocken können.
- **Eingebaute Info-Elemente** (Uhr/Datum/Akku/Event-Indikator) sind *in Scope*
  und Klasse A (§3.5). Nur vom `AppWidgetHost` gehostete **Fremd-Widgets** sind
  ausgeklammert — die existieren nirgends und wären Neuentwicklung
  (eigener `HOME_WIDGETS_SPEC`).

---

## §9 Offene Punkte (für Review-Runde 1)

Die strukturellen Punkte sind entschieden (v1.7); verbliebene Detailfragen leben in
den jeweiligen Detail-Specs (`APP_USAGE_SPEC §10`, `BACKUP_SCHEMA_PORT_SPEC §11`,
`WALLPAPER_RESTORE_SPEC §12`, `HOME_INFO_ELEMENTS_SPEC §10`, `HOME_CURATION_SPEC §10`).

Entschieden:
- **Ein `:core`, kein separates `:common-domain`.** Der Contracts-vs-Utilities-Split
  wäre bei eurer Größe reine Ästhetik (MRG-INV-5); erst splitten, wenn `:core`
  spürbar wuchert oder eine Abhängigkeitsrichtung verletzt würde.
- **`crashreporting` ist von Anfang an ein eigenes `:feature-crashreporting`** (nicht
  in `:common-*` verschmiert) — es ist eine kohärente vertikale Scheibe von ~20
  Dateien (app 13 / domain 5 / data 2), die beide Apps wortgleich nutzen: das
  Lehrbuch-Profil eines Feature-Moduls (§4.2).
- **Ein `:baselineprofile` + ein `:macrobenchmark` mit Per-App-Flavors**, nicht zwei
  Kopien: das Journey-Harness ist geteilt, nur das generierte Profil ist per App;
  die UI-Selektoren (Grid vs. Liste) kommen aus einem kleinen Per-App-Selektor-Satz.
  Divergieren die Journeys je stark, Fallback: zwei dünne Test-Module über einer
  gemeinsamen Journey-Library.

---

## Review-Log

| Runde | Datum | Reviewer | Ergebnis |
|---|---|---|---|
| v1.7 | `<offen>` | `<offen>` | Struktur-Punkte entschieden: ein `:core` (kein `:common-domain`); `crashreporting` eigenes `:feature`-Modul ab Start (§4.2, Phase 3); ein `:baselineprofile`/`:macrobenchmark` mit Per-App-Flavors; §9 aufgelöst. `HOME_CURATION_SPEC` als Folge-Spec (Lese-Port + Schreib-Callback) angelegt |
| v1.6 | `<offen>` | `<offen>` | Neutraler Namespace-Stamm bestätigt: `com.github.reygnn.launcher` (§5); §9-Punkt aufgelöst |
| v1.5 | `<offen>` | `<offen>` | ComponentKey-Format entschieden: strukturiert kanonisch, flach nur Projektion (MRG-INV-10, §7); Store-Keying-Regel festgeschrieben (Hidden=`key.flat` component-granular per KDoc, CustomNames/Usage=`key.packageName`); §9-Punkt aufgelöst |
| v1.4 | `<offen>` | `<offen>` | Single-User entschieden: Nyx entfernt `userSerial` (`NYX_SINGLE_USER_CLEANUP`), geteiltes `AppInfo` neutral in `:core` ohne `isFavorite` (MRG-INV-9, §7); `MULTI_USER_IDENTITY_SPEC` verworfen; ComponentKey-Format als §9-Punkt |
| v1.3 | `<offen>` | `<offen>` | App-Usage als geteilter Kern aufgenommen (§3.5-Zeile + Reihenfolge); Detail in `APP_USAGE_SPEC`. Fast alles neutral (Store per Package-Name, Scoring rein, Export end-to-end); nur 3 dünne App-Modell-Adapter |
| v1.2 | `<offen>` | `<offen>` | „Widgets" geklärt = eingebaute Info-Elemente (Uhr/Datum/Akku/Event-Indikator); als Klasse A eingetragen (ClockDelegate null Produktkopplung), Extraktion gleich hinter ACRA; AppWidget-Host bleibt out-of-scope |
| v1.1 | `<offen>` | `<offen>` | §3.5 ergänzt: Wunschliste klassifiziert; Widgets als nicht existent korrigiert (→ `HOME_WIDGETS_SPEC`); Swipe in Detektion/Bindung + Drawer in Helfer/Adapter aufgeteilt |
| 1 | `<offen>` | `<offen>` | ausstehend |
