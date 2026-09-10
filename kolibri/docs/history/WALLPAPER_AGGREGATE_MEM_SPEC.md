# WALLPAPER_AGGREGATE_MEM_SPEC.md

**Status: ABGELEHNT / YAGNI (2026-08-15) — nach Usecase-Prüfung (§0) nicht
gebaut.** Als Referenz aufbewahrt: falls je *mehrere hochauflösende* Layer als
realer Usecase auftauchen (heute nicht), liegt hier die fertige Analyse + der
greedy-Ansatz (§5/§6). Ursprünglicher Fokus: das **aggregierte** Retained-Memory der
Multi-Layer-Wallpaper deckeln, damit gestapelte hochauflösende Quellbilder nicht den
Heap sprengen (OOM, `largeHeap="false"`).

---

## 0. Usecase-Realität & Entscheidung (2026-08-15)

Nach Rücksprache mit dem Autor des Multi-Layer-Modus **verworfen** — die Prämisse
(mehrere hochauflösende Layer gestapelt) trifft auf keinen echten Usecase zu:

- **Ein großes Bild als Wallpaper (z. B. POCO 108 MP) ist Single-Layer.** Läuft über
  `applySingleLayer` (N=1), aggregiert = Pro-Layer-Budget. Schon heute auf
  `RENDER_WALLPAPER_PIXELS` (10,5 MP ≈ 42 MB) gedeckelt. Kein aggregiertes Problem.
- **Multi-Layer wurde für Collagen gebaut:** ein Hintergrundbild + kleine Overlays
  (chinesischer Siegelstempel, Yin/Yang-Symbol, Signatur unten rechts), typischerweise
  AI-generierte Grafiken — **keine** 108-MP-Kamerafotos. Gemessen (§1): 12,4 MB.
  Selbst ein größerer Hintergrund-Layer bleibt einzeln durch das Pro-Layer-Budget auf
  ~42 MB begrenzt; die Overlays sind winzig.

**Fazit:** Das **Pro-Layer-Budget** (`WALLPAPER_RENDER_RES_SPEC`) ist für beide realen
Fälle das ausreichende Sicherheitsnetz. Das aggregierte Budget wäre Over-Engineering
für ein Szenario, das nicht vorkommt. **Re-Evaluations-Trigger:** ein realer Report
von OOM/Speicherdruck bei Multi-Layer, oder ein neuer Usecase, in dem mehrere
hochauflösende Fotos gestapelt werden. Bis dahin: `WallpaperMem`-Logging (Branch
`chore/wallpaper-mem-logging`) beobachten, nicht bauen.

Der Rest dieses Dokuments (§1–§9) ist die **archivierte** Analyse, Stand vor der
Ablehnung — nicht als Bauauftrag lesen.

---

Schwester-Dokumente:
- `WALLPAPER_RENDER_RES_SPEC.md` — definiert das **Pro-Layer**-Budget
  (`RENDER_WALLPAPER_PIXELS = 10,5 MP`) und die S_render-Kompensation. Dieses Spec
  baut **darauf auf**: es lässt das Pro-Layer-Budget als Obergrenze stehen und legt
  eine **Gesamt**-Decke darüber. Der §3-Constraint hier ist eine direkte Fortsetzung
  des dortigen §3.2.
- `WALLPAPER_PARALLEL_DECODE_SPEC.md` — der Transient-Peak (§4 hier) interagiert mit
  dem dortigen `2×cap`-Overlap.

Berührt (geplant): `BitmapDownsampling.kt` (neuer Planer, pure Math),
`BoundedBitmapDecoder.kt` (Bounds/Decode-Split), `WallpaperViewBinder.kt`
(Planungsphase vor dem Parallel-Decode), `HomeFragment.loadBitmapFromUri`
(Loader-Signatur), ein Heap-Provider (di/AppModule).

---

## 1. Problem (gemessen, nicht spekuliert)

Gemessen am 2026-08-15 auf Pixel 9a, Release-Build mit `WallpaperMem`-Logging
(Branch `chore/wallpaper-mem-logging`):

- **Typische 4-Layer-Collage: 12,4 MB retained.** Vier Layer à ~1 MP
  (704×1504, 768×1376, 1024×1024, 393×245), alle `sample=1` (schon klein genug,
  gar kein Downsampling). Völlig unkritisch.
