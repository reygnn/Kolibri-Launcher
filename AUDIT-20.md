# AUDIT-20 — Wallpaper: Composite-Flatten & In-Memory-Cache

> **Erzeugt** 2026-08-17 gegen `main` @ `7cc9ecdb` (Version 0.99.176 / code 196),
> auf die Frage: *„Multi-Agent-Code-Review auf dem **Composite- und Cache-Pfad**"*
> — der Option-D-Arbeit aus `WALLPAPER_DRAWER_HOME_REBUILD_SPEC.md` §9.3/§9.4a
> (Flatten-on-Commit, versionierter Composite-Pfad, `WallpaperCompositeCache`,
> lazy Backfill, AUTO-Classifier-Sampling des Composites).
> **Methode:** fünf parallele Finder-Dimensionen (Cache-Kohärenz/Versionierung,
> Bitmap-/Memory-Lifecycle, Concurrency/Coroutine-Cancellation, Flatten-Korrektheit
> + Classifier, Projekt-Regel-Konformität), **jede mit adversarialer Verifikation
> je Dimension**, dann Synthese (11 Agents). **Danach jeder überlebende Fund selbst
> am Code nachgelesen** (Toast-Zeile, `write()`-Mechanik, `parseWallpaperState`,
> Clear-Pfad — alle bestätigt). Gegengeprüft: `CLAUDE.md`, `ACCEPTED_LIMITATIONS.md`,
> `KNOWN_ISSUES.md`.
>
> **Status: F1 + F2 + F4 GEFIXT** (Branch `fix/composite-lifecycle-hardening`),
> **F3 noch OFFEN** (separater Cleanup). Vier Funde, fast alle in **einem
> Cluster: dem Composite-Lebenszyklus** (Erzeugen / Löschen / Selbstheilung) —
> **nicht** im Cache-*Lesepfad*, der solide ist. Kausalkette: **F1** (Race) bzw.
> **F4** (stiller Compress-Fehler) erzeugt einen verwaisten Composite-Pfad, **F2**
> macht ihn *permanent* (keine Selbstheilung) → **leerer Homescreen über
> Neustarts**. **F3** ist ein separater Ressourcen-Leak auf dem „Wallpaper
> entfernen"-Pfad. Empfohlener Zuschnitt und Umsetzungsstand unter §3.
>
> **Nicht in diesem Dokument:** der TEMP-Debug-Toast auf jedem Cache-Fill
> (`HomeFragment.kt:1581`, Commit `8faa14a3`) — bewusst ausgeklammert, da bereits
> in `CLAUDE.md`/der Commit-Message als „remove later" markiert und als sofortiger
> Ein-Zeilen-Release-Blocker separat zu behandeln.

---

## 0. Ergebnis der fünf Dimensionen

| Achse | Ergebnis |
|---|---|
| **Cache-Kohärenz / Versionierung** | 🟢 Sauber. Kein Stale-Read, keine Key-Verwechslung Composite↔Single-Layer. Der versionierte Dateiname (`composite_<ts>_<n>.webp`) macht Invalidation-by-replace wasserdicht — jeder Flatten ⇒ neuer Pfad ⇒ natürlicher Cache-Miss / Re-Klassifizierung. |
| **Bitmap / Memory-Lifecycle** | 🟠 Der Cache hält die ~10 MB HARDWARE-Bitmap korrekt (never-recycle-Invariante intakt), **aber** kein `invalidate()` auf dem User-Clear-Pfad (F3). Decode gebounded (`BoundedBitmapDecoder`), `Throwable`-Umbrella an den Allokationsgrenzen korrekt. |
| **Concurrency / Cancellation** | 🟠 Ein Serialisierungs-Loch in `WallpaperCompositeStore.write()` (F1). **Kein** Cancellation-Swallow — `cancel_files`-Disziplin hält (`loadBitmapFromUri` synchron + Marker, `write()` mit `CancellationException`-first-Arm). |
| **Flatten-Korrektheit / Classifier** | 🟢 Software-Composite und Classifier-Sample-Pfad konsistent, keine Blend-/Alpha-/Transform-Order-Divergenz. Ein stiller Compress-Fehlerpfad (F4). |
| **Projekt-Regel-Konformität** | 🟢 Rule 9/11/13 eingehalten (bis auf den separat behandelten TEMP-Toast). Keine `Flow.catch`-Cancellation-Swallow, keine zu enge `catch(Exception)` an Allokationsgrenzen. |

