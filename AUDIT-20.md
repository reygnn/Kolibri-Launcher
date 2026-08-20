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
> **Widerrufen am 2026-08-18 (§6/F10):** aus dem einen Toast sind mit `e73f2096`
> zwei geworden (Warm + Single-Layer-Decode) — „separat als Ein-Zeiler" trägt nicht
> mehr, wenn die Zeile sich vermehrt, also ist er jetzt als **F10** geführt.
>
> **Follow-up 2026-08-17** (gegen `main` @ `11ef0cf7`, *nach* dem F1–F4-Fix): ein
> zweiter Multi-Agent-Review desselben Pfads (6 Dimensionen, adversariale
> Verifikation je Fund, 15 Agents) fand **drei neue Punkte** — alle wieder im
> **Composite-Lebenszyklus** (Schreib-/Aufräum-Seite), keiner im Lesepfad —
> **inzwischen alle gefixt** (F5: `fix/composite-clear-main-thread-io`; F6 + F7:
> `fix/composite-lifecycle-clear-prune`). Siehe **§4**. Der ebenfalls erneut
> bestätigte TEMP-Toast wird weiterhin bewusst ausgeklammert (s. o.).
>
> **Vierter Durchgang 2026-08-18** (gegen `main` @ `ebd13868`, Version 0.99.178 /
> code 198, *nach* dem v4-Umbau auf den reinen In-Memory-Composite): gezielter
> **manueller** Blick auf den Refill/Warm-Pfad — ausdrücklich **keine**
> Multi-Agent-Flächenabdeckung wie §1/§4/§5. Sechs Punkte, zum Zeitpunkt des
> Durchgangs **alle OFFEN** (F13/F14 inzwischen erledigt — s. §6), keiner
> ein Korrektheitsdefekt: **F10** (TEMP-Toasts, Release-Blocker), **F11**
> (Edit-Cancel ohne Warm-Trigger, Performance), **F12** (unerreichbarer
> Cache-Eintrag nach Auflösungswechsel, Speicher), **F13** (Single→Multi ist eine
> Einbahnstraße), **F14** (Layer-Alpha/Blend/Sichtbarkeit ohne UI) und **F15**
> (Backup-Restore füllt den Cache nicht nach — am Gerät beobachtet). Siehe **§6**.

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
| **F6** | Composite-Dir-Lifecycle | `WallpaperRepositoryImpl.clearWallpaper` (`:390`) + `WallpaperDelegate.onClearWallpaper` | Clear-Pfad nimmt **nicht** `compositeRegenLock` → ein paralleler lazy Backfill schreibt seinen Composite **nach** dem Clear zurück → verwaiste `composite_*.webp` (nur `wallpapers/` wird ge-gc't, nie das Composite-Dir) | `low` | CONFIRMED | ✅ GEFIXT |
| **F7** | Composite-Dir-Lifecycle | `WallpaperDelegate.regenerateFlattenedComposite` (`:619`) | `write()` → `deleteAllExcept` läuft **unbedingt vor** dem latest-wins-Guard (`:629`); Edit→Revert-auf-gleichen-State kann `state`→`path_A` referenzieren lassen, während Disk nur `path_B` hält | `low` | PLAUSIBLE | ✅ GEFIXT |

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

**Fix:** Den Clear-Pfad in dieselbe Serialisierung ziehen. **UMGESETZT**
(`fix/composite-lifecycle-clear-prune`): `onClearWallpaper` läuft jetzt unter
`compositeRegenLock` (Delegate-Ebene, wo der Lock lebt), sodass Clear und Regen
gegenseitig ausschließend sind. Zusätzlich setzt der Clear-Block optimistisch
`_wallpaperState.value = WallpaperState.NONE` (analog zu `onCancelWallpaperEditMode`s
synchronem Restore) — ein Regen, der auf dem Lock **wartete** und mit einem pre-Clear-State
gecaptured wurde, sieht danach NONE, sein latest-wins-Guard schlägt fehl und er löscht per
F7 seine **eigene** Datei, statt das entfernte Wallpaper mit orphaned Composite zu
resurrecten. Regressionstest `onClearWallpaper waits for an in-flight backfill then clears
and resets state to NONE` in `WallpaperDelegateTest` (Gate-Pattern wie der F1-Mutex-Test).

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

**Fix:** `deleteAllExcept` erst *nach* dem latest-wins-Guard ausführen. **UMGESETZT**
(`fix/composite-lifecycle-clear-prune`): `WallpaperCompositeStore.write()` prunt jetzt
**nicht** mehr selbst — es schreibt nur die neue versionierte Datei (Temp + atomarer Rename)
und lässt die vorige unangetastet. Das Aufräumen ist in zwei neue Store-Methoden entkoppelt,
die der Delegate **erst nach** dem Guard aufruft: bei einem WIN
`saveWallpaperStateUseCase(...)` **dann** `compositeStore.prune(path)` (droppt alle anderen
Composites + fegt stray `.tmp`); bei einem LOSS `compositeStore.delete(path)` (droppt nur die
**eigene** gerade geschriebene Datei). Damit unlinkt ein überholter/superseded Flatten nie die
Datei, die der aktuelle State referenziert. Tests: `WallpaperCompositeStoreTest` (write prunt
nicht mehr / `prune` behält nur die Kept-Datei + tmp-Sweep / `delete` trifft nur die genannte)
plus zwei `WallpaperDelegateTest`-Fälle (WIN → `prune`, kein `delete`; superseded → `delete`,
kein `prune`, kein Save). Store-`prune`/`delete` tragen den `CancellationException`-first-Arm
wie `write()` (Rule 11).

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
(`fix/composite-clear-main-thread-io`, s. F5). F6 + F7 gehörten als **ein** Fix zusammen
(Composite-Dir-Lebenszyklus unter den Lock + Prune nach dem Guard) — **UMGESETZT**
(`fix/composite-lifecycle-clear-prune`, s. F6/F7): `write()` wurde in Create (kein Prune) +
`prune`/`delete` (nach dem Guard) aufgeteilt, und `onClearWallpaper` läuft unter
`compositeRegenLock` mit optimistischem NONE. **Alle drei Follow-up-Funde (F5–F7) gefixt.**

---

## 5. Dritter Review (2026-08-18, post-Fix @ `177d8ead`)

> Dritter Durchlauf desselben Composite-Flatten-&-Cache-Pfads, **nachdem** F1–F7
> gemerged waren. Methode: 5 parallele Review-Linsen (Concurrency/Lock,
> Bitmap-/Memory-Lifecycle, Cache-Kohärenz/Persistenz, Flatten-Korrektheit +
> Classifier, Regel-Konformität), jede mit adversarialer Verifikation; die beiden
> überlebenden Funde danach selbst am Code nachgelesen (`renderingSingleImageNow`,
> `compositeStore.clear`-Call-Sites, `dirLock`-Abwesenheit, `hasWallpaper`-Semantik).
> Drei Linsen kamen **sauber** zurück (Memory/Bitmap-Lifecycle — never-recycle intakt,
> Flatten-Parität, Regel-Konformität). Beide neuen Funde sind **dieselbe Klasse** wie
> zuvor — Composite-Aufräumen, das einen in-flight Write/Decode rennt — auf zwei
> Kanten, die die F1–F7-Fixes nicht abdeckten. Beide `low`, beide **gefixt** auf
> `fix/composite-lifecycle-hardening-3`.

| # | Cluster | Ort | Was | Severity | Verdict | Status |
|---|---|---|---|---|---|---|
| **F8** | Composite-Dir-Lifecycle | `WallpaperCompositeStore` (kein interner Lock) + `WallpaperRepositoryImpl.clearWallpaper` (`:390`) / `purgeRepository` (`:413`) | `compositeRegenLock` ist app-seitig und privat im Delegate; zwei `:data`-Caller mutieren das Composite-Dir **ohne** ihn — `clearWallpaper()` (aus `BackupDataAssembler` beim Restore) und `purgeRepository()` (Factory-Reset), beide via `compositeStore.clear()`. Der `@Singleton`-Store hat **keine** eigene Serialisierung → ein Restore/Reset-Clear kann die Dateisystem-Ops eines in-flight Backfill-`write` interleaven; die Lock-KDoc behauptet fälschlich „serializes **every** composite-dir mutation" | `low` | CONFIRMED | ✅ GEFIXT |
| **F9** | Cache-Kohärenz | `HomeFragment.renderingSingleImageNow` (`:1554`) + `loadBitmapFromUri` (`:1586`) | Der Cache-PUT-Gate liest den **Live**-State zur Decode-Fertigstellung; für NONE liefert er `true` (`!isMultiLayer`). Ein Composite-Decode, der **nach** dem Wallpaper-Clear fertig wird (blockierender Decode, **kein** Suspension-Point vor dem `put`), re-inserted die verwaiste ~10 MB HARDWARE-Bitmap direkt **nachdem** `updateWallpaper` den Cache per F3-`invalidate()` geleert hat → F3 in diesem Race negiert | `low` | CONFIRMED | ✅ GEFIXT |

---

### F8 — `:data`-Clear/Purge umgeht `compositeRegenLock` · `low` · CONFIRMED

`data/wallpaper/WallpaperCompositeStore.kt` (kein interner Lock) + `data/WallpaperRepositoryImpl.kt:390` (`clearWallpaper`) / `:413` (`purgeRepository`)

`compositeRegenLock` ist ein privates `:app`-Feld von `WallpaperDelegate`; seine KDoc
sagte „**serializes every composite-dir mutation across its callers**". Das ist
überzogen: **zwei** `:data`-Pfade mutieren dasselbe Composite-Dir und können den
App-Layer-Lock strukturell nicht nehmen — `WallpaperRepositoryImpl.clearWallpaper()`
(erreicht aus `BackupDataAssembler:375` beim Backup-Restore) und `purgeRepository()`
(aus `ResetRepositoryImpl:124` beim Factory-Reset), beide über
`compositeStore.clear() → deleteAll()`. Der `@Singleton WallpaperCompositeStore` trug
**keine** eigene Serialisierung (grep: null `Mutex`/`synchronized`), d. h. die
Serialisierung lag **allein** im Delegate-Lock, den diese Caller nicht erreichen.

**Failure-Szenario:** Multi-Layer-State mit `flattenedWallpaperPath == null` (frische
Option-D-Installation / Post-Restore) → `maybeBackfillComposite` hält
`compositeRegenLock`, ist mitten in `write` (Temp+Rename). Der Nutzer triggert einen
Backup-Restore → `clearWallpaper() → compositeStore.clear() → deleteAll()` fegt das
Composite-Dir, unserialisiert gegen den laufenden Backfill-`write`. Gewinnt der Backfill
danach seinen latest-wins-Guard, persistiert er `path_P` und `prune(P)` behält eine
bereits gelöschte Datei → DataStore zeigt ins Leere.

Severity `low` und self-heilend: der **F2-Fix** (`validatedCompositePath` nullt den
fehlenden Pfad beim nächsten Read, Backfill re-armt) verwandelt das in einen
transienten Per-Layer-Fallback, keinen bleibenden Blank. Der schärfere Defekt ist die
**falsche Lock-Zusage** in der KDoc.

**Fix (UMGESETZT, `fix/composite-lifecycle-hardening-3`):** Ein store-interner
`dirLock: Mutex` serialisiert jetzt **jede** Composite-Dir-Mutation
(`write`/`prune`/`delete`/`clear`) **layer-agnostisch** — genau die Empfehlung des
Funds. `clear()` wurde dafür `suspend` (alle drei Call-Sites laufen bereits in einem
`withContext(ioDispatcher)`-Frame, die F5-Dispatch-Zusage bleibt). Damit kann ein
Restore/Reset-Clear die Dateisystem-Ops eines in-flight `write` nicht mehr interleaven.
Die Lock-KDoc im Delegate wurde korrigiert (Scope-Absatz): sie deckt nur die
Delegate-eigenen Caller ab; die Dateisystem-Mutual-Exclusion gegen `:data` liefert eine
Ebene tiefer der Store-`dirLock`, der residuale Cross-Layer-Fall (Clear zwischen Write
und Prune) bleibt via F2 self-heilend. **Wichtig:** Der Store-Lock macht die
Einzeloperationen exklusiv — er macht die Delegate-Sequenz write→persist→prune **nicht**
atomar gegen einen Clear; das bleibt bewusst F2 überlassen. Test:
`WallpaperCompositeStoreTest` — „clear removes every composite, not just the latest"
(zwei Composites → `clear` → leer; pinnt das `suspend`-Clear).

---

### F9 — Cache-PUT re-füllt nach F3-`invalidate()` · `low` · CONFIRMED

`app/ui/home/HomeFragment.kt:1586` (`loadBitmapFromUri`) + `:1554` (`renderingSingleImageNow`)

Der Cache-`put` ist auf `renderingSingleImageNow()` gegated, das den **Live**-State
`viewModel.wallpaperState.value` zur Decode-Fertigstellung liest — nicht den State, zu
dem der dekodierte Key gehörte. Für NONE liefert es `true` (`!isMultiLayer` ist wahr, da
NONE keine Layer hat). `loadBitmapFromUri` ist synchron: zwischen dem blockierenden
Decode-Return und der `put`-Zeile liegt **kein** Suspension-Point, ein gecancelter Render
erreicht das `put` also trotzdem.

**Failure-Szenario:** Multi-Layer-Composite `P1` am Schirm, drawer→home startet den
Decode von `P1` (~90 ms blockierend). Mitten im Decode entfernt der Nutzer das Wallpaper:
`onClearWallpaper` setzt NONE, `updateWallpaper(NONE)` ruft `compositeCache.invalidate()`
(F3), dann cancelt der Scheduler den `P1`-Decode. Der gecancelte Coroutine-Frame läuft
den synchronen `loadBitmapFromUri` zu Ende: `renderingSingleImageNow()` liest jetzt
NONE → `true` → `compositeCache.put(P1, bitmapP1)` **nach** dem `invalidate`. Die
verwaiste ~10 MB HARDWARE-Bitmap bleibt resident bis zum nächsten `put` oder Prozess-Tod.
Kein Falsch-Render (Versionierung garantiert, dass `P1` nie wieder als Inhalt ausgeliefert
wird) — reiner Memory-Defekt, aber F3 ist im Race-Fenster still negiert.

**Fix (UMGESETZT, `fix/composite-lifecycle-hardening-3`):** `renderingSingleImageNow()`
verlangt jetzt zusätzlich `s.hasWallpaper` — NONE ⇒ `false`, das Fenster ist zu. Ein
echtes Single-Layer- oder Composite-Wallpaper hat `hasWallpaper == true` und passiert das
Gate unverändert. Ein-Zeilen-Guard; der Gate selbst ist privates View-Glue in
`HomeFragment` und bleibt untestet (Rule 10, wie der F3-`invalidate`-Trigger) — die
Cache-Logik (`WallpaperCompositeCache`) ist separat gepinnt.

---

### Gegengeprüft & erneut sauber (dritter Review)

- **Bitmap-Lifecycle / never-recycle.** Cache/View/Binder/Flattener halten die Invariante;
  das einzige `recycle()` (`WallpaperDelegate:641`) trifft das lokale SOFTWARE-Flatten-
  Output, nie gecacht/gezeichnet; HARDWARE-Composite nur auf der Live-View,
  `composeToBitmap` nur auf der detached Software-View; Allokationsgrenzen fangen `Throwable`.
- **Flatten-Parität / Classifier.** `drawLayers` als Single-Source-of-Truth für
  Alpha/BlendMode/Matrix über Live-`onDraw` **und** `composeToBitmap`; keine
  Z-/Blend-/Transform-Divergenz. Luminanz-Math sauber.
- **Regel-Konformität.** Kein bares `Timber.e` außerhalb der Crash-Infra; jeder breite
  `Throwable`-Catch im Suspend-Frame trägt einen `CancellationException`-first-Arm oder
  `no suspension point`-Marker (auch die neuen Store-`dirLock`-Bodies); Allokationsgrenzen
  `Throwable`; Toast via `showToastSafe`.
- **Cache-Kohärenz (Rest).** Versionierter Pfad-Key trägt weiter; kein Stale-Read, keine
  Key-Verwechslung Composite↔Single-Layer außer dem F9-NONE-Fenster.

**Zuschnitt:** F8 + F9 als **ein** Fix (`fix/composite-lifecycle-hardening-3`) — Store-
`dirLock` + KDoc-Korrektur (F8), `hasWallpaper`-Gate (F9). **Beide dritten Funde (F8, F9)
gefixt.** Der TEMP-Debug-Toast (`HomeFragment`, Commit `8faa14a3`) bleibt weiterhin bewusst
ausgeklammert (s. o.).

---

## 6. Vierter Durchgang — Refill-Pfad (2026-08-18, gegen `main` @ `ebd13868`)

> Vierter Blick auf denselben Pfad, diesmal **nach** dem v4-Umbau (kein On-Disk-
> Composite mehr — `WallpaperCompositeStore` ist gelöscht, die ganze F1–F8-Disk-Klasse
> damit gegenstandslos) und fokussiert auf den **Refill/Warm**: `maybeWarmComposite`
> (`WallpaperDelegate.kt:626-649`), `warmComposite` (`:669-745`) und die Lesseite in
> `HomeFragment` (`:1535-1617`).
>
> **Methode — bewusst anders als §1/§4/§5:** *kein* Multi-Agent-Review, sondern ein
> manuelles Nachlesen der Warm-Trigger, ihrer Dispatcher-Zusagen und der
> Cache-Lebensdauer. Entsprechend schmaler im Anspruch: das hier ist **keine**
> Flächenabdeckung wie die drei Reviews davor, sondern das Ergebnis eines gezielten
> Blicks auf die Refill-Kante. Wer Vollständigkeit will, muss die
> Multi-Agent-Methode erneut fahren.
>
> **Status (ursprünglich): alle sechs OFFEN** — auf Wunsch nur dokumentiert, nicht
> gefixt (die Arbeit lag gerade woanders). Kein Fund ist ein Korrektheitsdefekt: F10 ist
> ein Release-Blocker (Debug-UI), F11 und F15 kosten Performance, F12 Speicher, F13 ist
> eine Modell-Asymmetrie, F14 eine halbfertige Feature-Fläche.
>
> **Update 2026-08-20:** F14 **entschieden** (alle drei Layer-Regler bleiben UI-los,
> `ACCEPTED_LIMITATIONS.md` §5), F13 **behoben** (`toSingleLayer`-Collapse am
> Commit-Rand). Offen bleiben **F10** (Release-Blocker), **F11**, **F12**, **F15**.
>
> **F15 ist der einzige Fund dieses Abschnitts, der aus einer Geräte-Beobachtung
> stammt statt aus dem Lesen** — und er hat eine Leseanalyse widerlegt: die
> Restore→Warm-Kette ist verdrahtet und ich hatte daraus geschlossen, dass sie greift.
> Sie greift nicht. Für den Single-Layer-Fall ist der Grund inzwischen code-belegt
> (es gibt gar keinen Warm-Pfad), für den Multi-Layer-Fall ist er offen. Die Lehre
> steht in F15: eine geschlossene Aufrufkette ist kein Nachweis, dass sie läuft —
> und der fehlende Test „State-Emission ⇒ Warm" ist der Grund, dass die Frage
> überhaupt offen sein kann.

### 6.0 Refill-Matrix: welche Aktion braucht einen Nachfüller, und wer feuert ihn

Die Findings unten lesen sich einzeln wie Sonderfälle. Sie sind es nicht — es sind
Löcher in **einer** Abbildung: die Liste der Aktionen, nach denen der Cache neu
gefüllt werden muss, wird von der Liste der Trigger im Code nicht vollständig
gedeckt. Das Raster steht deshalb vor den Funden.

**Wann ein Nachfüllen nötig ist,** ergibt sich aus dem Key (`WallpaperCompositeKey.of`,
`:39-62`): `RENDER_BUDGET_VERSION`, `widthPx × heightPx`, und pro Layer in Reihenfolge
`imageUri, scale, translateX, translateY, alpha, blendModeName, isVisible,
captureSampleSize`. Ändert sich davon etwas, ist es ein **neuer Key** — also ein Miss,
kein Überschreiben.

**Wer nachfüllt,** sind genau zwei Stellen: der Warm im Delegate (`:722`, nur
`composite://`-Keys, nur Multi-Layer) und der Decode in `HomeFragment` (`:1603`, nur
`file://`-Keys, nur echte Single-Layer). Und drei Trigger rufen den Warm: die
State-Collect-Schleife (`:400`), der Config-Change (`:558`) und der Edit-Commit
(`:609`). Der Self-Reschedule im `finally` (`:638-646`) ist kein vierter Trigger,
sondern eine Wiedervorlage des ersten.

| Aktion | Warum neuer Key / leerer Cache | Trigger heute | Lücke |
|---|---|---|---|
| **Inhalt: Edit-Commit** (Layer hinzu/weg, Reihenfolge, Pan/Zoom) | Layer-Terme im Hash | Edit-Commit (`:609`) | — |
| **Inhalt: Edit-Cancel** | Key kann sich geändert haben, ohne dass der persistierte Wert es tut | *keiner* | **F11** |
| **Inhalt: „Hintergrund wählen"** | neue `file://`-URI | *kein Warm* — lazy beim nächsten Decode (`:1603`) | by design, aber siehe F15 |
| **Inhalt: Backup-Restore** | neuer State | Collect (`:400`) — greift laut Gerätebeobachtung nicht | **F15** |
| **Auflösung** (Rotation, Falten, Multi-Window) | `widthPx × heightPx` im Hash — **nur** für `composite://`; der Single-Layer-Key kennt keine Maße und überlebt eine Rotation als Treffer | Config-Change (`:558`) | im Edit-Mode ausgesetzt → **F11** |
| **Prozessneustart** | Cache ist rein in-memory (kein On-Disk-Composite seit v4) | erste Emission nach `start()` (`:400`) | — |
| **App-Update mit `RENDER_BUDGET_VERSION`-Bump** | Versions-Term im Hash — bewusst als natürlicher Miss statt Migration | braucht keinen eigenen: der nächste Trigger greift | — |
| **Fehlgeschlagener Warm** (partieller Flatten, OOM bei der HARDWARE-Copy) | kein Eintrag für den aktuellen Key | *keiner* — der Self-Reschedule feuert denselben Key bewusst nicht (Schleifenschutz, `:643`) | **F12** |

Was **keinen** Refill braucht, obwohl man es vermuten könnte: drawer→home (das ist der
Treffer, für den der Cache existiert), Edit-Mode betreten und verlassen (die View
rendert dort per-Layer, überschreibt den Eintrag aber nicht — `renderingSingleImageNow`
schließt Edit-Mode aus) und „Wallpaper entfernen" (invalidiert, danach gibt es nichts
zu füllen).

