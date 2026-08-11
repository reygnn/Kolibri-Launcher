# AUDIT-17 — Korrektheit: Bug-Hunt jenseits der Perf-Achse

> **Erzeugt** 2026-08-11 gegen `main` @ `bc05070d` (Version 0.99.160 / code 180),
> auf die Frage: *„Wo steckt in der Codebasis **nicht-optimaler / fehlerhafter**
> Code — echte Korrektheitsdefekte (falsches Ergebnis, Datenverlust, Race,
> falsche UI), nicht Style, nicht Performance?"*
> **Erste Korrektheits-Linse der Serie.** AUDIT-14/15/16 haben ausschließlich auf
> Lag/Bloat geschaut; dieser Lauf schaut auf **Korrektheit** und ist damit eine
> neue Achse, keine vierte Perf-Runde.
> **Methode:** drei parallele Subsystem-Scans nach Fehlerklasse
> (Coroutines/Flows/Concurrency, Daten/Persistenz/Domain-Logik, UI/Lifecycle/
> Adapter), **danach jeder gemeldete Fund selbst am Code nachgelesen und
> verifiziert** — kein Befund steht hier auf Sub-Agent-Zuruf. Die Sub-Agenten
> erbten das Session-Modell (Opus 4.8).
>
> **Status: VOLLSTÄNDIG UMGESETZT.** Drei verifizierte Defekte, alle **echte
> Verhaltensfehler** (nicht Style/Perf) und von **keinem** Linter oder
> AUDIT-*/SPEC-Dokument abgedeckt. Alle drei auf Branch
> `fix/audit-17-correctness` gefixt, **jeder mit Test** (F2 Gate-Test, F1
> Payload-Routing-Test, F3 Sort-vor-Take-Test); Compile/Linter/Tests grün.

---

## 0. Ergebnis der drei Scans

| Achse | Ergebnis |
|---|---|
| **Coroutines / Flows / Concurrency** | 🟢 **sauber** — keine neuen Defekte. Jedes Ziel-Muster (combine-Init-Ordering, `WhileSubscribed`-Point-Reads, Cancellation-Swallow, `flatMapLatest`-Side-Effect-Races, Off-Main-Shared-State, `runBlocking`-auf-UI, `_binding`-Teardown) hat eine explizite Invariante + Guard, die am Code (nicht nur im KDoc) hält. |
| **Daten / Persistenz / Domain** | 🔴 **1 Defekt** (F3, `low`). Backup-Round-Trip, Consent-Tri-State, componentName-Normalisierung, Purge/Reset, Time-Weighted-Math, Reconcile-Veto alle sauber. |
| **UI / Lifecycle / Adapter** | 🔴 **2 Defekte** (F1/F2, beide `medium`). |

Die Concurrency-Achse zurück leer zu bekommen ist selbst ein Signal: die drei
Perf-Audits + die Linter-Suite (Cancellation-, Flow-catch-, unbuffered-SharedFlow-,
Teardown-Checks) haben genau diese Fehlerklasse bereits hart abgesichert.

---

## 1. Findings

| # | Achse | Ort | Was | Severity |
|---|---|---|---|---|
| **F1** ✅ | UI | `HomeFavoritesAdapter` + `AppInfoDiffCallback` | umbenannter Favorit behält auf dem Home-Screen den **alten Namen** | `medium` |
| **F2** ✅ | UI | `SwipeActionsViewModel.onDoneClicked` | „Done" im Ladefenster **überschreibt gespeicherte Swipe-Actions mit `null`** | `medium` |
| **F3** ✅ | Daten | `UsageExportRepositoryImpl.performImport` | Usage-Import kürzt **vor** dem Sortieren → verwirft die neuesten Timestamps | `low` |

### F1 — Umbenannter Favorit zeigt auf dem Home-Screen den alten Namen · ✅ umgesetzt
`app/.../ui/home/HomeFavoritesAdapter.kt:169-183` (Payload-Override) · `app/.../ui/util/AppInfoDiffCallback.kt:21-26` (`getChangePayload`)

> **Erledigt** (`fix/home-...` → Branch `fix/audit-17-correctness`): der
> Payload-Override nimmt den styling-only-Pfad jetzt **nur**, wenn jeder Payload
> `=== STYLING_PAYLOAD` ist; `PAYLOAD_NAME_CHANGE`, eine koaleszierte Mischung
> oder ein unbekannter Payload fallen auf den Voll-Bind durch (setzt den Text).
> `STYLING_PAYLOAD` ist jetzt `@VisibleForTesting internal`, damit der Test die
> echte Instanz fährt. Neuer Namens-Payload-Test + der bestehende Styling-Test
> auf die echte Konstante umgestellt. Robolectric-Netz grün.

