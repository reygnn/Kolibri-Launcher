# WALLPAPER_ACTIVITY_HOSTING_SPEC

> **Status: IMPLEMENTIERT (P1–P6, gestapelte Branches, NOCH NICHT GEMERGT — 2026-09-27).**
> Umsetzungsstand siehe Abschnitt gleich unten; Merge nur auf ausdrückliches Kommando.
> Promotet die Exploration `WALLPAPER_ACTIVITY_HOSTING_EXPLORATION.md` (§25) zur
> Implementierungs-Spec. Die Wallpaper-Render-Surface zieht von `HomeFragment`
> (Fragment-hosted) auf Activity-Level (persistent), analog zu nyx. Vor dem Bau
> gegen den (stark getesteten) Wallpaper-Pfad steht diese Spec + ein separater Go
> pro Phase. Geschwister: `WALLPAPER_COMPOSITE_LIFECYCLE_SPEC` (v4, der Composite-
> Cache, der hier erhalten bleibt), `WALLPAPER_DRAWER_HOME_REBUILD_SPEC`,
> `../../../docs/specs/WALLPAPER_SHARE_SPEC.md` (§4 `WallpaperHost`, die Naht, die
> hier bewusst aufgeschoben wird).

Grundlage: A17-GPU-Spike (2026-09-27, in der Exploration verankert) + drei
Recherche-Befunde derselben Session (3 Explore-Agents), die die ursprüngliche
§25-Rechnung verschieben. Diese Spec baut auf **verifizierten** Code-Ankern; alle
Zeilennummern sind Stand 2026-09-27 und als Orientierung, nicht als Vertrag, zu lesen.

---

## Umsetzungsstand (2026-09-27)

Phasenweise implementiert auf gestapelten Branches
`feature/wallpaper-activity-hosting-p1…p6` (je committet + gepusht, **noch nicht gemergt**).
Jede Phase mit `:kolibri:app:assembleDebug` + `checkConventions` + `checkRule13` + Unit-Tests
grün verifiziert; **P3 zusätzlich A17-device-verifiziert** (volle Edit-Session: Enter, Add-Layer,
Save, Cancel/Exit, drawer→home-Persistenz, kein Crash).

- **P1** — Layout-Gerüst (`activity_main.xml`: Container/View/Scrim/Stub, gone/unverdrahtet).
- **P2** — Prep: `WallpaperEditController` auf explizite Views (host-agnostisch), Spec-Re-Cut.
- **P3** — Render-Surface **+** Edit-Controller gemeinsam → `MainActivity` (Kopplung §2.4);
  A17-verifiziert.
- **P4** — vestigialen Single-Layer-Cache-Pfad zurückgebaut; Luminanz-Clear-Guard ergänzt.
- **P5** — `wallpaper_backdrop`-View → `wallpaperContainer.background` (nyx-Parität).
- **P6** — Sweep + Endverifikation (dieses Doc; voller Stack grün).

Offen (Folge, **nicht** Teil dieses Umbaus): Option 2B (Single-Slot-Composite-Holder statt LRU,
§2.5), der positive Luminanz-Emit-Guard (braucht Robolectric, §2.6), der `WallpaperHost`-Port
(WSS §4, §2.8). Optionaler eager-BLACK-Backdrop-Seed (§2.7) — das First-Frame-Verhalten ist
aktuell identisch zur alten View (transparent-Default), also keine Regression.

## 0. Entscheidung & warum (die ehrliche Payoff-Lage)

§25 wollte ursprünglich den *teardown-getriebenen* Composite-Cache-Apparat entfernen:
Kolibri hostet die Surface im `HomeFragment`, das bei drawer→home zerstört+neu gebaut
wird — und genau diese Teardown-Lücke erzwang `WallpaperCompositeCache` + `refillCache`
+ Anti-Flash-Backdrop. Der Umzug auf Activity-Level (persistent, wie nyx) sollte den
ganzen Apparat überflüssig machen.

**Drei Befunde (§1) verschieben das:** der Teardown passiert längst nicht mehr (der
Cache ist schon vestigial), und der Teil, der *bleiben muss* (der Flatten, spike-
gelockt), steckt genau in diesem Cache. Der „Cache weg"-Nutzen ist damit weitgehend
verdampft.

**Was voller §25 dann noch liefert — und warum wir ihn trotzdem fahren
(Maintainer-Entscheidung, kein gefeuerter Trigger):**
- **Architektur-Konsistenz mit nyx** — eine Surface-Hosting-Story für beide Launcher
  statt zwei divergenten. Vorbedingung für einen künftigen echten `WallpaperHost`
  (WSS §4).
