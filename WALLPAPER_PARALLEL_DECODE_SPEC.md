# WALLPAPER_PARALLEL_DECODE_SPEC.md

**Status: ENTWURF v2 (2026-08-13), greenfield — noch nicht gebaut. Review-Runden 1+2
(je 3 Agents: Concurrency / House-Rules / Test-Impact) eingearbeitet; keine
offenen Korrektheits-Findings mehr, nur noch Build-/Test-Präzisierungen (Konstruktor-
Parameter-Reihenfolge §4.5, `2×cap`-Memory §5, `== cap`-Assertion §6.3).** Fokus:
den `drawer→home` Wallpaper-Pop-in verkürzen, indem die
Layer-Decodes in `WallpaperViewBinder.applyFullRebuild` **parallel** statt
sequentiell laufen (bounded auf ≤ 4 gleichzeitige Decodes), ohne die drei
Korrektheits-Verträge (Cancellation, Partial-Failure-Skip, Z-Order) zu brechen.
Erst reviewen, dann bauen; nicht auf `main` mergen, bevor §4 (Nebenläufigkeits-
Struktur) und §6 (Test-Impact — die bestehende Cancellation-Assertion *muss*
angepasst werden) bestätigt sind.

> Diese Spec ist die ausgebaute Form der Deferred-Note
> `project-wallpaper-parallel-decode-lever.md` (2026-08-12). Die Note begründet
> *ob* wir das tun (Re-Eval-Trigger: High-Layer-Count-Collagen); diese Spec
> beschreibt *wie*.

Schwester-Dokument: **`WALLPAPER_RENDER_RES_SPEC.md`** — derselbe Rebuild-Pfad
(`applyFullRebuild`), aber ein orthogonaler Hebel: dort geht es um die
*Render-Auflösung* pro Layer (Gesten-Jank), hier um die *Nebenläufigkeit* der
Decodes (Pop-in-Latenz). Die beiden Änderungen berühren dieselbe Funktion,
kollidieren aber nicht: Res-Spec verkleinert jeden einzelnen Decode, diese Spec
überlappt sie. Wer beide baut, baut Res-Spec zuerst (kleinere Bitmaps → der
Parallel-Memory-Peak in §5 wird günstiger).

Berührte Dateien: `WallpaperViewBinder.kt` (die einzige Produktions-Änderung),
`WallpaperViewBinderCancellationTest.kt` (Assertion-Anpassung + neuer Test),
optional ein neuer `WallpaperViewBinderParallelDecodeTest.kt`. `HomeFragment.kt`
(der `bitmapLoader`) und `BoundedBitmapDecoder.kt` bleiben **unverändert**.

---

## 0. Scope

**In Scope:** Die `for (spec in plan.layers)`-Schleife in
`WallpaperViewBinder.applyFullRebuild` von *decode-then-add, sequenziell* auf
*decode-all-parallel (bounded), then add-sequential-in-order* umbauen. Der
`SwitchToSingleLayer`-Pfad (`applySingleLayer`) lädt genau einen Layer und
bleibt unangetastet.

**Out of Scope:**
- Die Decode-Auflösung / das Render-Budget (das ist `WALLPAPER_RENDER_RES_SPEC`).
- Der `BitmapLoader`-Vertrag und der Dispatcher: die Decode-Off-Main-Garantie
  liegt im Loader (`HomeFragment` wrappt in `withContext(Dispatchers.IO)`); der
  Binder bleibt dispatcher-agnostisch und bekommt seine Parallelität *strukturell*
  (mehrere gleichzeitige `bitmapLoader.load`-Aufrufe), nicht durch Dispatcher-
  Wahl. Siehe §4.5 zur CPU-Kern-Frage.
- Das `WallpaperViewDiff`-Planning und `remapUpdatesToAddedLayers`: beide bleiben
  wortgleich (§4.4 erklärt, warum die ID-basierte Remap gerade *deshalb*
  existiert und die Parallelisierung deckt).
- Der `SwitchToSingleLayer`- und der `UpdatePropertiesOnly`-Pfad.

---

## 1. Problem (gemessen)

Aus der Deferred-Note (On-Device, Pixel 9a, 4-Layer-Wallpaper, Trace
`~/kolibri-traces/drawer-home-redraw_ba735840_2026-08-12.perfetto-trace`):

- Auf `drawer→home` wird die `HomeFragment`-View zerstört und das Wallpaper voll
  neu dekodiert (kein Bitmap-Cache) → `FullRebuild`.
- Der Redraw ist **~65–72 ms**, **decode-bound und off-main**.
- Die Layer dekodieren **sequenziell**: `wallpaper_decode` ~15–33 ms pro großem
  Layer; `wallpaper_add_layer` / `wallpaper_apply` < 1 ms Main.
- Die View bleibt `INVISIBLE` (Option-A-Flicker-Guard) bis `wallpaper_apply` sie
  enthüllt → das Wallpaper „ploppt" ~70 ms nach der Landung auf Home rein.

**Bei aktuellen Layer-Zahlen unmerklich → das ist der Grund, warum es *deferred*
ist.** Der Hebel skaliert linear: ~12 große Layer ≈ 250–400 ms wären sichtbar.
Re-Eval-Trigger (aus der Note): der Nutzer baut High-Layer-Count-Collagen. Diese
Spec liegt bereit, *bevor* dieser Trigger feuert.

---

## 2. Aktueller Code (Ist-Zustand)

`WallpaperViewBinder.applyFullRebuild` (WallpaperViewBinder.kt:283–316), der
relevante Kern:

```kotlin
val addedLayerIds = ArrayList<String>(plan.layers.size)

for (spec in plan.layers) {
    try {
        val loaded = bitmapLoader.load(spec.imageUri) ?: continue   // (A) suspending decode
        LaunchTrace.section(WALLPAPER_ADD_LAYER) {                   // (B) Main-thread view mutation
            view.addLayer(bitmap = loaded.bitmap, /* … z-order via add order … */)
        }
        addedLayerIds.add(spec.id)
    } catch (e: CancellationException) {
        throw e                                                     // (C) Rethrow per canonical
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error loading layer ${spec.id}")  // (D) skip layer, report
    }
}
```

Drei Eigenschaften, die der Umbau **erhalten** muss:

1. **(A) ist der einzige Suspend-Punkt.** `bitmapLoader.load` suspendiert (Loader
   wrappt `withContext(IO)`); (B) ist synchrone Main-Thread-Mutation. Deshalb ist
   `WallpaperViewBinder.kt` auf der `checkConventions` `cancel_files`-Whitelist —
   ein `catch (Throwable)` in einem Suspend-Frame *muss* Cancellation zuerst
   rethrowen, sonst frisst er den Latest-Wins-Cancel und feuert Bogus-ACRA
   (die Bugs `e1ef671d` / `fb62b88d` / `4c09c30b`).
2. **(C) vor (D):** Cancellation propagiert, echte Fehler werden zu „skip".
3. **Z-Order = Add-Reihenfolge.** `view.addLayer` stapelt in Aufruf-Reihenfolge;
   die Schleife läuft `plan.layers` in Index-Reihenfolge → Layer i liegt unter
   Layer i+1. Ein übersprungener Decode (`?: continue`) verschiebt alle folgenden
   Positionen — deshalb existiert `remapUpdatesToAddedLayers` (§4.4).

---

## 3. Ansatz: Zwei-Phasen — bounded parallel decode, dann sequential ordered add

Der Kern der Umstellung ist die Trennung der zwei Verantwortlichkeiten, die die
Schleife heute verzahnt:

- **Phase 1 (parallel, off-main):** Alle Layer dekodieren, *nebenläufig*, aber
  **bounded auf ≤ `MAX_PARALLEL_DECODES` (= 4)** gleichzeitige Decodes. Jeder
  Decode kapselt seinen eigenen Cancellation-Rethrow und seinen eigenen
  Fehler-zu-Wert-Fang. Ergebnis: eine Liste in **`plan.layers`-Reihenfolge**
  (nicht in Fertigstellungs-Reihenfolge).
- **Phase 2 (sequenziell, Main):** Die Ergebnisse **in Plan-Reihenfolge**
  durchgehen und die überlebenden Layer mit `view.addLayer` in die View stapeln.
  Reine View-Mutation, kein Suspend-Punkt. Z-Order bleibt exakt erhalten, weil
  wir die *nach Index geordnete* Ergebnisliste iterieren, nicht die Reihenfolge,
  in der die Decodes fertig wurden.

Warum zwei Phasen und nicht „decode fertig → sofort adden": `view.addLayer` ist
Main-Thread-Mutation und muss die Z-Order respektieren. Würde man beim Fertig-
werden adden, käme Layer 3 vor Layer 1 in die View, wenn er schneller dekodiert
— Z-Order kaputt. Man müsste die Adds serialisieren *und* umsortieren; die
Zwei-Phasen-Variante bekommt beides gratis, weil `awaitAll()` die Reihenfolge
der Eingabe-Liste bewahrt. Siehe §7 für die verworfene „interleaved add"-
Alternative.

Wirkung (aus der Note): ~70 ms → ~max-single-layer (~33 ms), grob halbiert bei
4 Layern; bei 12 Layern greift der Cap und begrenzt es auf ⌈12/4⌉ ≈ 3 Decode-
Wellen statt 12 seriellen.

---

## 4. Design-Details

### 4.1 Nebenläufigkeits-Struktur

> **In-Code-Kommentare sind ENGLISCH (Rule 13 / `checkRule13`).** Der Fließtext
> dieser Spec ist Deutsch (wie das Schwester-Dokument), aber jeder *implementierte*
> Kommentar/KDoc muss Englisch sein — das folgende Beispiel ist deshalb schon mit
> englischen Kommentaren geschrieben, damit sie 1:1 übernommen werden können.
> `checkRule13` ist diff-aware und würde jeden neuen deutschen `+`-Kommentar rot
> machen.