Die „checked clean"-Liste (§3) ist bewusst lang: der **Lesepfad** des Caches ist
das Ergebnis mehrerer Iterationen (§9.4a) und trägt. Alle Funde sitzen auf der
**Schreib-/Aufräum-Seite** des Composites.

---

## 1. Findings

| # | Cluster | Ort | Was | Severity | Verdict | Status |
|---|---|---|---|---|---|---|
| **F1** | Composite-Lifecycle | `WallpaperCompositeStore.write()` (`:46-52`) + `WallpaperDelegate` Commit/Backfill | `write()` nicht serialisiert; `deleteAll()` eines parallelen Commit-Flatten unlinkt die noch offene Datei eines in-flight Backfills → persistierter Pfad zeigt ins Leere → **Home leer über Neustarts** | `med` | PLAUSIBLE | ✅ GEFIXT |
| **F2** | Composite-Lifecycle | `WallpaperRepositoryImpl.parseWallpaperState` (`:192`) + `maybeBackfillComposite` | `flattenedWallpaperPath` ungeprüft aus DataStore (Layer werden per `fileExists()` validiert, Composite nicht); Backfill auf `path != null` gegated → **dangling Composite heilt nie selbst** | `low` | CONFIRMED | ✅ GEFIXT |
| **F3** | Ressourcen-Leak | `WallpaperCompositeCache` (`:46`) + `clearWallpaper()` (`WallpaperRepositoryImpl:356`) | „Wallpaper entfernen" räumt weder die on-disk `composite_*.webp` noch die ~10 MB In-Memory-Bitmap ab (`compositeStore.clear()` nur in `purgeRepository`, kein `invalidate()` im Cache) | `low` | CONFIRMED | ⬜ OFFEN |
| **F4** | Composite-Lifecycle | `WallpaperCompositeStore.write()` (`:51`) | `Bitmap.compress()`-Boolean verworfen; ein `false`-Rückgabewert *ohne* Exception ergibt einen non-null Pfad auf eine korrupte/unvollständige Datei, die persistiert wird | `low` | PLAUSIBLE | ✅ GEFIXT |

---

### F1 — `WallpaperCompositeStore.write()` ist nicht serialisiert · `med` · PLAUSIBLE

`data/wallpaper/WallpaperCompositeStore.kt:46-52`

```kotlin
suspend fun write(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
    try {
        deleteAll() // previous composite (the new file does not exist yet)
        val file = File(dir(), "$COMPOSITE_PREFIX${System.currentTimeMillis()}_${counter.getAndIncrement()}.webp")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        }
        Uri.fromFile(file).toString()
    } catch ...
}
```

`deleteAll()` löscht **alle** `composite_*`-Dateien, bevor die neue geschrieben
wird. Composite-Regeneration ist zwischen den beiden Callern **nicht** serialisiert:

- der Hintergrund-**Backfill** (`WallpaperDelegate.maybeBackfillComposite`, für
  einen wiederhergestellten Multi-Layer-State ohne Composite),
- der **Edit-Commit**-Flatten (`onCommitWallpaperEditMode`).

Beide hoppen in `write()`s eigenes `withContext(Dispatchers.IO)` und können auf
demselben noch-`path==null`-State parallel laufen. Der versionierte Dateiname
verhindert eine Namenskollision — aber genau deshalb ist `deleteAll()` das Problem:
`write2.deleteAll()` entfernt die noch offene Datei von `write1` (unter Linux
unlinkt, `write1`s FD schreibt in den verwaisten Inode weiter). Nach beiden Writes
existiert nur `fileB` auf Disk.