- **Rückbau des *vestigialen* Single-Layer-Re-Decode-Pfads** (`warmSingleLayer`) und
  der **redundanten `wallpaper_backdrop`-View** (nyx-Parität: Backdrop = Container-
  Background).
- Die Surface lebt an ihrem natürlichen Ort (dort, wo `wallpaper_backdrop` schon
  sitzt), statt in einem View-Lifecycle, der sie gar nicht mehr zerstört.

Was §25 **nicht** mehr ist: ein „Cache-Killer". Der Composite-Cache bleibt (§2.5).

---

## 1. Die drei Recherche-Befunde

**Befund 1 — die Teardown-Prämisse ist hinfällig.** Kolibri reißt `HomeFragment` bei
drawer→home **nicht mehr** ab: der AppDrawer ist ein sichtbarkeits-getoggeltes Overlay
(`drawer_container` in `activity_main.xml`, gezeigt via `DrawerOverlayController`),
**kein** Nav-Ziel mehr — `nav_graph.xml` hat nur noch `homeFragment`. Zudem trägt
`MainActivity` `configChanges=orientation|screenSize|screenLayout|keyboardHidden|uiMode`
(Manifest), also recreaten Rotation/Fold weder Activity noch Fragment-View.
`HomeFragment.onDestroyView` feuert somit nur bei **Prozess-Tod / Activity-Finish** —
und dort stirbt der app-scoped Cache ohnehin mit. Der teardown-survival-Nutzen des
Caches ist tot; die KDoc (`WallpaperCompositeCache.kt:11`, `HomeFragment.kt:283`,
`MainActivity.kt:528`) beschreibt noch die alte drawer→home-Welt.

**Befund 2 — Flatten und Cache sind verhakt.** Der per-Frame-GPU-Flatten (N Layer → 1
Textur im DISPLAY-Modus), den der A17-Spike **gelockt** hat (1 geflachte Textur =
60 fps bombenfest; 2+ Live-Layer bei aktivem Redraw = ~35 fps, Stufe bei 1→2), ist
über den Composite-Cache implementiert: `WallpaperDelegate.warmComposite` flacht
(`WallpaperFlattener`) → HARDWARE-Bitmap → `compositeCache.put(compositeKey)`;
`HomeFragment.displayTargetFor`/`compositeCacheKeyIfHit` liest sie →
`WallpaperState.single(key)` → Single-Textur-Renderpfad. **„Cache weg, Flatten
behalten" ist nicht sauber trennbar** — die geflachte Textur *ist* der Cache-Inhalt.

**Befund 3 — versteckte Luminanz-Kopplung.** `warmComposite` ist der **Produzent** des
`CompositeLuminanceSignal` (`core/.../CompositeLuminanceSignal.kt`, `@Singleton
StateFlow<Float?>`), das der AUTO-Surface-Classifier
(`ClassifyWallpaperUseCase.kt:88`) konsumiert. Emit-Stelle:
`WallpaperDelegate.kt:934`. **Kein Test bewacht den Produce→Consume-Feed** an der
Delegate-Grenze — löscht man `warmComposite`, geht AUTO für Multi-Layer still dunkel.

---

## 2. Zielarchitektur

### 2.1 Hosting-Strategie — Hybrid (nur die View-Schicht zieht um)

> **WAH-INV-1 — Die Engine bleibt im ViewModel; nur die View-Schicht wird Activity-
> gehostet.** Kolibris `LauncherViewModel` ist `activityViewModels`-scoped und
> überlebt schon heute die Fragment-View-Recreation. `WallpaperDelegate` (State,
> Edit-Session, `refillCache`-Composite-Zweig, `warmComposite`, Luminanz, Backdrop-
> Persistenz, `onDisplayConfigChanged`, `leaveEditMode`) bleibt **unangetastet** in der
> VM. Damit bleiben die transaktionale Edit-Session und ihre ~Dutzend Regressionstests
> stehen. Nur die View-Schicht bewegt sich Fragment→Activity.

**Bleibt in `WallpaperDelegate` (VM):** `wallpaperState`, `isWallpaperEditMode`, die
komplette Edit-Session (Snapshot/Commit/Cancel, add/remove/swap, deferred deletes,
`pendingFocusLayerId`, `fabPosition`), `refillCache` (Composite-Zweig), `warmComposite`,
`CompositeLuminanceSignal`-Emit, `wallpaperBackdrop`-Persistenz, `onDisplayConfigChanged`,
`leaveEditMode`.