**Verifiziert am Code.** `HomeFavoritesAdapter` nutzt das geteilte
`AppInfoDiffCallback` (`:51`). Dessen `getChangePayload` liefert
`PAYLOAD_NAME_CHANGE`, sobald sich nur `displayName` ändert (gleiche
`componentName`):

```kotlin
// AppInfoDiffCallback.kt:21-26
override fun getChangePayload(oldItem: AppInfo, newItem: AppInfo): Any? {
    if (oldItem.displayName != newItem.displayName) return AppDrawerAdapter.PAYLOAD_NAME_CHANGE
    return null
}
```

Der Payload-Override im Adapter ignoriert aber den Payload-**Inhalt** und ruft
bedingungslos nur `applyStyling(holder)` — der `holder.button.text` **nie** neu
setzt:

```kotlin
// HomeFavoritesAdapter.kt:169-183
override fun onBindViewHolder(holder, position, payloads) {
    if (payloads.isEmpty()) { super.onBindViewHolder(holder, position, payloads); return }
    // "Every payload this adapter emits is STYLING_PAYLOAD"  <-- FALSCH
    applyStyling(holder)   // setzt KEINEN Text
}
```

Der Kommentar `:175` ist faktisch falsch: der Adapter emittiert
`STYLING_PAYLOAD` zwar selbst über `notifyItemRangeChanged` in `setStyling`, aber
der `submitList`-Pfad des `ListAdapter` schickt über **denselben**
`onBindViewHolder(…, payloads)`-Einstieg auch `PAYLOAD_NAME_CHANGE`. Ein
Namens-only-Delta wird also auf den Styling-only-Rebind geleitet, das
Text-Update geht verloren. `AppDrawerAdapter` (`:141`) behandelt
`PAYLOAD_NAME_CHANGE` korrekt via `updateName(item.displayName)`.

**Konkretes Fehlverhalten:** Favorit „Camera". Nutzer benennt ihn in Custom Names
zu „Cam" um und kehrt zum Home zurück (die HomeFragment-View wurde nur gestoppt,
nicht zerstört). Der Favoriten-Flow re-emittiert `AppInfo(displayName="Cam")`,
gleiche `componentName` → `renderFavorites` → `submitList` → DiffUtil:
`areItemsTheSame=true`, `areContentsTheSame=false`,
`getChangePayload=PAYLOAD_NAME_CHANGE` → `applyStyling` only → der Home-Button
zeigt weiter „Camera". Der App-Drawer zeigt „Cam" — die zwei Flächen
widersprechen sich sichtbar, bis ein Voll-Rebind (Rotation / Weg-Navigieren) die
View neu baut. Kein Test deckt den Payload-Pfad ab.

**Fix:** im Payload-Override nur dann styling-only rebinden, wenn **alle**
Payloads `STYLING_PAYLOAD` sind — sonst auf den Voll-Bind durchfallen (der Text +
Styling setzt). Das behandelt `PAYLOAD_NAME_CHANGE` und jede koaleszierte
Mischung korrekt.

### F2 — „Done" vor Ladeende löscht gespeicherte Swipe-Actions · ✅ umgesetzt
`app/.../ui/swipeactions/SwipeActionsViewModel.kt:203-215` (`onDoneClicked`) gegen `initialize()` `:109-145` · Button `app/.../ui/swipeactions/SwipeActionsActivity.kt:141`

> **Erledigt** (Branch `fix/audit-17-correctness`): `isLoaded`-Flag, am Ende von
> `initialize()` nach den Slot-Zuweisungen gesetzt; `onDoneClicked` persistiert
> nur bei `isLoaded`, navigiert aber immer hoch (früher Tap kein Dead-End). Ein
> legitimer user-cleared `null` (`isLoaded == true`) speichert weiterhin. Neuer
> Gate-Test (Done vor Load → kein Write, trotzdem NavigateUp); die zwei
> bestehenden `onDoneClicked`-Tests `advanceUntilIdle`n jetzt nach `initialize`.

**Verifiziert am Code.** `onDoneClicked` persistiert bedingungslos die
In-Memory-Slot-Werte:

```kotlin
setSwipeActionUseCase(SWIPE_FROM_LEFT_TO_RIGHT, swipeLeftComponent.value)
setSwipeActionUseCase(SWIPE_FROM_RIGHT_TO_LEFT, swipeRightComponent.value)
```

`swipeLeftComponent`/`swipeRightComponent` defaulten auf `null` und werden erst am
**Ende** von `initialize()` befüllt — nach `getInstalledAppsUseCase().first { it.isNotEmpty() }`
(begrenzt durch `INSTALLED_APPS_PRIME_TIMEOUT_MS`). Während dieses Fensters rendern
die Chips „leer". Der Done-Button (`SwipeActionsActivity:141`) ist die ganze Zeit
**aktiv** — kein `isLoading`/`isEnabled`-Gate. Ein Done-Tap im Ladefenster
schreibt `null`/`null` über die gespeicherten Zuweisungen.

**SwipeActions ist der einzige der drei Auswahl-Screens ohne Save-Gate** — genau
die Save-über-leer-Klasse, die das Projekt anderswo bewusst absichert:
- `OnboardingViewModel.onDoneClicked` (`:193-200`) — `PreselectState`-Tri-State-Gate.
- `HiddenAppsViewModel.onDoneClicked` (`:149-166`) — Diff gegen `initialHiddenComponents`,
  ein leerer Pre-Load-State erzeugt leere Diffs → kein Write.
- `SwipeActionsViewModel` — **weder noch**, wholesale-Overwrite.