```kotlin
// The cap is a constructor param (§4.5), and it MUST come BEFORE bitmapLoader so
// that bitmapLoader stays the LAST parameter — bitmapLoader is a `fun interface`
// and the tests construct via trailing-lambda SAM (`WallpaperViewBinder { uri -> … }`),
// which binds to the last param. Putting an Int last would break all those sites.
class WallpaperViewBinder(
    @VisibleForTesting private val maxParallelDecodes: Int = DEFAULT_MAX_PARALLEL_DECODES,  // = 4
    private val bitmapLoader: BitmapLoader,
) { /* … */ }

private suspend fun applyFullRebuild(view, plan, onRebuildComplete) {
    view.visibility = View.INVISIBLE
    view.clearLayers()

    // -------- Phase 1: bounded parallel decode (off-main, order-preserving) --------
    val decoded: List<LayerDecodeResult> = coroutineScope {
        val gate = Semaphore(maxParallelDecodes)
        plan.layers
            .map { spec -> async { gate.withPermit { decodeLayer(spec) } } }
            .awaitAll()   // preserves input order: decoded[i] is for plan.layers[i]
    }

    // -------- Phase 2: sequential ordered add (Main, no suspension point) --------
    val addedLayerIds = ArrayList<String>(plan.layers.size)
    for (result in decoded) {
        when (result) {
            is LayerDecodeResult.Loaded -> {
                val loaded = result.bitmap ?: continue  // loader returned null: already logged, skip
                LaunchTrace.section(LaunchTrace.Names.WALLPAPER_ADD_LAYER) {
                    view.addLayer(bitmap = loaded.bitmap, /* … unchanged … */)
                }
                addedLayerIds.add(result.spec.id)
            }
            is LayerDecodeResult.Failed ->
                TimberWrapper.silentError(result.error, "Error loading layer ${result.spec.id}")
        }
    }

    // … UNCHANGED from here: restoreActiveLayerId, remapUpdatesToAddedLayers,
    //    applyUpdates(view, effectiveUpdates, onRebuildComplete, revealWhenDone = true)
}

private suspend fun decodeLayer(spec: LayerLoadSpec): LayerDecodeResult =
    try {
        // load() may return null (the loader logged it itself) -> Loaded(null).
        LayerDecodeResult.Loaded(spec, bitmapLoader.load(spec.imageUri))
    } catch (e: CancellationException) {
        // Rethrow per canonical: load is the suspension point; a latest-wins switch
        // or onDestroyView cancels the render. Rethrowing lets awaitAll re-raise into
        // the coroutineScope block, which then tears the sibling decodes down (§4.2).
        throw e
    } catch (e: Throwable) {
        // Real decode failure (loader threw instead of returning null): carry it as a
        // value, do NOT log here — Phase 2 logs deterministically on one thread (§4.3).
        LayerDecodeResult.Failed(spec, e)
    }

private sealed interface LayerDecodeResult {
    data class Loaded(val spec: LayerLoadSpec, val bitmap: DecodedWallpaperBitmap?) : LayerDecodeResult
    data class Failed(val spec: LayerLoadSpec, val error: Throwable) : LayerDecodeResult
}
```

Bausteine: `kotlinx.coroutines.coroutineScope`, `async`, `awaitAll`,
`kotlinx.coroutines.sync.Semaphore`, `kotlinx.coroutines.sync.withPermit`.

### 4.2 Cancellation-Vertrag (das Herzstück, `cancel_files`)

Die Cancellation-Regel wandert von *einem* `catch` in der Schleife in *jeden*
`decodeLayer`-Aufruf — und wird durch `coroutineScope` sogar strenger:

- **Rethrow INNEN.** Der `catch (CancellationException) { throw e }`-Arm sitzt in
  `decodeLayer`, also im `async`-Body. Damit propagiert ein Cancel aus `awaitAll`
  heraus zum Aufrufer, statt zu `Failed` zu kollabieren.