**Zieht von `HomeFragment` nach `MainActivity`** (gebunden an `lifecycleScope` statt
`viewLifecycleOwner.lifecycleScope`):
- View-Refs `wallpaperView` (ZoomableImageView) / `wallpaperContainer` / `wallpaperScrim`
- `wallpaperViewBinder` (Feld, `HomeFragment.kt:370`), `wallpaperRenderScheduler`
  (`:391`)
- `updateWallpaper` (`:1547`), `displayTargetFor` (`:1600`), `compositeCacheKeyIfHit`
  (`:1615`), `loadBitmapFromUri` (`:1624`), `applyScrim` (`:716`)
- `@Inject compositeCache` (`:286`)
- Observer von `viewModel.wallpaperState` (→ `updateWallpaper`), `scrimAlphaState`
  (→ `applyScrim`), `isWallpaperEditMode` (→ Edit-Controller + `applyScrim` + Toggle-
  Re-Render), `fabPosition`, `wallpaperBackdrop`
- die vier `wallpaperView`-Callbacks + `wallpaperRenderScheduler.cancel()` →
  von `onDestroyView` nach `MainActivity.onDestroy`
- der Wallpaper-Zweig von `onConfigurationChanged` (`HomeFragment.kt:517-529` →
  MainActivity, die den Config-Change ohnehin erhält)

**Verworfen — Option A (alles als Activity-Felder, wie nyx).** nyx hält die ganze
Render-Maschine als Activity-Felder (ClockDelegate-Muster) **nur, weil es kein
Home-VM hat** und dafür `NyxWallpaperEditCoordinator` erfinden musste. Für Kolibri
hieße das, eine getestete VM-Delegate unnötig auszuweiden. Hybrid ist strikt kleiner.

### 2.2 Layout-Umbau

`activity_main.xml` bekommt einen `wallpaper_container` (FrameLayout) als **unterstes
z-Kind** mit `wallpaper_view` (ZoomableImageView, `scaleType=matrix`, `gone`) +
`wallpaper_scrim` (View, `gone`) darin, und den `wallpaperEditOverlayStub` (ViewStub)
als **oberstes** Kind — spiegelt `nyx/app/src/main/res/layout/activity_main.xml`.

```
FrameLayout (root)
├─ wallpaper_container (FrameLayout)     ← NEU, unterstes z (übernimmt Backdrop-Rolle, §2.7)
│   ├─ wallpaper_view (ZoomableImageView, gone)   ← aus fragment_home.xml
│   └─ wallpaper_scrim (View, gone)                ← aus fragment_home.xml
├─ nav_host_fragment (NavHost)           ← unverändert (Home-Content zeichnet drüber)
├─ drawer_container (Overlay, gone)      ← unverändert
└─ wallpaperEditOverlayStub (ViewStub)   ← aus fragment_home.xml (oberstes)
```

> **WAH-INV-2 — Wallpaper unter dem Home-Inhalt (= WSS-INV-3).** Der Container ist der
> unterste Z-Layer; der NavHost (Favoriten/Home) liegt darüber, der Scrim dazwischen.

`fragment_home.xml` verliert die äußere `wallpaperContainer`-FrameLayout, `wallpaperView`,
`wallpaperScrim`, `wallpaperEditOverlayStub`; neue Wurzel = der `homeGestureRoot`-Subtree
(`HomeGestureLayout`).

### 2.3 Edit-Controller re-hosten

`WallpaperEditController` (`kolibri/.../ui/home/WallpaperEditController.kt`) wird von
`FragmentHomeBinding` + `LauncherViewModel` (`HomeFragment.kt:479`) auf Activity-
erreichbare Views umparametrisiert — Muster: `NyxWallpaperEditController`s Konstruktor
(`stub`, `wallpaperView`, `dimTarget`, `viewModel`, `launchLayerPicker`,
`rerenderWallpaper`):
- `binding.wallpaperView` → Activity-`wallpaperView`
- `binding.wallpaperEditOverlayStub` → Activity-Stub
- **`dimTarget` (`WallpaperEditController.kt:124`, heute `binding.rootLayout`) →
  `findViewById(R.id.nav_host_fragment)`** — das Activity-Äquivalent des gedimmten
  Home-Inhalts (nyx dimmt `home_content`). **Der einzige nicht-mechanische
  Re-Host-Punkt.**

Bleibt app-lokal, **MainActivity-owned**, an Activity-Lifecycle gekoppelt (nicht
geteilt — §2.8). Der `registerForActivityResult`-Bild-Picker (`layerPickerLauncher` +
`WallpaperImagePicker`) zieht nach MainActivity, registriert als **Feld-Initializer
oder in `onCreate`** (nyx: Feld-Initializer, `MainActivity.kt:173`) — Pflicht wegen des
`registerForActivityResult`-Placement-Gates (`checkConventions`).

