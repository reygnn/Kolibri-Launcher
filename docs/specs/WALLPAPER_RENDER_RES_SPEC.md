# WALLPAPER_RENDER_RES_SPEC.md

**Status: ENTWURF v3 (2026-08-12), Review-Runden 1+2 eingearbeitet.** Fokus: den
GPU-gebundenen Gesten-Jank beim Zoom/Pan im Wallpaper-Edit-Mode beseitigen, indem
die **Render-Auflösung** der Wallpaper-Bitmaps von der **Crash-Schutz-Grenze**
entkoppelt wird — ohne gespeicherte Zoom/Pan-Transforms zu verschieben. Erst
reviewen, dann bauen; nicht auf `main` mergen, bevor der Ansatz (§4/§5) bestätigt
und der Korrektheits-Constraint (§3) abgesichert ist.

> **v3-Änderungen gegenüber v2** (Review-Runde 2, §10): (1) **S-Plumbing
> spezifiziert** (§4.0) — der Decoder verwirft S/Original-Dims heute; Ansatz Y
> ist ohne diese Verkabelung nicht baubar. (2) §3.3-Überaussage korrigiert: S
> *betritt* Layer/View (für `showOriginalSize` + Save/Load), nur die
> Scale-**Mathematik** bleibt loaded-bitmap-space. (3) §7-Backfill: das
> **Pre-#21-24–26-MP-Band** ist mehrdeutig (akzeptierte Limitation). (4) §5:
> Headroom an **`ZOOM_IN_MULTIPLIER` (3,0) × Cover** festgemacht, nicht am
> dynamischen `effectiveMaxScale`; und der Trade-off Zoom-Schärfe↔Jank ehrlich
> benannt. (5) §4-Y als **tag-only** klargestellt (kein `÷S` beim Speichern).
>
> **v2-Änderungen gegenüber v1** (Review-Runde 1): Kompensation `S_render/S_captured`
> statt `·S`; Persistenz-Grenze statt Draw-Matrix; `showOriginalSize`-Translate;
> Export-Sorge gestrichen (`composeToBitmap` tot).

