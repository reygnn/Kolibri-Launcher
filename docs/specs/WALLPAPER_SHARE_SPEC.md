# WALLPAPER_SHARE_SPEC — Wallpaper-Rendering teilen (das Kronjuwel)

> **Erzeugt** gegen `main` @ `131d633`, als Folge-Spec zu `WALLPAPER_RESTORE_SPEC §12`
> ("Rendering/Compositing-Extraktion als eigener WALLPAPER_SHARE_SPEC"). Konsumiert
> die Port-Doktrin „geteilt wird die Mechanik, nicht die Produkt-Philosophie"
> (MRG-INV-8) und den `MONOREPO_MERGE_SPEC §3`-Home-Platzierungs-Port.
>
> **Fokus:** die produktneutrale **Wallpaper-Render-/Edit-Oberfläche** (custom
> Wallpaper mit Layern, Zoom/Pan, Composite, Scrim, Luminanz) so aus Kolibri
> herausziehen, dass **nyx sein eigenes Wallpaper anzeigt** — ohne duplizierten
> Render-/Compositing-/Decode-Code. Kern ist die **eine echte per-App-Naht**: wo
> die Wallpaper-View im Home sitzt (Kolibris Favoriten-`HomeFragment` vs. nyx'
> Grid-`MainActivity`/`ViewPager2`).
>
> **Voraussetzung (erfüllt):** Persistenz (`WallpaperState`/`Repository` → `:core`,
> `WallpaperFileManager`/`RepositoryImpl` → `:common-data`) und die reine
> Render-Logik (`WallpaperRenderScheduler`, `WallpaperCompositeKey`, `ScrimRender`,
> `BitmapDownsampling`, `WallpaperMemoryReport`, `WallpaperSaveAction`,
> `LuminanceDownsampling` → `:core.wallpaper`) sind bereits geteilt.
>
> **Nicht im Fokus:** Backup/Restore (`WALLPAPER_RESTORE_SPEC`, backup-gated); der
> Bild-Picker + Storage-Permission-UX (app-lokal); das konkrete nyx-Design
> (Farben/Abstände). Die vier Kolibri-Render-Detail-Specs
> (`WALLPAPER_COMPOSITE_LIFECYCLE_SPEC`, `WALLPAPER_PARALLEL_DECODE_SPEC`,
> `WALLPAPER_RENDER_RES_SPEC`, `WALLPAPER_SCRIM_USER_SETTING_SPEC`) werden **nicht
> neu modelliert** — sie beschreiben die Interna der hier verschobenen Klassen und
> gelten unverändert weiter; `WALLPAPER_DRAWER_HOME_REBUILD_SPEC` (Anti-Flash /
> Rebuild-Trigger) wird zur per-App-Naht (§6).
>
> **Status:** ENTWURF v1.1. Signaturen in §4/§5 sind Vorschläge auf Basis des realen
> Kolibri-Codes. Review-Runde 1 eingearbeitet (§9): Backdrop = User-Wahl (transparent
> auch mit custom Wallpaper), `WallpaperDelegate` geteilt, nyx-Edit v1 reduziert,
> nyx-Bildwahl via `READ_MEDIA_IMAGES`.

---

## §0 Bestandsaufnahme (was nach Persistenz + Render-Logik noch in Kolibri liegt)