- **Ein einziger 12-MP-Foto-Layer dazu: +11,4 MB → 23,9 MB.** Quelle 3000×4000,
  selbst nach `sample=2` noch 1500×2000 = 3 MP. Dieser **eine** Layer verbraucht
  **mehr als die anderen vier zusammen**.

Kernbefund: **Die Anzeigegröße eines Layers in der Collage sagt nichts über seinen
Speicherverbrauch — die Quell-Auflösung zählt.** Ein optisch winziger Layer kann ein
full-res 108-MP-POCO-Foto sein (~27 MB nach Downsampling auf das 10,5-MP-Budget).

Heute gibt es **kein aggregiertes Budget** und **keinen Layer-Cap** (grep bestätigt:
kein `MAX_LAYER*`). `RENDER_WALLPAPER_PIXELS` deckelt jeden Layer *einzeln* auf
~42 MB (10,5 MP × 4 B, ARGB_8888). Die Summe wächst linear:

| Szenario | retained (Worst Case, alle Layer ≥ 10,5 MP) |
|---|---|
| 3 hochauflösende Layer | ~126 MB |
| 5 hochauflösende Layer | ~210 MB |

Bei `largeHeap="false"` liegt `Runtime.maxMemory()` je nach Gerät zwischen ~128 MB
(alt/knapp) und ~512 MB. 210 MB retained ist auf der unteren Hälfte dieser Spanne
ein sicherer OOM — und der Decode-Transient (§4) legt nochmal drauf.

## 2. Warum das Pro-Layer-Budget das nicht löst

`RENDER_WALLPAPER_PIXELS` ist bewusst pro Layer und **ohne Seiten- oder
Anzahl-Bezug** (RENDER_RES_SPEC §5: Zoom-Schärfe-Headroom an `ZOOM_IN_MULTIPLIER`).
Es weiß nichts über Geschwister-Layer. Genau die Multiplikation mit der Layer-Zahl
ist das Loch. Eine reine Absenkung des Pro-Layer-Budgets würde auch den
**Einzelbild**-Fall (Single-Layer, ein großes Foto) unnötig unschärfer machen,
obwohl der gar nicht das Problem ist. Die Begrenzung muss also **aggregiert** und
**nur unter Druck** greifen.

## 3. Korrektheits-Constraint (Fortsetzung von RENDER_RES_SPEC §3.2)

`layer.sampleSize` (= S_render) ist die Decode-Downsample-Stufe, gegen die der
**gespeicherte** Transform via `S_render / S_captured` kompensiert wird
(`WallpaperViewBinder.compensatedScale`). Ein aggregiertes Budget macht S_render
**abhängig vom Geschwister-Set**: fügt man einen großen Layer hinzu, kann ein
bestehender Layer beim nächsten Rebuild *gröber* dekodiert werden (S_render steigt).

**Das ist kompatibel, nicht neu:** die Kompensation nutzt bereits das *aktuelle*
S_render zur Renderzeit (in `layer.sampleSize` festgehalten) gegen das *gespeicherte*
S_captured. Der Transform bleibt also korrekt. **Neu ist nur** die akzeptierte
Limitation, dass die On-Screen-Schärfe eines Layers jetzt von seinen Nachbarn
abhängt (mehr/größere Layer ⇒ jeder etwas unschärfer). Das gehört nach
`ACCEPTED_LIMITATIONS.md`, sobald gebaut.

**Invariante:** S_captured (die persistierte Stufe) darf sich dadurch **nie** ändern —
nur S_render (die Live-Decode-Stufe). Save bleibt tag-only (RENDER_RES_SPEC §4-Y).

## 4. Transient-Peak (Interaktion mit PARALLEL_DECODE_SPEC)

Das aggregierte Budget begrenzt das **retained** Set (was die View am Ende hält).
Der **Rebuild-Transient** liegt höher: `applyFullRebuild` dekodiert Phase 1 *alle* N
Layer parallel und hält *alle* N Result-Bitmaps, **bevor** Phase 2 sie parented —
plus bis zu `2×cap` Decode-Scratch (PARALLEL_DECODE_SPEC §5). Effektiver Peak ≈
retained + ungeparentete Results + Scratch, grob bis ~2× retained.

