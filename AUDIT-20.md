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
> **Status: ALLE VIER GEFIXT.** F1 + F2 + F4 auf Branch
> `fix/composite-lifecycle-hardening` (gemerged), F3 auf Branch
> `fix/composite-clear-leak`. Vier Funde, fast alle in **einem
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
>
> **Follow-up 2026-08-17** (gegen `main` @ `11ef0cf7`, *nach* dem F1–F4-Fix): ein
> zweiter Multi-Agent-Review desselben Pfads (6 Dimensionen, adversariale
> Verifikation je Fund, 15 Agents) fand **drei neue Punkte** — alle wieder im
> **Composite-Lebenszyklus** (Schreib-/Aufräum-Seite), keiner im Lesepfad. Siehe
> **§4**. Der ebenfalls erneut bestätigte TEMP-Toast wird weiterhin bewusst
> ausgeklammert (s. o.).

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
| **F3** | Ressourcen-Leak | `WallpaperCompositeCache` (`:46`) + `clearWallpaper()` (`WallpaperRepositoryImpl:356`) | „Wallpaper entfernen" räumt weder die on-disk `composite_*.webp` noch die ~10 MB In-Memory-Bitmap ab (`compositeStore.clear()` nur in `purgeRepository`, kein `invalidate()` im Cache) | `low` | CONFIRMED | ✅ GEFIXT |
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

**F3 separat** als kleiner Cleanup (`fix/composite-clear-leak`) — **UMGESETZT:**

- **On-disk:** `WallpaperRepositoryImpl.clearWallpaper()` löscht jetzt auch das
  abgeleitete Composite (`compositeStore.clear()`, IO-wrapped) — spiegelt
  `purgeRepository`. Greift auch auf dem Backup-Restore-Clear-Pfad (altes Composite
  weg, das restaurierte Multi-Layer-State bekommt via F2-Backfill ein frisches).
- **In-Memory:** neues `WallpaperCompositeCache.invalidate()` (`@Synchronized`,
  droppt nur die Referenz — never-recycle-Invariante bleibt). Aufgerufen in
  `HomeFragment.updateWallpaper` sobald `!state.hasWallpaper` — **ein** Chokepoint,
  der User-Clear *und* Factory-Reset abdeckt (Reset re-emittiert NONE ohne
  Prozess-Neustart, siehe `SettingsViewModel.onFactoryResetConfirmed`).

  Tests: neuer `WallpaperCompositeCacheTest` (get/put/recycle + `invalidate`), plus
  `WallpaperRepositoryImplTest`-Fall „clearWallpaper also clears the on-disk
  composite". Der Ein-Zeilen-Trigger in `HomeFragment` (`!hasWallpaper` →
  `invalidate`) bleibt als dünner View-Glue ungetestet (Rule 10) — die Cache-Logik
  selbst und der Disk-Clear sind gepinnt.

Alle vier sind Mehr-Datei-Changes über `:app`/`:data` → nach Projektkonvention auf
einem eigenen Branch.

---

## 4. Follow-up-Review (2026-08-17, post-Fix @ `11ef0cf7`)

> Zweiter Durchlauf desselben Composite-Flatten-&-Cache-Pfads, **nachdem** F1–F4
> gemerged waren. Methode: 6 parallele Review-Linsen (Memory/Bitmap-Lifecycle,
> Concurrency/Cancellation, Cache-Kohärenz, State/Persistenz, Compositing-Korrektheit,
> Regel-Konformität), **jeder Fund adversarial zum Widerlegen** an eine zweite Instanz
> gegeben, dann die Substanz-Funde selbst am Code nachgelesen (15 Agents). Der Lesepfad
> und die never-recycle-Invariante hielten erneut sauber; alle drei neuen Funde sitzen
> wieder auf der **Schreib-/Aufräum-Seite** des Composites. F5 ist eigenständig
> (Dispatcher-Bug), **F6 + F7 teilen eine Wurzel**: der Composite-Dir-Lebenszyklus
> (`clear` / überholter `write`) ist **nicht** mit `compositeRegenLock` serialisiert.

