# AUDIT-16 — Lag & Bloat: die Deltas jenseits von AUDIT-14/15

> **Erzeugt** 2026-08-11 gegen `main` @ `b058300e` (Version 0.99.160 / code 180),
> auf dieselbe Frage wie AUDIT-14/15: *„Wo entsteht in einem text-only Launcher
> Lag, Bloat, unnötige Loops, Allokationen in Loops?"*
> **Methode:** drei parallele Subsystem-Scans (RecyclerView-Adapter/Rendering,
> Filter-/Sort-Pipeline, Per-Tick-/Per-Event-Arbeit), **danach jede zitierte
> Stelle selbst am Code nachgelesen** — kein Befund steht hier auf Zuruf eines
> Sub-Agents. Zwei Sub-Agent-Fehlframings sind in §4 korrigiert und **nicht** als
> neue Findings übernommen.
>
> **Status dieses Dokuments: BERICHTEND, Umsetzung im Gang.** Ursprünglich als
> reiner Befundbericht angelegt (die Aufgabe war, die Findings zu notieren).
> Inzwischen umgesetzt: **N1** (✅, Branch `fix/home-redundant-settext`) und
> **N2** (✅, Branch `refactor/settings-vm-search`); **N3** bewusst nicht
> umgesetzt (⛔, Emissions-Invariante > Mikro-Scan); **N4/N5** mit
> 🔎 **gemeldet, nicht umgesetzt** markiert. Statusskala wie in den
> Schwester-Audits.
>
> **Kein akuter Defekt.** Wie AUDIT-14/15: nichts ruckelt hart, nichts leakt,
> nichts blockiert den ersten Frame. Alle Findings sind `low`. Der Code ist nach
> zwei Vor-Audits stark vor-optimiert (DiffUtil/`ListAdapter` überall, Suche im
> Drawer debounced + gegen `displayNameLower`, `onDraw` allokationsfrei,
> Wallpaper-Luminanz sauber gegated).

---

## 0. Verhältnis zu AUDIT-14 & AUDIT-15 (dieselbe Frage, gestern schon beantwortet)

AUDIT-14 (`bd6f23f1`, 0.99.152) und AUDIT-15 (`c2136250`, 0.99.157) — beide
2026-08-10 — beantworten die Kern-Lag-Frage bereits und sind `GESCHLOSSEN`.
AUDIT-16 sammelt ausschließlich, was in **beiden** Vorgängern nicht vorkommt
(gegengeprüft am Code und an `AUDIT-14.md`/`AUDIT-15.md`). Die folgenden
Kandidaten aus den Sub-Agent-Scans sind **bereits triagiert** und hier **NICHT**
erneut als neu gemeldet:

| Sub-Agent-Kandidat | Bereits abgehandelt in | Status dort |
|---|---|---|
| `AppSearchFilter` (Drawer-Suche) nutzt `contains(ignoreCase=true)` | AUDIT-15 **F2** | ✅ umgesetzt (gegen `displayNameLower`) |
| `SimpleDateFormat` pro Minuten-Tick im `ClockDelegate` | AUDIT-14 Nit §215 / AUDIT-15 §0 | bewusst vertagt |
| `applyCustomNames`-Terminal-Sort tot auf Favoriten-/Recents-Pfad | AUDIT-14 F1 §5.3 / AUDIT-15 **F3** | ⛔ bewusst nicht umgesetzt (RAL-1a) |
| `GetDrawerAppsUseCase` „Doppel-Sort" bei `:146` | = derselbe RAL-1a-Sort (s. §4) | ⛔ bewusst nicht umgesetzt |
| `GetFavoriteAppsUseCase` Voll-Listen-`copy` pro Emission | AUDIT-15 **F3** (b) | ⛔ bewusst nicht umgesetzt |
| `AppContextMenuAdapter` `getString` pro Bind | AUDIT-15 **F6 (b)** | bewusst belassen (Label variiert echt) |

Die beiden heißesten Adapter (`AppDrawerAdapter`, `HomeFavoritesAdapter`), die
Enumeration (`InstalledAppsRepositoryImpl`, Single-Pass off-IO), `onDraw`/Gesten
(`ZoomableImageView`, `HomeGestureLayout`) und die Wallpaper-Luminanz wurden
erneut geprüft und sind **sauber** — Details in §2.

---

## 1. Neue Findings (alle `low`, alle 🔎 gemeldet/nicht umgesetzt)