### 2.4 Observer-Split (load-bearing) + Render/Edit-Kopplung

**Kopplungs-Befund (aus dem Code, treibt die P2/P3-Grenze):** Render- und Edit-Schicht
zielen auf denselben `wallpaperView`, und `HomeFragment`s **Observer 8**
(`isWallpaperEditMode`) macht in EINEM Block `wallpaperEditController.applyEditMode()` +
`applyScrim()` + Toggle-`updateWallpaper()` + `applyWallpaperEditModeToGestures()`. Der
`WallpaperEditController` wurde zudem mit dem ganzen `FragmentHomeBinding` konstruiert.
Darum sind Render und Edit **nicht getrennt umziehbar** — sie wandern gemeinsam (P3),
nachdem der Controller in P2 host-agnostisch gemacht wurde. Einzig die Gesten-Sperre bleibt
im Fragment. Nach dem Umzug:
- **MainActivity** observt `isWallpaperEditMode` → Edit-Controller + `applyScrim` +
  Toggle-Re-Render.
- **HomeFragment** behält einen *separaten* Observer desselben `isWallpaperEditMode`-
  Flows → nur `applyWallpaperEditModeToGestures` (Gesten sperren während des Edits).

> **WAH-INV-3 — Zwei Observer auf einem StateFlow ist gewollt.** Der Edit-Modus-Flow
> hat nach dem Split zwei Konsumenten (Activity: Render/Scrim/Controller; Fragment:
> Gesten-Sperre). Das ist korrekt, kein Duplikat.

### 2.5 Flatten / Single-Textur-DISPLAY-Pfad — Option 2A (empfohlen)

> **WAH-INV-4 — DISPLAY rendert eine geflachte Textur, EDIT rendert N live.** Der
> A17-Spike lockt das: 1 geflachte Textur hält 60 fps, 2+ Live-Layer bei aktivem
> Redraw fallen auf ~35 fps. `compositeCacheKeyIfHit` gibt im Edit-Modus `null` →
> EDIT immer N live; DISPLAY bei Multi-Layer-Cache-Hit die geflachte Single-Textur.

`WallpaperCompositeCache` **bleibt** die Heimat der geflachten HARDWARE-Textur; nur
seine *Begründung* (teardown-survival, Befund 1) entfällt. Konkret:
- Die drei **Reader**-Teile ziehen verbatim Fragment→Activity: `displayTargetFor`,
  `compositeCacheKeyIfHit`, der `composite://`-Zweig von `loadBitmapFromUri`.
  MainActivity liest `context.resources.displayMetrics` — **dieselbe** Metrik-Quelle
  wie die Delegate-Schreibseite (`WallpaperDelegate.kt:853-861`), sonst trifft der
  `composite://`-Key nie.

> **WAH-INV-5 — Schreib- und Leseseite lesen dieselbe `displayMetrics`.** Warmseite
> (`WallpaperDelegate`) und Leseseite (MainActivity nach dem Umzug) müssen dieselbe
> Auflösungsquelle benutzen, oder der Composite-Key ist ein Dauer-Miss.

- `warmComposite` + der Composite-Zweig von `refillCache` + die Luminanz bleiben
  **unverändert** im Delegate. Produzent (Delegate) und Konsument der Textur (Render-
  Surface) sind dieselben wie heute — nur der *Host* der Surface hat sich geändert.
- Produzent-Trigger unverändert: `leaveEditMode(finalState)` (`:1032`) +
  `onDisplayConfigChanged` (`:664`).

**Ehrlich zurückgebaut wird nur der Single-Layer-Cache-Pfad** — `warmSingleLayer`
(`:831`), der Single-Zweig von `refillCache`, der `file://`-Cache-Read in
`loadBitmapFromUri`. Der existierte allein für drawer→home-Re-Decode-Vermeidung, die
die persistente Surface tot macht (Befund 1). Ein Single-Layer-Wallpaper dekodiert
dann direkt (bounded) statt über den Cache — bei persistenter Surface einmalig, kein
Re-Decode mehr.

*Option 2B (Folge-Vereinfachung, **nicht** Teil dieses Umbaus): den LRU-`WallpaperCompositeCache`
durch einen Single-Slot-Holder ersetzen (`{HARDWARE-Bitmap, resolutionKey, luminance}`,
`@Singleton`, geformt wie `CompositeLuminanceSignal`), da eine persistente Surface
genau ein Wallpaper zeigt. Das macht `WallpaperCompositeCache` kolibri-ungenutzt (bleibt
für nyx? — nyx nutzt ihn nicht; nur `WallpaperCompositeBenchmark` bliebe Consumer) und
erlaubt, die ~12 Cache-Tests zu löschen. **Bewusst aufgeschoben** — es fasst den device-
gelockten Composite-Pfad an, für null funktionalen Gewinn beim Surface-Umzug.*