Schwester-Dokumente: keine direkten. Berührt `BitmapDownsampling.kt`
(`MAX_WALLPAPER_PIXELS`, #21-Crash-Fix), `BoundedBitmapDecoder.kt`,
`ZoomableImageView.kt` (Transform-Semantik), die Transform-Persistenz in
`WallpaperDelegate` / `WallpaperRepository` / `WallpaperState`.

---

## 0. Scope

**In Scope:** Die Decode-/Render-Auflösung der Wallpaper-Layer so wählen, dass
die GPU pro Geste-Frame keine massiv übergroßen Texturen abtastet. Gespeicherte
Transforms müssen visuell identisch bleiben, auch über die Budget-Änderung
hinweg.

**Out of Scope:** Die App-CPU-Seite (bereits vermessen, vernachlässigbar). Der
Rebuild-Pfad (sauber, Commit `a11c9377`). Der #21-Crash-Schutz bleibt erhalten —
das neue Budget liegt unter der alten 24-MP-Grenze.

---

## 1. Problem (gemessen)

Perfetto-Trace (Release, Pixel 9a, 25 s kontinuierliches Multi-Layer-Zoom/Pan;
archiviert als `gesture-jank_903cb8ae_2026-08-12.perfetto-trace`):

- **App-Main-Thread ist unschuldig:** `gesture_touch` max 0,28 ms,
  `gesture_ondraw` max 0,48 ms.
- **Jank ist GPU/RenderThread-gebunden:** `DrawFrames` **35–65 ms/Frame** (Budget
  120 Hz = 8,3 ms), `waitForBufferRelease`/`dequeueBuffer` bis **53 ms** → **124×
  Buffer Stuffing**, **17× App Deadline Missed** (max **115 ms**), Vollbild-Draw
  `0 0 1080 2424` bis **39 ms**.

---

## 2. Root Cause

Wallpaper-Bitmaps dekodieren auf `MAX_WALLPAPER_PIXELS = 24_000_000` (24 MP,
`BitmapDownsampling.kt`) — eine **Flächen-Grenze allein für den Canvas-
`MAX_BITMAP_SIZE`-Crash (~100 MB, #21)**, absichtlich keine Seiten-Begrenzung. Der
Screen ist 1080×2424 = **2,6 MP**. Ein 16-MP-Foto (4608×3456) liegt unter 24 MP,
bleibt **voll aufgelöst** → eine **~6× (bis 9×) übergroße Textur**, die die GPU
pro Geste-Frame über die volle Screen-Fläche abtastet. Die Decode-Grenze ist für
Crash-Schutz dimensioniert, nicht für Render-Effizienz.

---

## 3. Korrektheits-Constraint

### 3.1 Transform-Semantik

`ZoomableImageView` speichert Zoom/Pan **relativ zur GELADENEN Bitmap**:

- Single-Layer: `_singleScale` (+ `_singleTranslateX/Y`), Matrix =
  `postScale(_singleScale); postTranslate(tx, ty)` (`rebuildSingleMatrix`,
  ZoomableImageView.kt:1018).
- Multi-Layer: `layer.scale` / `translateX/Y`, identische Matrix-Form
  (`WallpaperLayer.buildMatrixInto:131`).

`scale` bildet **geladenes-Bitmap-Pixel → View-Pixel** ab; `translate` ist
**View-Koordinaten**. Bitmap-Pixel `(px,py)` → `(px·scale+tx, py·scale+ty)`.

### 3.2 Die Kompensation ist ein VERHÄLTNIS (v1-Korrektur)

**Wichtig, weil der #21-Decode bereits live ist** (`HomeFragment.kt:1505`): >24-MP-
Bilder werden *heute schon* mit `S_captured ≥ 2` dekodiert, panbar/zoombar, und
ihr Transform wird **gegen die bereits heruntergerechnete Bitmap** gespeichert
(`onSaveWallpaperTransform`, WallpaperDelegate.kt:454; die View emittiert die
loaded-bitmap-relative Scale, ZoomableImageView.kt:1046). Die v1-Annahme „alle
bestehenden Transforms bei S=1" ist damit **falsch**.

Korrekte Kompensation bei Wechsel von `S_captured` (Erfassungs-Downsample) auf
`S_render` (neuer Render-Downsample):

> **`view_scale_render = gespeicherter_view_scale · (S_render / S_captured)`**,
> **`translate` unverändert.**

Der Translate-unverändert-Teil gilt **nur, wenn der Scale-Faktor das korrekte
Verhältnis ist** (View-Koordinaten hängen nicht von der Auflösung ab, aber ein
falscher Scale desynchronisiert den Zentrier-Term). Bei `S_captured = S_render`
(z. B. 16-MP-Bild, beide 1) ist das Verhältnis 1 → nichts ändert sich.

### 3.3 Die View bleibt in loaded-bitmap-space — die Umrechnung sitzt an der Persistenz-Grenze (v1-Korrektur)

`layer.scale` / `_singleScale` werden **überall** als loaded-bitmap-relativ
gelesen; diese Konsumenten self-correcten, weil sie die *aktuelle* geladene
Bitmap-Größe verwenden — sie dürfen NICHT angefasst werden:

- `computeLayerBaseScale` = `max(view/bmp.width, …)` (ZoomableImageView.kt:186)
- `effectiveMinScale`/`effectiveMaxScale` gegen `_singleBaseScale` (:147–180)
- Edge-Resistance / Snap-Back: `scaledW = intrinsic · _singleScale`
  (:1068, :1130, :1217, :1279)
- `centerCropLayer` / `applyFitWidth` (:186, :388)

**Daher:** die Scale-**Mathematik** der View bleibt loaded-bitmap-space. v1s „S
beim Draw-Matrix-Bau multiplizieren" hätte all diese Rechnungen um Faktor S
gebrochen — verworfen.

**Präzisierung (v3):** „S betritt die View nie" (so v2) ist zu stark. S ist ein
**Per-Layer-Datum**, das sehr wohl in Layer/View liegt — aber nur an zwei
Stellen genutzt: (a) `showOriginalSize` (§6.1, braucht `scale = S`), (b) die
Save/Load-Grenze (§4). Die oben gelisteten Scale-Consumer sehen S **nicht**; für
sie bleibt alles loaded-bitmap-relativ. „S nur an der Persistenz-Grenze
*anwenden*" bleibt richtig; „S existiert nicht im Layer" war falsch — es MUSS
dort als Feld liegen (§4.0).

---

## 4. Ansätze

### 4.0 Voraussetzung: S-Plumbing (v3, Runde-2-Finding 1)

Alle Ansätze brauchen an der Persistenz-Grenze entweder den **Sample-Faktor S**
oder die **Original-Dimensionen** pro Layer. Beides existiert im heutigen
Datenfluss NICHT und muss verkabelt werden — der Kern-Implementierungsaufwand:

- `decodeBoundedWallpaperBitmap` berechnet `inSampleSize` intern und gibt **nur
  die `Bitmap`** zurück (BoundedBitmapDecoder.kt:22) — S und `bounds.outWidth/
  outHeight` werden verworfen. Muss S (oder origW/origH) mit-zurückgeben.
- `WallpaperLayer.intrinsicWidth/Height` = **geladene** Bitmap-Größe
  (`bitmap.width`, ZoomableImageView.kt:498) — die Original-Größe ist aus dem
  Layer nicht rekonstruierbar. `WallpaperLayer` braucht ein neues Feld
  (`sampleSize: Int` bzw. `originalWidth/Height`).
- Der Save-Pfad (`WallpaperDelegate.onSave*Transform`) muss das Feld
  durchreichen; der Load/Backfill-Pfad (§7) braucht einen Bounds-Pass für
  origW/origH.

**Wichtig — S ist PER LAYER.** Jeder Layer hat eigene Original-Dimensionen →
eigenen `S_captured`/`S_render`. Weder das gespeicherte Feld noch der Backfill
noch die `·(S_render/S_captured)`-Kompensation dürfen ein globales S annehmen;
alles läuft pro Layer.

### 4.1 Die eigentlichen Ansätze

Alle halten die Scale-Mathematik der View in loaded-bitmap-space; sie
unterscheiden sich darin, **wie die auflösungs-unabhängige Persistenz**
hergestellt wird.

### X — Persistenz auf Original-Auflösung normalisieren
Gespeichert wird die **Original-Bild→View**-Scale: `stored = view_scale /
S_captured` beim Speichern, `view_scale = stored · S_render` beim Laden.
Budget-unabhängig, überlebt beliebig viele Budget-Wechsel.
- **Contra:** Bestehende Daten sind **roh** (loaded-bitmap-relativ, nicht
  normalisiert). Ohne Versions-Flag ist beim Lesen nicht unterscheidbar, ob ein
  Wert legacy-roh oder neu-normalisiert ist → braucht eine einmalige
  Migrations-Umschreibung ODER ein Schema-Feld. Kollidiert mit „additiv
  rückwärtskompatibel, kein DataMigrationManager" (CLAUDE.md §5).

### Y — Erfassungs-Sample-Faktor als ADDITIVES Feld + Bounds-Backfill (empfohlen)
`WallpaperLayerState` (WallpaperState.kt:10) bekommt ein **optionales**
`captureSampleSize: Int?` (bzw. `captureBudgetPx`).

**Y ist tag-only (v3, Runde-2-Finding 4).** Beim Speichern wird der `view_scale`
**roh** abgelegt (KEIN `÷S`) und lediglich das Feld `captureSampleSize = S_render`
dazugeschrieben. Beim Laden: `view_scale · (S_render / S_captured)`. Anders als
Ansatz X (der beim Speichern `÷S` und beim Laden `×S` rechnet) transformiert Y
den gespeicherten Scale **nicht** — wer bei Y trotzdem `÷S` beim Speichern
anwendet, dividiert doppelt (`view_scale · S_render / S_captured²`).
- **Legacy-Backfill (Pflicht, §7):** Fehlt das Feld, ist `S_captured` zur
  Ladezeit **neu berechenbar** — der Bounds-Pass in `BoundedBitmapDecoder`
  (`inJustDecodeBounds`, BoundedBitmapDecoder.kt:26) liefert die *Original*-
  Dimensionen unabhängig vom `inSampleSize`; also `S_captured =
  calculateWallpaperInSampleSize(origW, origH, maxPixels = 24_000_000)` (das
  ALTE Budget). Damit ist auch der heute-schon-downsampled-Fall korrekt.
- **Pro:** Additiv (fehlendes Feld = Default via Backfill), überlebt mehrere
  Budget-Wechsel, kein DataMigrationManager. Fügt sich in CLAUDE.md §5.
- **Contra:** Ein optionales Feld mehr in der State- + Serialisierungs-Schicht.

### Z — Reine Bounds-Neuberechnung, kein neues Feld
Wie Ys Backfill, aber **immer** (nie ein Feld speichern): `S_captured` stets aus
Bounds+24 MP, `S_render` aus neuem Budget.
- **Contra:** Bricht, sobald **nach** der Budget-Änderung neu gespeichert wird —
  dann ist der rohe Wert bei `S_render(neu)` erfasst, aber Z nimmt weiter
  `S_captured = calc(…,24 MP)` an → Drift. Nur bei **genau einer** Budget-
  Änderung *und* keinem Re-Save korrekt. Zu fragil. Verworfen.

---

## 5. Empfehlung

**Ansatz Y.** Additives `captureSampleSize`-Feld mit Bounds-Backfill für
Altdaten. Er ist der einzige Ansatz, der (a) den §3.2-Verhältnis-Constraint
korrekt für heute-schon-downsampled-Bilder erfüllt, (b) Re-Save nach Budget-
Wechsel überlebt, und (c) die CLAUDE.md-§5-Vorgabe (additiv, kein
DataMigrationManager) einhält. X ist konzeptuell am saubersten, scheitert aber am
Migrations-Constraint; Z ist zu fragil.

**Budget-Herleitung (v3, Runde-2-Finding 3).** `effectiveMaxScale` ist **keine
feste Decke** — sie ratcht mit jedem Reinzoomen hoch (`maxOf(MAX_SCALE,
maxOf(base, current)·ZOOM_IN_MULTIPLIER)`, ZoomableImageView.kt:165; der
`applyTransform`-Kommentar bei :344 nennt das dynamische Wachstum explizit). Es
gibt also kein „maximaler Zoom", aus dem man ableiten könnte. Die feste Größe ist
**`ZOOM_IN_MULTIPLIER = 3,0`** (ZoomableImageView.kt:113): das Bild darf bis
**3× über Cover** hineingezoomt werden.

**Der ehrliche Trade-off.** Damit die Textur bei 3× Cover-Zoom noch pixelscharf
ist, bräuchte sie ~3× linear = **~9× Screen-Fläche** — und das ist ziemlich genau
die heutige 24-MP-Grenze (≈9× von 2,6 MP). Die 24 MP sind also **nicht nur** ein
Crash-Artefakt, sondern zufällig auch der Zoom-Schärfe-Puffer. Das Budget zu
senken bedeutet daher zwangsläufig: **weniger Schärfe bei starkem Reinzoomen**,
im Tausch gegen flüssige Gesten. Das ist die eigentliche Produkt-Entscheidung,
nicht ein reiner Free-Win.

**Vorschlag (per Re-Trace UND visueller Zoom-Prüfung zu bestätigen):** Budget =
Screen × K. `K = 4` (≈ 10,5 MP) hält Schärfe bis ~2× Cover-Zoom und rechnet
darüber hoch; `K = 9` wäre der Status quo (kein Jank-Gewinn). Der Sweet Spot
liegt dazwischen und ist **subjektiv** — die Re-Messung (§8) liefert die
Jank-Seite, ein Blick bei Vollzoom die Schärfe-Seite. Nicht allein aus einer
Zahl ableitbar.

---

## 6. Edge-Cases & Korrekturen

1. **`showOriginalSize` (1:1) — Scale UND Translate (v1-Korrektur).** Auf einer
   downsampled Bitmap muss „Originalgröße" `scale = S` setzen, um die *originalen*
   Pixel 1:1 zu zeigen. Aber die Methode zentriert mit der **downsampled**
   Bitmap-Größe: `(width − drawable.intrinsicWidth)/2` (ZoomableImageView.kt:425)
   bzw. `(width − bmp.width)/2` (:414). Bei `scale = S` ist die gezeichnete Breite
   `bmp.width·S` → korrekt ist `(width − bmp.width·S)/2`. Sonst off-center um
   `bmp.width·(S−1)/2`. Eigener Test.
2. **Export-Qualität — gegenstandslos (v1-Streichung).** `composeToBitmap`
   (ZoomableImageView.kt:662) ist **toter Code** (`@Suppress("unused")`, null
   Aufrufer in app/data/domain). Das Wallpaper wird **live pro Frame** in
   `onDraw` gezeichnet, kein gespeichertes Full-Res-Artefakt. Downsampling
   degradiert also nichts außer der On-Screen-Schärfe bei Vollzoom (§6.3). *Falls*
   `composeToBitmap` je reaktiviert wird, braucht es dieselbe S-Behandlung — dann
   sollte es bei Original-Auflösung komponieren (Ein-Zeilen-Hinweis am Code).
3. **Zoom-in-Schärfe.** Der einzige echte Preis: screen-nahe Textur ist bei
   starkem Reinzoomen unschärfer. Der 4×-Headroom (§5) federt ab; Grenze aus
   `effectiveMaxScale`.
4. **#21-Schutz bleibt.** Neues Budget ≤ 24 MP → Canvas-Schutz unberührt, der
   Instrumented-`BoundedBitmapDecoder`-Test bleibt grün.
5. **Single/Multi-Parität.** `_singleScale` UND `layer.scale` bekommen die
   IDENTISCHE `·(S_render/S_captured)`-Behandlung an der Persistenz-Grenze
   (`onSaveWallpaperTransform` / `onSaveAllLayerTransforms` /
   `onSaveLayerTransform`, WallpaperDelegate.kt:454/710/719), sonst driften sie.

---

## 7. Migration (Pflicht, unverzichtbar)

Jeder persistierte Transform ist heute **feldlos** (kein `captureSampleSize`).
Beim Laden eines feldlosen Layers:

1. Bounds-Pass → `origW, origH`.
2. `S_captured = calculateWallpaperInSampleSize(origW, origH, 24_000_000)` (altes
   Budget — das war die Grenze, als der Wert erfasst wurde).
3. `S_render = calculateWallpaperInSampleSize(origW, origH, NEW_BUDGET)`.
4. `view_scale = gespeichert · (S_render / S_captured)`, Translate unverändert.
5. Beim nächsten Save `captureSampleSize = S_render` schreiben (ab dann exakt).

Ohne diesen Backfill würde Ys „×(S_render/S_captured)" auf Altdaten mit
implizitem, aber nicht-1-`S_captured` falsch angewandt.

**Mehrdeutiges Pre-#21-Band (v3, Runde-2-Finding 2 — akzeptierte Limitation).**
Schritt 2 nimmt an, dass 24 MP das Budget war, als der Wert erfasst wurde. Das
gilt nur **post-#21**. *Vor* dem #21-Fix gab es gar kein Downsampling — dekodiert
wurde voll aufgelöst, begrenzt nur durch das Canvas-Limit (~100 MB ≈ **26,2 MP**;
das 24-MP-Budget liegt bewusst mit Marge darunter, BitmapDownsampling.kt:17). Für
Bilder im schmalen Band **24 MP < X ≲ 26 MP** (z. B. ein 6016×4000 = 24,06-MP-
APS-C-Foto) ist ein feldloser Transform:

- **pre-#21** bei S=1 erfasst (Full-Res, zeichnete unter 100 MB), aber
- der Backfill berechnet `calc(X, 24 MP) = 2` → das Bild wird **2× falsch**
  gezeichnet.

Pre- und Post-#21 sind feldlos **nicht unterscheidbar** (byte-identisch). Das
betrifft nur genau-über-24-MP-Bilder, die vor dem #21-Update gespeichert wurden —
ein schmaler Rand-Fall. **Akzeptierte Limitation:** der Nutzer justiert diese
eine (seltene) Wallpaper-Position einmalig neu. Alternativ (nicht empfohlen, mehr
Aufwand): das Backfill-Budget auf das Canvas-Limit (26 MP) statt 24 MP setzen —
dann ist das Band S=1 auf beiden Seiten, aber post-#21-24–26-MP-Transforms
(die real bei S=2 stehen) würden dann falsch. Es gibt keine feldlose Auflösung,
die BEIDE Seiten trifft; das Feld selbst (ab erstem Re-Save) ist die einzige
saubere Lösung, und die greift automatisch.

---

## 8. Test-Plan

- **JVM (pure math):** `calculateWallpaperInSampleSize` mit neuem Budget +
  `neue_scale = scale·(S_render/S_captured)` als reine Funktion (BitmapDownsampling
  ist dafür ausgelegt). Fälle: S_captured=1, S_captured=2 (heute-downsampled),
  S_render=S_captured (No-Op).
- **Restore-Contract:** Transform speichern (mit Feld) → laden bei gesenktem
  Budget → Matrix-Ergebnis sub-pixel-identisch. Plus: **feldloser** (Legacy-)
  Transform → Backfill-Pfad → identisch.
- **Re-Save-Roundtrip:** speichern → laden → erneut speichern → laden; kein Drift
  (die „doppelt anwenden"-Falle aus §4-Z).
- **`showOriginalSize`:** 1:1 auf downsampled Bitmap bleibt zentriert.
- **Instrumented (`:app:halo`-Stil, Rule 10):** `BoundedBitmapDecoder` unter
  Canvas-Limit; >Budget-Bild rendert bei Ziel-Auflösung.
- **Perfetto-Re-Messung:** dieselbe Geste-Sequenz wie §1; Erfolg: `DrawFrames`-
  Median unter Frame-Budget, `App Deadline Missed` → ~0, Buffer Stuffing deutlich
  runter. Trace als „nachher" archivieren.

---

## 9. Was dieses Dokument NICHT ist

- Keine Implementierung — Code folgt nach Review-Freigabe in eigenem Branch.
- Keine Aussage über den Rebuild-Pfad (sauber, Commit `a11c9377`).

---

## 10. Änderungshistorie

- **v3 (2026-08-12):** Review-Runde 2 (Agent) eingearbeitet. §4.0 S-Plumbing
  (Decoder → Layer-Feld → Save/Load) als Bau-Voraussetzung; §3.3-Präzisierung
  (S ist Per-Layer-Datum in Layer/View, nur die Scale-Mathematik bleibt
  loaded-bitmap-space); §7 Pre-#21-24–26-MP-Band als akzeptierte Limitation;
  §5 Headroom an `ZOOM_IN_MULTIPLIER = 3` × Cover statt am dynamischen
  `effectiveMaxScale`, plus der Zoom-Schärfe↔Jank-Trade-off ehrlich benannt;
  §4-Y als tag-only klargestellt (Doppel-Dividier-Falle). Runde 2 bestätigte
  F1/F2/F3 aus Runde 1 als echt gelöst.
- **v2 (2026-08-12):** Review-Runde 1 (Agent) eingearbeitet. Kompensation
  Verhältnis statt `·S`; Persistenz-Grenzen-Ansatz statt Draw-Matrix; additives
  `captureSampleSize`-Feld + Bounds-Backfill (Ansatz Y) statt v1s Ansatz B;
  `showOriginalSize`-Translate-Fix; Export-Sorge gestrichen (`composeToBitmap`
  tot); Pflicht-Migrations-Regel (§7).
- **v1 (2026-08-12):** Erst-Entwurf.