- **Zwei Wege, wie Geschwister-Decodes abgeräumt werden — und keiner ist „ein
  Child wirft `CancellationException` und cancelt die Geschwister".** Das ist
  *nicht*, wie kotlinx.coroutines funktioniert: ein Child, das mit
  `CancellationException` endet, cancelt seinen Parent (und damit die
  Geschwister) **nicht** über den Child-Failure-Pfad (`JobSupport.childCancelled`
  behandelt eine `CancellationException`-Ursache als „erledigt"). Die
  Geschwister-Teardown kommt aus zwei anderen Quellen:
    1. **Produktion (der reale Latest-Wins-Fall):** Der Scheduler ruft
       `job.cancel()` von *außen* (`WallpaperRenderScheduler.render`,
       WallpaperRenderScheduler.kt:42). Diese Cancellation fließt *nach unten*
       durch `launch` → `coroutineScope` → jeden `async` — der robuste,
       zuverlässige Pfad.
    2. **Ein Child wirft spontan (im Wesentlichen nur der Test-Fake):** `awaitAll`
       re-raist die `CancellationException` in den `coroutineScope`-Block; die
       aus dem Block geworfene Cancellation **cancelt den Scope-Job** (nicht
       „failt" — fail = eine Nicht-Cancellation-Ursache) und propagiert zum
       Aufrufer. Das theoretische „noch laufende Geschwister mit-canceln" ist
       hier **vakuum**: im Test-Fake werfen alle N asyncs *synchron* und sind
       längst fertig, wenn `awaitAll` re-raist — es gibt keine laufenden
       Geschwister mehr. Real würde dieser Sub-Fall nur greifen, wenn ein Loader
       für *eine* URI spontan `CancellationException` würfe, während *andere*
       Loader noch suspendiert sind — was weder der Fake noch der echte Loader je
       tun. Abstrakt korrekte kotlinx-Semantik, aber kein Pfad, den dieser Code
       geht.
  Beide Wege liefern dasselbe Netto-Ergebnis (Aufrufer sieht
  `CancellationException`, Geschwister werden abgeräumt) — der Vertrag hält, nur
  eben nicht über den Mechanismus „geworfene Cancellation eines Childs". In der
  Praxis ist es **immer Weg 1** (externes `job.cancel()`).
- **Linter.** `WallpaperViewBinder.kt` bleibt auf `cancel_files`. Der neue breite
  `catch (Throwable)` in `decodeLayer` sitzt strukturell hinter einem
  `catch (CancellationException)`-Arm auf demselben `try` → der
  `check-cancellation-rethrow.awk` Case-(a)-Walk ist erfüllt, **kein** Kommentar-
  Marker nötig. Die synchrone Phase-2-Schleife hat keinen Suspend-Punkt; ihr
  einziger breiter Fang ist der bestehende in `applyUpdates` (unverändert, trägt
  schon seinen `no suspension point`-Marker).

> **Abweichung vom Ist-Verhalten, bewusst — und der Ressourcen-Trade-off ehrlich
> benannt:** Heute (sequenziell) läuft bei einem Cancel *ein* In-Flight-Decode zu
> Ende, spätere starten nie. Parallel starten bis zu `maxParallelDecodes` Decodes
> *gleichzeitig*. Wichtig: `BitmapFactory`-Decode ist **nicht-kooperativ
> blockierend** — ein bereits laufender Decode kann *nicht* mitten abgebrochen
> werden; er läuft zu Ende und wirft die `CancellationException` erst am
> `withContext(IO)`-Resume danach. Also: bei einem Supersede laufen bis zu `cap`
> (4) In-Flight-Decodes zu Ende und werden verworfen — parallel verschwendet damit
> **mehr** Decode-Arbeit als sequenziell (dort genau 1), nicht weniger. Gespart
> werden nur die *noch nicht gestarteten* Decodes (das tut sequenziell schon).
> `coroutineScope` wartet zudem auf alle Children, bevor es zurückkehrt, also
> hängt die Teardown-Latenz an bis zu `cap` laufenden Decodes. Der Korrektheits-
> Vertrag ist davon unberührt (Latest-Wins hält), aber die frühere Formulierung
> „kein Decode läuft für einen toten Job weiter" war invertiert. Diese Abweichung
> **bricht außerdem die bestehende Test-Assertion `loadedUris.size == 1`** — siehe
> §6.1. Das ist der eine Punkt, den ein Reviewer *nicht* übersehen darf.

### 4.3 Partial-Failure-Vertrag (Skip + Logging-Parität)

Der Ist-Code unterscheidet zwei Fehler-Arten, und die Parität muss exakt bleiben:

| Fall | Ist-Verhalten | Neu |
|------|---------------|-----|
| `load()` gibt `null` zurück | `?: continue` — Binder loggt **nicht** (Loader `loadBitmapFromUri` hat via `silentError` selbst geloggt) | `Loaded(null)` → `continue`, Binder loggt nicht |
| `load()` **wirft** (z. B. Fake-Loader, oder ein Loader, dessen eigenes Catch nicht greift) | `catch(Throwable)` → `silentError("Error loading layer $id")`, skip | `Failed(spec, e)` → Phase 2 `silentError("Error loading layer ${spec.id}")`, skip |

Zwei Gründe, das Logging in **Phase 2** (Main, sequenziell) statt in `decodeLayer`
(parallel, IO) zu machen:

1. **Determinismus.** Reports erscheinen in Layer-Reihenfolge, einer nach dem
   anderen, nicht verschränkt aus 4 Decode-Threads.
2. **`silentError` wirft in DEBUG (Rule 9).** Läge der `silentError`-Aufruf im
   `async`, würde ein DEBUG-Throw als *nicht*-Cancellation-Throwable aus dem
   Child entkommen → `coroutineScope` cancelt alle Geschwister → der ganze
   Rebuild bricht ab, quer über Threads. In Phase 2 bleibt der DEBUG-Throw auf
   dem Main-Thread, single-threaded, genau wie heute die Schleife. (Der bestehende
   Skip-Test läuft ohnehin mit `preventCrashForTesting`, sonst wäre er heute schon
   rot — die Parität gilt für RELEASE *und* das Test-Setup.)

`plan.layers.size` bleibt die Vorab-Kapazität von `addedLayerIds`; die
Ergebnisliste ist immer gleich lang wie `plan.layers` (ein Ergebnis pro Spec,
`Loaded` oder `Failed`), also bleibt die Index-Ausrichtung für §4.4 trivial.

### 4.4 Z-Order & Update-Remap (warum nichts daran anders wird)

`awaitAll()` liefert `decoded[i]` für `plan.layers[i]` — **Fertigstellungs-
Reihenfolge ist irrelevant**. Phase 2 iteriert `decoded` in Index-Reihenfolge und
ruft `addLayer` in genau der Reihenfolge auf, in der die Schleife es heute tut →
Z-Order bit-identisch. Ein übersprungener Layer (`null` oder `Failed`) verschiebt
die folgenden Positionen exakt wie heute das `continue`, und
`remapUpdatesToAddedLayers` (WallpaperViewBinder.kt:435) mappt die Updates über
die stabilen IDs zurück — **unverändert**. Der Diff-by-Identity-Schutz
(`WallpaperViewDiff` KDoc, „scrambled wallpaper on load failure") gilt weiter,
weil die Add-Reihenfolge weiter Plan-Reihenfolge ist.

### 4.5 Der Cap: warum 4, wo er lebt, Kern-Frage

- **Warum überhaupt ein Cap:** BitmapFactory-Decode ist **CPU-schwer**, nicht
  I/O-schwer. `Dispatchers.IO` hat einen 64-Thread-Default-Pool; 12 ungebremste
  parallele Decodes würden die CPU oversubscriben (Context-Switch-Overhead frisst
  den Gewinn) *und* den Memory-Peak (§5) auf 12× treiben. Der Cap ≈ Kern-Zahl
  hält beide im Zaum.
- **Warum 4 (fix):** Der gemessene Fall sind 4 Layer (braucht gar keinen Cap).
  Der Re-Eval-Fall (12 Layer) ist der riskante — dort greift 4. Pixel-9a-Klasse
  hat 8 Kerne; 4 gleichzeitige CPU-Decodes lassen dem Main-Thread + System Luft.
  Ein fixer Wert ist außerdem **deterministisch testbar** (§6.3), im Gegensatz zu
  `Runtime.availableProcessors()`.
- **Wo der Cap lebt:** Als `Semaphore(maxParallelDecodes)` **im Binder**, nicht im
  Loader/Dispatcher. So bleibt der Binder dispatcher-agnostisch (der Loader wählt
  weiter `Dispatchers.IO`), und der Cap ist unabhängig von der Pool-Größe des
  Dispatchers garantiert. `maxParallelDecodes` ist ein **Konstruktor-Parameter**
  (`@VisibleForTesting`-Default 4), damit der Cap-Test (§6.3) ihn deterministisch
  auf einen kleinen Wert setzen kann.
  > **Parameter-Reihenfolge ist zwingend:** `maxParallelDecodes` muss **vor**
  > `bitmapLoader` stehen, damit `bitmapLoader` der *letzte* Parameter bleibt.
  > `bitmapLoader` ist ein `fun interface`; sechs Test-Call-Sites konstruieren per
  > Trailing-Lambda-SAM (`WallpaperViewBinder { uri -> … }`,
  > `WallpaperViewBinderCancellationTest.kt:85,116`, `…VisibilityTest.kt:59,85,96`,
  > `…SingleLayerTest.kt:47,75`), was an den letzten Parameter bindet. Ein `Int`
  > als letzter Parameter bräche alle sechs (harter Compile-Fehler). Die
  > Produktions-Site (`HomeFragment.kt:354`) nutzt die *benannte* Form und wäre
  > robust — nur die Tests brechen, leicht zu übersehen bis zum Test-Compile. Der
  > §6.3-Cap-Test liest dann `WallpaperViewBinder(maxParallelDecodes = 2) { uri -> … }`
  > (benanntes erstes Arg + Trailing-Lambda als letztes).
- **Der Loader wird serial → concurrent aufgerufen (und ist dafür sicher).** Bisher
  rief die Schleife `bitmapLoader.load` strikt nacheinander; jetzt bis zu `cap`-mal
  gleichzeitig. `decodeBoundedWallpaperBitmap` (`BoundedBitmapDecoder.kt`) hält
  keinen geteilten mutablen Zustand — eigene lokale `BitmapFactory.Options` pro
  Aufruf, frischer Stream pro `openStream()`, `contentResolver`/`BitmapFactory`
  thread-safe. Der Loader bleibt **unverändert**; nur seine Aufruf-Nebenläufigkeit
  ändert sich, und die ist safe.
- **Erwogen, verworfen:** `Dispatchers.Default.limitedParallelism(4)` im Loader
  statt Semaphore. Verschiebt die Cap-Verantwortung in `HomeFragment` und koppelt
  sie an einen Dispatcher-Wechsel IO→Default; der Semaphore hält die Änderung in
  *einer* Datei und einer Verantwortlichkeit. Siehe §7.

### 4.6 Tracing (bleibt balanciert)

- `WALLPAPER_ADD_LAYER` bleibt in Phase 2 um jeden `addLayer` gewickelt — eine
  balancierte Section pro Layer, alle auf dem Main-Thread. Unverändert.
- `WALLPAPER_DECODE` liegt im Loader (`HomeFragment`), also im `withContext(IO)`.
  Bei ≤ 4 parallelen Decodes öffnen/schließen 4 IO-Threads je ihre eigene
  Section. `android.os.Trace`-Sections sind **thread-scoped** → jede ist auf
  ihrem Thread balanciert; im Perfetto-Track überlappen sie jetzt sichtbar,
  was genau der beabsichtigte Beweis der Parallelität ist. **Keine Trace-
  Änderung nötig.**
  > **Invariante, die das garantiert:** `WALLPAPER_DECODE` wickelt einen
  > *vollständig synchronen* `loadBitmapFromUri`-Aufruf innerhalb *eines*
  > `withContext(IO)` — zwischen `beginSection` und `endSection` liegt **kein**
  > Suspend-Punkt, also laufen beide auf demselben IO-Thread. Käme dort je ein
  > Suspend-Punkt hinein, könnte `endSection` auf einem anderen Thread landen und
  > die Section wäre unbalanciert. Diese Invariante gilt es zu halten (dieselbe
  > „kein Suspend-Punkt im getracten Block"-Disziplin wie bei den Main-Sections).

---

## 5. Memory-Analyse (ehrlich)

Die Deferred-Note sagt „12× Decode-Memory-Spike". Präziser:

- Die **fertig gebaute** Wallpaper hält *immer* alle N Layer-Bitmaps (die View
  besitzt sie) — sequenziell wie parallel identisch. Der Cap ändert das
  End-Retained-Set nicht.
- Der **marginale** Parallel-Aufschlag sind zwei Transienten:
  1. **≤ `cap` gleichzeitige Decode-Scratch-Buffer** (BitmapFactory-interner
     Speicher während des Dekodierens). Sequenziell: 1. Parallel-bounded: ≤ 4.
  2. **Fertig dekodierte, noch nicht geparentete Result-Bitmaps.** Weil Phase 2
     erst nach `awaitAll` addet, existieren am Ende von Phase 1 bis zu N
     Result-Bitmaps, bevor die View sie übernimmt. Sequenziell wandert jedes
     Result sofort in die View; das End-Retained ist gleich, aber der *Zeitpunkt*
     unterscheidet sich.
- **Cross-Render-Overlap: der Peak ist `2×cap`, nicht `cap`.** Der `Semaphore` wird
  **pro Render** frisch angelegt (lokales `val gate` im `coroutineScope`) — er
  bounded *einen* Render, nicht das ganze System. Und §4.2 hält fest: ein
  superseder Render kann seine bis zu `cap` bereits laufenden (nicht-canceloaren)
  Decodes nicht stoppen. `WallpaperRenderScheduler.render` cancelt den alten Job und
  startet den neuen **synchron** auf `Main.immediate`; der neue Render beginnt also
  Phase 1 und dispatcht *seine* bis zu `cap` Decodes auf `Dispatchers.IO`, während
  die bis zu `cap` Decodes des alten Renders auf demselben 64-Thread-Pool noch
  auslaufen. Bei einem schnellen `drawer→home→drawer→home`-Wechsel ist der
  Worst-Case-Concurrent-Scratch damit **`2×cap`** — plus die schon fertigen, aber
  von den alten `Deferred`s bis zum Scope-Teardown gehaltenen Result-Bitmaps.
- **Netto (korrigiert):** Der ehrliche Zusatz-Peak im Normalfall ist `(cap − 1)`
  zusätzliche Scratch-Buffer plus das „decoded-but-unparented"-Fenster; im
  Supersede-Flurry bis zu `2×cap` gleichzeitige Decodes. Mit
  `WALLPAPER_RENDER_RES_SPEC` (kleinere Bitmaps) gebaut, bleibt selbst `2×cap`
  günstig. Der Cap = 4 ist die Versicherung gegen den 12-Layer-Re-Eval-Fall; unter
  4 Layern ist er nie bindend — aber die `2×cap`-Rechnung ist genau der Grund, den
  Cap nicht leichtfertig hochzudrehen.

> **Wenn der Peak doch drückt** (sehr große Bitmaps × 12, verschärft durch den
> `2×cap`-Supersede-Fall): die „interleaved add"-Variante (§7) würde das
> „unparented"-Fenster schließen, kostet aber die Z-Order-Einfachheit; ein
> *geteilter* (renderübergreifender) Semaphore würde den `2×cap`-Peak auf `cap`
> drücken, koppelt aber die Renders aneinander. Erst messen (Re-Eval-Trigger),
> dann entscheiden.

---

## 6. Test-Impact

### 6.1 BESTEHEND, MUSS ANGEPASST WERDEN — `WallpaperViewBinderCancellationTest`

`cancelled decode propagates instead of being reported as an error`
(WallpaperViewBinderCancellationTest.kt:83) prüft heute:

```kotlin
assertEquals("the loop must abort … instead of decoding the next layer", 1, loadedUris.size)
```

Diese `size == 1`-Assertion ist an die **sequenzielle** Ausführung gekoppelt:
Layer 0 wirft `CancellationException`, Layer 1 wird nie geladen. Parallel ist das
falsch — und zwar **nicht** wegen des Caps. Traced unter `runTest`s
`StandardTestDispatcher` (single-threaded, FIFO): *beide* `async`-Bodies sind
schon *pre-launched* (in die Queue gestellt), bevor `awaitAll()` den Parent
suspendiert. Der Fake wirft die `CancellationException` **synchron** — jeder
`async` acquired das Permit, wirft, released, ohne je zu suspendieren, also
**blockt der `Semaphore` nie** und der Cap spielt hier keine Rolle. Ein spontan
geworfener `CancellationException` cancelt die Geschwister *nicht* (§4.2), also
laufen **alle N** Layer durch `load()`, bevor die Cancellation via `awaitAll`
beim Aufrufer ankommt. Für die 2-Layer-Fixture ist `loadedUris.size`
deterministisch **2** (nicht flaky) → die alte `== 1`-Assertion bricht.

**Der ehrliche Bound ist `<= plan.layers.size`, NICHT `<= maxParallelDecodes`.**
Die frühere Fassung schrieb `<= cap`; das passt nur zufällig, solange N ≤ cap
(hier 2 ≤ 4). Wüchse die Fixture über 4 Layer mit einem synchron-werfenden Loader,
wäre `size == N > cap` und die `<= cap`-Assertion ginge **fälschlich rot**. Da
`<= plan.layers.size` trivial immer wahr ist, trägt die Count-Assertion *kein*
Signal mehr — **am besten ganz streichen** und nur die zwei *eigentlichen*
Garantien behalten (beide unter dem Parallel-Impl verifiziert):

1. `thrown is CancellationException` (Cancel erreicht den Aufrufer, nicht den
   `silentError`-Zweig).
2. `loggedErrors == emptyList()` (ein gecancelter Render ist **kein** Crash,
   nichts wird gemeldet).

> Reviewer-Hinweis: Diese Test-Änderung ist *nicht* optional und *nicht* „den
> Test grün machen" — sie ist der sichtbare Beweis, dass die Verhaltens-Abweichung
> in §4.2 bewusst ist. Der KDoc des Tests muss den Grund **präzise** festhalten:
> „Alle Decodes sind pre-launched, bevor die Cancellation sichtbar wird" — *nicht*
> „bis zu cap in Flight beim Cancel" (der Cap gatet in diesem Test nicht). In
> Produktion kommt Latest-Wins dagegen aus einem *externen* `job.cancel()`, das
> nach unten propagiert und In-Flight-Children *doch* mit-cancelt — ein anderer
> Mechanismus als der Test ihn modelliert. Beide erfüllen die zwei Invarianten,
> also bleibt der Test gültig; die zwei Wege aber nicht verwechseln (§4.2).

### 6.2 BESTEHEND, BLEIBT GRÜN — Skip-on-Failure

`layer whose decode throws is skipped and reported without aborting the rebuild`
(WallpaperViewBinderCancellationTest.kt:113) muss **unverändert grün** bleiben:
Layer 0 wirft `IOException` → `Failed` → Phase 2 loggt „L0" einmal, skip; Layer 1
dekodiert → `view.layerCount == 1`. Das ist der Parität-Beweis für §4.3. Wenn
dieser Test rot wird, ist die Logging-Parität gebrochen.

### 6.3 NEU — Concurrency-Cap wird eingehalten

Ein Loader, der bei Eintritt einen Zähler hochzählt (und den Max-Stand merkt),
auf einem gemeinsamen Signal suspendiert und beim Austritt runterzählt. Mit
`maxParallelDecodes = 2` (Konstruktor-Override) und z. B. 5 Layern: assert, dass
der beobachtete Max-Concurrent-Stand **`== 2`** ist. **Nicht `<= 2`** — ein
`<=`-Bound geht *vakuum* grün auch für einen voll serialisierten (kaputten) Impl,
dessen Max nur 1 wäre; erst `== cap` pinnt die *Liveness*-Eigenschaft, dass der
Semaphore wirklich `cap` gleichzeitig durchlässt. Deterministisch dank
`StandardTestDispatcher` + dem injizierbaren Cap — **kein** Verlass auf
`availableProcessors()`, und **kein** `MainDispatcherRule` nötig (der Binder
berührt `Dispatchers.Main` nie). Vier Präconditions, die stimmen müssen, sonst
misst der Test nichts (oder scheitert im Teardown):

- **(a) Der Loader muss echt suspendieren** (auf `Channel`/`CompletableDeferred`).
  Ein synchroner Loader lässt den `Semaphore` nie blocken → der Test sähe alle N
  „gleichzeitig" und bewiese nichts.
- **(b) `bind` muss als Child-Coroutine gestartet und *im suspendierten Zustand*
  inspiziert werden** (`launch { … }` + `testScheduler.runCurrent()`), nicht inline
  awaited — sonst suspendiert der Test-Body selbst und kann den Zähler nicht lesen.
- **(c) Ein einfacher `Int` genügt** — unter dem single-threaded Scheduler gibt es
  keine echte Thread-Contention; „max gleichzeitig *im Loader suspendiert*" ist
  genau das, was der Semaphore bounded. `AtomicInteger` wäre irreführend (suggeriert
  Contention, die es nicht gibt).
- **(d) Teardown-Hygiene: das Signal am Ende freigeben + `advanceUntilIdle()`.**
  Weil `bind` als Child ge`launch`t ist und der Loader auf einem geteilten Signal
  suspendiert, wirft `runTest` beim Testende `UncompletedCoroutinesError`, wenn der
  Test asserted und endet, ohne das Signal zu vervollständigen und die Coroutine
  auslaufen zu lassen. Nach der `== cap`-Assertion also: Signal freigeben,
  `advanceUntilIdle()`, dann erst enden. (Dieselbe Disziplin fährt
  `WallpaperRenderSchedulerTest` bewusst zu Ende — `WallpaperRenderSchedulerTest.kt:58,90`.)

### 6.4 NEU — Ordnung trotz Out-of-Order-Fertigstellung

Ein Loader, der **später** geplante Layer **schneller** fertig macht, muss trotzdem
zu `view` in **Plan-Reihenfolge** führen. Assert: die Layer-IDs in der View sind
`[L0, L1, L2]`, nicht die Fertigstellungs-Reihenfolge. (Robolectric wie die
Geschwister-Tests: echter `ZoomableImageView`, echte `Uri`s.)

**Mechanik, ehrlich benannt:** „Out-of-order" ist nur real, wenn der Loader
*pro Layer* auf ein eigenes Signal (ein `CompletableDeferred` je URI) suspendiert
und der Test die Signale **in umgekehrter Reihenfolge** freigibt (L2, dann L1,
dann L0). Ohne das ist der Test bloß in-order-synchron und beweist nichts.

**Und was er beweist, ist eng:** `awaitAll()` bewahrt die Eingabe-Reihenfolge
*by construction* — selbst ein voll sequenzieller Impl bestünde diesen Test. Er
prüft also **Order-Preservation, nicht Nebenläufigkeit**, und ist gegenüber dem
vorgeschlagenen Zwei-Phasen-Impl fast tautologisch. Sein realer Wert ist eine
**Regressions-Sperre gegen ein künftiges „interleaved add"-Refactor** (§7): würde
jemand die Adds beim Fertigwerden statt in Plan-Reihenfolge feuern, ginge genau
dieser Test rot. Deshalb behalten — aber nicht als „Parallelitäts-Beweis"
verkaufen.

### 6.5 BESTEHEND, BLEIBT GRÜN — Regressions-Deckung des geänderten Pfades

`WallpaperViewBinderVisibilityTest` prüft **Multi-Layer-Full-Rebuilds** und
exerziert damit direkt das umgebaute `applyFullRebuild` — es ist *kein*
unberührter Pfad, sondern verhaltens-erhaltende Regressions-Deckung *der
geänderten Funktion*. Beide Fälle bleiben grün unter dem Parallel-Impl:
all-bitmap → `layerCount == 2` + `VISIBLE`; all-null → `result.bitmap ?: continue`
skippt beide → `layerCount == 0` + `GONE`. Wenn einer rot wird, hat der Umbau das
Rebuild-Verhalten verändert — genau der Alarm, den man will.

### 6.6 Echt unberührt

`WallpaperViewBinderSingleLayerTest` (Single-Layer-Pfad, unverändert),
`WallpaperViewBinderTest` (reiner Unit-Test der `remapUpdatesToAddedLayers`-
Companion), `WallpaperViewDiffTest` (Diffing), `WallpaperRenderSchedulerTest`
(Scheduler) — keiner berührt `applyFullRebuild`s Decode-Schleife.

### 6.7 Test-Constraint (macht §6.3/§6.4 überhaupt deterministisch)

`StandardTestDispatcher` ist single-threaded — es gibt in dieser Suite **keine**
echte Multi-Thread-Parallelität; §6.3/§6.4 testen *logische* Nebenläufigkeit über
kooperatives Suspendieren, was der korrekte, deterministische Weg ist. Die
Kehrseite: bekäme der Loader in einem dieser Tests einen *echten* Multi-Thread-
Dispatcher (z. B. reales `Dispatchers.IO`), bräche die Determinismus-Garantie. Der
Binder **muss** dispatcher-agnostisch bleiben (der Loader läuft im Test auf dem
Test-Scheduler) — genau das macht die neuen Tests reproduzierbar.

---

## 7. Verworfene Alternativen

- **`chunked(4)` statt Semaphore.** Dekodiert in Wellen von 4 mit einem Barrier
  zwischen den Wellen. Der Semaphore hält dagegen *durchgehend* 4 in Flight (sobald
  einer fertig ist, startet der nächste) → bessere Wall-Clock bei ungleichen
  Decode-Zeiten. Semaphore gewinnt.
- **Interleaved add (decode fertig → sofort adden).** Würde das „unparented"-
  Memory-Fenster (§5) schließen, bricht aber die Z-Order-Einfachheit: Adds müssten
  serialisiert *und* nach Plan-Index umsortiert werden (eine `TreeMap`/Slot-
  Füllung). Mehr Komplexität für einen Memory-Peak, der bei ≤ 4 Layern nicht
  existiert und bei 12 Layern erst nach dem Re-Eval-Trigger relevant wird. Später,
  falls §5 misst, dass es drückt.
- **`Dispatchers.Default.limitedParallelism(4)` im Loader** statt Semaphore im
  Binder. Koppelt Cap an Dispatcher-Wechsel und verschiebt die Verantwortung nach
  `HomeFragment`. Der Semaphore hält Cap + Parallel-Struktur in *einer* Datei.
- **Decode-Cache über View-Recreation hinweg.** Würde den `drawer→home`-Rebuild
  ganz vermeiden statt ihn zu beschleunigen — größerer Umbau (Cache-Invalidierung,
  Memory-Lifecycle), eigenes Projekt. Diese Spec beschleunigt den *bestehenden*
  Rebuild, ohne Cache.

---

## 8. Rollout & Akzeptanz

**Baubar erst nach:** Review dieser Spec (Agents), plus explizite Freigabe des
Nutzers *und* eine Feature-Branch (`feature/wallpaper-parallel-decode`) — die
Änderung berührt eine Datei, ist aber ein revertierbarer Feature-Block
(CLAUDE.md „branch before non-trivial work").

**Akzeptanz-Checkliste:**
- [ ] `applyFullRebuild` in zwei Phasen; Z-Order bit-identisch zu heute.
- [ ] `decodeLayer` rethrowt `CancellationException` vor dem breiten `Throwable`-
      Fang; `WallpaperViewBinder.kt` bleibt auf `cancel_files`, `./gradlew
      checkConventions` grün.
- [ ] `Semaphore(maxParallelDecodes)`, Default 4, Konstruktor-überschreibbar —
      **`maxParallelDecodes` als ERSTER Konstruktor-Parameter**, `bitmapLoader`
      bleibt letzter (Trailing-Lambda-SAM der Tests, §4.5). Alle sechs Test-Call-
      Sites kompilieren weiter.
- [ ] Logging-Parität: Loader-`null` → still, Loader-Throw → einmal „Error loading
      layer $id", beides in Phase 2.
- [ ] §6.1-Test angepasst (Count-Assertion **gestrichen**, nur die zwei echten
      Invarianten + präzise KDoc-Begründung „pre-launched, nicht cap"), §6.2 +
      §6.5 grün, §6.3 + §6.4 neu und grün (§6.3: Assertion **`== cap`** nicht `<= cap`,
      plus die vier Präconditions inkl. Teardown-Hygiene; §6.7-Dispatcher-Constraint).
- [ ] `HomeFragment.kt`, `BoundedBitmapDecoder.kt`, `WallpaperViewDiff`,
      `remapUpdatesToAddedLayers` unverändert.
- [ ] Re-Trace `drawer→home` (Pixel 9a, 4-Layer): `wallpaper_decode`-Sections
      überlappen; Redraw-Zeit gesunken. Trace archivieren wie die Note.

**Re-Eval-Trigger (aus der Note, hier gilt er weiter):** greift, wenn ein Nutzer
High-Layer-Count-Collagen baut — dann ist der Hebel *sichtbar* und der Cap +
Memory-Peak (§5) werden real. Erst dann ist auch die interleaved-add-Frage (§7)
messbar zu entscheiden.
```
