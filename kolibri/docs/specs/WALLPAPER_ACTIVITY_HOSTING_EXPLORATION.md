# Wallpaper auf Activity-Level hosten — Architektur-Exploration (Handover)

**Status:** Exploration / Backlog. Kein konkreter Auftrag, sondern eine
Erkenntnis aus dem Monorepo-Merge mit `nyx`, die eine spätere
Komplexitäts-Reduktion in Kolibri ermöglicht. Angelegt aus einer nyx-Session
(2026-09-12); die Kolibri-Session entscheidet, ob/wann.

---

## Die Erkenntnis in einem Satz

Kolibri hostet die Wallpaper-Render-Surface im **Fragment** (`HomeFragment` im
NavHost), das beim drawer→home-Wechsel zerstört + neu gebaut wird — und **genau
diese Teardown-Lücke** erzwingt den `WallpaperCompositeCache` + `refillCache` +
den Activity-eigenen Anti-Flash-Backdrop. `nyx` hostet dasselbe Wallpaper in
einer **persistenten Activity-View**, die nie stirbt, und braucht deshalb **keine
dieser Mechaniken**.

## Beweis aus nyx

nyx' `MainActivity` hält `wallpaperView` (dieselbe geteilte
`:common-ui.wallpaper.ZoomableImageView`) direkt in seinem Layout — die View
wird nie zerstört. Konsequenzen, in nyx live auf A17 verifiziert:

- **Kein Re-Decode beim App-Resume.** `repeatOnLifecycle(STARTED)` re-collectet
  zwar den `wallpaperState`, aber `WallpaperViewBinder.bind(view, sameState)`
  sieht `snapshot == target` → `RebuildPlan.Noop` → kein Decode, kein Flash.
- **Kein Composite-Cache nötig.** Multi-Layer wird direkt aus den `file://`-Layern
  gerendert; es gibt keinen Zyklus, über den ein geflachter Composite überbrücken
  müsste.
- **Kein Anti-Flash-Backdrop nötig.** Es gibt keine Teardown-Lücke, durch die das
  System-Wallpaper blitzen könnte.

nyx implementiert den vollen Edit-Mode (Multi-Layer, Pan/Zoom, FAB-Cluster) **ohne**
`refillCache`/`warmComposite`/`WallpaperCompositeCache` — siehe
`nyx/.../home/wallpaper/NyxWallpaperEditCoordinator.kt` (Port von
`WallpaperDelegate` minus Cache) und `nyx/.../home/MainActivity.kt`
(`renderWallpaper` via `WallpaperRenderScheduler`, persistenter `wallpaperView`).

## Was Kolibri heute deswegen trägt

- `WallpaperCompositeCache` (jetzt in `:common-ui`) — in-memory Composite-Cache.
- `WallpaperDelegate.refillCache` / `warmComposite` / `warmSingleLayer` +
  `compositeRegenLock` + `refillInProgress` — der ganze Warm-Apparat.
- `HomeFragment.displayTargetFor` / `compositeCacheKeyIfHit` /
  `loadBitmapFromUri` (`composite://`-Auflösung) — die Render-seitige Cache-Glue.
- Der Activity-eigene `wallpaper_backdrop` in `activity_main.xml` (+
  `MainActivity.observeWallpaperBackdrop`) als opaker Sockel gegen den
  drawer→home-Flash.
- `WALLPAPER_DRAWER_HOME_REBUILD_SPEC` — die Spec, die diese Naht beschreibt.

## Der mögliche Refactor

Die Wallpaper-Render-Surface (`wallpaperContainer` + `ZoomableImageView` +
`wallpaperScrim`) aus `fragment_home.xml` auf **Activity-Level** ziehen (dorthin,
wo `wallpaper_backdrop` schon ist), sodass sie über den drawer→home-Wechsel
**persistiert**. Dann entfallen:

- `WallpaperCompositeCache` + der gesamte `refillCache`/`warmComposite`-Pfad,
- `displayTargetFor`/`compositeCacheKeyIfHit` (kein `composite://`-Umweg mehr),
- der Anti-Flash-Backdrop (keine Lücke mehr),

und `HomeFragment` würde nur noch **auf** die Activity-View zeigen statt sie zu
besitzen — analog zu nyx. Das `WallpaperHost`-Naht-Konzept aus
`docs/specs/WALLPAPER_SHARE_SPEC.md` (§4) ist genau diese Abstraktion und wäre der
saubere Aufhänger.

## Risiken & Caveats (nicht unterschätzen)

- **Groß und an verifiziertem Code.** Der Wallpaper-Pfad ist stark getestet
  (Robolectric + androidTest + Contract-Tests). Ein Umzug fasst `HomeFragment`,
  `WallpaperDelegate`, `MainActivity`, `activity_main.xml`, `fragment_home.xml`
  und die Navigation an.
- **Der Flatten-Nutzen ist nicht nur Teardown.** `WallpaperFlattener` (jetzt in
  `:common-ui`) flacht N Layer → 1 Textur. Das senkt die **per-Frame-GPU-Last**
  für Multi-Layer *unabhängig* vom Teardown. Vor dem Rückbau messen, ob ein
  persistenter Multi-Layer-View (N Texturen live) auf schwacher GPU (A17) teurer
  rendert als eine geflachte Textur. Falls ja: die Flatten-Optimierung erhalten
  (einmal flatten beim Settle, auch ohne den Reattach-Cache) statt komplett
  streichen.
- **Fragment-Lifecycle-Kopplungen.** `HomeFragment` nutzt `viewLifecycleOwner`
  für Scrim/Observer/Teardown-Nulling. Beim Umzug auf Activity-Level muss diese
  Lifecycle-Bindung neu gedacht werden (nyx: `MainActivity`-Lifecycle direkt).
- **Edge-to-edge / Insets / Backdrop-Fensterflag** (`FLAG_SHOW_WALLPAPER`,
  transparentes Fenster) müssen erhalten bleiben.

## Trigger

Angehen, wenn (a) Kolibri ohnehin einen Home-Architektur-Umbau macht (z.B. die in
`TODO.md` genannte edge-to-edge-SettingsActivity-Modernisierung wächst zu einem
Home-Refactor), (b) die Cache-Komplexität einen echten Bug produziert, oder (c)
die geteilte `WallpaperHost`-Naht (WALLPAPER_SHARE_SPEC WV-Reihe) sowieso gebaut
wird. Bis dahin ist der Cache **bewusst akzeptiert** — er funktioniert, ist
getestet, und der Rückbau ist reiner Eleganz-/Wartbarkeits-Gewinn, kein Bugfix.

## Anker

Kolibri: `ui/home/HomeFragment.kt` (`updateWallpaper`, `displayTargetFor`,
`loadBitmapFromUri`, `applyScrim`), `ui/main/delegate/WallpaperDelegate.kt`
(`refillCache`/`warmComposite`/`warmSingleLayer`), `ui/main/MainActivity.kt`
(`observeWallpaperBackdrop`, `setupWindow`), `res/layout/{fragment_home,activity_main}.xml`,
`docs/specs/WALLPAPER_DRAWER_HOME_REBUILD_SPEC*`.
Geteilt: `:common-ui.wallpaper.{WallpaperCompositeCache,WallpaperFlattener,ZoomableImageView,WallpaperViewBinder}`,
`:core.wallpaper.{WallpaperCompositeKey,WallpaperRenderScheduler}`,
`docs/specs/WALLPAPER_SHARE_SPEC.md` (§4 WallpaperHost).
nyx-Referenz (der cache-freie Beweis): `nyx/app/.../home/MainActivity.kt`,
`nyx/app/.../home/wallpaper/NyxWallpaperEditCoordinator.kt`.