Das Muster hinter F11, F12 und F15 ist damit dasselbe: **der Warm ist an
Zustandsänderungen gehängt, die über DataStore laufen** — und jede Kante, die den Key
ändert, *ohne* eine Emission zu erzeugen (Cancel nach Rotation), oder die eine
Emission erzeugt, *ohne* dass der Warm greift (Restore), oder die den Eintrag verliert,
*ohne* dass sich etwas ändert (fehlgeschlagener Warm), fällt durch. Ein vierter Fund
derselben Klasse ist zu erwarten, solange die Abbildung nicht geschlossen ist; der
strukturelle Fix wäre, den Warm nicht an Emissionen, sondern an „aktueller Key ≠
gecachter Key" zu hängen und diese Prüfung an den Stellen zu fahren, an denen der
Cache tatsächlich gebraucht wird.
>
> **F13 und F14 kamen nicht aus dem Review, sondern aus dem Betrieb** — und beide aus
> derselben Beobachtung: der F10-Toast meldete nach einem Editor-Save „Composite" für
> ein gefühltes Einzelbild. Der naheliegende Verdacht (Edit-Mode konvertiert
> Single→Multi) war **falsch**; die Ursache ist die fehlende Rückrichtung beim
> Layer-Löschen (**F13**). Beim Prüfen von F13s Begründung — „ein Collapse wäre
> verlustbehaftet, weil Layer Alpha/Blend/Sichtbarkeit tragen" — stellte sich heraus,
> dass genau diese drei Eigenschaften **kein UI** haben (**F14**), die Begründung also
> auf einer Fähigkeit steht, die es in Produktion nicht gibt. Die Reihenfolge ist
> Absicht so festgehalten: F14 ist die Vorentscheidung für F13. Die Toast-Beschriftung
> selbst ist inzwischen korrigiert (`layerCount == 1` meldet als Single-Layer-Fill).

