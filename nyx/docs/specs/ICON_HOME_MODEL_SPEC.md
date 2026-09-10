# ICON_HOME_MODEL_SPEC — Domänenmodell & Modulschnitt für den Icon-Launcher (grüne Wiese, noch kein Code)

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als Schwester-Projekt zum
> großen Kolibri (`Kolibri-Launcher`). Ziel-Ablage: `docs/specs/ICON_HOME_MODEL_SPEC.md`
> im neuen Repo; hier als eigenständiges Blatt geführt.
>
> **Fokus:** das **Domänenmodell** eines icon-basierten Launchers und der
> **Modulschnitt**, der sich vom großen Kolibri ableitet — `ComponentKey`,
> `HomeLayout`-Graph (paged Grid + Dock), `IconRef`, die neuen Repository-Interfaces
> und ihr Contract-Triple, plus die eine Infrastruktur-Entscheidung mit Domänen-Sog:
> **wo Icons (Pixel) leben dürfen und wo nicht.**
>
> **Nicht im Fokus (bewusst, siehe §9):** Drag&Drop-Gesten-Layer, Widget-Host
> (`AppWidgetHost`), Icon-Packs (`appfilter.xml`), Folder-Compositing-Rendering,
> Onboarding/Default-Launcher-Rolle. Diese sind referenziert, aber nicht ausmodelliert
> — das hier ist der v1-Sketch, kein Bauplan-für-alles.
>
> **Status:** ENTWURF v3. §7 ratifiziert; App-Unizität als **IHM-INV-7** ratifiziert
> (Runde 2); Produktname **Nyx Launcher** (`com.github.reygnn.nyx_launcher`). Offen
> bleibt ein §10-Punkt (Grid-Konfig) plus die noch nicht als Modell-Invariante
> gehobene Dock-Kapazität.
>
> **Verhältnis zum großen Kolibri:** *Wir erben Architektur & Konventionen, nicht die
> Produkt-Philosophie.* Der große Kolibri ist explizit *anti-Grid, anti-Widget*
> („No widgets. No icon grid."). Dieser Launcher widerspricht dieser Haltung —
> übernommen werden der 3-Modul-Chain (`:app → :data → :domain`), Hilt, MVVM/Clean,
> Rule 1 (Repository-Interface immer), Rule 2 (Contract-Triple), Rule 5
> (DataStore-only), Rule 10 (JVM-first), Rule 11 (kein Catch um can't-throw), die
> Custom-Names/Hidden-Apps/Backup-Features und die komplette Crash-/ACRA-Infra. Die
> Feature-Entscheidungen (Grid, Folder, Dock, später Widgets) sind neu und diesem
> Produkt eigen.

---

## §0 Ausgangslage & der eine harte Knoten

### §0.1 Was aus den zwei Text-Launchern trägt

Beide bestehenden Launcher sind **Listen**-Launcher. Ihre „Favoriten" sind eine
*flache, geordnete Liste* (`List<AppInfo>` mit Index-Reihenfolge). Fast unverändert
übernehmbar:

| Baustein | Herkunft | Anpassung |
|---|---|---|
| App-Enumeration | `InstalledAppsRepository` (großer Kolibri) | keine — `LauncherApps`/`PackageManager` MAIN/LAUNCHER-Query bleibt |
| App-Drawer + Suche | `GetDrawerAppsUseCase` + Debounce | keine — Drawer ist auch im Icon-Launcher eine Liste |
| Hidden Apps | `HiddenAppsRepository` | keine |
| Custom Names | `CustomNamesRepository` | keine — `LauncherApp.customName` |
| Backup/Restore | `BackupRepository` (ZIP/SAF) | erweitern um `home_layout`-Sektion (§6) |
| Adaptives Theming | Wallpaper-Luminanz-Klassifikator | keine — Icon-Beschriftung folgt derselben Foreground-Farbe |
| Crash/ACRA/ANR | komplette Infra | keine |

### §0.2 Der harte Knoten — Pixel vs. reine Domäne

`:domain` ist **reines Kotlin-JVM** (`kotlin("jvm")`, kein Android-SDK auf dem
Compile-Classpath — vgl. großer Kolibri, CLAUDE.md §Architektur). `Drawable`,
`Bitmap`, `AdaptiveIconDrawable`, `LauncherApps` sind **Android**. Also:

> **IHM-INV-1 — Die Domäne kennt die Pixel nie.** `:domain` modelliert ausschließlich
> die *Referenz* auf ein Icon (`ComponentKey` / `IconRef`), niemals das gerenderte
> Bild. Rasterisieren, Compositing und Cachen sind Infrastruktur in `:data`. Kein
> `Bitmap`/`Drawable`/`LauncherApps`-Import kreuzt jemals die `:domain`-Grenze.

Das ist exakt dieselbe Disziplin, mit der der große Kolibri seine Domain-Modelle
Parcelable-frei und `@StringRes`-frei hält (sealed Identifier statt `Int`, Mapper in
`:app`; CLAUDE.md „Use-case messages are sealed identifiers"). Der Icon-Cache ist
damit **bewusst kein** Domain-Repository (Detail §5).

### §0.3 Der eigentliche Modell-Bruch

Der Icon-Launcher ersetzt die flache Favoriten-Liste durch einen **2D-positionierten,
paginierten Graphen** mit heterogenen Knoten (App / Folder / später Shortcut /
Widget) über mehrere Home-Pages plus ein seitenloses Dock. Das ist die neue
Repository-Ebene, die es in keinem der zwei Text-Launcher gibt.

---

## §1 Zielbild (These)

Ein icon-basierter Home-Screen als **`HomeLayout`-Graph**, der:

1. positionsstabil ist (Item-IDs unabhängig von der Zelle — Umsortieren ändert
   Positionen, nicht Identitäten),
2. als **ein** versioniertes JSON-Blob in DataStore lebt (Rule 5 gewahrt, §7-E1),
3. annotationsfrei in `:domain` steht; `@Serializable`-DTOs + Mapper leben in `:data`
   (spiegelt `DomainMessageMappers`),
4. über einen **hand-rolled, speichergebundenen LRU-Icon-Cache** in `:data` gerendert
   wird (Nutzer-Entscheid: Hand-Roll-Ethos, kein Coil/Glide — konsistent mit eigener
   ANR-Erkennung + self-hosted ACRA).

---

## §2 Domänenmodell — `:domain` (Android-frei)

### §2.1 Identität & App

```kotlin
// Stabile Identität einer startbaren Activity. Übersteht DataStore-Roundtrips
// und Neuinstallationen des Zielpakets (Position bleibt, Icon wird neu aufgelöst).
data class ComponentKey(
    val packageName: String,
    val className: String,
    val userSerial: Long,   // 0 = primärer User. Feld existiert ab v1, damit
)                           // Work-Profile/cloned später NICHT die Serialisierung
                            // bricht — ohne UserHandle (Android) in die Domäne zu ziehen.

// Was die Domäne über eine App weiß — KEIN Icon hier (IHM-INV-1).
data class LauncherApp(
    val key: ComponentKey,
    val label: String,          // System-Label (PackageManager)
    val customName: String?,    // aus CustomNamesRepository; UI zeigt customName ?: label
)
```

### §2.2 Icon-Referenz (nie die Bitmap)

```kotlin
sealed interface IconRef {
    // Systemauflösung über LauncherApps.getActivityIcon(density).
    data class System(val key: ComponentKey) : IconRef

    // v2 (§9): Mapping aus einem installierten Icon-Pack via appfilter.xml.
    data class Pack(val key: ComponentKey, val packId: String) : IconRef

    // Folder-Icons sind ABGELEITET (Komposit aus Mitglieder-Icons) und werden
    // NICHT persistiert — kein IconRef-Fall, sondern Render-Zeit-Berechnung in :data.
}
```

> **IHM-INV-2 — Folder-Icons sind abgeleitet, nie gespeichert.** Ändert sich die
> Mitgliedschaft, ändert sich das Icon ohne Migration.

### §2.3 Grid-Geometrie & Items

```kotlin
data class GridSpec(val columns: Int, val rows: Int)   // z.B. 4×6; Nutzer-konfigurierbar
data class CellPos(val page: Int, val x: Int, val y: Int)
data class Span(val w: Int, val h: Int)                // v1: immer (1,1) außer Widgets (§9)

@JvmInline value class ItemId(val raw: String)         // positionsunabhängig, stabil

sealed interface HomeItem {
    val id: ItemId
    data class App(override val id: ItemId, val key: ComponentKey) : HomeItem
    data class Folder(
        override val id: ItemId,
        val title: String,
        val members: List<ComponentKey>,               // Reihenfolge = Anzeigereihenfolge
    ) : HomeItem
    // Shortcut / Widget → §9 (v2)
}

data class PlacedItem(
    val item: HomeItem,
    val pos: CellPos,
    val span: Span = Span(1, 1),
)
```

### §2.4 Das Aggregat

```kotlin
data class HomeLayout(
    val grid: GridSpec,
    val pages: Int,
    val items: List<PlacedItem>,   // Home-Grid, über pages verteilt
    val dock: List<HomeItem>,      // Hotseat: eine Reihe, seitenlos, kein CellPos
)
```

> **IHM-INV-3 — Keine Kollision, kein Off-Grid.** In `items` besetzt keine belegte
> Zelle (`page,x,y` bis `+span`) eine zweite; alle Zellen liegen in
> `[0,pages) × [0,columns) × [0,rows)`. Erzwungen im Use-Case (`MoveItemUseCase`,
> `PlaceItemUseCase`), nicht im Datentyp — Verletzung ist ein Programmierfehler
> (Rule 11: nicht wegfangen, `silentError` lässt es im DEBUG laut werden).

> **IHM-INV-4 — Item-IDs sind global eindeutig und stabil.** Ein `ItemId` erscheint
> höchstens einmal über `items` ∪ `dock`. Umsortieren mutiert `pos`, nie `id`.

> **IHM-INV-7 — App-Unizität** *(ratifiziert Runde 2)*. Jede `ComponentKey` erscheint
> **höchstens einmal** über `items` ∪ alle Folder-`members` ∪ `dock`. Eine App ist also
> nie zugleich top-level und in einem Folder (oder in zwei Foldern). Folgen: die
> Transitionen (`MIU-*`, `RFF-*`) **verschieben** eine App, duplizieren sie nie; ein
> „Add" einer bereits enthaltenen App ist damit **unerreichbar** und wäre ein
> Programmierfehler (`silentError`, Rule 11) — **kein** `NoOp`, **kein** `Rejected`.
> **Etabliert** wird die Invariante am Rand (Import/Reconcile: fail-closed dedupe nach
> `RECONCILE_HOME_LAYOUT_SPEC` RHL-INV-4, Präzedenz Dock > Grid > Folder), **erhalten**
> von jeder Transition.

---

## §3 Use-Cases (Auswahl, `:domain/usecase/`)

Fein granuliert wie im großen Kolibri (~55 Use-Cases dort). Kern-Set v1:

| Use-Case | Zweck | Invariante |
|---|---|---|
| `ObserveHomeLayoutUseCase` | cold `Flow<HomeLayout>` fürs ViewModel | — |
| `PlaceItemUseCase` | App aus Drawer setzen (unizitätssicher) | `HOME_EDIT_USECASES_SPEC`, HEU-INV-1 |
| `MoveItemUseCase` | Item verschieben; **Drop auf App ⇒ Folder erzeugen** | IHM-INV-2/3/4 |
| `RemoveItemUseCase` | Item vom Home nehmen (App bleibt im Drawer) | `HOME_EDIT_USECASES_SPEC`, HEU-INV-2 |
| `RenameFolderUseCase` | Folder-Titel setzen | `HOME_EDIT_USECASES_SPEC` |
| `ReconcileHomeLayoutUseCase` | deinstallierte Pakete prunen + Unizität herstellen | `RECONCILE_HOME_LAYOUT_SPEC`, fail-closed |

> **`MoveItemUseCase` ist der spannende** — voll ausmodelliert in `MOVE_ITEM_SPEC`
> (Drop-Matrix, reine Transition, `MIU-INV-*`). Drop-Semantik: leere Zelle → Move; Zelle
> mit App → Folder aus beiden; Zelle mit Folder → App in Folder aufnehmen. Reine
> Zustandslogik, voll JVM-testbar (§7-E2).

> **Reconcile fail-closed** (übernommen aus `RECONCILE_SPEC`, R-INV-Familie): Liefert
> die App-Enumeration transient leer, wird das Layout **nicht** geprunt. Ein
> leeres/fehlerhaftes Enumerations-Ergebnis darf nie einen Home-Screen leeren.

---

## §4 Repositories & Contract-Triple (Rule 1 + Rule 2)

```kotlin
// :domain/repository — cold Flow, konsistent mit DATASTORE_READ_SPEC (Belang A:
// jeder DataStore-Repo ist ein cold flow, kein externalScope/shareIn-Param mehr).
interface HomeLayoutRepository {
    fun layout(): Flow<HomeLayout>
    suspend fun save(layout: HomeLayout)
}
```

Volles Triple pro neuem Interface (Rule 2):

- `HomeLayoutRepositoryContract` (abstrakt, in `domain/src/testFixtures/`)
- `FakeHomeLayoutRepositoryContractTest` (Fake, `:domain/test`)
- `HomeLayoutRepositoryImplContractTest` (Impl, `:data/test`)

> **IHM-INV-5 — Fake und Impl teilen den Contract.** Driften sie, fängt der
> Contract es zu JVM-Geschwindigkeit (im großen Kolibri hat genau dieses Muster drei
> reale Fake-vs-Impl-Bugs gefangen).

Bestehende Interfaces (`InstalledAppsRepository`, `HiddenAppsRepository`,
`CustomNamesRepository`, `BackupRepository`) werden 1:1 mitgebracht, ihre Triples
existieren bereits.

---

## §5 Icon-Cache — Infrastruktur, `:data` (Hand-Roll, Nutzer-Entscheid)

> Voll ausmodelliert in `ICON_LOADER_SPEC` (Drei-Schichten-Split, `ICL-INV-*`,
> Test-Inventar). Hier nur die Modell-relevante Kurzfassung.

Fasst `Bitmap`/`Drawable`/`LauncherApps` an → **niemals** `:domain` (IHM-INV-1).
Interface in `:data`, konsumiert von `:app`-Adaptern.

```kotlin
interface IconLoader {
    suspend fun bitmap(ref: IconRef, sizePx: Int): Bitmap   // cache-backed
    fun evict(pkg: String)                                  // an PackageUpdateReceiver
    fun trim(level: Int)                                    // onTrimMemory
}
```

Zweistufig, hand-rolled:

1. **In-Memory-LRU über Byte-Budget** — nicht über Item-Anzahl. Gewicht =
   `Bitmap.allocationByteCount`; Budget aus `ActivityManager.memoryClass` abgeleitet.
2. **Optionaler Disk-Cache** unter `cacheDir` für fertig rasterisierte Adaptive-Icons
   (FG/BG-Komposit ± Themed-Mask), damit der teure Composite-Pass nicht bei jedem
   Kaltstart läuft.
3. **Pipeline:** `ComponentKey` → `LauncherApps.resolveActivity` → `getIcon(density)`
   → falls `AdaptiveIconDrawable`: Layer kompositieren → auf Ziel-px rasterisieren →
   Cache. Läuft auf `@IoDispatcher`/`@DefaultDispatcher` (Qualifier existieren in
   `:domain/di/DispatcherModule`), **nie** auf Main.
4. **Stale-Binding-Guard:** RecyclerView-Row setzt Placeholder, holt async, swappt nur
   bei passender Generation/Position — dieselbe Stale-Read-/Adapter-Null-Out-Paranoia,
   die der große Kolibri per `checkConventions` erzwingt.

> **IHM-INV-6 — Cache-Kohärenz an Paket-Events.** `PackageUpdateReceiver`
> (mitgebracht aus `:data`) triggert `evict(pkg)` bei replace/remove; ein
> aktualisiertes Paket zeigt nie ein veraltetes Icon.

---

## §6 Backup/Restore-Delta

`BackupRepository` (ZIP/SAF, mitgebracht) bekommt eine Sektion `home_layout`:

- Serialisiert dasselbe versionierte Blob wie DataStore (§7-E1).
- Per-Section-Import respektiert (großer Kolibri kann „nur Favoriten" / „nur Themes"
  importieren) → hier „nur Home-Layout".
- Legacy-Import ohne die Sektion → Default-Layout (leeres Grid, Dock leer).

---

## §7 Entscheidungen (getroffen — Review-Runde 1)

### §7-E1 — Persistenz: ein JSON-Blob, kein Room

**Spannung:** Rule 5 sagt DataStore-Preferences-only; `HomeLayout` ist aber ein
Graph, Preferences ist Key-Value.

**Vorschlag:** `HomeLayout` zu **einem** JSON-String (`kotlinx.serialization`, im
Katalog `= "1.11.0"`) serialisieren, unter Key `home_layout_v1` ablegen.

- Kein Room → konsistent mit Rule 5 und dem Backup-Ansatz (alles ist Preferences +
  SAF-ZIP).
- **Schema-Version im Key** (`_v1`) + `schemaVersion`-Feld im Blob → Migration ohne
  DB-Migrations-Apparat.
- **Domäne bleibt annotationsfrei:** `@Serializable`-DTOs (`HomeLayoutDto`, …) +
  Mapper leben in `:data`, nicht auf den `:domain`-Klassen. Spiegelt exakt
  `DomainMessageMappers`.

> **Sub-Frage entschieden (Runde 1):** DTO-Schicht in `:data`. `@Serializable` auf
> den Domain-Klassen ist verworfen — es zöge `kotlinx.serialization`-Annotationen in
> die reine Domäne und bräche IHM-INV-1s Geist (Domäne bleibt Framework-frei, wie
> Parcelable-frei/`@StringRes`-frei im großen Kolibri). Die Boilerplate der Mapper
> ist der bewusst gezahlte Preis, exakt wie bei `DomainMessageMappers`.

### §7-E2 — Test-Split (Rule 10, value bar not cost bar)

**Vorschlag:**

| Ebene | Was | Wohin |
|---|---|---|
| JVM (default) | `MoveItemUseCase`-Semantik, IHM-INV-3/4-Guards, LRU-Eviction-Policy, Byte-Budget-Rechnung, `ComponentKey`→Dateiname, Generation-Guard, Blob-Serialisierung roundtrip | `:domain/test`, `:data/test`, `:app/test` |
| Robolectric | Adaptive-Icon-Compositing, Rasterisierung, Disk-Roundtrip (`android.graphics`) | `:app/testDebug` bzw. `:data/test` |
| `androidTest` | **Drag&Drop** (Touch-Priorität existiert nur auf echtem Device), reale `RecyclerView`/Grid-Measure-Pass, `LauncherApps`-Permission-Gate | `:app/androidTest` |

> **Redundanz-Guardrail (Rule 10):** Reine In-Memory-Policy, die schon JVM-gepinnt
> ist, bekommt **kein** zweites instrumentiertes Test. Die Frage ist „braucht das ein
> Device, um wahr zu sein?" — Drag&Drop ja, LRU-Rechnung nein.

---

## §8 Modul-/DI-Deltas gegenüber dem großen Kolibri

- `:domain` — neue Modelle (§2), Use-Cases (§3), `HomeLayoutRepository`-Interface.
  Keine neue Abhängigkeit.
- `:data` — `HomeLayoutRepositoryImpl` (DataStore-Blob), `IconLoader`-Impl +
  In-Memory-LRU + Disk-Cache, `home_layout`-Backup-Sektion. `RepositoryModule`
  `@Binds` für `HomeLayoutRepository`; `AppModule` `@Provides` für `IconLoader` +
  `LauncherApps`.
- `:app` — neue Feature-Pakete `ui/homegrid/` (Grid-Host + Adapter), `ui/folder/`,
  `ui/dock/`. Drawer/Settings/Hidden/CustomNames/Backup wandern aus dem großen
  Kolibri.
- **Stack-Lücke geschlossen bewusst NICHT durch ein Lib:** kein Coil/Glide im
  Katalog → der `IconLoader` ist selbstgebaut (§5, Nutzer-Entscheid).

---

## §9 Außerhalb des Scopes (bewusst, v2+)

- **Drag&Drop-Gesten-Layer** — Modell (`MoveItemUseCase`) ist v1, die *Geste* nicht.
- **Widgets** (`AppWidgetHost`, `Span > (1,1)`) — schwer; `Span` ist im Modell schon
  vorgesehen, damit v2 nicht bricht.
- **Icon-Packs** (`IconRef.Pack`, `appfilter.xml`-Parsing) — Fall existiert im
  `sealed interface`, Parser nicht.
- **Folder-Compositing-Rendering** — IHM-INV-2 legt die Semantik fest, der Renderer
  ist v2.
- **Default-Launcher-Rolle / Onboarding** — 1:1 aus dem großen Kolibri, hier nicht
  wiederholt.

---

## §10 Offene Punkte für Review-Runde 1

1. `GridSpec` fix vs. nutzer-konfigurierbar in v1 (Modell trägt beides; Frage ist die
   Settings-UI).
2. Produkt-/Paketname — **entschieden: „Nyx Launcher", `com.github.reygnn.nyx_launcher`**
   (Runde 3). Betrifft nur den Package-Root, nicht das Modell.
3. Braucht das Dock eigene Invarianten (max. Länge = `columns`?) oder reicht „eine
   Reihe, seitenlos"? — *vorläufig beantwortet in `MOVE_ITEM_SPEC` §7-D3 (Kapazität =
   `columns`); hier noch nicht als Modell-Invariante ratifiziert.*

*(Erledigt Runde 2: App-Unizität → ratifiziert als IHM-INV-7, §2.4.)*

---

## Review-Log

- **v1 (ENTWURF)** — Erst-Sketch. Scope-Entscheid des Maintainers: „nur Domänenmodell/
  Architektur skizzieren", Icon-Strategie „selbstgebauter LRU-Cache". §7-E1 und §7-E2
  als Vorschläge eingetragen, nicht ratifiziert.
- **v2 (Review-Runde 1)** — §7 ratifiziert: §7-E1 (JSON-Blob) inkl. Sub-Frage
  (DTO-Schicht in `:data`, `@Serializable`-auf-Domain verworfen) und §7-E2
  (Test-Split) sind Entscheidungen. §10-Punkt 1 damit erledigt, Liste auf drei
  offene Punkte gekürzt.
- **v3 (Review-Runde 2)** — App-Unizität als **IHM-INV-7** ratifiziert (§2.4). Klärt
  zugleich `MOVE_ITEM_SPEC` `AddedToFolder` und `REMOVE_FROM_FOLDER_SPEC` §6:
  Duplikat-Add ist unerreichbar → `silentError`, nicht `NoOp`. §10-Punkt (Unizität)
  geschlossen.