Konsequenz für §5: Die Decke muss den **Peak** meinen, nicht nur das End-Retained —
also entweder konservativ tief ansetzen, oder Phase 2 inkrementell parenten und
konsumierte Result-Referenzen früh nullen (separate Optimierung, siehe §7).

## 5. Design-Optionen

### Option A — Gleichverteilung: Budget / layerCount
Pro-Layer-Budget = `RENDER_WALLPAPER_PIXELS / layerCount`, nach unten geclamped.
- **Contra:** bestraft den Misch-Fall (4 winzige + 1 großer). Der große würde auf
  1/5 gedrosselt, obwohl das Gesamt-Retained problemlos wäre. Ignoriert, dass die
  kleinen Layer ihr Budget gar nicht ausschöpfen. Grob und übervorsichtig.

### Option B — Globales Byte-Budget, proportional geschätzt
Feste Gesamt-Byte-Decke; bei Überschreitung alle Layer proportional herunterrechnen.
- **Contra:** proportional trifft auch die kleinen Layer, die nichts zum Problem
  beitragen. Unnötige Schärfeverluste.

### Option C — Gesamt-Decke + „größten zuerst schärfen/drosseln" (greedy) ⭐
Pro-Layer-Budget bleibt die Obergrenze (Einzelbild/kleine Collage unangetastet).
Zusätzlich eine **Gesamt-Retained-Decke** `MAX_TOTAL_WALLPAPER_BYTES`. Nur wenn die
projizierte Summe sie überschreitet, wird `sampleSize` **auf den größten
Byte-Verbrauchern zuerst** verdoppelt (jede Verdopplung viertelt diesen Layer),
bis die Summe unter der Decke liegt. Kleine Layer bleiben full-res.
- **Pro:** trifft exakt den gemessenen Fall (der eine 12-MP-Layer wird gedrosselt,
  die vier 1-MP-Layer nicht). Degradiert nur unter Druck und nur dort, wo der
  Speicher tatsächlich sitzt.

### Option D — Decke geräte-heap-abhängig (Ergänzung zu C)
`MAX_TOTAL_WALLPAPER_BYTES` nicht hart kodieren, sondern aus dem Heap ableiten
(`Runtime.getRuntime().maxMemory()` bzw. `ActivityManager.memoryClass`), z. B.
`fraction × maxMemory()`. Weil der OOM-Schwellwert geräteabhängig ist (largeHeap
false ⇒ 128–512 MB), ist das der eigentliche Punkt: auf knappen Geräten härter
schützen, auf großen keine Schärfe verschenken.

## 6. Empfehlung: **C + D**

Gesamt-Retained-Decke aus dem Heap abgeleitet, mit greedy „größten zuerst drosseln".

**Algorithmus (Planungsphase vor dem Decode):**
1. **Bounds-Pass für alle N Layer** (`inJustDecodeBounds`, keine Allokation — heute
   schon Schritt 1 in `decodeBoundedWallpaperBitmap`, nur pro Layer statt global).
2. Provisorisch `s_i = calculateWallpaperInSampleSize(W_i, H_i, RENDER_WALLPAPER_PIXELS)`,
   `bytes_i = (W_i/s_i)·(H_i/s_i)·4`.
3. `total = Σ bytes_i`. Wenn `total ≤ Decke` → fertig, mit `s_i` dekodieren.
4. Sonst: wiederhole — nimm den Layer mit dem größten `bytes_i`, verdopple sein
   `s_i` (⇒ `bytes_i /= 4`), bis `total ≤ Decke` **oder** ein harter Floor erreicht
   ist (nie unter Screen-Auflösung × K_min ⇒ lieber unscharf als OOM, aber nicht
   absurd 1-Pixel).
5. Decode jeden Layer mit dem aufgelösten `s_i`.

**Decke (zu bestätigen, §8):** `MAX_TOTAL_WALLPAPER_BYTES =
clamp(fraction × maxMemory(), floor, ceil)`. Startvorschlag `fraction = 0,25`,
`floor = 48 MB`, `ceil` so, dass ein **Einzel**-Layer immer das volle Pro-Layer-Budget
(~42 MB) bekommt. **Peak-Sicherheit (§4):** entweder `fraction` konservativ (0,20)
und/oder Phase-2-Inkremental-Parenting (§7), damit `2×retained`-Transient nicht selbst
OOMt.