| # | Ort | Was | Severity |
|---|---|---|---|
| **N1** ✅ | `HomeFragment` Observer 3 + `LauncherViewModel.uiState` | dreifaches `setText` (Zeit/Datum/Akku) bei **jedem** Akku-Broadcast, inkl. unveränderter Zeit/Datum-Strings | `low` |
| **N2** ✅ | Vier Settings-VMs (Hidden/Onboarding/Swipe/CustomNames) | Suche nutzt `contains(ignoreCase=true)` statt `displayNameLower` — das **F2-Muster in den Dateien, die F2 nicht angefasst hat** | `low` |
| **N3** ⛔ | dieselben Settings-VMs | `combine` re-sortiert/re-scannt die Auswahl-Liste bei jedem Keystroke, obwohl nur `query` sich änderte | `low` |
| **N4** 🔎 | `InstalledAppsRepositoryImpl.processResolveInfoList` | `mutableListOf()` wächst elementweise, Endgröße ist vorab bekannt | `low` (sub-nit) |
| **N5** 🔎 | `SwipeActionsAppListAdapter`, `OnboardingAppListAdapter` | kein `getChangePayload` → Selection-Toggle macht Full-Rebind statt Partial | `low` |

### N1 — Home-Textblock: dreifaches `setText` bei jedem Akku-Broadcast · ✅ umgesetzt
`app/.../ui/home/HomeFragment.kt:517-528`, `app/.../ui/main/LauncherViewModel.kt:234-250`

> **Erledigt** (Branch `fix/home-redundant-settext`): Observer 3 in drei
> Per-Feld-Collectoren aufgeteilt, jeder
> `viewModel.uiState.map { it.timeString/dateString/batteryString }.distinctUntilChanged()`
> — genau das Idiom von Observer 4 direkt darunter. Ein Akku-Tick setzt jetzt
> nur noch `batteryText`; `timeText`/`dateText` werden nur bei tatsächlicher
> String-Änderung neu gesetzt. `:app:compileDebugKotlin` + `checkConventions`
> grün, `HomeFragmentRobolectricTest` + `LauncherViewModelTest` grün.
> **Korrektur zum Sub-Agent-Report:** die zunächst als „gleiche Form nochmal"
> genannte Stelle `HomeFragment.kt:1500-1502` ist **kein** zweiter Bug-Fundort,
> sondern die bewusste einmalige „pixel injection" in `onStart` (läuft einmal
> pro Start, nicht pro Broadcast — überbrückt die ms bis der Flow emittiert).
> Dort alle drei Felder einmal zu setzen ist korrekt und billig — **unangetastet
> gelassen**.

**Der einzige genuin neue, real spürbare Micro-Lag dieses Audits.** Observer 3
collectet den **ganzen** `uiState` und setzt bedingungslos alle drei TextViews:

```kotlin
// HomeFragment.kt:522-528
) { state ->
    if (_binding == null) return@collectOnStarted
    binding.timeText.text = state.timeString
    binding.dateText.text = state.dateString
    binding.batteryText.text = state.batteryString
}
```

`uiState` ist ein 4-fach-`combine` aus Zeit/Datum/Akku/Events mit
`SharingStarted.Eagerly` (`LauncherViewModel.kt:234-250`). Eine **Akku**-Änderung
erzeugt also einen neuen, distinct `HomeUiState` → der StateFlow re-emittiert →
Observer 3 setzt **alle drei** TextViews neu, auch die unveränderten Zeit- und
Datum-Strings. `TextView.setText` short-circuitet **nicht** bei gleichem String;
jeder Aufruf plant measure/layout/invalidate.

Warum heiß: `ACTION_BATTERY_CHANGED` (registriert `MainActivity.kt:601-605`) feuert
beim Laden häufig (pro Prozent, dazu Spannung/Temperatur). Jedes distinct Prozent
→ ein voller Triple-`setText` mit zwei redundanten Layout-planenden Zuweisungen.
Der Akku-StateFlow selbst dedupt zwar (`ClockDelegate.updateBatteryLevel`), aber
die Dedup sitzt **vor** dem `combine` — der kombinierte `HomeUiState` ist bei
jeder Prozent-Änderung neu, und Zeit/Datum reiten unverändert mit.

**Fix (umgesetzt):** pro Feld `.map { it.timeString }.distinctUntilChanged()` (bzw.
`dateString`/`batteryString`) vor der Zuweisung — exakt das Muster, das
**Observer 4 direkt daneben** bei `:531-534` schon für `timeBasedEvents` nutzt.
Die `onStart`-Einmal-Injektion bei `:1500-1502` bleibt unverändert (kein
Per-Broadcast-Pfad, s. o.). Der billigste Fix des Audits, höchster realer Nutzen.

