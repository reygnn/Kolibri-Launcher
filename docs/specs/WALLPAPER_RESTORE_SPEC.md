# WALLPAPER_RESTORE_SPEC — Wallpaper-Persistenz & die Blob-Gegenseite des Backups

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als Gegenstück zur Blob-Naht
> aus `BACKUP_SCHEMA_PORT_SPEC §3.3/§5`. Blockiert dessen Migrations-Phase C.
> Konsumiert die Port-Doktrin „geteilt wird die Mechanik, nicht das Schema"
> (MRG-INV-8) und den Zuständigkeits-Schnitt aus `BACKUP_SCHEMA_PORT_SPEC §5`.
>
> **Fokus:** die produktneutrale **Wallpaper-Persistenz-Oberfläche** (State-Modell,
> `WallpaperRepository`, `WallpaperFileManager`) in die geteilten Module heben, und
> die **wallpaper-spezifische Implementierung** der generischen Blob-Naht liefern:
> `collectBlobs` (Export → ZIP-Bilder), `rebindBlobs` + `BlobRestorer` (Import →
> interner Speicher), das Zusammensetzen des `WallpaperState` und das
> verlustfrei-per-Layer-Restore. Plus Legacy-Single-Layer-Kompat und der
> Datei-Lebenszyklus (Orphan-GC).
>
> **Nicht im Fokus:** das *Rendering/Compositing* (Composite-Cache, RenderScheduler,
> Flattener, Parallel-Decode, Luminanz, Scrim) — das ist im großen Kolibri bereits
> als `WALLPAPER_COMPOSITE_LIFECYCLE_SPEC`, `WALLPAPER_PARALLEL_DECODE_SPEC`,
> `WALLPAPER_RENDER_RES_SPEC`, `WALLPAPER_SCRIM_USER_SETTING_SPEC` ausmodelliert; sie
> werden hier nur referenziert. Ihr Hochziehen in geteilte Module ist
> `MONOREPO_MERGE_SPEC`-Sache (Klasse A, eigener Extraktionsschritt). Ebenfalls
> ausgeklammert: der Bild-Picker + Storage-Permission-UX (app-lokal).
>
> **Status:** ENTWURF v1.1. Die Signaturen in §3–§5 sind Vorschläge auf Basis des
> realen Kolibri-Codes; Review-Runde 1 ausstehend.
>
> **Verhältnis zum großen Kolibri:** Kolibris `WallpaperRestorer` ist heute an
> `LauncherSettings` getippt (`restoreFromBackup(settings): Int`) — die einzige echte
> Kopplung. State-Modell, Repository und File-Manager sind bereits produktneutral;
> dieser Spec entkoppelt nur den Restorer vom Produkt-Schema und hebt die
> neutrale Oberfläche hoch.

---

## §0 Bestandsaufnahme (was real existiert)

| Rolle | Datei | Android? | Kopplung |
|---|---|---|---|
| State-Modell | `domain/model/WallpaperState.kt` (`WallpaperState`, `WallpaperLayerState`) | **nein** | produktneutral |
| Repository-Port | `domain/repository/WallpaperRepository.kt` (`: Purgeable`) | nein | produktneutral |
| Datei-Manager | `data/WallpaperFileManager.kt` | ja (`Uri`, Streams, interner Speicher) | produktneutral |
| Restorer (Callback) | `data/WallpaperRestorer.kt` (`restoreFromBackup(settings): Int`) | — | **an `LauncherSettings` getippt** |
| Restore-Logik | `data/BackupRepositoryImpl.kt` (`restoreWallpaperFromBackup`, `importSingleLayerWallpaper`) | ja | Orchestrierung |
| Backup-Teilschema | `domain/model/BackupData.kt` → `WallpaperLayerBackup` + flache Single-Layer-Felder | nein | produktneutral (in `LauncherSettings` eingebettet) |

Beobachtete Fakten, die den Schnitt bestimmen:

- **State (immutable):** `WallpaperState { layers: List<WallpaperLayerState> }` mit
  `NONE` / `multiLayer(layers)` / `single(uri, scale, tx, ty)`; `WallpaperLayerState`
  trägt `id` (atomarer Counter-Suffix via `newId()`, kollisionsfrei bei
  Millisekunden-Batch-Restore), `imageUri`, `scale`, `translateX/Y`,
  `captureSampleSize`; abgeleitet `hasImage`, `isTransformed`, `referencedUris`.