## 7. Architektur-Impact & offene Umbauten

- **`BitmapDownsampling.kt` (:app):** neuer **pure** Planer
  `planAggregateSampleSizes(sizes: List<Pair<Int,Int>>, perLayerMax, totalByteBudget): List<Int>`
  — JVM-testbar (Rule 10), keine Android-Abhängigkeit. Der Kern des greedy §6-Loops.
- **`BoundedBitmapDecoder.kt`:** Bounds- und Pixel-Decode **entkoppeln**. Heute macht
  `decodeBoundedWallpaperBitmap` beides in einem Call. Neu: eine Bounds-Funktion
  (liefert W/H) + eine Decode-mit-vorgegebenem-`sampleSize`-Funktion. Der Planer sitzt
  dazwischen im Binder.
- **`WallpaperViewBinder.applyFullRebuild`:** Planungsphase (Bounds aller Layer →
  `planAggregateSampleSizes`) **vor** dem Parallel-Decode. Die `BitmapLoader`-Schnittstelle
  (`load(uri)`) muss das pro Layer aufgelöste `maxPixels`/`sampleSize` mitbekommen —
  entweder Signatur erweitern oder das Bounds/Decode-Splitting in den Binder ziehen.
  **Single-Layer-Pfad (`applySingleLayer`) bleibt unberührt** (N=1 ⇒ aggregiert =
  Pro-Layer-Budget).
- **`HomeFragment.loadBitmapFromUri`:** an die neue Loader-Signatur anpassen.
- **Heap-Provider:** `maxMemory()`/`memoryClass` hinter eine kleine Schnittstelle
  (di/AppModule), damit der Planer-Test einen festen Wert injizieren kann statt echten
  Heap zu lesen.
- **Optional (Peak, §4):** Phase 2 inkrementell parenten + konsumierte
  Result-Referenzen nullen, um den Rebuild-Transient von ~2× auf ~1× retained zu
  senken. Separat baubar; ohne das muss die Decke konservativer sein.
- **Tests:** Planer-Math in `BitmapDownsamplingTest` (greedy-Grenzfälle: alle klein,
  einer riesig, alle riesig, Floor erreicht). Binder-Planungsphase in den
  `WallpaperViewBinder*Test`. Instrumented-Decoder-Test bleibt grün (#21-Schutz, ≤ 24 MP).
- **`ACCEPTED_LIMITATIONS.md`:** Eintrag „Layer-Schärfe hängt vom Geschwister-Set ab"
  (§3), inkl. Re-Evaluations-Trigger.

## 8. Zu bestätigen (bevor Code)

1. **Decke-Formel + `fraction`/`floor`/`ceil`.** `0,25 × maxMemory()` sinnvoll?
   Auf welchem knappsten Zielgerät testen?
2. **Peak-Strategie:** konservative Decke *oder* Inkremental-Parenting (§7) — oder
   beides?
3. **Floor:** wie unscharf darf ein Layer unter Extremdruck maximal werden
   (Screen-Auflösung? × K_min)? Ab wann lieber Layer-Cap statt weiter drosseln?
4. **Ist ein harter Layer-Cap** (z. B. analog `MAX_FAVORITES_ON_HOME`) als
   einfachere Alternative gewünscht, statt/zusätzlich zum aggregierten Budget?
   (Einfacher, aber grobe UX-Grenze; das aggregierte Budget schützt transparent.)
5. **Lohnt sich der Umbau** angesichts der Messung (reale Collage 12,4 MB)? Das
   Risiko ist real, aber nur für Power-User mit mehreren hochauflösenden Layern.

## 9. Was dieses Dokument NICHT ist

- Kein gebauter Code — reines Konzept, wartet auf Bestätigung von §5/§8.
- Keine Änderung an `RENDER_WALLPAPER_PIXELS` oder der S_render-Kompensation
  (RENDER_RES_SPEC bleibt gültig; dieses Spec legt nur eine Decke darüber).
- Kein Ersatz für den #21-Canvas-Crash-Schutz (`MAX_WALLPAPER_PIXELS`, ≤ 24 MP) —
  der bleibt die harte Einzelbitmap-Grenze darunter.
