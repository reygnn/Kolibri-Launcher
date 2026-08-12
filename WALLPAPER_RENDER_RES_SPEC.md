# WALLPAPER_RENDER_RES_SPEC.md

**Status: ENTWURF (2026-08-12).** Fokus: den GPU-gebundenen Gesten-Jank beim
Zoom/Pan im Wallpaper-Edit-Mode beseitigen, indem die **Render-Auflösung** der
Wallpaper-Bitmaps von der **Crash-Schutz-Grenze** entkoppelt wird — ohne
gespeicherte Zoom/Pan-Transforms zu verschieben. Erst reviewen, dann bauen;
nicht auf `main` mergen, bevor der Ansatz (§4/§5) bestätigt und der
Korrektheits-Constraint (§3) abgesichert ist.

Schwester-Dokumente: keine direkten. Berührt `BitmapDownsampling.kt`
(`MAX_WALLPAPER_PIXELS`, #21-Crash-Fix), `BoundedBitmapDecoder.kt`,
`ZoomableImageView.kt` (Transform-Semantik), die Transform-Persistenz in
`WallpaperDelegate` / `WallpaperRepository`.

---

## 0. Scope

**In Scope:** Die Decode-/Render-Auflösung der Wallpaper-Layer so wählen, dass
die GPU pro Geste-Frame keine massiv übergroßen Texturen abtastet. Gespeicherte
Transforms müssen visuell identisch bleiben.

**Out of Scope:** Die App-CPU-Seite des Rebuilds/Gesten (bereits vermessen und
vernachlässigbar, siehe `chore/wallpaper-rebuild-tracing` /
`chore/gesture-jank-tracing` Commits). Der Rebuild-Pfad selbst (sauber). Der
#21-Crash-Schutz bleibt erhalten — er wird nur nicht mehr die *einzige* Grenze
sein.

---

## 1. Problem (gemessen)

Perfetto-Trace (Release, Pixel 9a, 25 s kontinuierliches Multi-Layer-Zoom/Pan im
Edit-Mode; archiviert als `gesture-jank_903cb8ae_2026-08-12.perfetto-trace`):

- **App-Main-Thread ist unschuldig:** `gesture_touch` max 0,28 ms,
  `gesture_ondraw` max 0,48 ms. Matrix-Math und Draw-Command-Recording sind
  winzig.
- **Der Jank ist GPU/RenderThread-gebunden:**
  - `DrawFrames` **35–65 ms/Frame** (Budget bei 120 Hz = 8,3 ms)
  - `waitForBufferRelease` / `dequeueBuffer` je bis **53 ms** (100×) → **124×
    Buffer Stuffing**
  - **17× App Deadline Missed** (max **115 ms**)
  - Vollbild-Draw `Drawing 0 0 1080 2424` bis **39 ms** (79×)

Nutzer-sichtbar als Ruckeln beim Zoomen/Schieben eines Wallpapers im Edit-Mode.

---

## 2. Root Cause

Wallpaper-Bitmaps werden auf `MAX_WALLPAPER_PIXELS = 24_000_000` (24 MP)
dekodiert (`BitmapDownsampling.kt`). Diese Grenze existiert **ausschließlich** als
Schutz gegen den Canvas-`MAX_BITMAP_SIZE`-Crash (~100 MB per-Bitmap, #21) — sie
ist eine **Flächen-Obergrenze**, absichtlich *keine* Seiten-Begrenzung, damit
"nur Bilder, die sonst crashen würden" heruntergerechnet werden.

Folge: Der Screen ist 1080×2424 = **2,6 MP**. Ein typisches 16-MP-Kamerafoto
(4608×3456) liegt unter 24 MP und bleibt daher **voll aufgelöst** — eine
**~6-fach** (bis 24 MP: ~9-fach) übergroße Textur, die die GPU bei **jedem**
Geste-Frame über die volle Screen-Fläche abtastet. Mit mehreren Layern +
Blend-Modes kommt Overdraw hinzu. Das sind die 35–65 ms DrawFrames.

Kernaussage: **Die Decode-Grenze ist für Crash-Schutz dimensioniert, nicht für
Render-Effizienz.** Zum Zeichnen genügt Screen-Auflösung plus Zoom-Headroom.

---

## 3. Korrektheits-Constraint (der Grund, warum das kein Einzeiler ist)

`ZoomableImageView` speichert Zoom/Pan **bitmap-absolut**:

- Single-Layer: `_singleScale` (+ `_singleTranslateX/Y`), Matrix =
  `postScale(_singleScale); postTranslate(tx, ty)` (`rebuildSingleMatrix`,
  Z. 1018).
- Multi-Layer: `layer.scale` / `layer.translateX/Y`, identische Matrix-Form
  (`WallpaperLayer.buildMatrixInto`).

`scale` bildet **Bitmap-Pixel → View-Pixel** ab; `translate` ist in
**View-Koordinaten**. Ein Bitmap-Pixel `(px, py)` landet bei
`(px·scale + tx, py·scale + ty)`.

Senkt man die Decode-Auflösung um Faktor **S** (Bitmap-Dimensionen → /S), zeigt
ein *unverändert* gespeicherter `scale` das Bild um Faktor S zu klein. Der
`BitmapDownsampling.kt`-Kommentar warnt genau davor: heute ist das sicher, weil
Bilder, die die 24-MP-Grenze treffen, *vorher* crashten → sie haben keinen
gültigen gespeicherten Transform. Ein 16-MP-Foto bleibt voll aufgelöst → sein
Transform bleibt gültig. Senkt man das Budget, verlieren viele bestehende
Transforms ihre Gültigkeit.

**Die gute Nachricht — die Kompensation ist exakt und trivial.** Aus der
Matrix-Form folgt direkt:

> Bei Downsample-Faktor **S**: **`neue_scale = gespeicherte_scale · S`**,
> **`translate` unverändert** (View-Koordinaten hängen nicht von der
> Bitmap-Auflösung ab).

Gilt identisch für Single- und Multi-Layer. Das `showOriginalSize()`-Feature
(1:1-Pixel, `scale = 1.0`) braucht analog `scale = S`, um die *originalen* Pixel
1:1 zu zeigen (sonst zeigt es die heruntergerechnete Auflösung 1:1). Die
`centerCrop`-Basisskala wird ohnehin aus der Intrinsic-Größe neu berechnet und
ist gegen Downsampling transparent (selbstkorrigierend).

---

## 4. Ansätze

### A — `MAX_WALLPAPER_PIXELS` senken + Transforms migrieren
Budget von 24 MP auf ~Screen×Headroom (z. B. 4× Screen ≈ 10,5 MP) senken. Beim
Laden den effektiven `inSampleSize` (Faktor S) kennen und gespeicherte Transforms
mit `·S` kompensieren (§3).

- **Pro:** Kleinster Eingriff, der die Textur wirklich schrumpft. Kompensation
  ist eine Multiplikation an genau einer Stelle (Transform-Restore).
- **Contra:** Man muss S beim Restore kennen. Bestehende gespeicherte Transforms
  wurden gegen die *damalige* Auflösung (S=1 für <24-MP-Bilder) aufgenommen — für
  die ist `·S_neu` korrekt, sofern die Referenz-Auflösung als S=1 gilt. Migration
  ist also transparent, muss aber pro Layer/Bild belegt sein.

### B — Render-Auflösung von der Transform-Referenz entkoppeln (empfohlen)
Wie A, aber den **Sample-Faktor S explizit als Metadatum** pro Layer führen (im
`WallpaperLayer` / in der `WallpaperState`), statt ihn implizit aus der
Bitmap-Größe abzuleiten. Der gespeicherte Transform bleibt gegen die
**Original-Auflösung** definiert (S=1-Referenz); beim Bauen der Draw-Matrix wird
`scale·S` verwendet. Decode passiert bei `originalDim/S`.

- **Pro:** Transform-Persistenz bleibt auflösungs-**unabhängig** und stabil, auch
  wenn sich das Budget später wieder ändert. Kein „Transform gegen aktuelle
  Bitmap-Größe"-Fragil mehr. `showOriginalSize` wird korrekt (`scale·S`).
- **Contra:** Ein Feld mehr in `WallpaperLayer` + Draw-Matrix-Bau. Etwas mehr
  Fläche als A, aber die saubere Trennung ist genau der Punkt, den §3 verlangt.

### C — Downsampled-Proxy nur während der Geste, Full-Res beim Settle
Full-Res-Bitmap als Transform-Wahrheit behalten, aber während einer aktiven Geste
eine Low-Res-Proxy-Textur zeichnen; beim Loslassen zurück auf Full-Res.

- **Pro:** Transform-Semantik **komplett unberührt** (Full-Res bleibt Referenz).
- **Contra:** Zwei Bitmaps pro Layer (Speicher), Proxy-Erzeugung + Swap-Logik,
  und der **Settle-Frame zeichnet weiterhin Full-Res** (der 39-ms-Frame bleibt
  beim Loslassen). Löst den Jank *während* der Geste, nicht den Endframe.
  Komplexeste Variante.

### D — Hardware-Layer / `RenderNode`-Caching
Statische Layer während einer Ein-Layer-Geste cachen.

- **Contra:** Der aktive Layer ändert sich pro Frame (Transform), profitiert also
  nicht; und die Textur-Größe — die eigentliche Ursache — bleibt. Adressiert das
  Problem nicht an der Wurzel. Verworfen.

---

## 5. Empfehlung

**Ansatz B.** Er behebt die Ursache (Textur-Größe) und macht die
Transform-Persistenz zugleich **auflösungs-unabhängig** — was den §3-Constraint
nicht nur umschifft, sondern strukturell auflöst. A ist der Minimal-Weg und eine
akzeptable Zwischenstufe, falls B zu groß wird; C/D adressieren die Wurzel nicht.

**Budget-Vorschlag (zu bestätigen durch Messung):** Ziel-Fläche ≈ **4× Screen**
(≈ 10,5 MP bei 1080×2424) als Kompromiss zwischen Zoom-Headroom (Nutzer können
in ein Detail zoomen) und GPU-Last. `effectiveMaxScale` (Z. 165) begrenzt den
Zoom ohnehin — der Headroom sollte an dieser Ober-Zoom-Grenze ausgerichtet
werden, nicht geraten. **Vor dem Commit erneut tracen** und prüfen, dass
`DrawFrames` unter das Frame-Budget fällt.

---

## 6. Risiken / offene Fragen

1. **Migration bestehender Transforms.** Alle heute gespeicherten Transforms sind
   gegen die aktuelle (meist S=1) Auflösung aufgenommen. B muss belegen, dass der
   Restore-Pfad `scale·S` genau einmal anwendet und nicht doppelt (z. B. bei
   Reorder/Swap, die `saveCurrentViewTransforms` auslösen). **Contract-Test
   nötig:** speichern → Budget senken → laden → Transform visuell identisch.
2. **Zoom-in-Schärfe.** Bei starkem Hineinzoomen wird eine screen-nahe Textur
   sichtbar unschärfer als die Full-Res. Der 4×-Headroom soll das abfedern; die
   genaue Grenze aus `effectiveMaxScale` ableiten.
3. **`showOriginalSize` (1:1).** Muss auf `scale = S` umgestellt werden, sonst
   zeigt „Originalgröße" die heruntergerechnete Auflösung. Eigener Test.
4. **Interaktion mit dem #21-Crash-Schutz.** Das neue Budget MUSS ≤ dem alten
   24-MP-Schutz bleiben (tut es), damit der Canvas-Schutz erhalten bleibt. Der
   bestehende Instrumented-Test für `BoundedBitmapDecoder` bleibt grün.
5. **Single- vs. Multi-Layer-Parität.** Beide Pfade (`_singleScale`,
   `layer.scale`) müssen dieselbe `·S`-Kompensation bekommen — sonst driften sie.

---

## 7. Test-Plan

- **JVM (pure math):** `calculateWallpaperInSampleSize` mit neuem Budget +
  `neue_scale = scale·S`-Kompensation als reine Funktion (bereits JVM-testbar,
  `BitmapDownsampling.kt` ist dafür ausgelegt).
- **Contract/Restore:** Transform speichern → laden bei gesenktem Budget →
  Matrix-Ergebnis identisch (Toleranz ~Sub-Pixel).
- **Instrumented (`:app:halo`-Stil, Rule 10):** `BoundedBitmapDecoder` weiterhin
  unter Canvas-Limit; Draw eines >Budget-Bildes rendert bei Ziel-Auflösung.
- **Perfetto-Re-Messung:** dieselbe Geste-Sequenz wie in §1; Erfolgskriterium
  `DrawFrames`-Median unter das Frame-Budget, `App Deadline Missed` → nahe 0,
  Buffer Stuffing deutlich reduziert. Trace als „nachher" archivieren.

---

## 8. Was dieses Dokument NICHT ist

- Keine Implementierung — es legt den Ansatz fest, den Code schreibt ein
  Folge-Branch nach Review.
- Keine Aussage über den Rebuild-Pfad (der ist sauber, siehe Commit
  `a11c9377`).