### 2.6 Luminanz-Feed (Befund 3)

Unter 2A **bleibt der Produzent im Delegate** (`warmComposite` sampelt die Luminanz aus
dem SOFTWARE-Composite vor der HARDWARE-Kopie, `:891-934`; `dropLuminanceIfCurrent` bei
Fail/Supersede). → **Kein Re-Sourcing nötig.**

> **WAH-INV-6 (Tripwire) — `warmComposite` beim Single-Layer-Rückbau NICHT löschen.**
> Es ist der einzige Produzent des `CompositeLuminanceSignal` (AUTO-Modus). Ein
> vollständiger Cache-Rückbau (2B) *plus* Löschen von `warmComposite` würde AUTO für
> Multi-Layer still dunkel schalten — dann braucht die Luminanz vorher ein neues
> Zuhause (Flatten-nur-für-Luminanz-Produzent oder Sampling im `FullRebuild` des
> Binders). Nicht Teil dieses Umbaus.

**Auflage / Stand P4:** Guard-Test für den bisher ungetesteten Feed. Umgesetzt ist die
**Clear → `null`**-Hälfte als schneller JVM-Test (`onClearWallpaper drops the composite
luminance…`). Die **Composite-Warm → `emit(value)`**-Hälfte trifft die
`Bitmap.copy(HARDWARE)`-Zeile in `warmComposite` und bräuchte deshalb **Robolectric**
(kein bestehender `WallpaperDelegateTest` ist Robolectric; die Datei ist bewusst reines
schnelles JVM) — daher **verschoben**; **WAH-INV-6** (nie `warmComposite` löschen) bleibt
der stehende Tripwire. Der bestehende Negativ-Guard (`a failed warm drops the composite
luminance…`, `emit(null)`) deckt den Fail-Pfad bereits ab.

### 2.7 Backdrop (Befund 4) — separate View droppen, Einstellung behalten

Die separate `wallpaper_backdrop`-View (`activity_main.xml:16`, getrieben von
`MainActivity.observeWallpaperBackdrop:536`) existiert laut ihrer eigenen KDoc als
Anti-Flash-Sockel gegen den drawer→home-*Fragment-View-Teardown* — der laut Befund 1
nicht mehr passiert. Mit dem nun Activity-persistenten Container kann dieser die
Backdrop-Rolle tragen, wie bei nyx:

> **WAH-INV-7 — Backdrop = Container-Background, nicht eine separate View.**
> `wallpaperContainer.background`: `SYSTEM_WALLPAPER` ⇒ `Color.TRANSPARENT` (live
> System-Wallpaper scheint durchs transparente Fenster), `BLACK` ⇒ `Color.BLACK`
> (nyx `MainActivity.kt:620-626`). Die **Einstellung `WallpaperBackdrop`
> (WSS-INV-6) bleibt** User-Wahl — nur die redundante Sibling-View geht.

**Cold-Start-First-Frame:** der Container-Background wird in `onCreate` **eager** aus
dem gehaltenen Backdrop-Wert geseedet (vor dem ersten Frame; spiegelt nyx' eager
Scrim-Seed, `MainActivity.kt:431-432`), damit ein `BLACK`-Start nie das System-
Wallpaper durchblitzt. Default bleibt `transparent`, bis der Flow emittiert (heutiges
`SYSTEM_WALLPAPER`-Verhalten erhalten).

Runtime `FLAG_SHOW_WALLPAPER` + transparentes Fenster (`setupWindow:686-690`)
**bleiben** — kein Wechsel auf nyx' Theme-basiertes `windowShowWallpaper` (orthogonal,
würde `styles.xml`/`SplashTheme` ohne funktionalen Gewinn anfassen).

*Zero-Risk-Fallback: `wallpaper_backdrop` behalten. Sie lebt schon auf Activity-Level
und funktioniert; Behalten kostet nur eine kleine nyx-Divergenz. Deshalb als letzte
Phase (§3 P5) sequenziert — droppbar bei Zeitdruck.*

### 2.8 `WallpaperHost`-Naht (WSS §4) — aufschieben

Direkt verdrahten wie nyx; den `WallpaperHost`-Port aufschieben.