| # | Cluster | Ort | Was | Severity | Verdict | Status |
|---|---|---|---|---|---|---|
| **F10** | Debug-Residuum | `WallpaperDelegate.kt:733-740` + `HomeFragment.kt:1604-1606` | Die TEMP-Debug-Toasts auf jedem Cache-Fill sind **nicht mehr einer, sondern zwei** (Composite-Warm + Single-Layer-Decode); beide „remove later"-markiert, beide user-sichtbar in RELEASE | `med` (Release-Blocker) | CONFIRMED | ⛔ OFFEN |
| **F11** | Warm-Trigger | `WallpaperDelegate.onCancelWallpaperEditMode` (`:772-802`) vs. `onCommitWallpaperEditMode` (`:609`) | Der **Commit**-Pfad warmt explizit, der **Cancel**-Pfad nicht — verlässt man den Edit-Mode per Abbruch ohne persistierte Änderung, feuert keine DataStore-Emission und damit kein Warm | `low` | CONFIRMED | ⛔ OFFEN |
| **F12** | Cache-Residenz | `WallpaperCompositeCache` (`:46-50`, kein Key-Change-Drop) + `warmComposite`-Fehlerpfade (`:683-716`) | Nach einem Auflösungswechsel ist der gecachte Eintrag unter dem alten Key **unerreichbar**; scheitert der Warm für den neuen Key, bleibt die ~10 MB HARDWARE-Bitmap resident, ohne je wieder getroffen zu werden. Die Luminanz wird in genau diesen Fällen gedroppt (`dropLuminanceIfCurrent`), die Bitmap nicht | `low` | CONFIRMED | ⛔ OFFEN |
| **F13** | Modell / Repräsentation | `WallpaperState.withRemovedLayer` (`:233-238`) + `isMultiLayer` (`:157`) | Single→Multi ist eine **Einbahnstraße**: Layer runterlöschen kollabiert nie zurück, also ist ein State mit **genau einem** Layer weiterhin `isMultiLayer` und nimmt den Flatten- statt den Decode-Cache-Pfad. Ein pauschaler Collapse wäre verlustbehaftet (`alpha`/`blendModeName`/`isVisible`/`label` existieren nur pro Layer) — was durch F14 aktuell allerdings hypothetisch ist | `low` | CONFIRMED | ✅ BEHOBEN (2026-08-20): bedingter `toSingleLayer` am Commit-Rand |
| **F15** | Warm-Trigger | `WallpaperDelegate.maybeWarmComposite` (`:629`) + `HomeFragment.loadBitmapFromUri` (`:1603`) | **Backup-Restore aktualisiert den Cache nicht.** Am Gerät beobachtet: nach einem Restore mit Wallpaper wird erst beim nächsten drawer→home nachgefüllt. Code-verifiziert für den Single-Layer-Fall — der Warm ist multi-layer-only, für ein Single-Layer-Wallpaper existiert **kein** proaktiver Pfad, der Fill hängt am nächsten Decode. Multi-Layer-Fall: Ursache offen | `med` | CONFIRMED (Beobachtung) | ⛔ OFFEN |
| **F14** | Halbfertige Feature-Fläche | `WallpaperDelegate.kt:993-1006` (Setter) + `WallpaperLayer.kt:112-125` (`AVAILABLE_BLEND_MODES`) | Layer-**Alpha**, **Blend-Modus** und **Sichtbarkeit** sind modelliert, persistiert, backup-fest und gerendert — aber **kein UI ruft die Setter auf**. In Produktion ist damit jeder Layer `alpha == 1f` / `blendModeName == null` / `isVisible == true`; die 12 Blend-Modi haben null Konsumenten. Trägt bereits Folge-Argumentation (F13, `ACCEPTED_LIMITATIONS.md` §1), die ihre Verfügbarkeit voraussetzt | `low` | CONFIRMED | ✅ ENTSCHIEDEN (2026-08-19): alle drei UI-los, Editor transform-only (§5); Modell-Felder bleiben dormant |