- **Repository:** `wallpaperState: Flow<WallpaperState>`, `saveWallpaperState`,
  `clearWallpaper`, `getWallpaperStateSync`. Erbt `Purgeable`.
- **File-Manager:** `copyToInternal(Uri): Uri?` (auf `Dispatchers.IO`),
  `copyFromInputStream`, `isInternalUri`, `fileExists`, `deleteFile`, `clearAll`,
  `gcOrphans(referencedUris, minAgeMillis = 60_000L)` — Orphan-GC mit Min-Age-Guard
  gegen in-flight-Kopien.
- **Export (`writeZipBackup`):** je Layer mit `imageUri` → `resolveToLocalFile` →
  ZIP-Entry `wallpapers/layer_N.img`, `imageFileName` gesetzt; **Dedup nach
  Dateipfad** (`entryByPath`), damit Layer, die dieselbe Datei teilen, denselben
  Entry referenzieren (kein Orphan). Single-Layer-Fallback `wallpapers/single.img`.
- **Import (`restoreWallpaperFromBackup`):** je Layer → URI-Erreichbarkeit prüfen →
  `copyToInternal` → `WallpaperLayerState(imageUri = intern)` → sammeln →
  `WallpaperState.multiLayer(valid)` → `assembler.saveWallpaperStateForRestore`.
  **Per-Layer-OOM isoliert** (ein kaputter Layer bricht den Import nicht ab),
  `CancellationException` rethrown, Rückgabe = Anzahl bild-tragender Layer, die
  *nicht* restauriert werden konnten.
- **Legacy:** ein altes Single-Image-Backup kodiert nur die flachen
  `wallpaperUri/Scale/TranslateX/Y`-Felder (leeres `wallpaperLayers`) →
  `importSingleLayerWallpaper` rekonstruiert den kanonischen Ein-Layer-State.

---

## §1 Zielbild

- State-Modell, Repository und File-Manager liegen **geteilt**; beide Launcher
  benutzen exakt dieselbe Wallpaper-Persistenz.
- Der Restorer ist vom Produkt-Schema entkoppelt: er arbeitet auf dem
  produktneutralen Wallpaper-**Teilschema** (`WallpaperLayerBackup` + flacher
  Legacy-Deskriptor), nicht auf `LauncherSettings`.
- Wallpaper ist der erste Klient der generischen Blob-Naht
  (`BACKUP_SCHEMA_PORT_SPEC §3.3`): `collectBlobs`/`rebindBlobs` + ein
  `BlobRestorer`, der über den geteilten `WallpaperFileManager` in den internen
  Speicher kopiert.
- Nyx bekommt Wallpaper-Backup/Restore geschenkt, sobald sein `BackupSchema`
  das geteilte Wallpaper-Teilschema einbettet — **kein** duplizierter Copy-/ZIP-/
  Layer-Rebuild-Code.

---

## §2 Was ist neutral, was bleibt

> **WRS-INV-1 — Wallpaper-Persistenz ist produktfrei.** `WallpaperState`,
> `WallpaperLayerState`, `WallpaperRepository`, `WallpaperFileManager` und
> `WallpaperLayerBackup` referenzieren kein `AppInfo`/`HomeLayout`/Favoriten/Grid.
> Sie sind reine Wertetypen bzw. dünne IO-Klassen und wandern unverändert (nur
> Namespace) in die geteilten Module.

| Teil | Neutral (geteilt) | Bleibt pro App |
|---|---|---|
| `WallpaperState`/`WallpaperLayerState` | ✅ `:core` | |
| `WallpaperRepository` (+ Impl, DataStore) | ✅ `:domain`/`:common-data` | |
| `WallpaperFileManager` (copy/GC) | ✅ `:common-data` | |
| `WallpaperLayerBackup` + Blob-Beitrag/Rebind | ✅ (via Schema-Teil) | |
| Restore-Anwendung (Rebuild + clear/refill) | ✅ | |
| Bild-Picker, Storage-Permission-UX | | ✅ (`:app-*`) |
| *Wo* der Wallpaper im Home sitzt, Rebuild-Trigger | | ✅ (Port aus `MONOREPO_MERGE_SPEC §3`) |
| Rendering/Compositing/Scrim | referenziert (eigene Specs) | |

---

## §3 Geteilte Wallpaper-Persistenz-Oberfläche

Umzug ohne Verhaltensänderung, nur Namespace + Modul:

```kotlin
// :core (pure-JVM)
data class WallpaperLayerState(
    val id: String = newId(), val imageUri: String? = null,
    val scale: Float = DEFAULT_SCALE, val translateX: Float = 0f, val translateY: Float = 0f,
    val captureSampleSize: Int? = null,
) { /* hasImage, isTransformed, newId() (AtomicLong-Suffix) */ }

data class WallpaperState(val layers: List<WallpaperLayerState> = emptyList()) {
    companion object { val NONE = WallpaperState(); fun multiLayer(...); fun single(...) }
    /* layerCount, hasWallpaper, isTransformed, referencedUris */
}

// :domain
interface WallpaperRepository : Purgeable {
    val wallpaperState: Flow<WallpaperState>
    suspend fun saveWallpaperState(state: WallpaperState)
    suspend fun clearWallpaper()
    suspend fun getWallpaperStateSync(): WallpaperState
}
```

`WallpaperFileManager` (`:common-data`) bleibt wie er ist: `copyToInternal`,
`copyFromInputStream`, `isInternalUri`, `fileExists`, `deleteFile`, `clearAll`,
`gcOrphans`.

> **WRS-INV-2 — `WallpaperLayerState.newId()` bleibt kollisionsfrei.** Der
> AtomicLong-Suffix (nicht ein nackter Timestamp) muss erhalten bleiben, damit ein
> Multi-Layer-Legacy-Backup mit lauter `null`-IDs, in derselben Millisekunde
> restauriert, keine ID-Kollision erzeugt.

---

## §4 Blob-Beitrag & Rebind (die Wallpaper-Implementierung von `BACKUP_SCHEMA_PORT_SPEC §3.3`)

Wallpaper stellt genau die zwei Schema-Haken bereit, die die generische Engine
aufruft. Gebündelt als wiederverwendbares Modul, das beide App-Schemata delegieren:

```kotlin
// geteilt — beide App-BackupSchemata delegieren hierauf
object WallpaperBackupPart {

    /** Export: Layer mit lokaler Bilddatei → benannte ZIP-Blobs (dedup nach Pfad). */
    fun collectBlobs(layers: List<WallpaperLayerBackup>, resolveToLocalFile: (String) -> File?)
        : Pair<List<WallpaperLayerBackup>, List<BackupBlob>> { /* entryByPath-Dedup, §0 */ }

    /** Import: extrahierte Blob-Namen → interne URIs in die Layer zurückschreiben. */
    fun rebindBlobs(layers: List<WallpaperLayerBackup>, resolved: Map<BlobName, InternalUri>)
        : List<WallpaperLayerBackup> = layers.map { l ->
            l.imageFileName?.let { resolved[it] }?.let { l.copy(imageUri = it) } ?: l
        }
}
```

> **WRS-INV-3 — Geteilte Dateien = ein Blob.** `collectBlobs` dedupt nach
> absolutem Dateipfad (`entryByPath`): mehrere Layer, die dieselbe Bilddatei
> referenzieren, teilen sich **einen** ZIP-Entry. Ein Layer, der einen Entry-Namen
> stempelt, der nie geschrieben wird, ist verboten (Orphan-Referenz, die der
> Importer nicht auflösen kann).

---

## §5 Restore-Anwendung: State zusammensetzen, Repository neu befüllen

Der entkoppelte Restorer — statt `restoreFromBackup(settings: LauncherSettings)` nun
auf dem neutralen Teilschema, schreibt direkt durch das geteilte Repository:

```kotlin
// geteilt (:common-data), ersetzt WallpaperRestorer + assembler.saveWallpaperStateForRestore
class WallpaperRestore(
    private val fileManager: WallpaperFileManager,
    private val wallpaperRepository: WallpaperRepository,
) {
    /** @return Anzahl bild-tragender Layer, die nicht restauriert werden konnten. */
    suspend fun apply(layers: List<WallpaperLayerBackup>, legacy: LegacySingleLayer?): Int
}
```

Ablauf (Verhalten von heute, als Invarianten fixiert):

> **WRS-INV-4 — Restore ist verlustfrei-per-Layer und total.** Jeder Layer wird
> einzeln restauriert; ein Fehlschlag (URI nicht erreichbar, Copy-Fehler, OOM beim
> Bitmap-Copy) überspringt **nur diesen** Layer und zählt ihn als „dropped", bricht
> aber den Rest nicht ab. `CancellationException` wird rethrown (kein bogus-Report
> pro Rest-Layer, kein Weiterkopieren in einem toten Job). Nur Layer mit
> nicht-leerer Quell-URI zählen in den Dropped-Count — ein Metadaten-only-Layer
> (leere `imageUri`) hatte nie ein Bild und löst keine „Bild nicht mehr
> verfügbar"-Warnung aus.