**Failure-Szenario:** Wiederhergestellter composite-loser Multi-Layer-State triggert
Backfill-`flatten1` (`write1` → `fileA`, komprimiert noch). Nutzer geht in Edit-Mode
und committet innerhalb dieses Fensters → `flatten2` (`write2`) für denselben
`path==null`-State. `write2.deleteAll()` unlinkt `fileA` mitten im Compress. Speichert
dann `write1` seinen Pfad per latest-wins **zuletzt**, wird `flattenedWallpaperPath`
= `fileA` persistiert — auf eine unverlinkte Datei. Nächster Display-Decode →
`FileNotFoundException` → `null` → `applySingleLayer` setzt den Wallpaper-View auf
GONE. `maybeBackfillComposite` regeneriert **nicht** (Pfad ist non-null, siehe F2)
→ **Home bleibt leer über Neustarts**, bis ein manueller Re-Edit.

PLAUSIBLE statt CONFIRMED: der katastrophale Ausgang braucht das verlierende
Interleaving *plus* eine bestimmte Save-Reihenfolge; gängige Ordnungen self-correcten
zu einem transienten Orphan.

**Fix:** Composite-Regeneration serialisieren — `kotlinx.coroutines.sync.Mutex` um
`regenerateFlattenedComposite()` über **beide** Caller (oder in `write()`/`clear()`
um `deleteAll()`+create+compress). Idealerweise zusätzlich in eine Temp-Datei
schreiben, verifizieren, atomar umbenennen und **erst dann** strikt-ältere
Composites löschen. Den Commit-Pfad gegen einen laufenden Backfill mutual-excluden.

---

### F2 — Dangling Composite-Pfad heilt nie selbst · `low` · CONFIRMED

`data/WallpaperRepositoryImpl.kt:192` (+ `WallpaperDelegate.maybeBackfillComposite:574`)

```kotlin
WallpaperState(
    layers = validLayers,                                   // ← per-Layer fileExists()-validiert (:178)
    flattenedWallpaperPath = preferences[KEY_WALLPAPER_FLATTENED_PATH],  // ← UNGEPRÜFT übernommen
)
```

Jede Layer-URI wird in `parseWallpaperState` per `wallpaperFileManager.fileExists()`
validiert und bei Fehlen aus dem State entfernt (`:178-186`). Der Composite-Pfad
**nicht** — er wird roh aus DataStore übernommen. Und `maybeBackfillComposite`
bricht bei `state.flattenedWallpaperPath != null` früh ab. Damit ist ein dangling
(fehlender) Composite-Pfad ein **permanenter** Zustand: der einzige
Regenerationsmechanismus ist blockiert, bis zum nächsten manuellen Edit-Commit.

Für sich genommen `low` (der Composite liegt in `filesDir`, wird nicht GC'd, nicht
gebackupt — eine *tatsächlich* fehlende Datei setzt F1 oder F4 voraus). Aber F2
verwandelt beide von **transient** in **permanent** — das ist der Hebel, der die
Kette gefährlich macht.

**Fix:** Composite-Pfad in `parseWallpaperState` per `fileExists()` prüfen und bei
Fehlen nullen (spiegelt die Layer-Validierung), **oder** Backfill auf
`path == null || file missing` gaten.

---

### F3 — „Wallpaper entfernen" räumt den Composite nicht ab · `low` · CONFIRMED

`ui/home/wallpaper/WallpaperCompositeCache.kt:46` + `data/WallpaperRepositoryImpl.kt:356`

Zwei Hälften desselben Leaks auf dem User-Clear-Pfad (`onClearWallpaper`):

- **On-disk:** `clearWallpaper()` (`:356`) entfernt nur DataStore-Keys.
  `compositeStore.clear()` ist **nur** in `purgeRepository()` (`:382`) verdrahtet,
  nicht im User-Clear-Pfad. `clearAll()`/`gcOrphans` laufen über `filesDir/wallpapers/`,
  **nie** über `filesDir/wallpaper_composite/`.
- **In-Memory:** `WallpaperCompositeCache` hat nur `get`/`put`, kein `invalidate()`;
  `get()` droppt die Referenz nur bei `isRecycled`.

