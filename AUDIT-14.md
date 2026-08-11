# AUDIT-14 — Wo Launcher laggen und bloaten, und wo Kolibri es (nicht) tut

> **Erzeugt** 2026-08-10 gegen `main` @ `bd6f23f1` (Version 0.99.152 / code 172),
> auf die Frage: *„Wo entsteht Lag und Bloat in Android-Launchern — und ist
> Kolibri betroffen?"*
> **Methode:** fünf parallele Subsystem-Scans (App-Enumeration/Icons,
> RecyclerView-Adapter, Wallpaper/Bitmap, Flow-Pipeline/Sortierung, Cold-Start),
> **danach jede zitierte Stelle selbst am Code nachgelesen** — kein Befund steht
> hier auf Zuruf eines Sub-Agents. Bereits umgesetzte Optimierungen und bewusst
> vertagte Punkte (`REACTIVE_APPLIST_SPEC`, `INSTALLED_APPS_LOAD_SPEC`,
> `KNOWN_ISSUES.md`) sind gegengeprüft und **nicht** als neue Findings gemeldet.
>
> **Kein akuter Defekt.** Nichts ruckelt hart, nichts leakt, nichts blockiert den
> ersten Frame. Kolibri ist für einen Launcher **überdurchschnittlich sauber**
> geschrieben. Was bleibt, ist **Optimierungspotenzial** — überwiegend `low`, ein
> Befund `low–moderate`. Severity-Skala wie in den Schwester-Audits.

---

## Status: GESCHLOSSEN (2026-08-10)