> **WRS-INV-5 — Erst kopieren, dann State schreiben, dann rendern.** Der Restore
> kopiert alle erreichbaren Bilder via `copyToInternal` in den internen Speicher,
> baut daraus `WallpaperState.multiLayer(valid)` und schreibt **einmal** über
> `WallpaperRepository.saveWallpaperState`. Das Rendering reagiert danach auf den
> `wallpaperState`-Flow (Rebuild-Trigger, `MONOREPO_MERGE_SPEC §3`) — der Restore
> selbst kennt kein Compositing.

---

## §6 Legacy-Single-Layer-Kompat

> **WRS-INV-6 — Alte flache Backups bleiben importierbar.** Ein Backup mit leerem
> `wallpaperLayers`, aber gesetzten flachen `wallpaperUri/Scale/TranslateX/Y`-Feldern
> wird zum kanonischen Ein-Layer-`WallpaperState.single(...)` rekonstruiert. Neue
> Backups schreiben immer `wallpaperLayers` und erreichen diesen Pfad nie. Der
> flache Deskriptor (`LegacySingleLayer`) lebt neben den Layern im geteilten
> Teilschema, nicht in `LauncherSettings`.

---

## §7 Datei-Lebenszyklus & Orphan-GC

> **WRS-INV-7 — Interner Speicher wird gegen den referenzierten State GC't, mit
> Min-Age-Guard.** `gcOrphans(referencedUris, minAgeMillis = 60_000L)` löscht interne
> Wallpaper-Dateien, die kein aktueller `WallpaperState.referencedUris` referenziert
> — aber erst nach der Mindest-Alter-Schwelle, damit eine gerade laufende
> `copyToInternal`/`copyFromInputStream` (deren State-Save noch nicht committed ist)
> nicht unter den Füßen weggelöscht wird. `referencedUris` ist die einzige
> Wahrheitsquelle dafür, welche Dateien leben.

Beim Import gilt: `clearWallpaper` + Neuschreiben ersetzt den State; die alten,
nun unreferenzierten internen Dateien werden vom nächsten `gcOrphans` eingesammelt
(nicht synchron im Restore-Pfad gelöscht — das hält den Restore idempotent und
crash-sicher).

---

## §8 Modul-Zuordnung

| Einheit | Zielmodul |
|---|---|
| `WallpaperState`, `WallpaperLayerState`, `WallpaperLayerBackup`, `LegacySingleLayer` | `:core` |
| `WallpaperRepository` (Port) | `:domain` |
| `WallpaperRepositoryImpl` (DataStore), `WallpaperFileManager`, `WallpaperBackupPart`, `WallpaperRestore`, `BlobRestorer`-Impl | `:common-data` |
| Bild-Picker, Storage-Permission-UX, *Platzierung* im Home, Rebuild-Trigger-Impl | `:app-nyx` / `:app-kolibri` |
| Rendering/Compositing (Composite-Cache, RenderScheduler, Flattener, Luminanz, Scrim) | eigener Extraktionsschritt (Klasse A, referenziert Kolibris `WALLPAPER_*_SPEC`) |

---

## §9 Testplan (Rule 10 + eure Testkonvention)

- **JVM (schnell, kein Gerät):** `WallpaperState`-Fabriken + abgeleitete Properties
  (`referencedUris`, `isTransformed`, `hasWallpaper`); `WallpaperBackupPart.collectBlobs`
  (Dedup nach Pfad, Orphan-Verbot) und `rebindBlobs` (Name→URI, fehlende Bindung
  lässt Layer unangetastet); `WallpaperRestore.apply` mit **Fake** `WallpaperFileManager`
  + MockK-`WallpaperRepository` (per-Layer-Isolation, Dropped-Count,
  Legacy-Single-Layer, `newId`-Kollisionsfreiheit im Millisekunden-Batch).
- **Robolectric/`androidTest` (nur Gerätewahrheit):** `copyToInternal`/`copyFromInputStream`
  (echte Streams/interner Speicher), ZIP-Roundtrip mit Bildern, `gcOrphans`
  (Min-Age-Guard gegen in-flight-Kopie), URI-Erreichbarkeitsprobe.
- **Dispatcher:** ein Dispatcher via `MainDispatcherRule`, **kein** separater
  `TestScope`/`StandardTestDispatcher`; `TESTING_CONVENTIONS.kt`.