> **WAH-INV-8 — Blast-Radius kolibri-only.** Den echten `WallpaperHost` jetzt zu bauen
> hieße, nyx' *ausgelieferten*, getesteten `MainActivity` + `NyxWallpaperEditController`
> auf einen Host nachzurüsten — Risiko an funktionierendem Code für null Sofortnutzen.
> `WallpaperViewBinder.bind` nimmt heute eine nackte `ZoomableImageView`; eine Naht
> re-plumbt die von *beiden* Apps genutzte Binder-API. Jede App behält ihren eigenen
> Edit-Controller (kein zweiter Consumer, den die Naht heute vereinen würde). Der
> Port bleibt der Nordstern (WSS §4) für einen dritten Consumer / echten geteilten
> Edit-Controller.

---

## 3. Phasenplan (Build + Tests je Phase grün)

Verifikation je Phase: `./gradlew test` + `./gradlew checkConventions` +
`./gradlew checkRule13`, dann A17 via `~/apk/install-aab.sh` — **Display an vor /
aus nach** jeder Device-Prüfung. Geräte-Check je Phase in der Phase benannt.

**P1 — Layout-Gerüst (kein Render-Wechsel).** `wallpaper_container`/`view`/`scrim`/Stub
in `activity_main.xml` (gone, **unverdrahtet**; Fragment rendert weiter). Zwei
ZoomableImageViews existieren, aber nur die des Fragments ist gebunden → kein
Doppel-Render. *Bricht evtl.:* Inflation, z-Order. *Device:* keine sichtbare Änderung.

**P2 — Prep: `WallpaperEditController` auf explizite Views umparametrisieren** (IN-Fragment,
kein Umzug). Statt des ganzen `FragmentHomeBinding` bekommt der Controller `wallpaperView`,
`editOverlayStub`, `dimTarget` einzeln — die drei binding-Views, die er je nutzte. Reiner
Refactor, kein Verhaltenswechsel: macht den Controller **host-agnostisch**, damit P3 ihn
ohne Body-Änderung unter der Activity re-hosten kann (Activity-Views statt Fragment-Views).
*Bricht evtl.:* nichts Funktionales (mechanisch; einzige Konstruktionsstelle
`HomeFragment.onViewCreated`, keine Test-Referenzen). *Device:* nicht nötig (kein
Verhaltenswechsel) — Build + checkConventions + Unit-Tests genügen.

**P3 — Render-Surface UND Edit-Controller GEMEINSAM → MainActivity** (`lifecycleScope`).
Zusammengelegt (Re-Cut gegenüber der ersten Planung), **weil beide auf denselben
`wallpaperView` zielen** und Observer 8 Render + Edit + Scrim in EINEM Block mischt — eine
Trennung „nur Render zuerst" erzeugte einen kaputten Zwischenzustand (Kopplungs-Befund,
§2.4). Verschoben: die View-Refs + `wallpaperViewBinder`/`wallpaperRenderScheduler` +
`updateWallpaper`/`displayTargetFor`/`compositeCacheKeyIfHit`/`loadBitmapFromUri`/`applyScrim`
+ `@Inject compositeCache` + der (in P2 host-agnostische) `WallpaperEditController`
(`dimTarget = nav_host_fragment`) + der Layer-Picker (→ `onCreate`/Feld) + der Wallpaper-Zweig
von `onConfigurationChanged`. `fragment_home.xml` verliert die Views; Fragment-Wurzel wird
`homeGestureRoot`. MainActivity observt `wallpaperState`/`scrimAlpha`/`isWallpaperEditMode`/
`fabPosition`/`wallpaperBackdrop`; HomeFragment behält nur den Gesten-Disable-Observer
(§2.4). **Rule-11-Marker mitnehmen:** `loadBitmapFromUri` trägt `Catch kept` /
`no suspension point` (MainActivity ist schon in `rule11_files`/`cancel_files`),
`decodeBoundedWallpaperBitmap` behält `Throwable`-Breite. *Bricht evtl.:*
Transparent-Window-Bleed, Scrim/Insets, Latest-wins-Race, Config-Change-Re-Render,
Edit-Re-Hosting (dimTarget, Touch-Forwarding an `wallpaperView.onTouchEvent`, Back-Press),
ActivityResult-Placement-Gate. *Device:* Single-+Multi-Layer identisch; Scrim; Rotation;
drawer→home; **volle Edit-Session** (Enter, Pan/Zoom, Add-Layer, Delete, Swap,
Backdrop-Toggle, Save, Cancel, Back-Press).