---

### F10 — Zwei TEMP-Debug-Toasts statt einem · `med` (Release-Blocker) · CONFIRMED

`app/ui/main/delegate/WallpaperDelegate.kt:733-740` + `app/ui/home/HomeFragment.kt:1604-1606`

Der im Kopf dieses Dokuments dreimal bewusst ausgeklammerte TEMP-Toast (ursprünglich
`8faa14a3`, ein Site) hat sich mit Commit `e73f2096` auf **zwei** Sites vermehrt — und
genau deshalb steht er jetzt hier drin: „separat als Ein-Zeilen-Blocker behandeln"
funktioniert nicht mehr, wenn die Zeile sich vermehrt.

```kotlin
// WallpaperDelegate.warmComposite — nach dem key-gated put
scope.sendEvent(
    UiEvent.ShowToastFromString("Composite cache filled (${metrics.widthPixels}x${metrics.heightPixels})")
)

// HomeFragment.loadBitmapFromUri — nach dem Single-Layer-put
view?.post { context?.showToastSafe("Single-layer cache filled") }
```

Beide sind unbedingt (kein `BuildConfig.DEBUG`-Gate), beide mit hartkodiertem
englischem String (also auch an der Localization-Parität vorbei, die für echte
UI-Strings gilt), beide in RELEASE sichtbar. Der Delegate-Toast feuert bei jedem
Cold-Start-Warm, jedem Edit-Commit und jeder Rotation; der HomeFragment-Toast bei
jedem Single-Layer-Erstdecode.

