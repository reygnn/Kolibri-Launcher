# WALLPAPER_RENDER_RES_SPEC.md

**Status: ENTWURF v2 (2026-08-12), Review-Runde 1 eingearbeitet.** Fokus: den
GPU-gebundenen Gesten-Jank beim Zoom/Pan im Wallpaper-Edit-Mode beseitigen, indem
die **Render-Auflösung** der Wallpaper-Bitmaps von der **Crash-Schutz-Grenze**
entkoppelt wird — ohne gespeicherte Zoom/Pan-Transforms zu verschieben. Erst
reviewen, dann bauen; nicht auf `main` mergen, bevor der Ansatz (§4/§5) bestätigt
und der Korrektheits-Constraint (§3) abgesichert ist.

> **v2-Änderungen gegenüber v1** (Review-Runde 1, §10): (1) Kompensation ist ein
> **Verhältnis** `S_render/S_captured`, nicht `·S` — große Bilder sind *heute
> schon* downsampled, der #21-Decode ist live. (2) Die S-Umrechnung sitzt an der
> **Persistenz-Grenze** (zweiseitig: save/load), NICHT im Draw-Matrix-Bau — die
> View bleibt komplett in loaded-bitmap-space, sonst brechen Snap/Edge/Zoom.
> (3) `showOriginalSize` braucht auch einen Translate-Fix. (4) Export-Sorge
> gestrichen: `composeToBitmap` ist toter Code → reiner Gewinn.

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

**Daher:** S NUR an der Persistenz-Grenze anwenden, die View unverändert lassen.
v1s „S beim Draw-Matrix-Bau multiplizieren" hätte all diese Rechnungen um Faktor
S gebrochen — verworfen.

---

## 4. Ansätze

Alle halten die View in loaded-bitmap-space; sie unterscheiden sich nur darin,
**wie die auflösungs-unabhängige Persistenz** hergestellt wird. Die Umrechnung
ist **zweiseitig**: eine Transformation beim Speichern, die inverse beim Laden.

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
`captureSampleSize: Int?` (bzw. `captureBudgetPx`). Beim Speichern wird das
aktuelle `S` mitgeschrieben; beim Laden `view_scale · (S_render/S_captured)`.
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

**Budget-Vorschlag (per Re-Trace zu bestätigen):** Ziel-Fläche ≈ **4× Screen**
(≈ 10,5 MP bei 1080×2424). Der Zoom-Headroom MUSS aus `effectiveMaxScale`
(ZoomableImageView.kt:165) abgeleitet werden — der maximale Zoom bestimmt, wie
viele Original-Pixel bei Vollzoom auf den Screen fallen; darunter wird es
sichtbar unscharf. Nicht raten.

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

- **v2 (2026-08-12):** Review-Runde 1 (Agent) eingearbeitet. Kompensation
  Verhältnis statt `·S`; Persistenz-Grenzen-Ansatz statt Draw-Matrix; additives
  `captureSampleSize`-Feld + Bounds-Backfill (Ansatz Y) statt v1s Ansatz B;
  `showOriginalSize`-Translate-Fix; Export-Sorge gestrichen (`composeToBitmap`
  tot); Pflicht-Migrations-Regel (§7).
- **v1 (2026-08-12):** Erst-Entwurf.