| Rolle | Datei | ~LOC | Android? | Produkt-Kopplung |
|---|---|---|---|---|
| Render-Surface (View) | `ui/home/ZoomableImageView.kt` | 1632 | ja (View/Canvas/Bitmap/Matrix) | keine (0 AppInfo/HomeLayout/Favoriten) |
| State→View-Binder | `ui/home/wallpaper/WallpaperViewBinder.kt` | 546 | ja | keine |
| Edit-Mode-Controller | `ui/home/WallpaperEditController.kt` | 560 | ja (Views) | keine |
| Layer-View-Holder | `ui/home/WallpaperLayer.kt` | 154 | ja | keine |
| Composite-Cache | `ui/home/wallpaper/WallpaperCompositeCache.kt` | 85 | hält `Bitmap` | keine |
| Flattener | `ui/home/wallpaper/WallpaperFlattener.kt` | 153 | ja (Canvas/Bitmap) | keine |
| Rebuild-Plan | `ui/home/wallpaper/RebuildPlan.kt` | 272 | teils (1 gfx) | keine |
| Bounded-Decoder + `DecodedWallpaperBitmap` | `ui/home/wallpaper/BoundedBitmapDecoder.kt` | 93 | ja (BitmapFactory) | keine |
| Edit-Transition | `ui/home/wallpaper/WallpaperEditTransition.kt` | 101 | referenziert `ZoomableImageView` | an die View gekoppelt |
| FAB-Cluster | `ui/home/wallpaperfab/*` (Constants, CommandsPanel, FabDragHandler, FabPositionMath, SpeedDialFabCluster) | — | gemischt (Math/Drag rein, Panel Views) | keine |
| ViewModel-Orchestrator | `ui/main/delegate/WallpaperDelegate.kt` | 1193 | indirekt | keine (0 Produkt-Hits) |
| Luminanz-Impl | `data/wallpaper/WallpaperBitmapLuminanceImpl.kt` | 260 | ja (Bitmap/BitmapFactory) | keine |
| Luminanz-Port | `domain/repository/WallpaperBitmapLuminance.kt` | 35 | nein | keine |
| Backdrop/Surface-Modelle | `domain/model/{WallpaperBackdrop,WallpaperSurfaceMode,DomainWallpaperColors}.kt` | ~65 | nein | keine (reine Enums/Werte) |
| Render-nahe Use-Cases | Observe/Save/Set/Clear-WallpaperState, ObserveWallpaperBackdrop/SetBackdrop, ClassifyWallpaper, ResolveWallpaperSurface, Get/SetWallpaperScrimAlpha | klein | nein | **Scrim/Backdrop/Surface lesen `SettingsRepository`** (die fette) |
| Bild-Picker | `ui/util/WallpaperImagePicker.kt` | 80 | ja (ActivityResult) | app-lokal |
| Drawer-Surface-Farbe | `ui/util/WallpaperSurface.kt` | 27 | ja (Context) | app-lokal |

**Home-Verdrahtung heute (Kolibri):**
- `res/layout/fragment_home.xml`: `@id/wallpaperContainer` → `ZoomableImageView`
  (`@id/wallpaperView`, Layer 0), darüber `@id/wallpaperScrim`, Edit-UI als
  `ViewStub` (`view_wallpaper_edit_overlay.xml`).
- `res/layout/activity_main.xml`: `@id/wallpaper_backdrop` — ein **Activity-owned
  opaker Sockel** unter dem Fragment (TRANSPARENT = System-Wallpaper via
  `FLAG_SHOW_WALLPAPER`, oder BLACK). Anti-Flash-Mechanismus.
- `HomeFragment` instanziiert `WallpaperViewBinder`, `WallpaperRenderScheduler`,
  `WallpaperEditController`, hält `WallpaperCompositeCache`.

**nyx heute:** **kein** Wallpaper-Code; reiner System-Wallpaper-Konsument
(`windowShowWallpaper=true`, transparentes Fenster). Home = `DragLayer`
(FrameLayout-Root) mit `home_content` (Uhr + `ViewPager2` + Dock), Drawer-Overlay,
Remove-Bar.

---

## §1 Zielbild

- Render-Surface, Binder, Edit-Controller, Cache, Flattener, Decoder und der
  FAB-Cluster liegen **geteilt** in `:common-ui`; die Luminanz-Impl in
  `:common-data`. Beide Launcher benutzen exakt dieselbe Wallpaper-Render-Maschine.