Alles mit tatsächlichem Impact ist umgesetzt und in `main`. Der Rest ist
ausschließlich `low` und bewusst vertagt an die §7-Re-Evaluierungs-Trigger
(nicht offen im Sinne von „vergessen").

**Umgesetzt:**

| Finding | Was | Version |
|---|---|---|
| **F1** (a/b/c, `low–moderate`) | `favoriteApps` off-Main + `distinctUntilChanged`; `distinctUntilChanged` an den vier geteilten DataStore-Quell-Flows | 0.99.153 / 0.99.154 |
| **F2** Bullet 1 + 3 | `usageFlow` nur im TIME_WEIGHTED-Modus (`flatMapLatest`); `sortOrderFlow`/`hiddenAppsFlow`-Dedupe | 0.99.154 |
| **Nit §208** | `AppInfo.displayNameLower` (vorberechneter Sort-Key) | 0.99.154 |
| **F3** (alle drei Teile) | `ColorStateList`-Cache; Payload-`onBindViewHolder`; gehoistete Listener — Hit-Test-Contract beidseitig per Emulator-Instrumented-Test verifiziert | 0.99.155 / 0.99.156 |
| **Nit §212** | `AppInfo.componentName` als vorberechneter Body-`val` | (dieser Change) |

**Bewusst vertagt (alle `low`, an §7-Trigger gebunden):**

- **F2 Bullet 2** (TIME_WEIGHTED-Recompute pro Tick) — semantisch nötig, „erst
  messen"; ernster nehmen, wenn die installierte App-Zahl spürbar wächst.
- **F4** (`getWallpaperColors`-Binder in `onCreate`) — Cold-Start-Mikro, „messen".
- **F5** (Wallpaper-Memory: Budget × Multi-Layer, kein `recycle()`) — „messen,
  dann entscheiden"; `recycle()` ist aktiv riskant. Trigger: steigende
  Multi-Layer-OOM-Reports.
- **F1 §5.3** (`applyCustomNames` ohne Sort für den Favoriten-Pfad) — Mikro-Gewinn an
  einer breit genutzten Funktion; nur mitnehmen, wenn die Pipeline ohnehin
  angefasst wird.
- **Nit §215** (`SimpleDateFormat` pro Minuten-Tick) — vernachlässigbar (1×/min).

**Nebenprodukt:** Die androidTest-Arbeit an F3 (echtes Touch-Dispatch, das
JVM/Robolectric nicht erreicht) hat die androidTest-Philosophie in `CLAUDE.md`
von einer Cost-Bar auf eine Value-Bar umgestellt.

---

## 1. Wo Launcher generell laggen — und Kolibris Stand dagegen

Die klassischen Lag-/Bloat-Quellen eines Home-Launchers, und wo Kolibri jeweils
steht (Belege in §3/§4):

| Klassische Quelle | Typischer Schaden | Kolibri |
|---|---|---|
| **Icon-Loading** (`loadIcon`/Drawable-Decode pro App/Bind, kein Cache) | Der **#1**-Launcher-Killer: Main-Thread-Decode, GC-Druck, Scroll-Jank | **Entfällt strukturell.** Text-Launcher: `AppInfo` trägt kein Drawable/Bitmap, kein einziger `loadIcon`-Aufruf im Code. Der ganze Bottleneck existiert nicht. |
| **App-Enumeration auf Main** (`queryIntentActivities` + `loadLabel` synchron) | Blockiert ersten Frame / Re-Sort | **Off-Main + reaktiv.** Enumeration auf `Dispatchers.IO`, Start/Rename ohne Enumeration (`REACTIVE_APPLIST_SPEC`, umgesetzt). |
| **RecyclerView** (kein DiffUtil, `notifyDataSetChanged`, teures Bind) | Scroll-Jank, Animations-Flackern | **Größtenteils vorbildlich.** `ListAdapter`+DiffUtil, `itemAnimator=null`, günstiges Bind, `AppDrawerAdapter` mit Payload-Partial-Rebinds. Eine Ausnahme: §3-F3. |
| **Wallpaper/Bitmap** (Full-Res-Decode auf Main, keine Downsampling, Retention) | Frame-Drops, Memory-Bloat, GC-Pausen | **Stark.** IO-Decode, Downsampling, allokationsfreies `onDraw`, Luminanz nur pro State auf 32×32. Rest: §3-F5 (Memory-Budget). |
| **Flow-Pipeline** (Full-Sort/-Map pro Emission, kein `distinctUntilChanged`, auf Main) | CPU-/GC-Last, Micro-Jank | **Gemischt.** Drawer ist mustergültig (`flowOn(Default)`+`distinctUntilChanged`); Favoriten **nicht** — §3-F1 (Headline). |
| **Suche** (Filter+Sort pro Tastendruck auf Main) | Tipp-Latenz | **Effizient.** O(N)-`contains`, kein Sort pro Tastendruck, debounced. |
| **Cold-Start** (I/O/`runBlocking` auf Main in `onCreate`) | Lange Time-to-Home | **Schlank.** ACRA-Arbeit deferred, Consent-Read ist Ein-Key (`KNOWN_ISSUES §3`). Rest: §3-F4 (ein Binder-Call). |

**Kurzfazit:** Kolibri umgeht die zwei größten Launcher-Fallen (Icons, Main-Thread-
Enumeration) architektonisch komplett. Die Findings unten sind Feinschliff, keine
Grundsatzprobleme.

---

## 2. Datenfluss-Karte (für §3 nötig)

```
InstalledAppsRepositoryImpl.appsStateFlow   (queryIntentActivities + loadLabel, flowOn IO, WhileSubscribed)
      → ObserveInstalledAppsUseCase (Veto/Reconcile)
      → InstalledAppsStateRepository.rawAppsFlow  (StateFlow<List<AppInfo>>, keep-last-good)
              ├── GetDrawerAppsUseCase.drawerApps      → combine(...) → distinctUntilChanged → flowOn(Default)   ✅
              └── GetFavoriteAppsUseCase.favoriteApps  → combine(...) → (kein distinctUntilChanged, kein flowOn)  ⚠ F1
```

**Geteilter Store:** `favorites`, `hidden`, `favoritesOrder`, `sortOrder`,
`customNames` **und** `usage` liegen alle im **einen** `settingsDataStore`. Jeder
Write darauf lässt `DataStore.data` für **alle** abgeleiteten Flows re-emittieren
— relevant für F1/F2.

---

## 3. Verifizierte Findings (nach Impact)

### F1 — Die Favoriten-Pipeline rechnet einen Full-Sort **aller** Apps auf dem **Main-Thread**, bei jedem App-Start (Headline, `low–moderate`)

Der eine Befund mit potenziell **sichtbarem** Effekt. Drei Ursachen greifen
ineinander; einzeln harmlos, zusammen ein vermeidbarer Main-Thread-Stall.

**(a) `favoriteApps` hat weder `flowOn` noch `distinctUntilChanged`.**
`GetFavoriteAppsUseCase.favoriteApps` (`domain/…/usecase/GetFavoriteAppsUseCase.kt:68-109`)
ist ein 5-Quellen-`combine`, dessen Block `applyCustomNames(rawApps, customNames)`
(`:97`) plus `processApps` (`:104`) ausführt — **ohne** abschließendes
`.flowOn(...)` und **ohne** `.distinctUntilChanged()`. Zum Vergleich der
Zwilling: `GetDrawerAppsUseCase` hat **beides** (`GetDrawerAppsUseCase.kt:113`
`distinctUntilChanged`, `:123` `flowOn(dispatcher)` mit `@DefaultDispatcher`).
Die Asymmetrie ist der Kern: was am Drawer bewusst abgesichert wurde, fehlt an
den Favoriten.

**(b) Collect läuft auf Main.** `favoriteApps` wird in
`AppManagementDelegate.start` via `scope.launchSafe { …collect… }`
(`app/…/delegate/AppManagementDelegate.kt:117-121`) kollektiert, und `launchSafe`
dispatcht auf `mainDispatcher` (`app/…/delegate/DelegateScope.kt:53`,
`coroutineScope.launch(mainDispatcher)`). Ohne `flowOn` läuft der `combine`-
Transform **im Collector-Kontext = Main**. `applyCustomNames`
(`domain/…/usecase/ApplyCustomNames.kt:26-28`) macht `.map { it.copy(...) }
.sortedBy { it.displayName.lowercase() }` über die **gesamte** installierte
Liste (alle N Apps, nicht nur die Favoriten) — voller O(N log N)-String-Sort +
N Objekt-Copies + N `lowercase()`-Allokationen, auf Main. Der Sort ist für die
Favoriten sogar **weggeworfene Arbeit**: `processApps` sortiert danach ohnehin
nach `savedOrder` und `take(MAX_FAVORITES_ON_HOME)`.

**(c) Die Quell-Flows haben kein `distinctUntilChanged` → sie feuern bei jedem
Store-Write.** Drei der fünf Quellen —
`favoriteComponentsFlow` (`FavoritesRepositoryImpl.kt:61` → `readFlowFailOpen`),
`favoriteComponentsOrderFlow` (`FavoritesOrderRepositoryImpl.kt:60` →
`readFlowFailOpen`),
`hiddenAppsFlow` (`HiddenAppsRepositoryImpl.kt:99-100` → `safeReadFlow`) —
laufen über Helfer, die **kein** `distinctUntilChanged` anhängen
(`DataStoreReadFlow.kt:32` `safeReadFlow = data.catch{}` ;
`:65-79` `readFlowFailOpen = data.catch{}.map(transform)`). Da alle im geteilten
`settingsDataStore` liegen, re-emittiert **jeder** Write — auch der Usage-Write
bei **jedem App-Start** (`recordAppLaunchUseCase` → `AppUsageRepositoryImpl.recordPackageLaunch:96`)
— diese drei Flows **unverändert** und re-triggert damit den combine.

**Die Kette:** App antippen → `onAppClicked` (`AppManagementDelegate.kt:134`)
schreibt Usage → geteilter Store re-emittiert → `favoriteComponents/​Order/​Hidden`-
Flow feuern (kein Dedup) → `favoriteApps`-combine läuft neu **auf Main** → Full-
Map+Sort aller Apps → mangels terminalem `distinctUntilChanged` wird das
(identische) Ergebnis noch downstream geschoben.

Dass `customNamesFlow` (`CustomNamesRepositoryImpl.kt`) **und** `usageFlow`
(`AppUsageRepositoryImpl.kt:86-91`) ihr `distinctUntilChanged` bereits explizit
tragen (mit KDoc-Begründung „a write to an unrelated preference … does not
signal"), zeigt: die Einsicht existiert im Projekt — die drei älteren Flows
wurden nur nicht nachgezogen.

**Severity `low–moderate`:** off-main wäre es reine CPU; **auf Main** ist es je
nach installierter App-Zahl (N=100–400+) ein kurzer Stall pro Start/Settings-
Write, solange der Home-/Drawer-Kontext lebt. Kein hartes Ruckeln, aber
messbare, unnötige Main-Thread-Arbeit auf dem heißesten Nutzerpfad (Tap-to-Launch).

**Fix (Template ist der Drawer, §5).**

---

### F2 — Drawer rechnet pro App-Start neu, auch wenn das Ergebnis identisch ist (`low`, off-main)

Kein Jank (läuft auf `Default`), aber redundante CPU/Batterie pro Start:

- `usageFlow` ist `combine`-Eingang des Drawers auch im `ALPHABETICAL`-Modus
  (`GetDrawerAppsUseCase.kt:63-67`), wo die Nutzungs-Reihenfolge irrelevant ist.
  Ein Usage-Tick re-läuft dort trotzdem `applyCustomNames` (Full-Sort) + Filter + Alpha-
  Sort; das terminale `distinctUntilChanged` (`:113`) verwirft danach das
  identische Ergebnis — die **Arbeit** lief bereits.
- Im `TIME_WEIGHTED_USAGE`-Modus macht `sortAppsByTimeWeightedUsage`
  (`AppUsageRepositoryImpl.kt:149-181`) pro Tick einen **frischen Whole-Store-
  Read** (`dataStore.data.…first()`, `:153`) plus O(N)-Score plus O(N log N)-
  `sortedWith` (`:169-172`) — bei jedem Start neu.
- Wurzel identisch zu F1(c): `sortOrderFlow` (`SettingsRepositoryImpl.kt:133`,
  via `valueFlow`/`enumFlow`, `:114-131`) und `hiddenAppsFlow` tragen kein
  `distinctUntilChanged`, also feuert der Drawer-combine bei jedem Store-Write.

Der Drawer ist durch `flowOn(Default)` + terminal `distinctUntilChanged` gegen
**Jank** und **Adapter-Churn** abgeschirmt — F2 ist nur der verschwendete
Rechen-Overhead dahinter.

---

### F3 — `HomeFavoritesAdapter`: per-Bind-Allokationen + Full-Rebind bei jedem Styling-Emit (`low`)

`HomeFavoritesAdapter.onBindViewHolder` (`app/…/ui/home/HomeFavoritesAdapter.kt:123-161`)
hat **keinen** Payload-`onBindViewHolder`-Override. `setStyling` feuert aber bei
jeder Theme-/Alignment-/Layout-Änderung `notifyItemRangeChanged(0, itemCount,
STYLING_PAYLOAD)` (`:69-81`) → **voller** Rebind aller Zeilen. Pro Bind:

- `createSubtlePressColor(styling.textColor)` (`:145`, Impl `:182-194`) — neue
  `ColorStateList` + `arrayOf` + zwei `intArray` **jeden** Bind.
- `setOnClickListener { onAppClick(app) }` (`:152`) und `setOnLongClickListener`
  (`:153-156`) — zwei neue Lambdas pro Bind.

`AppDrawerAdapter` zeigt im selben Projekt das korrekte Muster: Payload-Override
für Teil-Rebinds (`AppDrawerAdapter.kt:118-145`) und in den ViewHolder-`init`
gehoistete Listener (`:157-204`). Gedeckelt durch die Favoriten-Anzahl (user-
gepinnt, praktisch klein; harte Grenze `MAX_FAVORITES_ON_HOME`) → `low`, aber
ein 1:1-Übertrag des vorhandenen Musters.

---

### F4 — Cold-Start: synchroner `getWallpaperColors`-Binder in `Application.onCreate` (`low`)

`KolibriLauncherApp.registerSystemWallpaperColorsListener` macht nach dem
Registrieren des Listeners einen synchronen
`wallpaperManager.getWallpaperColors(FLAG_SYSTEM)` (`KolibriLauncherApp.kt:205`)
— ein Binder-Call in `WallpaperManagerService` auf dem kritischen Pfad. Der
`addOnColorsChangedListener` direkt darüber (`:189-198`) liefert die aktuellen
Farben ohnehin; der Kommentar (`:200-206`) begründet den Initial-Poll als
Race-Absicherung. Kandidat, ihn off-main zu ziehen oder ganz auf den Listener-
Initial-Callback zu vertrauen. Einzige synchrone System-I/O auf dem Startpfad,
die **nicht** in `KNOWN_ISSUES.md` steht (ACRA-Init `attachBaseContext` ist
architektonisch gepinnt und bleibt; Consent-`runBlocking` ist `KNOWN_ISSUES §3`).

---

### F5 — Wallpaper-Memory: großzügiges Downsample-Budget × Multi-Layer, kein `recycle()` beim Swap (`low`, teils bewusst)

Der Render-Pfad ist sonst stark (§4). Zwei Memory-Hebel:

- **Budget.** `MAX_WALLPAPER_PIXELS = 24_000_000` (`BitmapDownsampling.kt:31`)
  ≈ 91,5 MB/Bitmap in ARGB_8888. Der KDoc sagt explizit, es ist eine
  **Crash-Grenze** (Canvas ~100 MB), **kein** Memory-Spar-Budget — ein 16-MP-Foto
  bleibt absichtlich in Full-Res. Bei Multi-Layer werden N solche Bitmaps
  gleichzeitig gehalten (`WallpaperLayer.bitmap` in `ZoomableImageView.layers`) →
  der eigentliche Memory-Footprint. Bewusst so gewählt, aber der größte
  verbleibende Bloat-Hebel.
- **Kein `recycle()` beim Layer-Swap.** `ZoomableImageView.clearLayers`
  (`:543`) und `WallpaperViewBinder.applyFullRebuild` (`:218`) geben die alten
  großen Bitmaps nur an den GC frei, ohne `Bitmap.recycle()`. Der Luminanz-Pfad
  recycelt seinen Temp-Bitmap korrekt (`WallpaperBitmapLuminanceImpl.kt:83,143`)
  — die großen Layer-Bitmaps nicht. Beim Wallpaper-Wechsel churnt das zig MB →
  GC-Pausen, die während des Rebuilds Frames kosten können. `recycle()` hier ist
  **heikel** (ein noch referenzierter Bitmap → `Canvas: trying to use a recycled
  bitmap`), also nur mit sauberem „latest-wins, alter Layer sicher unreferenziert"-
  Nachweis. Deshalb `low` und eher „messen, dann entscheiden" als Sofort-Fix.
- **Nit.** `bitmapPaint` trägt `isFilterBitmap = true` (`ZoomableImageView.kt:294`)
  → bilineare Filterung großer (nicht heruntergerechneter) Bitmaps in jedem
  Pan/Zoom-Frame. Standard, aber auf schwacher GPU bei einem Full-Res-Bild spürbar.

---

### Nits (jeweils `low`, der Vollständigkeit halber)

- **Kein gecachter Sort-Key.** `displayName.lowercase()` wird im Sort-Comparator
  jedes Mal neu berechnet (`ApplyCustomNames.kt:28`, `GetDrawerAppsUseCase.kt:88,103`,
  `AppUsageRepositoryImpl.kt:171`, `InstalledAppsRepositoryImpl.kt:261`) statt
  einmal pro `AppInfo` vorberechnet. Multipliziert sich mit F1/F2.
- **`AppInfo.componentName`** ist ein berechneter Getter (`startsWith` +
  String-Concat) und wird in `AppInfoDiffCallback.areItemsTheSame` zweimal pro
  Vergleichspaar aufgerufen — Allokation während DiffUtil, nicht im Scroll-Bind.
- **`SimpleDateFormat` pro Minuten-Tick** (`ClockDelegate.kt`, `updateTimeAndDate`)
  neu allokiert — vernachlässigbar (1×/Minute), nur notiert.

---

## 4. Bereits optimal — ehrliche Non-Issues (nicht anfassen)

Diese Punkte wurden geprüft und sind **korrekt**; sie erklären, warum Kolibri
trotz F1–F5 flüssig ist:

- **Keine Icons.** Kein `loadIcon`/Drawable-Decode/Icon-Cache nötig — der #1-
  Bottleneck existiert nicht (`AppInfo` trägt kein Bitmap).
- **Enumeration off-Main + reaktiv.** `flowOn(Dispatchers.IO)` +
  `WhileSubscribed`, Start/Rename ohne Enumeration (`REACTIVE_APPLIST_SPEC`,
  umgesetzt), Paket-Sturm debounced an der Quelle.
- **Drawer-Pipeline mustergültig:** `flowOn(Default)` + `distinctUntilChanged`
  (`GetDrawerAppsUseCase.kt:113,123`).
- **`AppDrawerAdapter` ist die Referenz:** `ListAdapter`+DiffUtil, Payload-Partial-
  Rebinds, in `init` gehoistete Listener, günstiges Bind, `itemAnimator=null`,
  `setHasFixedSize(true)`.
- **Wallpaper-Decode auf IO**, Downsampling vorhanden + instrumented-getestet,
  allokationsfreies `onDraw` (wiederverwendete Paint/Matrix/Rect + `*Into`-Helfer),
  Luminanz nur pro State auf 32×32 off-main, Staleness-Guard (latest-wins).
- **Uhr/Batterie:** nur Text-Updates mit Distinct-Value-Guard, keine Layout-Pässe.
- **Suche:** O(N)-`contains`, kein Sort pro Tastendruck, debounced.
- **Cold-Start:** kein eager Hilt-Work, ACRA-`onCreate`-Arbeit deferred,
  Watchdog per `Handler.post` nach `onCreate`, StrictMode nur DEBUG.

---

## 5. Empfehlungen (priorisiert)

**Zuerst F1 — risikoarm, größter Nutzen, Template existiert:**

1. **Minimal & sicher (Verhalten unverändert):** an `favoriteApps` genau das
   anhängen, was der Drawer hat — `.distinctUntilChanged()` **und**
   `.flowOn(dispatcher)` — und `@DefaultDispatcher` in `GetFavoriteAppsUseCase`
   injizieren. Damit läuft der Transform off-Main und redundante Emissionen
   werden terminal geschluckt. Kein Verhaltensrisiko (spiegelt 1:1 den Drawer).
2. **Wurzel (fixt F1 **und** F2 zugleich):** `distinctUntilChanged()` an die
   geteilten DataStore-Flows an der Quelle —
   `favoriteComponentsFlow`, `favoriteComponentsOrderFlow`, `hiddenAppsFlow`,
   `sortOrderFlow` — analog zu `customNamesFlow`/`usageFlow`. Vorher kurz prüfen,
   dass kein Consumer auf Re-Emission gleicher Werte baut (Point-Reads nutzen
   `.first()`, nicht den Flow → sehr wahrscheinlich sicher). Fixt die redundanten
   Re-Fires beider combines.
3. **Optional:** `applyCustomNames`-Variante ohne `.sortedBy` für den Favoriten-Pfad
   (der Sort wird dort ohnehin durch `savedOrder`/`take` verworfen).

**Dann F3:** Payload-`onBindViewHolder`-Override in `HomeFavoritesAdapter`
(Muster: `AppDrawerAdapter:118-145`), `ColorStateList` pro `textColor` cachen,
die zwei Listener in den ViewHolder-`init` heben.

**F4:** `getWallpaperColors`-Initial-Poll off-main ziehen oder weglassen.

**F5:** vor Sofort-Maßnahmen **messen** (Perfetto/`reportFullyDrawn` + Memory-
Profiler bei Multi-Layer-Wechsel). `recycle()` nur mit sauberem Unreferenziert-
Nachweis; Budget-Senkung nur, wenn Memory real drückt (Crash-Grenze bleibt Grund).

**Nits** (gecachter Lower-Case-Sort-Key etc.) lohnen erst, wenn F1/F2 gefixt sind
— dort multiplizieren sie sich am stärksten.

> Jede dieser Änderungen berührt Contract-/Flow-Verhalten und gehört auf einen
> eigenen Branch mit den üblichen Gates (`:domain:test :data:test :app:test
> checkConventions checkRule13`); neue `combine`/`.catch`-Arme unterliegen dem
> Flow.catch-/Cancellation-Gate. Nichts davon in diesem Audit umgesetzt.

---

## 6. Bewusst NICHT als Finding gemeldet (geprüft, dokumentiert/vertagt)

- **Voll-Enumeration bei jedem Paket-Event.** `listenForAppUpdates`
  (`AppManagementDelegate.kt:267-281`) verwirft den `PackageEvent`-Payload und
  ruft `refreshAppsUseCase()` (Voll-Enumeration). Das ist **im Code selbst**
  als bewusster Zwischenstand markiert (`:268-271`: „Step 1, behaviour-neutral;
  event-targeted reconcile lands in §3", `RECONCILE_SPEC`) und durch
  `debounce(250ms)` + Labels-only entschärft — bekannt und geplant, kein neuer
  Befund.
- **Consent-`runBlocking` in `attachBaseContext`** — Ein-Key-Read, bewusst,
  `KNOWN_ISSUES §3`.
- **ACRA-Init auf dem Startpfad** — muss vor allem anderen den Crash-Handler
  installieren; architektonisch gepinnt, nur „messen, nicht verschieben".
- **Samsung/Knox-StrictMode** — `KNOWN_ISSUES §1/§2`, Framework-seitig.

---

## 7. Re-Evaluierungs-Trigger

- **Favoriten-/Drawer-Pipeline wird angefasst** → F1/F2 mitziehen (der billige
  Fix ist das Anhängen zweier Operatoren, siehe §5).
- **Installierte App-Zahl wächst spürbar** (viele hundert Apps) → F1 wird von
  `low–moderate` Richtung `moderate`, weil der Main-Thread-Sort mit N skaliert.
- **Multi-Layer-Wallpaper wird häufiger genutzt / OOM-Reports steigen** → F5
  ernster nehmen (Budget + `recycle()`).
- **`AppInfo` bekommt ein Icon-Feld** (falls je ein Icon-Modus kommt) → dann
  greift der klassische #1-Bottleneck und dieses Audit ist neu zu schreiben:
  Icon-Cache (LruCache), Decode off-main, kein Decode im Bind.

---

## 8. Kein Linter

Bewusst **kein** Gate gebaut. F1/F2 sind „fehlendes `distinctUntilChanged`/
`flowOn`" — beides legitim abwesend an vielen Flows (Point-Read-Quellen,
Event-Trigger), also kein präzise flaggbares Defektmuster im Sinne der
`checkConventions`-Checks; ein Linter „jeder combine braucht flowOn" wäre
All-Noise. Der prüfbare Kern wäre allenfalls „ein UI-kollektierter combine über
`rawAppsFlow` ohne `flowOn`" — zu spezifisch für einen Gate, besser durch den
Drawer-als-Referenz-Review abgedeckt. Kandidat für eine spätere Sitzung, nicht
Teil dieses Audits.