| # | Cluster | Ort | Was | Severity | Verdict | Status |
|---|---|---|---|---|---|---|
| **F5** | Dispatcher | `WallpaperDelegate.regenerateFlattenedComposite` (`:609`) | Single-Layer-/NONE-Branch ruft `compositeStore.clear()` (blockierendes `listFiles()`+`delete()`) direkt auf dem **Main-Dispatcher** — die beiden Schwester-Call-Sites hoppen nach `ioDispatcher` | `med` | CONFIRMED | ✅ GEFIXT |
| **F6** | Composite-Dir-Lifecycle | `WallpaperRepositoryImpl.clearWallpaper` (`:390`) + `WallpaperDelegate.onClearWallpaper` | Clear-Pfad nimmt **nicht** `compositeRegenLock` → ein paralleler lazy Backfill schreibt seinen Composite **nach** dem Clear zurück → verwaiste `composite_*.webp` (nur `wallpapers/` wird ge-gc't, nie das Composite-Dir) | `low` | CONFIRMED | 🔲 OFFEN |
| **F7** | Composite-Dir-Lifecycle | `WallpaperDelegate.regenerateFlattenedComposite` (`:619`) | `write()` → `deleteAllExcept` läuft **unbedingt vor** dem latest-wins-Guard (`:629`); Edit→Revert-auf-gleichen-State kann `state`→`path_A` referenzieren lassen, während Disk nur `path_B` hält | `low` | PLAUSIBLE | 🔲 OFFEN |

---

### F5 — `compositeStore.clear()` blockierend auf dem Main-Thread · `med` · CONFIRMED

`app/ui/main/delegate/WallpaperDelegate.kt:607-611`

```kotlin
private suspend fun regenerateFlattenedComposite(state: WallpaperState) = compositeRegenLock.withLock {
    if (!state.isMultiLayer) {
        compositeStore.clear()   // ← blockierendes dir().mkdirs() + listFiles() + delete()
        return@withLock
    }
    …
}
```

`regenerateFlattenedComposite` läuft auf dem **Main-Dispatcher**: `onCommitWallpaperEditMode`
(`:567`) startet es via `scope.launchSafe`, und `DelegateScope.launchSafe` macht
`coroutineScope.launch(mainDispatcher)` (`DelegateScope.kt:53`, `@MainDispatcher`).
`compositeRegenLock.withLock` wechselt den Dispatcher nicht. Der Multi-Layer-Zweig ist
unkritisch — `flatten`/`write` hoppen intern nach IO — aber der **Single-Layer-/NONE-Branch**
ruft `compositeStore.clear()` (non-suspend, synchrones `deleteAll()`) ohne `withContext`-Hop.

Die beiden identischen Schwester-Call-Sites machen es richtig: `WallpaperRepositoryImpl.clearWallpaper`
(`:389`) und `purgeRepository` (`:409`) wrappen `compositeStore.clear()` jeweils in
`withContext(ioDispatcher)` mit „blocking file deletion"-Kommentar.

**Failure-Szenario:** Nutzer geht auf einem Single-Layer-Wallpaper in den Edit-Mode und
committet (oder entfernt Layer bis auf einen). `!state.isMultiLayer` ⇒ synchrones
`listFiles()`/`delete()` im `filesDir/wallpaper_composite/` **auf dem Main-Thread** →
StrictMode `DiskWriteViolation` + potenzieller Jank.

**Fix:** `compositeStore.clear()` in `withContext(ioDispatcher)` wrappen — analog zu
`clearWallpaper`/`purgeRepository`. **UMGESETZT** (`fix/composite-clear-main-thread-io`):
der Single-Layer-Branch macht jetzt `withContext(ioDispatcher) { compositeStore.clear() }`.
Regressionstest `onCommitWallpaperEditMode clears the composite off the main dispatcher for
a single-layer state` in `WallpaperDelegateTest` — baselinet den io-Dispatch-Count nach
`start()` und prüft, dass der Commit einen weiteren Hop durchdrückt (`CountingDispatcher`-
Idiom, wie die AUDIT-9-#N1-Guards für `clearAll`/`deleteFile`). Rein JVM.

---

### F6 — Clear-Pfad nicht serialisiert → verwaister Composite · `low` · CONFIRMED

`data/WallpaperRepositoryImpl.kt:379-395` (`clearWallpaper`) + `WallpaperDelegate.onClearWallpaper`

`clearWallpaper()` (und sein `compositeStore.clear()`) läuft in `:data` und hat **keinen**
Zugriff auf den Delegate-`compositeRegenLock` — der Clear-Pfad nimmt den Lock also nicht.
Damit ist er gegen einen laufenden lazy Backfill nicht mutual-excluded.

**Failure-Szenario:** State ist Multi-Layer mit `flattenedWallpaperPath == null` (frische
Option-D-Installation oder Post-Restore) → `maybeBackfillComposite` hält den Lock und ist
mitten in `flatten → write` (~90 ms + N Decodes). Nutzer tippt „Wallpaper entfernen" →
`onClearWallpaper` → `clearWallpaper()` löscht das Composite-Dir. Der Backfill-`write`
landet **danach** und legt erneut eine `composite_*.webp` an. Der latest-wins-Guard
(`_wallpaperState.value == state` ist jetzt NONE) überspringt den DataStore-Save korrekt
→ Wallpaper taucht nicht wieder auf, **aber** die WEBP bleibt permanenter **Orphan**
(`gcOrphans` läuft nur über `wallpapers/`, nie über `wallpaper_composite/`), bis ein
späterer Multi-Layer-Commit sie zufällig per `deleteAllExcept` mit-abräumt.

Severity `low`: kein Crash, kein sichtbarer Fehler, kleiner Disk-Leak, self-heilt beim
nächsten Multi-Layer-Commit. Widerspricht aber der `compositeRegenLock`-KDoc-Zusage, dass
der Lock Disk-Zustand und persistierten Pfad konsistent hält.

**Fix:** Den Clear-Pfad in dieselbe Serialisierung ziehen — entweder `onClearWallpaper`
unter `compositeRegenLock` ausführen (Delegate-Ebene, wo der Lock lebt), oder den Backfill
per `backfillInProgress`/Cancel gegen einen laufenden Clear absichern. Alternativ akzeptieren
und in der KDoc dokumentieren + `gcOrphans` einmalig auch das Composite-Dir mitnehmen lassen.

---

### F7 — `deleteAllExcept` vor dem latest-wins-Guard · `low` · PLAUSIBLE

`app/ui/main/delegate/WallpaperDelegate.kt:618-631`

```kotlin
val path = try {
    compositeStore.write(bitmap)     // ← ruft intern deleteAllExcept(neu) — UNBEDINGT
} finally { bitmap.recycle() }
path ?: return@withLock
…
if (_wallpaperState.value == state) {         // ← latest-wins-Guard erst HIER
    saveWallpaperStateUseCase(state.withFlattenedWallpaperPath(path))
}
```

`write()` (bzw. sein `deleteAllExcept`, `WallpaperCompositeStore.kt:75`) unlinkt die alten
Composites **bevor** der Guard entscheidet, ob dieser Pfad überhaupt persistiert wird. Der
Guard vergleicht gegen den beim Start gecaptureten `state`, nicht gegen „ist seit meinem
Capture ein Composite gelandet".

**Failure-Szenario (Revert):** Regen R1(stateA) hält den Lock, flattet, schreibt `path_A`,
`state == stateA` ⇒ speichert `stateA.withPath(path_A)`. Der Nutzer editiert zu stateB und
**revert**iert vor R1-Ende zurück auf einen gleichen stateA. Ein danach eingereihter Backfill
R2(stateB) bekommt den Lock, schreibt `path_B` und `deleteAllExcept` unlinkt `path_A`; sein
Guard schlägt fehl (aktuell ist `stateA.withPath(path_A)`) → kein Save. Ergebnis: `state`
referenziert `path_A`, während Disk nur `path_B` hält.

Severity `low` / PLAUSIBLE: self-heilt über den **F2-Fix** (`validatedCompositePath` nullt
den fehlenden Pfad beim nächsten Read, `maybeBackfillComposite` re-armt) — Konsequenz ist ein
transienter Per-Layer-Fallback-Render, kein bleibender Blank. Verletzt aber, wie F6, die
Konsistenz-Zusage des Locks.

**Fix:** Zusammen mit F6 lösbar — den Composite-Dir-Lebenszyklus vollständig unter
`compositeRegenLock` serialisieren **und** `deleteAllExcept` erst *nach* dem latest-wins-Guard
ausführen (nur prunen, wenn der neue Pfad tatsächlich persistiert wird), sodass ein überholter
Write die noch referenzierte Datei nie unlinkt.

---

### Gegengeprüft & erneut sauber (Follow-up)

- **Bitmap-Lifecycle / never-recycle.** `bitmap.recycle()` im `finally` (`:621`), F3-`invalidate()`
  droppt nur die Referenz — kein still-referenziertes Recycle, kein Leak auf dem Lesepfad.
- **Cancellation-Rethrow.** `applyFullRebuild` und die Loader-Catches sind korrekt geguarded
  (`no suspension point`-Marker bzw. `CancellationException`-first) — kein neuer Swallow.
- **Compositing-Parität, Downsample-Math, Blend-Order.** Keine Divergenz Software↔Live gefunden.
- **Cache-Kohärenz.** Versionierter Pfad-Key trägt weiterhin; kein Stale-Read, keine
  Key-Verwechslung.

**Zuschnitt-Vorschlag:** F5 war ein isolierter Ein-Zeilen-Fix (IO-Wrap) — **UMGESETZT**
(`fix/composite-clear-main-thread-io`, s. F5). F6 + F7 gehören als **ein** Fix zusammen
(Composite-Dir-Lebenszyklus unter den Lock + Prune nach dem Guard); beide `:app`/`:data`-
übergreifend → eigener Branch. **F6 + F7 noch offen.**