- Die **eine echte per-App-Naht** ist ein schmaler **`WallpaperHost`-Port**: die App
  liefert den Container (in den gerendert wird), die Scrim-View und die
  Backdrop-Steuerung; die geteilte Maschine rendert hinein und weiß NICHT, *wo* im
  Home das sitzt.
- Die render-nahen Settings (Scrim-Alpha, Backdrop, Surface-Mode) hängen an einem
  **schmalen `WallpaperDisplaySettings`-Port** (wie `TimeInfoSettings`), nicht an
  der fetten `SettingsRepository`.
- **nyx bekommt ein eigenes Wallpaper**, indem es (a) den `WallpaperHost`
  implementiert (Container hinter dem Grid), (b) `WallpaperDisplaySettings` über
  seinen Store bindet, (c) Bild-Picker + `READ_MEDIA`-UX beisteuert — **kein**
  duplizierter Render-/Decode-Code.

---

## §2 Was ist neutral, was bleibt pro App

> **WSS-INV-1 — Rendering ist produktfrei.** Keine der geteilten Render-Einheiten
> (View, Binder, Edit-Controller, Cache, Flattener, Decoder, FAB-Math) referenziert
> `AppInfo`/`HomeLayout`/Favoriten/Grid. Sie rendern einen `WallpaperState` in einen
> von der App gestellten Container — nichts weiter.

| Teil | Neutral (geteilt) | Bleibt pro App |
|---|---|---|
| `ZoomableImageView`, `WallpaperLayer`, `WallpaperViewBinder`, `WallpaperEditController`, `WallpaperCompositeCache`, `WallpaperFlattener`, `RebuildPlan`, `BoundedBitmapDecoder`, `WallpaperEditTransition`, FAB-Cluster | ✅ `:common-ui` | |
| `WallpaperBitmapLuminanceImpl` (+ Port), `WallpaperBackdrop`/`SurfaceMode`/Colors | ✅ `:common-data` / `:core` | |
| Render-nahe Use-Cases + `WallpaperDisplaySettings`-Port | ✅ `:core` | |
| `WallpaperDelegate` (Orchestrator) | ✅ `:common-ui` (oder geteilter VM-Baustein) | |
| **`WallpaperHost`-Impl** (wo Container/Scrim/Backdrop im Home sitzen) | | ✅ `:app-*` |
| Bild-Picker, Storage/`READ_MEDIA`-Permission-UX | | ✅ `:app-*` |
| Rebuild-Trigger (Drawer→Home), Backdrop-Fenster-Flag | | ✅ `:app-*` (Naht, §6) |

---

## §3 Modul-Zuordnung

| Einheit | Zielmodul |
|---|---|
| `WallpaperBackdrop`, `WallpaperSurfaceMode`, `DomainWallpaperColors`, `WallpaperDisplaySettings`-Port, render-nahe Use-Cases | `:core` |
| `WallpaperBitmapLuminanceImpl` (+ `WallpaperBitmapLuminance`-Port) | `:common-data` / `:core` |
| `ZoomableImageView`, `WallpaperLayer`, `WallpaperViewBinder`, `WallpaperEditController`, `WallpaperCompositeCache`, `WallpaperFlattener`, `RebuildPlan`, `BoundedBitmapDecoder`, `WallpaperEditTransition`, FAB-Cluster, `WallpaperDelegate`, `WallpaperHost`-Port | `:common-ui` |
| `WallpaperHost`-Impl, Bild-Picker, Permission-UX, *Platzierung* im Home, Backdrop-Fenster-Flag, Rebuild-Trigger-Impl | `:app-nyx` / `:app-kolibri` |

---

## §4 Die Integrationsnaht — `WallpaperHost` (der Kern)

Kolibri rendert in `wallpaperContainer` (FrameLayout) im `HomeFragment`; nyx müsste
in einen Container **unter** dem Grid rendern. Statt das in die geteilte Maschine zu
verdrahten, stellt die App die Views bereit:

```kotlin
// :common-ui — die App implementiert das, die Render-Maschine konsumiert es
interface WallpaperHost {
    /** FrameLayout, in das der/die Layer-View(s) gerendert werden (Layer 0…n). */
    val wallpaperContainer: FrameLayout
    /** View über dem Wallpaper, dessen Alpha der Scrim steuert (ScrimRender, :core). */
    val wallpaperScrim: View
    /** Setzt den opaken Sockel: custom Wallpaper ⇒ opak; sonst System-Wallpaper. */
    fun setBackdrop(backdrop: WallpaperBackdrop)
}
```

> **WSS-INV-2 — Die Render-Maschine kennt nur den `WallpaperHost`, nicht das Home.**
> `WallpaperViewBinder`/`WallpaperEditController` bekommen einen `WallpaperHost`
> injiziert und rendern in `host.wallpaperContainer`. Sie wissen nichts von
> Favoriten-Liste vs. Grid, von `HomeFragment` vs. `MainActivity`, von `ViewPager2`
> oder Dock. Das ist die einzige Stelle, an der Liste-vs-Grid überhaupt vorkommt.

**Platzierung pro App (die einzige echte Design-Entscheidung):**
- **Kolibri:** `WallpaperHost` = das bestehende `wallpaperContainer` + `wallpaperScrim`
  im `HomeFragment`; `setBackdrop` steuert `activity_main.xml`'s `wallpaper_backdrop`.
  Verhalten unverändert.
- **nyx:** `WallpaperHost` = ein neuer FrameLayout **als unterster Layer der
  `DragLayer`** (unter `home_content`), edge-to-edge, plus eine Scrim-View darüber.
  `setBackdrop` folgt der **User-Wahl** (`WallpaperBackdrop`, §6): `SYSTEM_WALLPAPER`
  ⇒ Fenster transparent + `FLAG_SHOW_WALLPAPER` (System-Wallpaper scheint durch);
  `BLACK` ⇒ opak. Die custom Composite-Layer rendern **in beiden Fällen** in den
  Container darüber — bei `SYSTEM_WALLPAPER` bauen transparente Collagen also auf dem
  live System-Wallpaper auf. nyx spiegelt Kolibris Backdrop-Schalter in seinen Settings.

> **WSS-INV-3 — Wallpaper liegt unter dem Home-Inhalt, nicht drüber.** Der Container
> ist der unterste Z-Layer; Grid/Dock/Drawer (nyx) bzw. Favoriten (Kolibri) liegen
> darüber. Der Scrim sitzt zwischen Wallpaper und Inhalt. Der Drag-/Gesten-Stack von
> nyx (`DragLayer.dispatchTouchEvent`) bleibt unberührt — der Wallpaper-Container ist
> nicht klickbar außer im Edit-Mode.

---

## §5 Settings-Port — `WallpaperDisplaySettings`

Scrim-Alpha, Backdrop und Surface-Mode sind heute an Kolibris fette
`SettingsRepository` getippt. Wie bei `TimeInfoSettings` ein schmaler Port:

```kotlin
// :core (geteilt)
interface WallpaperDisplaySettings {
    val scrimAlphaFlow: Flow<Float>
    val backdropFlow: Flow<WallpaperBackdrop>
    val surfaceModeFlow: Flow<WallpaperSurfaceMode>
    suspend fun setScrimAlpha(alpha: Float)
    suspend fun setBackdrop(backdrop: WallpaperBackdrop)
    suspend fun setSurfaceMode(mode: WallpaperSurfaceMode)
}
```

> **WSS-INV-4 — Nur diese Flags, nie der ganze Store.** Kein geteilter Render-Typ
> importiert die produktspezifische `SettingsRepository`. Kolibris `SettingsRepository`
> implementiert `WallpaperDisplaySettings` direkt; nyx bindet einen kleinen Adapter
> über seine drei Keys. Der `WallpaperState` selbst lebt weiter im
> `WallpaperRepository` (`:core`), nicht hier.