Folge: Nach „Wallpaper entfernen" bleibt die `composite_*.webp` auf Disk verwaist
**und** die ~10 MB HARDWARE-Bitmap app-scoped resident, bis ein späterer `write()`
sie ersetzt oder der Prozess stirbt. State wird NONE → HideAll, also fragt nichts
den Composite erneut an → nichts räumt ihn ab.

Beschränkt (eine Datei — `write()` löscht jede vorige; ein Bitmap — akkumuliert
nicht), daher `low`. Das Design akzeptiert bewusst *ein* gehaltenes Composite-Bitmap
(§5) — der Defekt ist allein, es zu halten, **während nichts angezeigt wird**.

**Fix:** Im Clear-Pfad (`clearWallpaper` / `onClearWallpaper`) `compositeStore.clear()`
(IO-wrapped, wie in `purgeRepository`) aufrufen **und** ein `@Synchronized clear()` /
`invalidate()` zu `WallpaperCompositeCache` hinzufügen, auf den Clear- (und Reset-/
Purge-)Pfaden aufgerufen.

---

### F4 — `compress()`-Boolean verworfen · `low` · PLAUSIBLE

`data/wallpaper/WallpaperCompositeStore.kt:51`

```kotlin
FileOutputStream(file).use { out ->
    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)  // ← Rückgabe ignoriert
}
Uri.fromFile(file).toString()  // ← non-null Pfad auch bei compress()==false
```

`deleteAll()` läuft **vor** dem Write, und der Boolean-Rückgabewert von `compress()`
wird verworfen. Gibt `compress` `false` zurück **ohne** zu werfen, entsteht ein
non-null Pfad auf eine korrupte/unvollständige Datei — `regenerateFlattenedComposite`
persistiert jeden non-null Pfad (`path ?: return` guardet nur `null`). Speist über
F2 in denselben permanent-blank-Zustand.

PLAUSIBLE: der Trigger braucht `compress()==false` *ohne* Exception — Storage-full-/
IO-Fehler auf dem `FileOutputStream` **werfen** `IOException` und werden vom
`Throwable`-Arm gefangen (→ `null`). Der reine `false`-Rückgabefall ist real, aber
schmal.

**Fix:** Rückgabe von `compress()` erfassen; bei `false` die Datei löschen und `null`
zurückgeben. Zusammen mit dem Temp-Datei-+-atomic-rename-Ansatz aus F1 lösbar.

---

## 2. Gegengeprüft & sauber (Auszug)

Explizit gesucht und **nicht** gefunden — die Achsen mit dem höchsten Bug-Potenzial
kamen sauber zurück:

- **Cache-Stale-Read.** Der versionierte Pfad (`composite_<ts>_<n>.webp`, jeder
  Flatten ⇒ neuer Name) macht Invalidation-by-replace strukturell korrekt: In-Memory-
  Cache und AUTO-Classifier (`distinctUntilChanged`) keyen auf den Pfad-String, ein
  neuer Composite ist ein natürlicher Miss. Keine „fixer Pfad → explizit invalidieren"-
  Kopplung mehr (§9.4a).
- **Key-Verwechslung Composite ↔ Single-Layer.** Der Cache-Gate
  (`renderingSingleImageNow()`, exkludiert Edit-Mode + Multi-Layer-ohne-Composite-
  Rebuild) cached nie eine Per-Layer-Edit-Bitmap unter einem Composite-Key.
- **Cancellation-Swallow.** `loadBitmapFromUri` ist ein synchroner Body mit
  `no suspension point`-Marker; `write()` hat den `CancellationException`-first-Arm
  vor dem `Throwable`-Arm. Kein bogus ACRA-Report auf latest-wins-Switch (der
  `4c09c30b`-Regressionstyp ist hier nicht präsent).
- **`catch(Exception)` an Allokationsgrenzen.** `write()` und `loadBitmapFromUri`
  fangen `Throwable` (OOM-inklusive) — die Exception-vs-Throwable-Breite stimmt.