Fachlich waren sie das richtige Instrument — die Frage „wie oft wird der Composite
tatsächlich neu geflattet?" ließ sich ohne Perfetto-Trace sonst nicht beantworten, und
die Auflösung im Text trennt rotationsgetriebene Refills von den übrigen. Sie sind
nur nie wieder rausgeflogen.

**Fix:** Beide Sites samt Kommentarblock entfernen. Wenn das Signal weiter gebraucht
wird, ist die Trace-Sektion aus `54815cee` (`LaunchTrace.WALLPAPER_WARM`) der
dauerhafte Ersatz — sie misst dasselbe, ohne UI. **Vor dem nächsten Release
erledigen.**

---

### F11 — Kein Warm-Trigger beim Edit-Abbruch · `low` · CONFIRMED

`app/ui/main/delegate/WallpaperDelegate.kt:772-802` (Cancel) vs. `:588-611` (Commit)

`maybeWarmComposite` hat genau drei Trigger: die State-Collect-Schleife (`:400`), den
Config-Change (`:558`) und den Edit-**Commit** (`:609`). Der Edit-**Cancel** ist in
dieser Liste nicht — er restauriert den Snapshot synchron (`:778`) und persistiert ihn
asynchron (`:792`), ruft aber selbst kein `maybeWarmComposite`.

Meist heilt das von allein: schrieb die Session Layer-Änderungen nach DataStore,
erzeugt das Zurückschreiben des Snapshots eine Emission → Collect → Warm. Das Loch ist
der Fall, in dem der persistierte Wert sich **nicht** ändert, während der Cache-Key
sich sehr wohl geändert hat:

**Failure-Szenario:** Multi-Layer-Wallpaper, Nutzer geht in den Edit-Mode und **dreht
das Gerät**. `onConfigurationChanged` → `onDisplayConfigChanged` → `maybeWarmComposite`
kehrt sofort zurück (`if (_isWallpaperEditMode.value) return`, `:628` — korrekt, die
Layer sind mitten in Änderung). Der Nutzer bricht die Session ohne Änderung ab: der
Snapshot ist identisch mit dem persistierten State, also keine Emission, also kein
Warm. Ergebnis: der Composite bleibt für die **neue** Auflösung kalt, bis irgendeine
andere State-Emission oder eine weitere Rotation kommt. Jedes drawer→home rendert
solange den Per-Layer-Pfad — laut Perfetto-Messung ~70–90 ms und ~2–3 verworfene
Frames pro Rebuild, also genau der Kostenpunkt, für den der Cache existiert.

Kein Korrektheitsfehler (der Per-Layer-Pfad ist bei jeder Auflösung richtig), rein
Performance — daher `low`.

**Fix:** `maybeWarmComposite(snapshot)` am Ende von `onCancelWallpaperEditMode`
aufrufen, symmetrisch zum Commit. Es ist bereits idempotent (Cache-Hit ⇒ No-Op,
Single-Flight über `backfillInProgress`), der Aufruf kostet im Normalfall also nichts.
Sauberer wäre, den Warm generell an „Edit-Mode verlassen" statt an die beiden Exits
einzeln zu hängen — dann kann kein dritter Exit-Pfad ihn erneut vergessen.

---

### F12 — Unerreichbarer Eintrag nach Auflösungswechsel bleibt resident · `low` · CONFIRMED

`app/ui/home/wallpaper/WallpaperCompositeCache.kt:46-50` + `WallpaperDelegate.warmComposite:683-716`

Der Cache ist Single-Entry und key-versioniert: ein Auflösungswechsel ändert den Key
(`WallpaperCompositeKey.of(state, w, h)`), der alte Eintrag ist ab sofort ein
garantierter Miss. Ersetzt wird er aber **nur** durch einen erfolgreichen `put` — es
gibt keinen Drop-on-Key-Change:

```kotlin
@Synchronized
fun put(path: String, decoded: DecodedWallpaperBitmap) {
    cachedPath = path
    cached = decoded          // ← der einzige Weg, wie ein alter Eintrag verschwindet
}
```

