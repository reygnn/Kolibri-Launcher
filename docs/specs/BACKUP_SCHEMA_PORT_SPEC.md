# BACKUP_SCHEMA_PORT_SPEC — Backup/Restore als geteilte Engine + pro-App-Schema

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als Detail-Spec für die Naht
> aus `MONOREPO_MERGE_SPEC §3.5`, Zeile „JSON Backup/Restore" — und für die dort
> markierte **Wallpaper ↔ Backup-Kopplung**. Konsumiert die §3-Port-Doktrin
> („geteilt wird die Mechanik, nicht das Schema", MRG-INV-8).
>
> **Fokus:** den Backup/Restore-Pfad so schneiden, dass die *generische Maschinerie*
> (ZIP-Container, kotlinx-Encode/Decode, Envelope, Versions-Gate, Blob-Anhänge,
> Import-Options-Gating) geteilt wird, während das *produktgebundene Schema*
> (Kolibris `LauncherSettings` vs. Nyx' `HomeLayout`-Backup) und die
> *Repo-Komposition* (Assembler) pro App bleiben. Definiert die drei Ports
> `BackupSchema<T>`, `BackupAssembler<T>` und die Blob-Naht (`BackupBlob`/`BlobRestorer`),
> an der Wallpaper andockt.
>
> **Nicht im Fokus:** *was* Wallpaper konkret als Blob beiträgt und wie es Bilder in
> den internen Speicher restauriert (→ `WALLPAPER_RESTORE_SPEC`), das konkrete
> Nyx-`HomeLayout`-Payload-Schema (§6 skizziert es, ausmodelliert wird es separat),
> und die Monorepo-Mechanik (`MONOREPO_MERGE_SPEC`).
>
> **Status:** ENTWURF v1.2. Die Port-Signaturen in §3 sind Vorschläge auf Basis des
> realen Kolibri-Codes; Review-Runde 1 ausstehend.
>
> **Verhältnis zum großen Kolibri:** Kolibris `BackupSerializer` ist ein
> **handgeschriebener** Motor über *einem* Schema — kotlinx für forward-compatible
> Reads, ein `org.json`-Strict-Pass für hand-editierte/teilkorrupte Dateien, plus
> Feld-für-Feld-Typvalidierung. Dieser Spec verallgemeinert nur die Teile, die
> schema-unabhängig sind; die paranoide Strict-Recovery bleibt schema-spezifisch
> und Kolibri behält seine Voll-Version. Nyx startet mit dem schlanken Default.

---

## §0 Bestandsaufnahme (die reale Pipeline)

```
ExportBackupUseCase(uri)          → BackupRepository.saveBackupToFile(uri): Boolean
ImportBackupUseCase(uri, options) → BackupRepository.loadBackupFromFile(uri, options): ImportResult
PreviewBackupUseCase(uri)         → BackupRepository.previewBackup(uri): BackupPreview?
```

`BackupRepository` (domain, 5 Methoden): `exportToJson`, `importFromJson`,
`saveBackupToFile`, `loadBackupFromFile`, `previewBackup`.

`BackupRepositoryImpl` (data, Android) komponiert drei Mitspieler:

| Mitspieler | Datei | Verantwortung | Kopplung |
|---|---|---|---|
| **Serializer** | `data/BackupSerializer.kt` | `BackupData ↔ JSON-String`, Preview, ZIP-Image-Auflösung, Typvalidierung. Kein `Context`/Repo/Dispatcher — **pure logic, JVM-testbar** | an `BackupData`/`LauncherSettings` **getippt** |
| **Assembler** | `data/BackupDataAssembler.kt` | komponiert **8 Repositories** (Favorites, Order, Hidden, CustomNames, InstalledApps, Swipe, Settings, Wallpaper) → baut/applyed das Payload; `coerceInSafe`-Clamping beim Import | **voll produktspezifisch** |
| **Impl** | `data/BackupRepositoryImpl.kt` | ZIP-I/O (`Uri`, `ContentResolver`, Streams), Orchestrierung, `WallpaperRestorer`-Callback | Android + Orchestrierung |

**Container-Format (real):** ein **ZIP**, nicht nacktes JSON —
```
backup.zip
 ├── backup.json      — settings + per-layer metadata (das kotlinx/org.json-Manifest)
 └── wallpapers/
     ├── layer_0.img  — Blob je Wallpaper-Layer
     └── single.img   — Legacy-Single-Layer-Blob
```
Import: unzip → `parseBackupData(backup.json)` → `resolveZipImages(backup, extractedMap)`
(Dateiname → interne URI) → `assembler.performImport(resolved, options, wallpaperRestorer)`.

**Robustheits-Eigenschaften, die es zu erhalten gilt:** hybrides Parsen
(kotlinx → `org.json`-Strict-Fallback → Merge), `@JsonNames` snake_case-Aliase,
`ignoreUnknownKeys = true` (forward-compat), `allowSpecialFloatingPointValues = false`
(kein Infinity/NaN schreibbar), `MAX_ARRAY_ELEMENTS`-Caps, skip-on-unknown für
Enum-Felder (Legacy-Backups lassen unbekannte Felder = `null` = aktuellen Wert
unangetastet), `coerceInSafe`-Clamping.

---

## §1 Zielbild

- **Eine** geteilte Engine trägt: ZIP-Container, kotlinx-Encode/Decode eines
  generischen `@Serializable T`, Envelope (`version`/`timestamp`/`appVersion`),
  Versions-Gate, Blob-Anhänge + Auflösung, Datei-I/O.
- **Pro App** bleibt: das `@Serializable`-Payload `T`, der Assembler (Repo-Komposition),
  die `ImportOptions`-Kategorien, und — optional — die schema-spezifische
  Strict-Recovery/Preview.
- Nyx bekommt Backup/Restore, indem es `BackupSchema<HomeLayoutBackup>` +
  `BackupAssembler<HomeLayoutBackup>` liefert — **kein** duplizierter ZIP-Code, kein
  duplizierter kotlinx-Motor.
- Wallpaper hängt über **eine** Blob-Naht an, gemeinsam für beide Apps.

---

## §2 Was ist neutral, was produktgebunden (die ehrliche Linie)

> **BSP-INV-1 — Generisch ist nur, was schemafrei ist.** Geteilt werden ZIP,
> Envelope, kotlinx über `KSerializer<T>`, Versions-Gate, Blob-Plumbing und Datei-I/O.
> **Nicht** geteilt (weil Feld-für-Feld auf ein Schema getippt): die
> `org.json`-Strict-Recovery, die Typvalidierung einzelner Settings-Felder und die
> reichhaltige `buildPreview`. Diese sind optionale, pro-Schema überschreibbare
> Haken mit schlankem Default (§3.1).

| Teil | Neutral (geteilt) | Pro App |
|---|---|---|
| ZIP-Container (`backup.json` + `blobs/`) | ✅ | |
| kotlinx `encode/decode<T>` + `Json{}`-Config | ✅ | |
| Envelope `{version, timestamp, appVersion, payload}` | ✅ | |
| Versions-Gate (`isVersionSupported`) | ✅ (Default) | überschreibbar |
| Blob-Sammlung/-Auflösung (`resolveZipImages` verallgemeinert) | ✅ (Mechanik) | *welche* Blobs (§3.3) |
| `ImportOptions`-Gating-**Muster** (unabhängige Toggles, `importNothing`) | ✅ | *welche Kategorien* |
| `ImportResult`/`BackupPreview`-**Hülle** | ✅ | Kategorie-Zählwerte |
| `org.json`-Strict-Recovery, Feld-Typvalidierung | | ✅ (Kolibri behält, Nyx schlank) |
| Assembler (Repo-Komposition, `coerceInSafe`) | | ✅ |
| Payload `T` (`LauncherSettings` / `HomeLayoutBackup`) | | ✅ |

---

## §3 Die Ports

### §3.1 `BackupSchema<T>` — der Payload-Beschreiber

```kotlin
// :domain (geteilt) — beschreibt EIN app-eigenes Payload T
interface BackupSchema<T> {
    val serializer: KSerializer<T>          // kotlinx-typisiertes Payload
    val currentVersion: String

    fun isVersionSupported(version: String): Boolean =
        version == currentVersion            // Default; Kolibri darf Bereich erlauben

    // --- Blob-Naht (§3.3): Wallpaper & Co. hängen hier ---
    fun collectBlobs(payload: T): List<BackupBlob> = emptyList()
    fun rebindBlobs(payload: T, resolved: Map<BlobName, InternalUri>): T = payload

    // --- Optionale Robustheit: schlanker Default, Kolibri überschreibt ---
    /** org.json-Strict-Fallback für hand-editierte/teilkorrupte Manifeste. */
    fun recover(rawJson: String): T? = null
    /** Strukturelle Zusammenfassung fürs Preview-Dialog. */
    fun preview(payload: T): BackupPreview
}
```

> **BSP-INV-2 — Die Engine kennt nur `BackupSchema<T>`.** Kein geteilter
> Engine-Typ importiert `BackupData`, `LauncherSettings` oder ein Nyx-Payload direkt.
> Der einzige Weg zum Schema führt über diesen Port. Kolibris heutiger
> `BackupSerializer` wird zur Kolibri-Implementierung von `BackupSchema<LauncherSettings>`
> (Voll-Version mit `recover`); Nyx liefert eine schlanke Implementierung ohne
> `recover` (kotlinx-only reicht für einen frischen Store).

### §3.2 `BackupAssembler<T>` — die Repo-Komposition (produktspezifisch)

```kotlin
// :domain (geteilt als Port), Impl pro App im :data-<app>
interface BackupAssembler<T> {
    /** Liest den aktuellen Zustand aus den App-Repositories in ein Payload. */
    suspend fun buildPayload(): T
    /** Wendet ein Payload selektiv an (Kategorie-Toggles) und meldet das Ergebnis. */
    suspend fun applyPayload(payload: T, options: ImportOptions, blobs: BlobRestorer): ImportResult
}
```

`ImportOptions` bleibt **pro App** (Kolibri: `importFavorites/importHiddenApps/…`;
Nyx: `importLayout/importFolders/…`), weil die Kategorien am Produktmodell hängen.
Geteilt ist nur das Muster: unabhängige Bool-Toggles + `importNothing`.

> **BSP-INV-3 — Selektiver Import ist total und verlustfrei-per-Default.** Eine im
> Payload fehlende (Legacy-)Kategorie lässt den aktuellen Wert unangetastet
> (skip-on-null), ein abgeschalteter Toggle überspringt seine Kategorie ohne
> Seiteneffekt, `importNothing` ⇒ No-Op ohne Store-Write. (Kolibris heutiges
> Verhalten, hier als Invariante festgeschrieben.)

### §3.3 Blob-Naht — `BackupBlob` / `BlobRestorer` (hier hängt Wallpaper)

```kotlin
typealias BlobName = String        // relativer ZIP-Pfad, z.B. "wallpapers/layer_0.img"
typealias InternalUri = String

// Export: Schema meldet, welche Binär-Anhänge ins ZIP gehören.
data class BackupBlob(val name: BlobName, val bytes: suspend () -> ByteArray)

// Import: Engine hat die Blobs extrahiert; Schema/Assembler restauriert sie.
fun interface BlobRestorer {
    /** Kopiert einen extrahierten Blob in den internen Speicher, liefert die interne URI. */
    suspend fun restore(name: BlobName, bytes: ByteArray): InternalUri
}
```

Ablauf, generisch:
- **Export:** Engine ruft `schema.collectBlobs(payload)`, schreibt jeden Blob als
  ZIP-Entry unter seinem `name`, encodet `payload` (mit Blob-Namen als Referenzen)
  nach `backup.json`.
- **Import:** Engine extrahiert `blobs/*`, ruft `schema.rebindBlobs(payload, resolvedMap)`
  (Namen → interne URIs, nachdem der `BlobRestorer` sie in den internen Speicher
  kopiert hat).

> **BSP-INV-4 — Die Engine kennt keine Bilder.** Die Engine transportiert Blobs
> als benannte Byte-Anhänge; *was* ein Blob ist (Wallpaper-Layer, künftig Icons …)
> weiß nur `BackupSchema<T>`. Der `BlobRestorer` (interner-Speicher-Copy,
> URI/Scheme-Validierung, Size-Caps) wird von der App geliefert. Damit ist
> `WALLPAPER_RESTORE_SPEC` die einzige Stelle, die Wallpaper-Blobs definiert —
> nicht die Backup-Engine.

### §3.4 `BackupEngine<T>` — die geteilte Maschinerie

```kotlin
// :common-data (android-library) — eine Instanz pro Schema, via Hilt gebunden
class BackupEngine<T>(
    private val schema: BackupSchema<T>,
    private val json: Json,                 // geteilte Config, s. §4
) {
    fun encodeManifest(payload: T): String
    fun parseManifest(rawJson: String): T?  // kotlinx → schema.recover(...) Fallback
    // ZIP schreiben/lesen, Blobs, Versions-Gate, Preview-Extraktion …
}
```

Ein generischer `BaseBackupRepository<T>(engine, assembler, fileIo)` implementiert die
5 `BackupRepository`-Methoden über `engine` + `assembler`. Kolibris
`BackupRepositoryImpl` wird ein dünner Spezialfall davon (oder verschwindet ganz).

---

## §4 ZIP-Containerformat (der neutrale On-Disk-Vertrag)

- Entry `backup.json` = kotlinx-serialisierte Envelope `{version, timestamp,
  appVersion, payload}`.
- Entry-Präfix `blobs/` (bzw. das bestehende `wallpapers/` als erste Blob-Klasse)
  = Binär-Anhänge, referenziert per Name aus dem Payload.
- `Json{}`-Config **geteilt**: `prettyPrint = true`, `ignoreUnknownKeys = true`,
  `encodeDefaults = true`, `allowSpecialFloatingPointValues = false`.
- **Blob-loses Schema ⇒ schlichte `.json` statt `.zip`.** Meldet `schema.collectBlobs`
  nichts (z. B. `BackupSchema<UsageData>`), schreibt die Engine nur `backup.json`
  ohne ZIP-Hülle — menschenlesbar, hand-editierbar. Der Container ist ZIP genau dann,
  wenn es Blobs gibt (Wallpaper).

> **BSP-INV-5 — Forward-compatible bleibt Vertrag.** `ignoreUnknownKeys` +
> `@JsonNames`-Aliase + skip-on-unknown-Enums gelten für jedes Schema. Ein Backup
> einer neueren App-Version importiert in eine ältere, ohne zu crashen: unbekannte
> Felder/Enum-Werte werden ignoriert, fehlende Felder lassen aktuelle Werte stehen.
> `allowSpecialFloatingPointValues = false` garantiert, dass nie ein Infinity/NaN
> geschrieben wird; hand-editierte non-finite Werte werden beim Lesen verworfen.

---

## §5 Wallpaper ↔ Backup (die in `MONOREPO_MERGE_SPEC §3.5` markierte Kopplung)

Warum beide zusammen geplant werden müssen:

- Das Wallpaper-Payload (Layer mit `scale`/`translate`/Bildreferenz) ist **Teil des
  Backup-Schemas** — bei Kolibri als `wallpaperLayers` in `LauncherSettings`.
- Die Bilder reisen als **Blobs** im selben ZIP (`wallpapers/layer_N.img`).
- Restore geht durch **denselben** `BlobRestorer` (interner-Speicher-Copy).

Zuständigkeits-Schnitt:

- **Dieser Spec** besitzt: die Blob-Mechanik (`BackupBlob`/`BlobRestorer`, §3.3), das
  ZIP-Format (§4), die generische Rebind-Auflösung.
- **`WALLPAPER_RESTORE_SPEC`** besitzt: *welche* Blobs Wallpaper beiträgt, die
  Legacy-Single-Layer-Kompatibilität, URI/Scheme-Validierung, Size-Caps, und wie
  `WallpaperRepository` beim Import geleert/neu befüllt wird (Kolibris
  `WallpaperRestorer`-Callback ist die Vorlage).

> **BSP-INV-6 — Wallpaper ist ein Blob-Klient, kein Sonderfall der Engine.** Wenn
> das Wallpaper-Schema wächst (mehr Layer-Felder, neue Bildformate), ändert sich nur
> das app-eigene `BackupSchema<T>.collectBlobs/rebindBlobs` — nie die Engine.

---

## §6 Nyx-Payload (Skizze, ausmodelliert separat)

Nyx serialisiert **nicht** `LauncherSettings`, sondern seinen Grid-Zustand. Grobe
Form (Detail in einem künftigen `NYX_BACKUP_SCHEMA_SPEC`):

```kotlin
@Serializable
data class HomeLayoutBackup(
    val pages: List<PageBackup>,        // Grid-Seiten mit Zellen
    val dock: List<CellBackup>,
    val folders: List<FolderBackup>,
    // geteilte, produktneutrale Blöcke, die Nyx AUCH nutzt:
    val wallpaperLayers: List<WallpaperLayerBackup> = emptyList(),
    val showCalendarEvent: Boolean? = null,   // aus HOME_INFO_ELEMENTS_SPEC
    val showAlarm: Boolean? = null,
)
```

Nyx nutzt dieselben produktneutralen Teilschemata (`WallpaperLayerBackup`, die
Info-Element-Toggles) und ergänzt sein Grid-eigenes. `WallpaperLayerBackup` selbst
sollte deshalb aus Kolibris `LauncherSettings` heraus nach `:core` gehoben werden
(es ist produktneutral).

---

## §7 Modul-Zuordnung

| Einheit | Zielmodul |
|---|---|
| `BackupSchema<T>`, `BackupAssembler<T>`, `BackupBlob`, `BlobRestorer`, `ImportOptions`-Muster, `ImportResult`/`BackupPreview`-Hülle | `:domain` (pure-JVM, geteilt) |
| `WallpaperLayerBackup` (aus `LauncherSettings` herausgehoben) | `:core` |
| `BackupEngine<T>`, `BaseBackupRepository<T>`, ZIP-I/O, `Json{}`-Config | `:common-data` (android-library) |
| Kolibri `BackupSchema<LauncherSettings>` (heutiger `BackupSerializer` inkl. `recover`), `LauncherSettings`, Kolibri-Assembler, Kolibri-`ImportOptions` | `:app-kolibri` / `:data-kolibri` |
| Nyx `BackupSchema<HomeLayoutBackup>` (schlank), `HomeLayoutBackup`, Nyx-Assembler, Nyx-`ImportOptions` | `:app-nyx` / `:data-nyx` |
| Wallpaper-`BlobRestorer`-Impl, `WallpaperRestorer`-Logik | pro App (Vorlage in `WALLPAPER_RESTORE_SPEC`) |

Bei Bedarf zu `:feature-backup` bündeln (`MONOREPO_MERGE_SPEC §4.2`); Start in
`:common-data` (MRG-INV-5).

---

## §8 Testplan (Rule 10 + eure Testkonvention)

- **JVM (schnell, kein Gerät):** Roundtrip `encodeManifest`/`parseManifest` über
  ein Test-`T`; Versions-Gate; `rebindBlobs` (Namen → URIs); `ImportOptions`-Gating
  (jeder Toggle einzeln, `importNothing` ⇒ No-Op); Envelope forward-compat
  (unbekannte Keys ignoriert, fehlende Felder lassen Default). Kolibris fünf
  bestehende Serializer-Suiten (Strict, Doomsday, Malformed, Logic, Wallpaper,
  NamingConvention) wandern zu `BackupSchemaKolibriTest` — sie testen ohnehin die
  reine Logik (der Code sagt das selbst).
- **Robolectric/`androidTest` (nur Gerätewahrheit):** ZIP-Schreiben/-Lesen über
  `Uri`/`ContentResolver`, Blob-Extraktion, `BlobRestorer`-Datei-Copy.
- **Dispatcher:** ein Dispatcher via `MainDispatcherRule`, **kein** separater
  `TestScope`/`StandardTestDispatcher`; Repos als MockK-Fakes. Siehe
  `TESTING_CONVENTIONS.kt`.

> **BSP-INV-7 — Verschoben, nicht verwässert.** Die bestehenden Backup-Tests
> bleiben grün und wandern mit; die Verallgemeinerung fügt nur generische
> Engine-Tests hinzu. Kein Verlust der Strict-/Malformed-/Wallpaper-Abdeckung.

---

## §9 Migration in grün-bleibenden Phasen

Voraussetzung: `:core`, `:common-data` existieren (`MONOREPO_MERGE_SPEC §6`).

- **Phase A — Ports definieren, Kolibri dahinter schieben.** `BackupSchema<T>`,
  `BackupAssembler<T>`, Blob-Typen in `:domain`. Kolibris `BackupSerializer` wird
  `KolibriBackupSchema : BackupSchema<LauncherSettings>` (Signaturen unverändert,
  nur hinter dem Port). `BackupDataAssembler` implementiert `BackupAssembler`. Kein
  Verhaltenswechsel, Tests grün.
- **Phase B — Engine extrahieren.** ZIP-I/O + kotlinx + Envelope aus
  `BackupRepositoryImpl` in `BackupEngine<T>`/`BaseBackupRepository<T>` (`:common-data`),
  generisch über `schema`. Kolibri konsumiert die generische Engine mit seinem Schema.
- **Phase C — `WallpaperLayerBackup` nach `:core` heben** + Blob-Naht auf
  `BackupBlob`/`BlobRestorer` umstellen (Kolibris `WallpaperRestorer` wird eine
  `BlobRestorer`-Impl). Wallpaper-Backup läuft unverändert, jetzt über den Port.
- **Phase D — Nyx andockt.** `HomeLayoutBackup` + `NyxBackupSchema` (schlank, ohne
  `recover`) + Nyx-Assembler + Nyx-`ImportOptions`. Nyx hat Export/Import/Preview —
  ohne eine Zeile duplizierter ZIP- oder kotlinx-Logik.

---

## §10 Querschnitt-Konventionen (geerbt)

- **Policy/IO-Split** — Manifest-Logik (encode/parse/preview/rebind) rein; ZIP/`Uri`/
  Streams in der Android-Engine.
- **Sealed Ergebnis-Identifier** — `ImportResult` ist ein sealed Typ, `:app` mappt zu
  UI-Feedback; kein `@StringRes` in Engine/Domain.
- **„Nur bei echter Änderung schreiben"** — `importNothing`/leeres Payload ⇒ kein
  Store-Write.
- **`:domain` bleibt Android-frei** — Ports nutzen `KSerializer<T>`, `ByteArray`,
  `String`-URIs; kein `Uri`/`Context`.
- **Referenz per nacktem Namen** — `BACKUP_SCHEMA_PORT_SPEC §3.3`, nie ein Pfad.

---

## §11 Offene Punkte (für Review-Runde 1)

**Entschieden:**
- **`recover` = geteilter, opt-in lenient-Helfer; Feld-Recovery bleibt pro Schema.**
  Der schema-unabhängige Teil (org.json strukturell lesen, salvagen was parst) wird
  als Engine-Utility herausgezogen; die feldspezifische Extraktion bleibt in Kolibris
  `recover()`-Override. Nyx startet mit `recover = null` (kotlinx-only) und kann den
  Helfer später mit einer Zeile einschalten, ohne Feldlogik zu schreiben.
- **`ImportOptions` bleibt ganz pro App** — kein geteiltes Kern-Set. Das *Muster*
  (unabhängige Toggles + `importNothing`) und der *Mechanismus* (Assembler wendet pro
  Kategorie an) sind schon geteilt; die konkrete Kategorienliste spiegelt das
  produktspezifische Schema und gehört zu ihm. Ein geteilter Bool-Sack-Typ mit
  „Basis + Erweiterung" wäre Over-Engineering. (Falls je eine geteilte selektive-
  Restore-UI kommt, dann ein geteiltes `ImportCategory`-Enum — heute nicht.)
- **Blob-Präfix: lesen-beide, schreiben `blobs/`.** Alt `wallpapers/` bleibt lesbar
  (Kompat), neue Backups schreiben unter `blobs/` (Wallpaper: `blobs/wallpaper/…`).
- **`BackupPreview` ist eine generische Hülle in `:domain`** mit
  `Map<Kategorie,Int>`-Zählwerten statt festverdrahteter Kolibri-Felder — so bekommt
  auch Nyx Preview ohne eigenen Code.
- `WALLPAPER_RESTORE_SPEC` ist gezogen ⇒ Phase C entblockt.

**Keine offenen Punkte mehr.**

---

## Review-Log

| Runde | Datum | Reviewer | Ergebnis |
|---|---|---|---|
| v1.2 | `<offen>` | `<offen>` | Entscheide: `recover` geteilter opt-in lenient-Helfer (Feld-Recovery pro Schema), `ImportOptions` pro App, blob-loses Schema ⇒ `.json` (§4). Keine offenen Punkte mehr |
| v1.1 | `<offen>` | `<offen>` | Empfehlungen als Entscheide: Blob-Präfix schreiben `blobs/` (alt lesbar), `BackupPreview` generische Hülle in `:domain`. Offen: `recover`-Reichweite, `ImportOptions`-Zuschnitt |
| 1 | `<offen>` | `<offen>` | ausstehend |