**P4 — Single-Layer-Cache-Pfad zurückbauen** (§2.5). `warmSingleLayer`, Single-Zweig
von `refillCache` + Diagnose-Toasts, `file://`-Cache-Read raus. **`warmComposite` +
Composite-`refillCache` + Luminanz bleiben** (WAH-INV-6). Luminanz-Guard-Test ergänzen
(§2.6). *Bricht evtl.:* AUTO-Classifier (Luminanz), Single-Layer-First-Decode. *Device:*
Single + Multi rendern; AUTO hell/dunkel korrekt.

**P5 — Backdrop konsolidieren** (§2.7). `wallpaper_backdrop`-View raus →
`wallpaperContainer.background` aus `WallpaperBackdrop`, eager seed in `onCreate`. Die 6
Backdrop-Tests retargeten. *Device:* Backdrop-Toggle black/system, Cold-Start-First-Frame
in beiden Modi.

**P6 — Test-Sweep + Endverifikation.** (Test-Edits landen je Phase; das ist der
Reconciliation-/Cleanup-Pass.) Voller `./gradlew test` + `checkConventions` +
`checkRule13`; A17-Smoke.

---

## 4. Test-Impact (unter 2A)

- **Löschen** — Single-Layer-Warm/Refill/Re-Decode-Tests in
  `app/src/test/.../ui/main/delegate/WallpaperDelegateTest.kt` (die an `warmSingleLayer`,
  Single-Layer-Cache-Hit-Toasts und die drawer→home-Re-Attach-Prämisse gebundene
  Teilmenge). **Composite-Warm- + Luminanz-Tests bleiben.**
- **Retargeten (nicht löschen)** — die 6 `wallpaperBackdrop`-Tests → assert
  `wallpaperContainer.background` statt der Backdrop-View (die *Einstellung* bleibt,
  §2.7).
- **Robolectric** — die Wallpaper-Surface-Assertions wandern in einen neuen
  `MainActivity`-Robolectric-Test; `HomeFragmentRobolectricTest` behält seine
  Nicht-Wallpaper-Coverage und muss nach dem Umzug (ohne `compositeCache`-Feld) weiter
  hosten.
- **Mechanisch NUR falls der Delegate-Konstruktor sich ändert — unter 2A tut er das
  NICHT:** die `compositeCache`-/`compositeLuminanceSignal`-Args in den 5
  `LauncherViewModel*Test` (`LauncherViewModelTest`, `…ContractTest`, `…DoomsdayTest`,
  `…SecurityTest`, `MonolithicLauncherViewModelTest`) bleiben unter 2A stehen. (Unter 2B
  fielen sie.)
- **Unberührt** — `WallpaperRepository`-Contract-Triple + Impl (Datenschicht, außer
  Reichweite); alle shared `:common-ui`-Render-Tests (`WallpaperViewBinder*`,
  `WallpaperViewDiff`, **`WallpaperCompositeCacheTest`** — die Klasse bleibt unter 2A
  kolibri-genutzt); androidTest TAPL + `WallpaperFlattenerInstrumentedTest` +
  `WallpaperBitmapDrawInstrumentedTest` + Backup-Round-Trips.

> **Reconciliation-Notiz:** Eine frühere Test-Impact-Kartierung sagte „Cache wird
> kolibri-ungenutzt / ~12 Cache-Tests löschen / `compositeCache` aus 5 Konstruktoren".
> Das beschreibt **Option 2B**. Diese Spec empfiehlt **2A** (Cache bleibt, nur
> Single-Layer-Pfad raus) → geringeres Risiko, und die Test-Deltas oben gelten.

---

## 5. Risiko-Register