**Konkretes Fehlverhalten:** Nutzer hat beide Slots konfiguriert. Cold-Start
direkt in Swipe Actions; während die App-Liste lädt (Chips „leer") tippt er Done
(oder ein versehentlicher Doppel-Tap). `onDoneClicked` speichert `null`/`null` →
beide gespeicherten Swipe-Gesten weg. Severity durch das kurze/variable
Ladefenster begrenzt (`medium`), aber ein echter, stiller Datenverlust.

**Fix:** einen Load-Gate analog zu den Geschwistern — ein `isLoaded`-Flag, das
`initialize()` am Ende setzt; `onDoneClicked` speichert nur bei `isLoaded`
(sonst NavigateUp ohne Write, oder Fehler-Toast). Boolean genügt: schlägt
`initialize()` fehl/timeouts, bleibt `isLoaded=false` und Done schreibt nicht.

### F3 — Usage-Import kürzt vor dem Sortieren, verwirft die neuesten Timestamps · ✅ umgesetzt
`data/.../data/UsageExportRepositoryImpl.kt:240-263` (`performImport`)

> **Erledigt** (Branch `fix/audit-17-correctness`): `.sortedDescending()` vor
> `.take(...)` gezogen, sodass die neuesten MAX überleben; der Replace-Zweig gibt
> die bereits sortierte Liste zurück, der Merge-Zweig ist automatisch mit
> korrigiert (Import war vor dem Merge gekürzt). Neuer Test: Replace-Import von
> MAX+50 Timestamps in aufsteigender Reihenfolge → neueste MAX überleben, älteste
> 50 fallen weg.

**Verifiziert am Code.** `.take(MAX_TIMESTAMPS_PER_APP)` (`:242`) läuft **vor**
`.sortedDescending()` (`:262`):

```kotlin
val validImportedTimestamps = importedTimestamps
    .filter { isValidTimestamp(it, currentTime) }
    .take(AppConstants.MAX_TIMESTAMPS_PER_APP)   // :242 — take VOR sort
...
} else {
    validImportedTimestamps.sortedDescending()   // :262 — sort NACH truncation
}
```

`importedTimestamps` bewahrt die JSON-Array-Reihenfolge (`parseUsageData` `:204-210`,
kein Sort), und `validateJsonStructure` (`:347`) erlaubt bis zu
`MAX_TIMESTAMPS_PER_APP * 2` = **300** Timestamps/Paket (bestätigt:
`MAX_TIMESTAMPS_PER_APP = 150`, `AppConstants.kt:97`). Trägt eine Datei 151–300
Timestamps für ein Paket in nicht-absteigender Reihenfolge, behält `take(150)`
eine willkürliche (datei-order) Teilmenge und verwirft den Rest — inklusive
**neuerer** Einträge. Der Merge-Pfad (`:256-259`) ist identisch betroffen:
`validImportedTimestamps` ist schon vor dem Merge gekürzt.

**Konkretes Fehlverhalten:** eine (handeditierte / fremde) Usage-Export-Datei
listet die 250 gültigen Timestamps eines Pakets **aufsteigend** (ältestes zuerst).
Validierung passiert (250 ≤ 300). Import behält die **ältesten** 150, wirft die
**neuesten** 100 weg, sortiert dann absteigend → korrumpiertes Time-Weighted-
Scoring und ein veraltetes „last launch" in Recents für das Paket.

**Scope/Severity:** Kolibris **eigene** Exporte werden immer absteigend
geschrieben und der Live-Store cappt pro Paket bei 150, ein Kolibri→Kolibri-
Round-Trip triggert das also **nie**. Bissig nur bei fremden/handeditierten
Dateien mit >150 nicht-absteigenden Timestamps/Paket → `low`. Kontrast: die zwei
korrekten Sites sortieren **vor** `take` (`AppUsageRepositoryImpl.recordPackageLaunch`
`:111-113`, `exportToJson` `:104-105`).

**Fix:** `.sortedDescending()` **vor** `.take(...)` in `performImport` ziehen
(dann ist der Replace-Sort `:262` redundant).

---

## 2. Gegengeprüft & sauber (Auszug)

- **Concurrency (ganze Achse):** combine-Init-Ordering in allen drei Auswahl-VMs
  (Selection VOR Master-Liste, ohne Suspend dazwischen), `WhileSubscribed`-
  Cold-Entries via `withTimeoutOrNull { …first { isNotEmpty } }`, jede
  Broad-Catch-im-Suspend-Frame rethrowt `CancellationException`, jeder
  `Flow.catch`-Arm guardet vor dem Logging, `flatMapLatest` cancelt nur reine
  Re-Derivation, `runBlocking` nur im dokumentierten `ConsentBootstrap`-Fail-Closed,
  alle `MutableSharedFlow` gepuffert bzw. `Channel(BUFFERED)`.
- **Daten:** Backup-Round-Trip (`BackupDataAssembler`/`BackupRepositoryImpl`/
  `BackupSerializer`/`WallpaperRepositoryImpl`), Consent-Tri-State
  (`CrashReportConsentRepositoryImpl`), Purge/Reset (`ResetRepositoryImpl`,
  `SettingsRepositoryImpl.purgeRepository`, `DataStorePurge`, `FactoryResetUseCase`),
  Time-Weighted-Math (`calculateTimeWeightedScore` — kein Div-by-Zero,
  Future-Clock-Guard, Decay-Bounds), Reconcile-Veto (`ObserveInstalledAppsUseCase`
  + `PackagePresenceImpl`). `ToggleFavoriteUseCase` kann einen Limit-abgelehnten
  Add **nicht** als „Removed" mislabeln (Branch unerreichbar). `AppInfo.displayNameLower`
  wird auf `copy()` korrekt neu berechnet.
- **UI:** `AppDrawerAdapter`/`FavoritesAdapter` (kein submitList-Aliasing,
  `bindingAdapterPosition` zur Klick-Zeit), `AppContextMenuDialogFragment`
  (`isAdded`/`isStateSaved`-Guards, `_binding`-Null-Checks, Adapter-Null-Out),
  die `initialize()`/Config-Change-Re-Entry-Guards.

---

## 3. Fazit

Erste Korrektheits-Linse nach drei Perf-Runden: die Concurrency-Achse — die
teuerste und am dichtesten gelinterte — kommt **leer** zurück, was die bisherige
Härtung bestätigt. Übrig bleiben drei echte Defekte in weniger belaufenen Ecken:

- **F1** (`medium`) — stale Favoriten-Name nach Rename, geteiltes `AppInfoDiffCallback`
  emittiert einen Payload, den `HomeFavoritesAdapter` fälschlich als styling-only behandelt.
- **F2** (`medium`) — SwipeActions „Done" ohne Load-Gate, stiller Verlust beider
  Swipe-Gesten bei frühem Tap. Der Ausreißer unter drei sonst gegateten Screens.
- **F3** (`low`) — Usage-Import truncate-before-sort, nur bei fremden Dateien.

Alle drei sind **user-sichtbar bzw. datenverlustrelevant**, keine Kosmetik — anders
als die AUDIT-16-`low`-Deltas.

**Umgesetzt:** alle drei auf Branch `fix/audit-17-correctness`, in der Reihenfolge
F2 (Datenverlust) → F1 (sichtbare Inkonsistenz) → F3 (Edge-Case), jeder mit einem
Regressions-Test und grünem Linter.

**Offen:** keine. AUDIT-17 ist vollständig umgesetzt.