- **Flatten-Parität.** Software-Composite vs. Per-Layer-Hardware-Render: keine
  Blend-/Alpha-/Transform-Order-Divergenz auf dem Review-Pfad (Parität separat
  instrumentiert, `WallpaperFlattenParityInstrumentedTest`).
- **Never-recycle-Invariante.** Weder View noch Cache recyceln Bitmaps; die
  replaced-out-Bitmap zeichnet bis zum nächsten Render weiter, GC reclaimt nach
  Release beider Seiten. F3 verletzt das nicht — es *versäumt* nur das Droppen der
  Referenz.

---

## 3. Fazit & empfohlener Zuschnitt

Der **Lesepfad** des Composite-Caches trägt — die Funde sitzen ausschließlich auf
der **Schreib-/Aufräum-Seite** und clustern kausal:

> **F1** (Race) bzw. **F4** (stiller Compress-Fehler) erzeugt einen verwaisten
> Composite-Pfad → **F2** (keine Selbstheilung) macht ihn permanent → leerer
> Homescreen über Neustarts.

**F1 + F2 + F4 als ein Fix** (`fix/composite-lifecycle-hardening`) — **UMGESETZT:**

- **F1** — `regenerateFlattenedComposite` (beide Caller, Commit + Backfill) läuft
  jetzt unter einem delegate-weiten `Mutex` (`compositeRegenLock`), der die ganze
  Kette flatten→write→persist serialisiert. Zusätzlich schreibt
  `WallpaperCompositeStore.write()` in eine **Temp-Datei**, benennt **atomar** um
  und löscht die alten Composites **erst danach** (`deleteAllExcept`) — ein
  fehlgeschlagener/überholter Write unlinkt nie die noch referenzierte Datei; die
  vorige bleibt als Fallback erhalten.
- **F2** — `parseWallpaperState` validiert den `flattenedWallpaperPath` jetzt per
  `fileExists()` (Uri-Overload, spiegelt die Layer-Validierung) und nullt einen
  fehlenden Pfad → der auf `path == null` gegatete Backfill regeneriert ihn. Damit
  ist ein dangling Composite **transient statt permanent** — das neutralisiert auch
  den schmalen Rest-Race von F1.
- **F4** — der `compress()`-Rückgabewert wird geprüft; `false` (ohne Throw) löscht
  die Temp-Datei und liefert `null`, statt einen Pfad auf eine korrupte Datei zu
  persistieren.

  Tests: `WallpaperCompositeStoreTest` (Temp/Rename/compress-Check + „failed write
  behält vorheriges Composite" als F1-Ordering-Regression), zwei neue
  `WallpaperRepositoryImplTest`-Fälle (Composite-Pfad behalten wenn Datei da /
  nullen wenn fehlt) **und** der eigentliche Mutex-Serialisierungstest in
  `WallpaperDelegateTest` („commit flatten waits for an in-flight backfill flatten
  (serialized by the regen mutex)"): eine `CompletableDeferred`-Gate parkt den
  Backfill-Flatten *innerhalb* des Locks, ein in dieses Fenster gefeuerter Commit
  darf seinen eigenen Flatten erst nach Lock-Freigabe starten. Rein JVM,
  deterministisch. — Anmerkung: der erste Anlauf hielt diesen Test für unmöglich
  (angeblich verbietet die MockK-Konvention `coAnswers`); das war eine *veraltete*
  Konventions-Zeile — `coAnswers` existiert auf MockK 1.14.11 und ist genau der
  suspendierende Answer-Scope für die Gate. `TESTING_CONVENTIONS.kt` wurde
  entsprechend korrigiert. Rule 10 musste dafür **nicht** gedehnt werden: der Test
  ist reine Coroutine-Logik, kein Gerät nötig.

**F3 separat** als kleiner Cleanup (`compositeStore.clear()` im User-Clear-Pfad +
`invalidate()` im Cache) — unabhängig, kein Korrektheitsrisiko, nur Ressourcen-Hygiene.
**Noch offen.**

Alle vier sind Mehr-Datei-Changes über `:app`/`:data` → nach Projektkonvention auf
einem eigenen Branch.