| Risiko | Wo | Mitigation |
|---|---|---|
| **First-Frame-Flash** (System-Wallpaper sichtbar vor Custom-Paint, v.a. `BLACK`) | Cold-Start, P3/P5 | Container-Background (+ Scrim) in `onCreate` **eager** seeden; `SYSTEM_WALLPAPER` bleibt transparent. |
| **Scrim / Insets-Regression** | P3 | Scrim ist Container-Kind auf Activity-Level; `applyScrim` GONE/VISIBLE + `ScrimRender`-Edit-Suppression auf Gerät prüfen; Statusbar/edge-to-edge unberührt. |
| **Edit-Re-Hosting** (dimTarget, Touch-Forwarding, Back-Press) | P3 | `dimTarget = nav_host_fragment`; Touch-Interceptor forwardet weiter an `wallpaperView.onTouchEvent`; volle Session + Back-Press-Commit auf Gerät. Stub-Inflation bleibt lazy. |
| **Luminanz dunkel** (AUTO) | P4 | `warmComposite` als Produzent behalten (WAH-INV-6); Guard-Test auf den `CompositeLuminanceSignal`-Feed. |
| **Latest-wins-Race** auf neuer Scope | P3 | `WallpaperRenderScheduler`-Semantik unverändert; jetzt `lifecycleScope`; `cancel()` → `onDestroy`. Schneller Wallpaper-Wechsel + Rotation testen. |
| **Transparent-Window / FLAG_SHOW_WALLPAPER** | P3/P5 | Runtime-Flag + transparentes Fenster behalten (kein Theme-Wechsel). System-Wallpaper scheint durch transparente Collage-Regionen. |
| **checkConventions an verschobenen Catches** | P3 | Rule-11 (`Catch kept`) + Cancellation (`no suspension point`) Marker mit `loadBitmapFromUri`; MainActivity schon whitelisted. Picker in `onCreate`/Feld (ActivityResult-Gate). |
| **Doppel-Render** im Übergang | P1→P3 | P1 fügt Activity-Views GONE/unverdrahtet ein; die Fragment-View wird im selben Commit entfernt, der die Activity-View verdrahtet (P2). Nie zwei gebundene `ZoomableImageView`. |
| **Config-Change-Re-Flatten-Miss** (Rotation) | P3/P4 | MainActivity erhält `onConfigurationChanged`; `updateWallpaper(current)` + `onDisplayConfigChanged()` erhalten. Rotation → Single-Textur-Hit landet auf neuer Auflösung. |

---

## 6. Offene Entscheidungen (vom Maintainer beim Bau zu bestätigen)

1. **2A vs. 2B** (§2.5) — Spec empfiehlt **2A** (Cache bleibt, nur Single-Layer-Pfad
   raus). 2B (Single-Slot-Holder, Cache kolibri-frei) ist eine *spätere* Vereinfachung,
   nicht Teil dieses Umbaus.
2. **Backdrop droppen vs. behalten** (§2.7) — Spec empfiehlt **droppen** (nyx-Parität);
   Zero-Risk-Fallback „behalten" ist als letzte Phase droppbar.

---

## 7. Anker & kritische Dateien

- `kolibri/app/.../ui/main/MainActivity.kt` — neuer Host der View-Schicht;
  `observeWallpaperBackdrop:536`, `setupWindow:686`.
- `kolibri/app/.../ui/home/HomeFragment.kt` — Quelle der ziehenden View-Schicht;
  `updateWallpaper:1547`, `displayTargetFor:1600`, `compositeCacheKeyIfHit:1615`,
  `loadBitmapFromUri:1624`, `applyScrim:716`, `onDestroyView:1720`,
  `onConfigurationChanged:517-529`.
- `kolibri/app/.../ui/home/WallpaperEditController.kt` — re-parametrisieren (`:124`
  dimTarget).
- `kolibri/app/.../ui/main/delegate/WallpaperDelegate.kt` — bleibt Engine;
  `refillCache:766`, `warmComposite:871`, `warmSingleLayer:831` (raus),
  `CompositeLuminanceSignal`-Emit `:934`, `onDisplayConfigChanged:664`,
  `leaveEditMode:1032`.
- `kolibri/app/src/main/res/layout/{activity_main,fragment_home}.xml`.
- Geteilt (unberührt, wiederverwendet): `common-ui/.../wallpaper/{WallpaperViewBinder,
  ZoomableImageView,WallpaperFlattener,WallpaperCompositeCache}.kt`,
  `core/.../wallpaper/{WallpaperRenderScheduler,WallpaperCompositeKey,
  CompositeLuminanceSignal}.kt`.
- **Referenz zum Spiegeln (nyx):** `nyx/app/.../home/MainActivity.kt`
  (Felder `:210-212`, findViewById `:333-335`, Binder `:216-245`, `renderWallpaper:567`,
  `applyBackdrop:620`, eager Scrim `:431`), `nyx/app/src/main/res/layout/activity_main.xml`,
  `nyx/.../home/wallpaper/NyxWallpaperEditController.kt`.

---

## 8. Trigger & Status

Kein §25-Trigger ist formal gefeuert (kein laufender Home-Refactor, kein Cache-Bug,
die `WallpaperHost`-Naht wird nicht gebaut). Voller §25 ist eine **bewusste
Maintainer-Entscheidung** für Architektur-Konsistenz mit nyx + Rückbau des
vestigialen Single-Layer-Pfads + der redundanten Backdrop-View — kein Bugfix. Der
Composite-Cache bleibt (2A). Umsetzung phasenweise mit separatem Go pro Phase; jede
Phase hält Build + Tests grün und wird auf dem A17 verifiziert.
