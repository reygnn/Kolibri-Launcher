# ICON_LOADER_SPEC — Hand-rolled Icon-Cache: Byte-LRU + Disk, Policy JVM-rein (Sig + Contract)

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`. Der einzige `:data`-Infrastruktur-
> Pfeiler des Icon-Launchers mit echtem Android-/Pixel-Bezug. Konsumiert `IconRef` /
> `ComponentKey` aus `ICON_HOME_MODEL_SPEC` und **erfüllt** dessen IHM-INV-6
> (Cache-Kohärenz an Paket-Events).
>
> **Fokus:** `IconLoader` (öffentliches Contract), die **reine** Policy darunter
> (`LruBudget`, `IconCacheKey`, Request-Coalescing) und der Android-Teil
> (`IconRasterizer`, Disk-Cache). Plus der Konsument-Vertrag für den Stale-Binding-Guard.
>
> **Nicht im Fokus (§9):** Icon-Packs (`IconRef.Pack` / `appfilter.xml`), themed/
> monochrome Icons (Android 13+), Notification-Badges, Shortcut-Icons. Alle mit
> reserviertem Key-Slot vorbereitet, keiner ausmodelliert.
>
> **Status:** ENTWURF v1. §8-Entscheidungen getroffen (Hand-Roll bestätigt), Review-
> Runde 1 ausstehend.
>
> **Verhältnis zum großen Kolibri:** Hand-Roll-Ethos wie eigene ANR-Erkennung /
> self-hosted ACRA — **kein** Coil/Glide (§8-D1). Und derselbe Rule-10-Split wie die
> Wallpaper-Decode-Specs: die Cache-*Policy* ist pure JVM, nur das Bitmap-Handwerk ist
> instrumentiert.

---

## §0 Der Split, der alles trägt

Ein Icon-Cache ist verführerisch, alles in eine `Bitmap`-behaftete Klasse zu werfen —
und damit unprüfbar zu machen. Dieser Spec zerlegt ihn in drei Schichten:

| Schicht | Kennt Android? | Wo getestet |
|---|---|---|
| **Policy** — Byte-Budget + LRU-Eviction, Keying/Dateiname, Request-Coalescing, Paket-Index | **nein** (nur `Long`/`String`/Keys) | JVM (default) |
| **Handwerk** — `AdaptiveIconDrawable`-Compositing, Rasterisierung, Disk-Write/Decode | ja (`Bitmap`/`Drawable`/`Canvas`) | Robolectric |
| **Rand** — `LauncherApps.getActivityIcon`, `onTrimMemory`, Permission | ja (System) | `androidTest` |

> **ICL-INV-6 — Eviction-Policy ist rein.** `LruBudget` operiert nur auf `CacheKey`
> und Gewichten (`Int` Bytes); kein `Bitmap`/`Drawable`/Dispatcher/keine Uhr. Damit ist
> das Herz des Caches (was fliegt wann raus?) eine Wahrheitstabelle, kein Device-Test.

---

## §1 Öffentliches Contract (`:data`)

```kotlin
interface IconLoader {
    suspend fun bitmap(ref: IconRef, sizePx: Int): Bitmap   // cache-backed; nie auf Main
    fun evict(pkg: String)                                  // Paket ersetzt/entfernt
    fun trim(level: Int)                                    // ComponentCallbacks2-Level
}
```

`bitmap` ist der einzige Lesepfad; UI ruft ihn aus einer Coroutine (RecyclerView-Adapter,
§7). `evict`/`trim` sind synchrone Index-Operationen (kein IO).

---

## §2 Keying (rein, JVM)

```kotlin
@JvmInline value class CacheKey(val raw: String)

enum class IconVariant { ADAPTIVE, THEMED, PACK }   // v1 nutzt nur ADAPTIVE; Rest reserviert

object IconCacheKey {
    // Reine Funktion: gleiche Eingabe ⇒ gleicher Key, stabil über Prozess-Neustarts.
    fun of(ref: IconRef, sizePx: Int, variant: IconVariant): CacheKey