### N2 — Settings-Suche ignoriert den vorberechneten Lower-Key · ✅ umgesetzt
- `app/.../ui/hiddenapps/HiddenAppsViewModel.kt:55`
- `app/.../ui/onboarding/OnboardingViewModel.kt:83`
- `app/.../ui/swipeactions/SwipeActionsViewModel.kt:68`
- `app/.../ui/customnames/CustomNamesViewModel.kt:116-117` (zwei Faltungen/App)

> **Erledigt** (Branch `refactor/settings-vm-search`): in allen vier VMs
> `query.lowercase()` einmal aus dem Filter-Lambda gezogen und gegen
> `AppInfo.displayNameLower` gematcht — exakt der F2-Fix, nur in den vier
> Geschwister-Dateien. CustomNames matcht zusätzlich `originalName`: der hat
> keinen vorberechneten Lower-Key, unterscheidet sich aber nur bei
> custom-benannten Apps von `displayName` (dasselbe Prädikat wie
> `appsWithCustomNames`), also wird `originalName` nur für diese kleine
> Teilmenge gefaltet statt für jede App pro Keystroke — **bewusst keine
> Domain-Änderung** (`originalNameLower` als `AppInfo`-Body-`val` würde app-weit
> pro `copy()` gefaltet, für ein einziges Screen). `:app:testDebugUnitTest`
> (die vier VM-Tests inkl. Such-Filter + `selectedApps`-Sortierung),
> `checkConventions` und `checkRule13` grün.

```kotlin
allApps.filter { it.displayName.contains(query, ignoreCase = true) }   // HiddenApps:55
```

`ignoreCase = true` macht Locale-Case-Folding **pro App, pro (gesettletem)
Tastendruck** neu, obwohl `AppInfo.displayNameLower` (der Sort-Key aus AUDIT-14
Nit §208) genau das schon einmalig vorhält. **Das ist exakt das Anti-Pattern, das
AUDIT-15 F2 für den Drawer (`AppSearchFilter`) behoben hat — nur in den vier
Settings-VMs, die F2 nicht berührt hat.** F2 hat den Drawer-Pfad umgestellt; diese
vier Geschwister-Flächen blieben auf dem alten Muster. CustomNames faltet sogar
zweimal pro App (`displayName` **und** `originalName`).

**Fix (mirror von F2, `AppSearchFilter.kt:39-41`):** `val q = query.lowercase()`
aus dem Lambda ziehen, gegen `app.displayNameLower.contains(q)` matchen.
CustomNames braucht dafür einen `originalNameLower`-Begleiter (einmal falten statt
pro Keystroke). Entschärft durch den Debounce an den jeweiligen Activities (z. B.
`HiddenAppsActivity.kt:150`), daher `low` — aber es ist dieselbe Fold-Arbeit über
die ganze Liste, die F2 bereits als vermeidbar eingestuft hat.

### N3 — `combine` re-sortiert die Auswahl-Liste bei reiner Query-Änderung · ⛔ wird nicht umgesetzt
- `HiddenAppsViewModel.kt:65-67` — `allApps.filter { selected.contains(...) }.sortedBy { it.displayNameLower }`
- `OnboardingViewModel.kt:93-95` — gleiche Form
- `SwipeActionsViewModel.kt:61-62` — zusätzlich zwei O(n)-`find` (`allApps.find { it.componentName == leftComp/rightComp }`)

In allen drei VMs mischt der `combine` `searchQuery` mit den Auswahl-/Master-Flows.
Ein Keystroke ändert nur `query`, re-runt aber das **ganze** Lambda: die
Auswahl-Liste wird neu gefiltert **und neu sortiert** (Hidden/Onboarding), bei
Swipe zusätzlich die Liste zweimal linear nach den Slot-Komponenten durchsucht —
obwohl `selected`/`leftComp`/`rightComp` sich beim Tippen gar nicht ändern.

Der naheliegende Fix wäre, die query-abhängige Filterung von der
Auswahl-Sortierung (bzw. den Slot-`find`s) in getrennte `combine`-Stufen zu
splitten, sodass letztere nur auf `masterList` + `selected`/Slot-Components keyen.