---

## §6 Rebuild-Trigger, Backdrop & Anti-Flash (per-App-Naht)

`WALLPAPER_DRAWER_HOME_REBUILD_SPEC` (Kolibri) beschreibt, wie beim Drawer→Home-
Wechsel ohne Flash neu aufgebaut wird (opaker Sockel + `WallpaperCompositeCache`-
Reattach statt Re-Decode). Das bleibt **pro App**, weil „wo ist der Drawer, wann
kommt Home zurück" produktspezifisch ist — beide haben aber einen Drawer-Overlay,
also ist die Mechanik (Cache-Reattach) geteilt, nur der Trigger app-lokal.

> **WSS-INV-5 — Cache-Reattach geteilt, Trigger app-lokal.** Der
> `WallpaperCompositeCache` (geteilt) hält den komponierten Bitmap über den
> Drawer-Zyklus; jede App entscheidet, *wann* sie `WallpaperViewBinder` zum
> Reattach/Rebuild anstößt (Kolibri: Fragment-Lifecycle; nyx: `showDrawer`/
> `hideDrawer` in `MainActivity`). Kein Re-Decode beim Zurückkehren.

> **WSS-INV-6 — Backdrop ist eine User-Wahl, kein Automatismus.** `WallpaperBackdrop`
> (`SYSTEM_WALLPAPER`/`BLACK`) kommt aus den Settings (`WallpaperDisplaySettings`), NICHT
> aus „ist ein custom Wallpaper aktiv". `SYSTEM_WALLPAPER` ⇒ transparentes Fenster +
> `FLAG_SHOW_WALLPAPER` (System-Wallpaper sichtbar); `BLACK` ⇒ opak. Die custom
> Composite-Layer rendern in beiden Fällen darüber — bei `SYSTEM_WALLPAPER` können
> **transparente Collagen bewusst auf dem live System-Wallpaper aufbauen** (durch die
> transparenten Bereiche scheint es durch). `setBackdrop` wendet nur die gewählte
> Option auf das Fenster an. (Der Anti-Flash-Aspekt beim Drawer→Home-Wechsel ist davon
> unabhängig, WSS-INV-5.)

---

## §7 Testplan (Rule 10 + Testkonvention)

- **Bereits JVM-getestet & geteilt (`:core.wallpaper`):** RenderScheduler,
  CompositeKey, SaveAction, EditTransition-Zustandslogik, Downsampling, ScrimRender,
  MemoryReport — wandern nicht, sind schon da.
- **Robolectric/`androidTest` (Gerätewahrheit, wandern mit):** `ZoomableImageView`
  Bitmap-Draw (androidTest: `Canvas` auf recycled Bitmap), `WallpaperFlattener`
  (androidTest), `WallpaperViewBinder` Diff/Parallel-Decode/Cancellation (JVM +
  Robolectric), `WallpaperBitmapLuminanceImpl`, Bounded-Decode.
- **Neu (dünn):** `WallpaperHost`-Fake (Container/Scrim/Backdrop-Spy) → Binder rendert
  gegen den Fake; `WallpaperDisplaySettings`-Contract-Triple (Fake + je-App-Impl).

> **WSS-INV-7 — Verschoben, nicht verwässert.** Kolibris bestehende Wallpaper-View-/
> Binder-/Luminanz-Tests wandern mit in die geteilten Module und bleiben grün.

---

## §8 Migration in grün-bleibenden Phasen

Voraussetzung: `:core`/`:common-ui`/`:common-data` existieren (✅); Persistenz + reine
Render-Logik geteilt (✅).

- **WV1 — Modelle + Settings-Port.** `WallpaperBackdrop`/`SurfaceMode`/Colors → `:core`;
  `WallpaperDisplaySettings`-Port einführen, Kolibri-`SettingsRepository` implementiert
  ihn, render-nahe Use-Cases auf den Port umhängen. Kolibri grün.