    // Dateiname für den Disk-Cache: "<pkgHash>-<contentHash>.webp".
    // Der pkgHash-Prefix erlaubt evict(pkg) per Glob, ohne Reverse-Hash.
    fun fileName(key: CacheKey): String
    fun packagePrefix(pkg: String): String
}
```

> **ICL-INV-5 — Deterministisches Keying.** `CacheKey` und Dateiname sind reine
> Funktionen von `(ComponentKey, sizePx, variant)`. Folge: Disk-Treffer überleben den
> Prozess-Neustart (Cold-Start zeigt Icons ohne Re-Composite).

---

## §3 Memory-Cache: Byte-LRU (rein, JVM)

```kotlin
// Reine Policy. maxBytes wird injiziert (aus ActivityManager.memoryClass, §8-D6).
class LruBudget(private val maxBytes: Long) {
    // Zugriff/Insert registrieren; liefert die Keys, die RAUS müssen, um ≤ maxBytes zu bleiben.
    fun touch(key: CacheKey, weightBytes: Int): List<CacheKey>
    // onTrimMemory: je höher das Level, desto aggressiver; liefert zu evictende Keys.
    fun trim(level: Int): List<CacheKey>
    fun forget(keys: Collection<CacheKey>)   // evict(pkg) meldet die Keys ab
}
```

Access-geordnete Struktur; Gewicht = `Bitmap.allocationByteCount` (vom Aufrufer
gemessen, damit `LruBudget` Android-frei bleibt).

> **ICL-INV-2 — Memory ist byte-beschränkt.** Nach jedem `touch` ist Σ Gewicht ≤
> `maxBytes`; Verdrängung ist strikt LRU. **Nicht** item-count-beschränkt (ein 512px-
> Icon wiegt 16× ein 128px-Icon).

> **ICL-INV-7 — `trim` ehrt den `onTrimMemory`-Vertrag.** `TRIM_RUNNING_*` → anteilige
> Verdrängung; `TRIM_UI_HIDDEN`/`TRIM_COMPLETE` → Memory leeren. **Disk überlebt** jedes
> `trim` (der teure Re-Composite ist genau das, was Disk retten soll).

---

## §4 Der Lade-Pfad + Request-Coalescing

`bitmap(ref, size)` in Reihenfolge:

1. **Memory-Hit** → zurück (via `LruBudget.touch`).
2. **In-Flight-Hit** → an das laufende `Deferred` desselben Keys anhängen (nicht neu
   rechnen).
3. **Disk-Hit** → decode → Memory → zurück.
4. **Miss** → `LauncherApps.getActivityIcon` → `IconRasterizer` (Composite+Raster) →
   Disk-Write **und** Memory → zurück.

Alles ab Schritt 3 auf `@IoDispatcher`/`@DefaultDispatcher` (Qualifier existieren in
`:domain/di/DispatcherModule`).

> **ICL-INV-1 — Nie auf Main.** Resolve, Composite, Rasterisierung, Disk-IO und Decode
> laufen nie auf dem Main-Thread.

> **ICL-INV-4 — Request-Coalescing.** N gleichzeitige Anfragen für denselben `CacheKey`
> lösen **höchstens einen** Resolve+Composite aus; alle Warter bekommen dasselbe
> Bitmap. (Umgesetzt als `Map<CacheKey, Deferred<Bitmap>>` hinter einem `Mutex`;
> beim Fling fragt eine RecyclerView Dutzende Rows dieselben paar Icons.)

---

## §5 Handwerk: `IconRasterizer` + Disk (Android, Robolectric)

```kotlin
class IconRasterizer {
    // AdaptiveIconDrawable: FG+BG-Layer auf sizePx komponieren; Legacy: skalieren.
    fun rasterize(drawable: Drawable, sizePx: Int): Bitmap
}
```

Disk-Cache unter `cacheDir/icons/`, Dateiname aus `IconCacheKey.fileName`. **Speichert
das fertig komponierte Ergebnis** (nicht die Roh-Layer), damit Schritt 4 aus §4 nach dem
ersten Mal entfällt. Byte-/Alters-beschränkt (LRU per mtime), Pruning lazy im Io-Kontext.

> **ICL-INV-8 — Kein manuelles `recycle`.** Gecachte Bitmaps sind geteilt und immutable;
> Verdrängung lässt nur die Referenz fallen, GC reclaimt. (Manuelles `recycle` eines noch
> referenzierten Bitmaps ist die klassische Use-after-free-Crash-Quelle.)

---

## §6 Kohärenz an Paket-Events (erfüllt IHM-INV-6)

`evict(pkg)`:
- Memory: über den **Paket-Index** (`Map<pkg, Set<CacheKey>>`) die betroffenen Keys →
  `LruBudget.forget`.
- Disk: Glob `packagePrefix(pkg) + "-*.webp"` löschen.

Verdrahtung an `PackageUpdateReceiver` (mitgebracht aus `:data`) bei replace/remove.

> **ICL-INV-3 — Kohärenz.** Nach `evict(pkg)` existiert für dieses Paket kein Memory-
> oder Disk-Eintrag; die nächste Anfrage re-resolved. Ein aktualisiertes Paket zeigt nie
> ein veraltetes Icon.

---

## §7 Konsument-Vertrag: Stale-Binding-Guard (`:app`)

Der Guard ist UI, gehört aber hierher, weil `bitmap()` **positions-agnostisch** ist:

> **ICL-INV-9 — Late-Result-Verwerfung.** Der Adapter setzt beim Bind einen Placeholder
> und startet die Anfrage getaggt mit der **Bind-Generation** des ViewHolders. Trifft das
> Bitmap ein, wird es **nur** gesetzt, wenn die Generation noch stimmt (Holder nicht
> rebound); bei `onViewRecycled` wird die Anfrage gecancelt. Verhindert das Falsch-Icon-
> Flackern beim Fling — dieselbe Stale-Read-/Adapter-Null-Out-Paranoia, die der große
> Kolibri per `checkConventions` erzwingt.

Folder-Preview (IHM-INV-2, abgeleitet, nie als `IconRef` gespeichert): ein
`FolderIconRenderer` holt bis zu 4 Member-Bitmaps via `IconLoader.bitmap` und komponiert
ein 2×2-Preview. Cachebar unter einem Key aus `hash(sortierte Member + size)` — ändert
sich die Mitgliedschaft, ändert sich der Key, die Invalidierung ist automatisch.

---

## §8 Entscheidungen (getroffen — v1, Review-Runde 1 ausstehend)

- **§8-D1 — Hand-rolled, kein Coil/Glide** (Nutzer-Entscheid). Begründung: kein neuer
  Katalog-Dep, volle Kontrolle über Byte-Budget (ICL-INV-2) und Paket-Kohärenz
  (ICL-INV-3), konsistent mit dem Hand-Roll-Ethos des großen Kolibri.
- **§8-D2 — Zweistufig: Memory (Byte-LRU) + Disk (komponiertes Ergebnis).** Disk
  speichert post-Composite, um den teuren Pass beim Cold-Start zu sparen.
- **§8-D3 — Kein manuelles `recycle`** (ICL-INV-8).
- **§8-D4 — Themed/monochrome Icons sind v2.** v1 = Adaptive (FG/BG) + Legacy; `THEMED`/
  `PACK` sind reservierte `IconVariant`-Slots, damit der Key nicht bricht.
- **§8-D5 — Request-Coalescing per `Deferred`-Map** (ICL-INV-4).
- **§8-D6 — Budget = `memoryClass / 8`, geclamped `[minBytes, maxBytes]`.** Tunable;
  Startwerte in Review-Runde 1 festzulegen (offen, §10).
- **§8-D7 — Dateiname `<pkgHash>-<contentHash>.webp`.** Der pkgHash-Prefix macht
  `evict(pkg)` per Glob möglich, ohne Reverse-Hash oder Disk-Index-Datei.

---

## §9 Test-Inventar (Rule 10: value bar, not cost bar)

### §9.1 JVM (default) — die Policy

- **`LruBudget`:** Eviction-Reihenfolge (strikt LRU), Byte-Buchhaltung ≤ `maxBytes`
  (ICL-INV-2), `trim`-Level-Schwellen (ICL-INV-7), `forget` entfernt genau die Keys.
- **`IconCacheKey`:** Determinismus + Stabilität (ICL-INV-5), Dateiname-Form,
  `packagePrefix`.
- **Request-Coalescing:** N gleichzeitige `bitmap`-Calls desselben Keys ⇒ Fake-Resolver
  wird **1×** aufgerufen (ICL-INV-4) — mit dem einen Dispatcher, kein separater
  `TestScope`.
- **Paket-Index:** `evict(pkg)` meldet genau die Keys dieses Pakets ab (ICL-INV-3,
  Memory-Seite).

### §9.2 Robolectric — das Handwerk

- `IconRasterizer`: `AdaptiveIconDrawable` FG/BG korrekt komponiert, Zielgröße stimmt;
  Legacy-Drawable skaliert.
- Disk: Write→Decode-Roundtrip, Glob-Delete trifft nur das Zielpaket.

### §9.3 `androidTest` — der Rand

- reales `LauncherApps.getActivityIcon` + Permission-Gate.
- `onTrimMemory` → Memory geleert, Disk intakt (ICL-INV-7).
- (optional, perf) Fling über den Drawer: keine Main-Thread-Decodes (ICL-INV-1),
  angelehnt an `PERF-BENCHMARK-SETUP`.

> **Dispatcher-Konvention (Projekt):** ein Dispatcher via `MainDispatcherRule`, kein
> separater `TestScope`/`StandardTestDispatcher` — `TESTING_CONVENTIONS.kt` im Test-Root.

> **DI:** `IconLoaderImpl` ist ein `@Singleton` (`@Provides` in `AppModule`, Rule 4);
> `LauncherApps`, `cacheDir`, `ActivityManager` injiziert. `LruBudget`/`IconCacheKey`
> sind reine Klassen ohne Hilt.

---

## §10 Offene Punkte für Review-Runde 1

1. Budget-Startwerte (§8-D6): Bruchteil + `min/max`-Clamp konkret.
2. Disk-Cache-Obergrenze (Bytes vs. Alter) und Pruning-Kadenz.
3. `webp`-Qualität/Format für den Disk-Write (lossless vs. Q90) — Größe vs. Schärfe.
4. Themed-Icons doch v1? (würde §8-D4 kippen und `THEMED` aktivieren).

---

## §11 Außerhalb des Scopes (v2+)

- **Icon-Packs** — `IconRef.Pack`, `appfilter.xml`-Parsing, `PACK`-Variante aktivieren.
- **Themed/monochrome Icons** — Monochrom-Layer + Material-You-Tint (§10-Punkt 4).
- **Notification-Badges / Shortcut-Icons.**

---

## Review-Log

- **v1 (ENTWURF)** — Erst-Ausformulierung nach „weiter". Drei-Schichten-Split (§0) als
  Fundament, Policy JVM-rein (ICL-INV-6), neun Invarianten. Hand-Roll (§8-D1) bestätigt.
  Themed/Packs/Badges nach v2; vier Tuning-Punkte (§10) offen.