> **Entscheidung: nicht umsetzen — Emissions-Invariante > Mikro-Scan.**
> Der Split fragmentiert genau die **atomare Single-Emission**, die diese VMs
> bewusst halten: die dokumentierten Guards (`HiddenAppsViewModel` /
> `SwipeActionsViewModel`, „Load … BEFORE publishing the master list … without a
> suspend point between them, so the init-block combine collector never emits a
> transient state (sub-frame flash)") existieren genau dafür. Ein
> verschachteltes `combine(selectableFlow, selectedFlow)` kann bei einer
> `selected`-Änderung transiente Doppel-Emissionen erzeugen (eine innere Stufe
> aktualisiert vor der anderen) — und die VM-Tests asserten **Endzustände**,
> würden einen transienten Flicker also nicht zuverlässig fangen. Dem steht als
> Nutzen ein O(n)-Filter/Scan über eine **kleine** Auswahl-Liste pro
> **gesettelten** (an der jeweiligen Activity debounced) Keystroke gegenüber —
> µs, `low`. Value ≈ 0 gegen ein reales Regressionsrisiko in
> invarianten-sensiblen VMs: dieselbe Wert-vs-Risiko-Abwägung, die AUDIT-15 F3/F5
> geschlossen hat. Re-Evaluierung nur, falls diese Screens je eine spürbar große
> Auswahl-Liste ohne Debounce bekommen.

### N4 — Un-presizte Liste in der Enumeration · 🔎 gemeldet (sub-nit)
`data/.../data/InstalledAppsRepositoryImpl.kt:215`

```kotlin
val appInfoList = mutableListOf<AppInfo>()          // wächst elementweise im for-Loop
```

Die Liste wird in `for (info in resolveInfoList)` (`:217-259`) Element für Element
gefüllt; die Obergrenze (`resolveInfoList.size`) steht vorab fest. `ArrayList(resolveInfoList.size)`
erspart die Backing-Array-Reallokationen. **Sub-Nit:** AUDIT-15 §2 hat
`processResolveInfoList` bereits als „Single-Pass, off-IO, sauber" abgesegnet —
das gilt weiter; dies ist nur die Presize-Feinheit obendrauf. Läuft nur bei
Enumeration (Cold-Start / Paket-Broadcast / Force-Reload), nicht pro Keystroke →
minimal.

### N5 — Fehlendes `getChangePayload` in zwei Settings-Adaptern · 🔎 gemeldet
`app/.../ui/swipeactions/SwipeActionsAppListAdapter.kt:67-74`,
`app/.../ui/onboarding/OnboardingAppListAdapter.kt:50-58`

Beide `DiffUtil.ItemCallback` überschreiben `getChangePayload` nicht. Ein
Selection-Toggle (`isSelected`) fällt damit durch `areContentsTheSame`
(Data-Class-`equals`) und erzwingt einen **Full-Bind**, der u. a. `appLabel.text`
(unverändert) neu setzt statt nur die Checkbox / den Slot-Indikator. **Fix:** ein
`isSelected`-only-Payload, damit ein Toggle nur das Delta-View rebindet — wie es
`AppDrawerAdapter` (`PAYLOAD_*`) und (seit AUDIT-15 F6c) `AppContextMenuAdapter`
schon tun. `low`: kleine, nicht-scrollende Auswahl-Listen.

---

## 2. Gegengeprüft & bewusst NICHT als Finding gemeldet

- **Drei Scans bestätigen die AUDIT-14/15-Sauberkeit:** `AppDrawerAdapter` (die
  einzige echt scrollende Fläche) ist payload-optimiert, listener-gehoistet,
  allokationsfrei im Bind; `HomeFavoritesAdapter` memoiziert seine Press-`ColorStateList`
  (Single-Entry-Cache); `AppInfoDiffCallback` diffed allokationsfrei über die
  vorberechneten `componentName`/`displayNameLower`.
- **`onDraw`/Gesten sauber:** `ZoomableImageView.onDraw` allokiert nichts
  (Paints/Matrizen/`RectF` als Felder, `buildMatrixInto`/`getTransformedBoundsInto`
  wiederverwendet); `HomeGestureLayout` macht den Tree-Walk nur bei `ACTION_DOWN`,
  `ACTION_MOVE` ist reine Float-Mathematik; `cancelChildGesture` recycelt sein
  `MotionEvent`. Kein Per-Frame-Heap-Churn.
- **Wallpaper-Luminanz korrekt gegated:** der teure Decode/Scale/Per-Pixel-Pfad
  (`WallpaperBitmapLuminanceImpl`) läuft off-IO und wird nur bei tatsächlicher
  `distinctUntilChanged`-Wallpaper-Änderung neu berechnet — nicht pro Resume/
  Config-Change; der System-Color-Zweig ist sauber getrennt und triggert **kein**
  Bitmap-Re-Decode.
- **Package-Events gut entschärft:** Removal-Hälfte eines In-Place-Updates wird
  gedroppt, Reload ist `debounce(250ms)` + `flatMapLatest`. Der Ganzlisten-Requery
  pro Event bleibt (statt inkrementellem Reconcile) — bereits als Reload-, nicht
  Runtime-Kosten in AUDIT-15 §2 vermerkt; kein Doppeleintrag.
- **`AppSearchFilter` / `SimpleDateFormat` / `applyCustomNames`-Sort /
  `AppContextMenuAdapter.getString` / `GetFavoriteAppsUseCase`-Copy** — siehe die
  Tabelle in §0, alle bereits in AUDIT-14 oder AUDIT-15 abgehandelt.
- **Hintergrund-Clock-Tick** (`SharingStarted.Eagerly`, Minuten-`BroadcastReceiver`
  läuft auch backgrounded) — pro Minute, `low`; `WhileSubscribed` wäre eine
  optionale Battery-Verfeinerung, aber kein Lag-Beitrag. Kein Finding.

---

## 3. Fazit

Nach AUDIT-14/15 ist die Lag-Front geräumt; die dritte Runde auf dieselbe Frage
findet **einen** genuin neuen, real spürbaren Punkt und vier kleine Deltas:

- **N1** (Triple-`setText` beim Laden) — der einzige mit echtem Micro-Lag-Beitrag,
  billigster Fix, klares Muster direkt nebenan (Observer 4).
- **N2** — das F2-Suchmuster in den vier Settings-VMs, die AUDIT-15 F2 nicht mit
  umgestellt hat. Konsequente Nachziehung.
- **N3** — der zugehörige `combine`-Re-Sort. Bewusst **nicht** umgesetzt: der
  saubere Split fragmentiert die atomare Single-Emission dieser VMs (Risiko
  transienter Doppel-Emissionen) gegen µs-Ersparnis über eine kleine, debounced
  Liste. Wert-vs-Risiko wie AUDIT-15 F3/F5.
- **N4/N5** — Presize-Nit und zwei fehlende Adapter-Payloads. Kosmetik.

**Umgesetzt:** **N1** (Branch `fix/home-redundant-settext`, Per-Feld-`distinctUntilChanged`)
und **N2** (Branch `refactor/settings-vm-search`, Suche gegen `displayNameLower`)
— Compile/Linter/Tests grün.

**Bewusst nicht umgesetzt:** **N3** (Emissions-Invariante > Mikro-Scan, siehe §1).

**Offen:** N4/N5 — als Findings festgehalten, noch nicht umgesetzt (optionaler
Aufräum-Beifang).

---

## 4. Korrekturen an den Sub-Agent-Zurufen (Transparenz)

- **„`GetDrawerAppsUseCase.kt:146` re-sortiert redundant, `sortedBy` streichen"** —
  **rückwärts geframed.** Der Code kommentiert bei `:131-133` explizit, dass der
  Sort **in `applyCustomNames`** der tote ist und `sortVisibleApps` (`:146`) der
  **operative** — genau die RAL-1a-Entscheidung, die AUDIT-14 F1 §5.3 / AUDIT-15
  F3 bereits bewusst nicht umgesetzt haben (`applyCustomNames` sortiert an jeder
  Quell-Grenze als Invariante; Consumer re-sortieren). `:146` zu streichen hieße,
  sich auf den intern-sortierten `applyCustomNames`-Output zu verlassen —
  „sortiert hier, unsortiert da", exakt die Fragmentierung, die die Spec ablehnt,
  und im `TIME_WEIGHTED`-Zweig ist `:146` ohnehin ein **anderer** Sort. **Kein
  neues Finding** — bereits geschlossen, hier nur zur Klarstellung.
- **„Settings-VM `contains` ist wie F2, also schon erledigt"** — **halb richtig.**
  Das *Muster* ist F2, aber F2 hat nur `AppSearchFilter` (Drawer) umgestellt; die
  vier Settings-VMs blieben unberührt. Deshalb ist N2 ein **echtes neues** Delta
  (andere Dateien), kein Doppeleintrag — verifiziert an allen vier Fundstellen.