`warmComposite` hat drei Abbruchpfade, die **vor** dem `put` liegen: Flatten liefert
null (`:683`), HARDWARE-Copy/Luminanz wirft (`:702-709`, u. a. OOM), `hardware == null`
(`:713`). Alle drei rufen `dropLuminanceIfCurrent(key)` — die Composite-**Luminanz**
wird also sauber invalidiert, damit der AUTO-Classifier keinen Fremdwert weiterbenutzt
(Review #1). Für die ~10 MB **Bitmap** existiert diese Symmetrie nicht.

**Failure-Szenario:** Multi-Layer-Wallpaper, Composite für Portrait gecacht. Nutzer
dreht auf Landscape → neuer Key → `displayTargetFor` fällt auf den Per-Layer-Pfad,
Warm startet. Der Warm scheitert (partieller Flatten, oder die HARDWARE-Copy wirft
OOM — plausibel gerade unter Speicherdruck, und die Copy ist die dokumentierte
Allokationsgrenze). Jetzt hält der Cache eine Portrait-Bitmap unter einem Key, den
niemand mehr abfragt, und die Self-Reschedule-Regel feuert denselben Key bewusst nicht
erneut (`:643`, Schutz gegen die Endlosschleife). Die ~10 MB bleiben bis zur nächsten
Rotation, zum nächsten State-Wechsel oder zum Prozessende resident.

Exakt die F3-Klasse („residieren, während nichts es anzeigen kann"), nur über die
Auflösungs- statt die Clear-Kante — und mit demselben `low`-Argument: **ein** Bitmap,
akkumuliert nicht, self-heilt beim nächsten erfolgreichen Warm.

**Fix (zwei Varianten, bewusst nicht vorentschieden):**

- **Konservativ:** In den drei Fehlerpfaden von `warmComposite` neben
  `dropLuminanceIfCurrent(key)` auch `compositeCache.invalidate()` aufrufen — aber
  **nur** key-gated wie die Luminanz, sonst löscht ein überholter Warm den Eintrag
  eines neueren. Stellt die Bitmap/Luminanz-Symmetrie her.
- **Strukturell:** Dem Cache ein `invalidateIfNotKey(currentKey)` geben und es auf dem
  Config-Change-Pfad aufrufen — dann ist „Eintrag für tote Auflösung" gar nicht erst
  ein Zustand, unabhängig davon, ob der Warm gelingt. Kostet dafür den Fallback-
  Eintrag im Fenster zwischen Rotation und fertigem Warm (kein Render-Nachteil — der
  Eintrag ist in diesem Fenster ohnehin unerreichbar).

Die zweite Variante ist die ehrlichere Invariante, die erste der kleinere Diff.

---

### F13 — Single→Multi ist eine Einbahnstraße · `low` · CONFIRMED → BEHOBEN (2026-08-20)

`domain/model/WallpaperState.kt:233-238` (`withRemovedLayer`) + `:157` (`isMultiLayer`)

Aufgefallen über F10: nach einem Save im Wallpaper-Editor meldete der Toast
„Composite cache filled" für ein Wallpaper, das der Nutzer als **ein Bild**
wahrnimmt. Der Verdacht „Edit-Mode konvertiert Single→Multi" ist beim Nachlesen
**zerfallen** — `onEnterWallpaperEditMode` (`WallpaperDelegate:576-581`) macht nur
Snapshot + Flag, und die einzige Konvertierung im ganzen `:app` ist
`onAddWallpaperLayer` (`:869`, `toMultiLayer()`), die erst beim tatsächlichen
Hinzufügen eines Layers feuert. Auch der Backup-Round-Trip erhält die Darstellung
(`BackupDataAssembler:122-135`: Single-Layer exportiert `wallpaperLayers = emptyList()`
plus Flachfelder). Ein Single-Layer-Wallpaper durch Edit + Save zu schicken lässt es
also Single-Layer.

Der echte Auslöser ist die fehlende Rückrichtung: `isMultiLayer` ist
`layers.isNotEmpty()`, und `withRemovedLayer` kollabiert beim letzten verbleibenden
Layer **nicht** zurück in die Single-Layer-Darstellung. Wer einen Layer hinzufügt
(→ 2 Layer) und dann wieder auf einen runterlöscht, hat dauerhaft einen
1-Layer-Multi-State. Der nimmt den Flatten-Pfad (Decode → Compose → HARDWARE-Copy,
Cache unter `composite://`) statt des Decode-Cache-Pfads (`file://`-Key), obwohl
inhaltlich ein Einzelbild vorliegt.

**Warum der Collapse bedingt ist — die Modell-Asymmetrie:**
`WallpaperLayerState` ist echt reicher als die Single-Layer-Felder von
`WallpaperState`. Ein Layer trägt `alpha`, `blendModeName`, `isVisible` und `label`;
die Single-Layer-Darstellung kennt nur
`imageUri`/`scale`/`translateX`/`translateY`/`captureSampleSize`. Ein pauschaler
Collapse würde Deckkraft, Blend-Modus und Sichtbarkeit stillschweigend verwerfen —
ein Layer auf `alpha = 0.5f` würde beim Löschen seines Nachbarn schlagartig voll
deckend.

**Die Einschränkung (F14):** genau diese drei Eigenschaften sind heute **nicht
einstellbar** — es gibt kein UI, das die Setter aufruft. In Produktion trägt damit
jeder Layer `alpha == 1f`, `blendModeName == null`, `isVisible == true`. Der
Verlust, gegen den die Einbahnstraße schützt, ist also aktuell **hypothetisch**;
der einzige reale Weg zu abweichenden Werten ist ein von Hand editiertes
Backup-JSON (der Importer liest die Felder). Das macht den bedingten Collapse
unten deutlich attraktiver, als die reine Modell-Betrachtung nahelegt: er würde
heute praktisch immer greifen und wäre trotzdem gegen den Backup-Edge korrekt.
Mit der F14-Entscheidung vom 2026-08-19 (alle drei Layer-Regler bleiben UI-los,
`ACCEPTED_LIMITATIONS.md` §5) ist dieser „hypothetisch"-Status jetzt dauerhaft —
kein UI wird je abweichende Werte erzeugen, der einzige reale Weg bleibt das
von Hand editierte Backup-JSON. Die frühere Gegenrechnung („sollte F14 ein
Alpha-/Blend-UI bringen, kippt es zurück") ist damit vom Tisch.

**Kosten:** schmal. Beide Pfade rendern korrekt und liefern eine Ein-Textur-Anbindung;
der Flatten kostet pro Refill eine zusätzliche Vollbild-Allokation plus Copy, und
Refills sind selten. Für den AUTO-Classifier ist der Composite-Weg sogar der genauere
(echte Composite-Luminanz statt `layers[0]`-Heuristik). Der einzige spürbare Effekt
war die falsche Toast-Beschriftung — behoben, indem `warmComposite` bei
`layerCount == 1` als Single-Layer-Fill meldet (die Mechanik bleibt der Flatten; der
Kommentar an der Stelle hält das fest).

**Behoben (2026-08-20) — Collapse in `toSingleLayer`.** Die
Einbahnstraße ist zu: neu `WallpaperState.toSingleLayer()` (Spiegelbild zu
`toMultiLayer`) kollabiert einen Multi-State mit genau einem Layer zurück in die
Single-Layer-Darstellung (No-op bei 0 oder 2+ Layern). `imageUri`/`scale`/
`translateX`/`translateY`/`captureSampleSize` mappen 1:1; `alpha`/`blendModeName`/
`isVisible` sowie `id`/`label` fallen weg — verlustfrei, weil diese Felder UI-los
sind (§5 / F14), **kein Backup Nicht-Default-Werte trägt** und sie ohnehin
stillgelegt werden (Keys via „Speicher aufräumen" gelöscht). Der Collapse ist
deshalb **unbedingt**; ein Schlicht-Guard (`alpha == 1f` …) ist nicht nötig.

**Abweichung von der ursprünglich skizzierten Stelle:** der Collapse sitzt **nicht**
in `withRemovedLayer`, sondern am **Commit-Rand** (`onCommitWallpaperEditMode`). In
`withRemovedLayer` würde er beim Sprung 2→1 **mitten in der Edit-Session** feuern und
die noch im Multi-Modus laufende View gegen einen bereits-Single-Layer-State
desyncen. Am Commit normalisiert er an der Persistenz-Grenze; `commit()` wendet
synchron an, daher sieht der anschließende Composite-Warm-Snapshot schon den
Single-Layer-State und überspringt den Flatten korrekt. `toMultiLayer` konvertiert
beim nächsten „Layer hinzufügen" ohnehin zurück, der Nutzer wird nicht eingesperrt.

**Tests:** `WallpaperStateTest` (neu, `:domain`) pinnt Guard, Feld-Mapping,
Round-Trip und No-ops **beider** Richtungen — inkl. der ersten dedizierten
`toMultiLayer`-Abdeckung; zwei `WallpaperDelegateTest`-Fälle pinnen Collapse+Persist
am Commit und „Non-plain bleibt multi".

*Die frühere Toast-Notiz oben (F10) bleibt gültig — die Single-Layer-Meldung bei
`layerCount == 1` ist jetzt zusätzlich dadurch abgesichert, dass ein schlichter
1-Layer-State gar nicht mehr im Multi-/Flatten-Pfad landet.*

**Der Rückweg existiert heute schon:** „Hintergrund wählen" setzt via
`SetWallpaperImageUseCase` einen frischen Single-Layer-State und verwirft den
Layer-Stack.

---

### F15 — Backup-Restore aktualisiert den Cache nicht · `med` · CONFIRMED (Beobachtung)

`ui/main/delegate/WallpaperDelegate.kt:626-649` (`maybeWarmComposite`) +
`ui/home/HomeFragment.kt:1590-1607` (`loadBitmapFromUri`)

**Beobachtet am Gerät** (nicht aus einem Review-Durchlauf): nach einem Backup-Restore
mit Wallpaper bleibt der Cache leer; nachgefüllt wird erst beim nächsten Wechsel
AppDrawer → Homescreen. Damit trägt der erste Home-Render nach dem Restore die volle
Decode-/Flatten-Last, statt sie im Hintergrund vorweggenommen zu bekommen — genau
das, wofür der Warm existiert.

**Code-verifiziert (Single-Layer-Fall).** Den Cache füllen genau zwei Stellen: der
Warm im Delegate (`:722`) und der Decode in `HomeFragment` (`:1603`). Der Warm ist
**multi-layer-only** — `maybeWarmComposite` steigt bei `!state.isMultiLayer` sofort
aus (`:629`). Für ein Single-Layer-Wallpaper gibt es also gar keinen proaktiven Pfad:
der Eintrag kann frühestens entstehen, wenn wirklich dekodiert wird, und das ist der
nächste Rebuild. Restauriert der Nutzer ein Backup mit einem Einzelbild-Wallpaper,
ist „erst beim nächsten Render" damit **kein Fehler im Trigger, sondern das Fehlen
eines Triggers**. Das deckt die Beobachtung vollständig ab, wenn das restaurierte
Wallpaper einlagig war.

**Offen (Multi-Layer-Fall).** Für einen mehrlagigen Restore ist die Kette
nachweisbar verdrahtet — `saveWallpaperStateForRestore` (`BackupDataAssembler:423`)
→ `saveWallpaperState` → dieselbe DataStore-Instanz, die
`observeWallpaperStateUseCase()` liest → der Collect im Delegate (`:391-401`, im
**viewModelScope** via `LauncherViewModel.init:313`, nicht view-lifecycle-gebunden)
→ `maybeWarmComposite`. Warum sie in der Praxis trotzdem nicht greift, ist **nicht
geklärt**. Kandidaten, keiner davon bestätigt:

1. **MainActivitys ViewModel überlebt den Settings-Ausflug nicht.** Der Restore läuft
   in `BackupFragment` unter der separaten SettingsActivity; wird MainActivity im
   Hintergrund abgeräumt, läuft kein Collect und der Warm fällt auf den nächsten
   MainActivity-Start — also faktisch auf „Kaltstart".
2. **Der Warm läuft, aber sein key-gated `put` greift nicht** (`:721`) — etwa weil
   `compositeKey` auf beiden Seiten aus unterschiedlichen `Resources` kommt: der
   Delegate hält den Application-Context, `HomeFragment` einen Activity-Context. Die
   §3a-Zusage „eine Metrik-Quelle" ist damit nur solange erfüllt, wie beide dieselben
   Werte liefern (Multi-Window / Split-Screen sind der Zweifelsfall).
3. **Der Flatten scheitert still** und F12 greift (kein Retry für denselben Key).

**Nichts davon ist getestet.** `WallpaperDelegateTest` hat drei Warm-Tests, alle
negativ (`does not warm a single-layer wallpaper`, `does not warm when the composite
is already cached`, `a failed warm drops the composite luminance`) — es gibt **keinen**
Test „State-Emission ⇒ Warm". Genau diese Lücke ist der Grund, warum die Frage
überhaupt offen sein kann.

**Nächster Diagnoseschritt (bevor irgendetwas gefixt wird):** unterscheiden, ob das
restaurierte Wallpaper ein- oder mehrlagig war. Der TEMP-Toast (F10) trennt beide
Fälle inzwischen sprachlich, und der UiEvent-Kanal ist ein `Channel(BUFFERED)`
(`BaseViewModel:41`) — ein Toast aus dem Hintergrund geht also nicht verloren,
sondern erscheint beim Zurückkehren. Bleibt er bei einem **mehrlagigen** Restore
auch dann aus, ist Kandidat 1–3 zu klären; erscheint er dagegen erst beim
drawer→home, war es der Single-Layer-Fall und der Fix ist ein Warm-Pfad für
einlagige Wallpaper.

**Fix-Richtung (nicht umgesetzt):** der Warm braucht einen Single-Layer-Zweig. Heute
ist er an `isMultiLayer` gebunden, weil nur der Flatten dort etwas zu tun hat — für
ein Einzelbild wäre das Äquivalent ein Decode-und-Cachen unter dem `file://`-Key,
also genau das, was `HomeFragment.loadBitmapFromUri` lazy tut, nur vorgezogen. Das
berührt allerdings die PUT-Gate-Logik (`renderingSingleImageNow`) und damit den
Pfad, an dem AUDIT-20 bereits mehrfach iteriert hat — nicht nebenbei zu machen.

---

### F14 — Layer-Alpha / Blend-Modus / Sichtbarkeit haben kein UI · `low` · CONFIRMED

`ui/main/delegate/WallpaperDelegate.kt:993-1006` + `ui/home/WallpaperLayer.kt:112-125`

Beim Nachprüfen der F13-Begründung aufgefallen — auf die Frage, ob die drei
Layer-Eigenschaften überhaupt erreichbar sind. Sie sind es nicht. Die Feature-Kette
ist **bis auf das letzte Glied** vollständig gebaut:

- **Modell:** `WallpaperLayerState.alpha` / `.blendModeName` / `.isVisible` / `.label`.
- **Persistenz:** als Layer-JSON in DataStore; `BackupSerializer.parseWallpaperLayerFromJson`
  liest alle drei, `WallpaperLayerBackup.fromLayerState` schreibt sie — der
  Backup-Round-Trip erhält sie.
- **Render:** `ZoomableImageView.drawLayers` setzt `paint.blendMode` (`:945`), wendet
  Alpha an und überspringt unsichtbare Layer (`:939`); `RebuildPlan` (`:128`, `:174-175`)
  und `WallpaperViewBinder` (`:335`, `:444-445`) reichen die Werte durch. Der Flattener
  nutzt denselben `drawLayers`-Pfad, deshalb greift der
  `WallpaperFlattenParityInstrumentedTest` über alle Blend-Modi bei Teil-Alpha.
- **Domain-Setter:** `onSetLayerAlpha` / `onSetLayerBlendMode` / `onSetLayerVisibility`
  im Delegate, durchgereicht von `LauncherViewModel:416-418`.
- **UI:** — **fehlt**. Eine repo-weite Suche über `app/src/main` findet für die drei
  ViewModel-Methoden ausschließlich ihre Definitionen, keinen einzigen Aufrufer.
  `WallpaperLayer.AVAILABLE_BLEND_MODES` (12 Modi mit eigenen
  `translatable="false"`-Strings, explizit als „useful for a UI picker" dokumentiert)
  hat in Produktion **null** Konsumenten — ein KDoc-Verweis in `ZoomableImageView` und
  eine Referenz im `androidTest`. Die Setter stammen aus `f549fa06 wallpaper
  multilayer support` und haben nie einen Aufrufer bekommen.

Der Wallpaper-Editor bietet tatsächlich an (alle `viewModel.on*`-Aufrufe in
`WallpaperEditController` plus den Layer-Picker in `HomeFragment`): Layer hinzufügen,
Layer entfernen, Reihenfolge tauschen, Pan/Zoom pro Layer, Speicher-Info, Save/Cancel.
Kein Deckkraft-Regler, kein Blend-Picker, kein Sichtbarkeits-Toggle.

Kein Defekt im engeren Sinn — nichts ist kaputt, nichts lügt den Nutzer an. Was es
ist: eine **halb fertige Feature-Fläche**, die als fertig *aussieht*, wenn man das
Modell liest, und die deshalb bereits Folge-Argumentation trägt, die auf ihrer
Verfügbarkeit aufbaut — F13s „Collapse wäre verlustbehaftet" ist genau so ein
Argument, und `ACCEPTED_LIMITATIONS.md` §1 begründet die Gates des AUTO-Classifiers
(`layers[0].alpha ≥ 0.8` und Normal-Blend) ebenfalls gegen Werte, die heute niemand
erzeugen kann. Die Gates sind dadurch nicht falsch, nur derzeit gegenstandslos.

**Zwei mögliche Richtungen, bewusst offen gelassen:**

- **UI nachziehen** — Deckkraft-Slider + Blend-Picker (`AVAILABLE_BLEND_MODES` ist
  fertig verdrahtet) + Sichtbarkeits-Toggle im Editor. Die gesamte Kette dahinter
  steht bereits, inklusive Flatten-Parität und Composite-Key
  (`WallpaperCompositeKey:51-52` hasht `blendModeName` und `isVisible` bereits mit,
  ein Wechsel invalidiert den Cache also korrekt).
- **Zurückbauen** — die drei Setter, die Model-Felder und die zwölf Blend-Strings
  entfernen. Macht F13s Collapse trivial korrekt, kostet aber die Render-Fähigkeit
  und bricht Backups, die die Felder tragen.

Die Entscheidung ist eine Produkt-, keine Technikfrage — deshalb hier nur
dokumentiert.

**Entscheidung gefallen (2026-08-19):** **Alle drei** Eigenschaften bleiben
**UI-los**, der Editor bleibt **transform-only** (Hinzufügen / Entfernen /
Reihenfolge / Pan-Zoom). Festgehalten als `ACCEPTED_LIMITATIONS.md` §5, mit einem
gemeinsamen „unused by design"-Marker über den drei Settern in `WallpaperDelegate`
und der KDoc an `AVAILABLE_BLEND_MODES`. Die Modell-/Render-/Backup-Felder bleiben
dormant erhalten (Rückbau bräche Backups — die separate „Zurückbauen"-Frage).

Kern der Begründung (Details in §5): Die kanonische Kolibri-Collage entsteht aus
KI-Bildern, die vorab in den Schwester-Apps `darkroom/chiaroscuro` (deckende
AMOLED-Schwarz-Vollbilder) und `darkroom/greenwall` (Alpha-Freisteller, Motiv auf
gekeytem Transparent-Hintergrund) aufbereitet werden. Kolibris
`canvas.drawBitmap` auf `ARGB_8888` respektiert diese **Per-Pixel**-Transparenz
gratis — ein greenwall-Cutout komponiert korrekt über einem chiaroscuro-Bild ganz
**ohne** Layer-Regler. Damit sind alle drei redundant bzw. das falsche Werkzeug:

- **Alpha** — uniformer Regler; würde nur den ganzen Layer (inkl. Motiv) ghosten
  oder auf den allgegenwärtigen Schwarzflächen ein No-op sein. Die relevante
  Transparenz steckt im PNG, nicht im Layer.
- **Blend-Modi** — Editor-Scope (Difference/Exclusion/… gehören in eine
  Bild-App). Randnotiz: für AMOLED-Schwarz wäre *Screen* der relevante Knopf
  (Schwarz ist in Screen neutral) — aber genau das macht greenwalls
  Transparent-Export sauberer per Pixel.
- **Sichtbarkeit** — nicht mal billig verdrahtbar: Der aktive Layer wird **nur**
  per Canvas-Hit-Test gewählt, und der überspringt unsichtbare Layer
  (`ZoomableImageView.handleLayerTap:1165`). Ohne Layer-Liste/Cycler wäre ein
  versteckter Layer, der nicht gerade aktiv ist, unerreichbar — der Toggle würde
  ihn stranden. Ein „Hide"-Toggle bräuchte erst einen ganzen Layer-Navigator.

---

### Gegengeprüft & sauber (vierter Durchgang)

Zwei Verdachtsmomente sind beim Nachlesen **zerfallen** — hier notiert, damit sie nicht
ein viertes Mal aufgemacht werden:

- **`backfillInProgress` als unsynchronisiertes `Boolean`** (`:276`). Kein Defekt: die
  KDoc sagt Main-Thread-Confinement zu, und die Zusage hält über **alle drei** Trigger
  — der Collect (`:400`) läuft auf dem Main-Dispatcher, `onDisplayConfigChanged`
  (`:558`) wird aus `HomeFragment.onConfigurationChanged` (`:514`) gerufen, und der
  Commit-Pfad (`:609`) liegt in `scope.launchSafe`, das per `DelegateScope.kt:53`
  explizit `launch(mainDispatcher)` macht. Set **und** Reset (im `finally`, `:637`)
  liegen damit auf demselben Thread. Ein `@Volatile`/`AtomicBoolean` wäre hier
  Kosmetik, die eine Confinement-Zusage durch eine schwächere Zusage ersetzt.
- **Das doppelte `compositeCache.invalidate()`** (Delegate `:539` im Clear unter dem
  Regen-Lock, HomeFragment `:1492` bei `!state.hasWallpaper`). Keine Redundanz aus
  Versehen: beide Seiten tragen ihre eigene Begründung im Kommentar (Serialisierung
  gegen den Warm-`put` bzw. der eine View-Chokepoint, der User-Clear **und**
  Factory-Reset abdeckt, weil der Reset NONE ohne Prozess-Neustart re-emittiert).
  Das ist die belt-and-braces-Konstruktion aus F3/F6, nicht ihr Rest.

Ebenfalls unverändert sauber: der key-gated `put` (`:721`), das F9-`hasWallpaper`-Gate
in `renderingSingleImageNow` (`HomeFragment:1566-1570`), die never-recycle-Invariante
(das einzige `recycle()` trifft den SOFTWARE-Temp, `:711`) und die
Cancellation-Disziplin im Warm (`CancellationException`-first vor dem `Throwable`-Arm,
`:702-704`).