> **WRS-INV-8 — Verschoben, nicht verwässert.** Kolibris bestehende Wallpaper-Backup-
> Tests (Teil der `BackupRepositoryImpl`-Suiten „Wallpaper") wandern mit; die
> Entkopplung fügt nur neue reine Tests für `WallpaperBackupPart`/`WallpaperRestore`
> hinzu. Keine verlorene Abdeckung von Per-Layer-OOM, Legacy oder Dedup.

---

## §10 Migration in grün-bleibenden Phasen

Voraussetzung: `:core`, `:common-data` existieren; `BACKUP_SCHEMA_PORT_SPEC`
Phase A/B (Ports + Engine) sind durch.

- **Phase A — Persistenz hochziehen.** `WallpaperState`/`WallpaperLayerState` →
  `:core`; `WallpaperRepository` → `:domain`; `WallpaperRepositoryImpl` +
  `WallpaperFileManager` → `:common-data`, neutraler Namespace. Kolibri zeigt drauf,
  Verhalten unverändert, Tests grün.
- **Phase B — Restorer entkoppeln.** `WallpaperRestorer(settings: LauncherSettings)`
  → `WallpaperRestore.apply(layers, legacy)` auf dem neutralen Teilschema; die
  `assembler.saveWallpaperStateForRestore`-Route entfällt (Schreiben direkt über
  `WallpaperRepository`). `WallpaperLayerBackup` + `LegacySingleLayer` nach `:core`.
- **Phase C — Blob-Naht verdrahten.** Kolibris ZIP-Export/-Import ruft
  `WallpaperBackupPart.collectBlobs`/`rebindBlobs` + `BlobRestorer` (statt der
  inline-Logik in `writeZipBackup`/`restoreWallpaperFromBackup`). Damit ist
  `BACKUP_SCHEMA_PORT_SPEC` Phase C entblockt.
- **Phase D — Nyx.** Nyx' `BackupSchema` bettet das geteilte Wallpaper-Teilschema
  ein und delegiert an `WallpaperBackupPart`/`WallpaperRestore`. Nyx hat
  Wallpaper-Backup/Restore ohne duplizierten Code.

---

## §11 Querschnitt-Konventionen (geerbt)

- **Policy/IO-Split** — Blob-Auswahl/Rebind + State-Rebuild rein; `copyToInternal`,
  ZIP, `contentResolver` in der Android-Schicht.
- **`:domain`/`:core` bleiben Android-frei** — State/Teilschema tragen `String`-URIs,
  kein `Uri`/`Context`.
- **„Nur bei echter Änderung schreiben"** — kein gültiger Layer ⇒ kein
  `saveWallpaperState`, keine leere Überschreibung eines vorhandenen Wallpapers.
- **Vier-Kategorien-Fehlerrahmen** — Per-Layer-Bitmap-Copy fängt `Throwable` (OOM
  ist `Error`), `CancellationException` wird rethrown.
- **Referenz per nacktem Namen** — `WALLPAPER_RESTORE_SPEC §5`, nie ein Pfad.

---

## §12 Offene Punkte (für Review-Runde 1)

**Entschieden:**
- **`WallpaperBackupPart` ist ein `object`** (reine, zustandslose Funktionen; Rule 10,
  JVM-testbar).
- **Orphan-Cleanup bleibt dem bestehenden GC-Zyklus überlassen** — `WallpaperRestore`
  stößt kein synchrones `gcOrphans` an (idempotent, crash-sicher, §7).
- **Blob-Präfix: lesen-beide, schreiben `blobs/wallpaper/…`** (spiegelt
  `BACKUP_SCHEMA_PORT_SPEC §11`; alt `wallpapers/` bleibt lesbar).
- **`captureSampleSize` wird nicht mitgesichert** — reines Render-Detail, kein Teil
  von `WallpaperLayerBackup`.

**Folge-Arbeit (kein offener Punkt):**
- Rendering/Compositing-Extraktion als eigener `WALLPAPER_SHARE_SPEC` (Klasse A,
  referenziert Kolibris bestehende `WALLPAPER_*_SPEC`), sobald diese Persistenz-Basis
  steht.

---

## Review-Log

| Runde | Datum | Reviewer | Ergebnis |
|---|---|---|---|
| v1.1 | `<offen>` | `<offen>` | Empfehlungen als Entscheide: `WallpaperBackupPart`=object, Orphan-GC dem Zyklus überlassen, `blobs/wallpaper/` schreiben, `captureSampleSize` nicht sichern; Compositing-Extraktion als Folge-Spec |
| 1 | `<offen>` | `<offen>` | ausstehend |