- **WV2 — Luminanz.** `WallpaperBitmapLuminanceImpl` (+ Port) → `:common-data`/`:core`.
- **WV3 — `WallpaperHost` + Binder-Kern.** `WallpaperHost`-Port + `WallpaperViewBinder`
  + `WallpaperCompositeCache` + `BoundedBitmapDecoder` + `WallpaperFlattener` +
  `RebuildPlan` → `:common-ui`; Kolibri implementiert `WallpaperHost` über sein
  bestehendes `wallpaperContainer`. Verhalten unverändert.
- **WV4 — View + Edit.** `ZoomableImageView`, `WallpaperLayer`,
  `WallpaperEditController`, `WallpaperEditTransition`, FAB-Cluster, `WallpaperDelegate`
  → `:common-ui`, neutraler Namespace. Kolibri zeigt drauf, alte Kopien weg.
- **WV5 — nyx dockt an.** nyx implementiert `WallpaperHost` (Container als unterster
  DragLayer-Layer + Scrim), bindet `WallpaperDisplaySettings` über seinen Store
  (inkl. **Backdrop-Schalter** transparent/opak in den Settings), Backdrop-Fenster-
  Schaltung (`FLAG_SHOW_WALLPAPER` ↔ opak), Rebuild-Trigger an `showDrawer`/
  `hideDrawer`. Bild-Auswahl via `READ_MEDIA_IMAGES` + Galerie-Picker (Runtime-
  Permission-UX wie C3a); **reduzierter Edit-Mode** (Bild setzen/löschen + Zoom/Pan,
  kein FAB-Speed-Dial). **Ab hier hat nyx ein eigenes Wallpaper.**

---

## §9 Entscheide (Review-Runde 1, 2026-09-12)

**Entschieden:**
- **Backdrop = User-Wahl, nicht Automatismus** (WSS-INV-6). Transparent
  (`SYSTEM_WALLPAPER`) muss auch **mit** custom Wallpaper möglich sein — der
  Maintainer nutzt Collagen, die bewusst auf dem live System-Wallpaper aufbauen. nyx
  bekommt Kolibris Backdrop-Schalter in den Settings.
- **`WallpaperHost` als Interface** (testbarer Fake, klare per-App-Impl).
- **`WallpaperDelegate` (1193 LOC) → `:common-ui`**, geteilter Baustein. Kolibris
  `LauncherViewModel` und nyx' `MainActivity` halten ihn gleichermaßen (nyx hat kein
  Home-VM — hält ihn wie den `ClockDelegate`).
- **FAB-Edit-Cluster in nyx v1: reduziert** — nur Bild setzen/löschen (+ Zoom/Pan der
  Layer), kein voller Speed-Dial. Voll-Edit später. WV5-Scope-Cut.
- **Bild-Auswahl in nyx: `READ_MEDIA_IMAGES` + Galerie-Picker** (nicht der
  permission-lose Photo-Picker) — deckt Multi-Bild-Collagen + Kolibris bestehenden
  `WallpaperImagePicker`-Pfad. Runtime-Permission-UX wie beim Kalender (analog C3a).

**Folge-Arbeit (kein offener Punkt):**
- `WALLPAPER_RESTORE_SPEC` W3/W4 (Backup-Blob-Naht) bleibt backup-gated und
  orthogonal zu diesem Spec.

---

## Review-Log

| Runde | Datum | Reviewer | Ergebnis |
|---|---|---|---|
| 1 | 2026-09-12 | Maintainer | Entscheide gesetzt (§9): Backdrop = User-Wahl (transparent auch mit custom Wallpaper, WSS-INV-6 korrigiert); `WallpaperHost`=Interface; `WallpaperDelegate`→`:common-ui`; nyx-Edit v1 reduziert; nyx-Bildwahl via `READ_MEDIA_IMAGES` |
