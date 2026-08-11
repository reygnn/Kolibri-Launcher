# AUDIT-15 — Lag & Bloat: die Deltas jenseits von AUDIT-14

> **Erzeugt** 2026-08-10 gegen `main` @ `c2136250` (Version 0.99.157 / code 177),
> auf die Frage: *„Wo entsteht in einem text-only Launcher Lag & Bloat — und wo
> stehen unnötige Loops/Schleifen im Code?"*
> **Methode:** drei parallele Subsystem-Scans (App-Lade-/Such-Pipeline,
> RecyclerView-Adapter, redundante Loops/Allokationen), **danach jede zitierte
> Stelle selbst am Code nachgelesen** — kein Befund steht hier auf Zuruf eines
> Sub-Agents. Zwei Sub-Agent-Übertreibungen sind in §4 korrigiert und
> **nicht** in die Findings übernommen.
>
> **Kein akuter Defekt.** Wie schon in AUDIT-14 festgehalten: nichts ruckelt hart,
> nichts leakt, nichts blockiert den ersten Frame. Alle Findings hier sind
> `low` — reines Optimierungspotenzial an der Reaktiv-Pipeline und an den
> Adapter-Rändern. Severity-Skala wie in den Schwester-Audits.

---

## 0. Verhältnis zu AUDIT-14 (gleiche Frage, heute schon beantwortet)

AUDIT-14 (`bd6f23f1`, 0.99.152, ebenfalls 2026-08-10) beantwortet die
Kern-Lag-Frage bereits und ist `GESCHLOSSEN`. Die dortigen Findings sind hier
**gegengeprüft und NICHT erneut als neu gemeldet**:

| Bereits in AUDIT-14 | Status dort |
|---|---|
| `favoriteApps`/`drawerApps` off-Main + `distinctUntilChanged` an den geteilten DataStore-Quell-Flows (F1) | umgesetzt |
| `usageFlow` nur im TIME_WEIGHTED-Modus (F2 Bullet 1/3) | umgesetzt |
| `AppInfo.displayNameLower` / `componentName` vorberechnet (Nit §208/§212) | umgesetzt |
| `ColorStateList`-Cache, Payload-`onBind`, gehoistete Listener im Drawer/Home-Adapter (F3) | umgesetzt |
| `applyNames`-Terminal-Sort auf dem Favoriten-Pfad überflüssig (F1 §5.3) | **bewusst vertagt** |
| `SimpleDateFormat` pro Minuten-Tick im `ClockDelegate` (Nit §215) | **bewusst vertagt** |

AUDIT-15 sammelt ausschließlich das, was in AUDIT-14 **nicht** vorkommt
(gegengeprüft per `grep` gegen AUDIT-14.md). Zwei der drei Scans bestätigten im
Übrigen, dass die zwei heißesten Adapter (`AppDrawerAdapter`,
`HomeFavoritesAdapter`) und die Enumeration (`InstalledAppsRepositoryImpl`,
off-IO, Single-Pass) nach AUDIT-14 **sauber** sind — kein neuer Befund dort.

---

## 1. Neue Findings (alle `low`)

| # | Ort | Was | Severity |
|---|---|---|---|
| **F1** ✅ | `FavoritesOrderRepositoryImpl.sortAppsWithGivenOrder` | O(n·m) `List.find`+`remove` in Schleife über die Favoriten | `low` |
| **F2** ✅ | `AppSearchFilter.filterAndDecide` | Suche nutzt `contains(ignoreCase=true)` statt des vorberechneten `displayNameLower` | `low` |
| **F3** | `GetFavoriteAppsUseCase.processApps` | zwei volle `.map{copy()}`-Durchläufe pro Emission (Naming + isFavorite) | `low` |
| **F4** ✅ | `item_favorite.xml` / `FavoritesAdapter` | totes `app_icon`-`ImageView`, pro Zeile inflatet und jedes Bind auf `GONE` gesetzt | `low` |
| **F5** | `HomeFragment.updateTimeBasedChips` | `removeAllViews()` + Per-Event-`addView`-Rebuild der Chip-Leiste ohne Diffing | `low` |
| **F6** ✅(a)(c) | `AppContextMenuAdapter` | Per-Bind-Lambda-Allokation + `getString`-Lookup; Farb-Update via payloadloses `notifyItemRangeChanged` | `low` |

### F1 — O(n·m) in `sortAppsWithGivenOrder` · ✅ umgesetzt
`data/.../data/FavoritesOrderRepositoryImpl.kt:153-172`

> **Erledigt:** Loop auf `HashMap<componentName, AppInfo>` (`putIfAbsent`,
> first-wins wie das alte `.find`) + ein `HashSet` konsumierter Keys → O(n+m).
> Beim Umbau fiel eine **Verhaltensdifferenz an malformed Input** auf: zwei
> Apps mit gleichem `componentName` in `appsToSort` — der alte Loop emittierte
> die zweite Kopie im alphabetischen Rest (App doppelt), die Map-Variante
> dedupliziert auf eine (first-wins). `componentName` ist die Komponenten-
> Identität, der Fall ist also nur malformed-state; die Dedup ist die sicherere
> Wahl, im Code dokumentiert und per neuem Impl-Test festgenagelt (`… -
> duplicate componentName in appsToSort - dedupes to the first`,
> mutations-geprüft). Contract-Tests (18 Fake + 18 Impl) grün, keine
> Extraktion nötig — die Funktion war über den relaxed-Mock-DataStore bereits
> direkt testbar.

```kotlin
for (componentName in order) {                                  // O(n)
    val app = remainingApps.find { it.componentName == componentName }  // O(m) List-Scan
    if (app != null) {
        orderedApps.add(app)
        remainingApps.remove(app)                               // O(m) List-Shift
    }
}
```

`.find{}` und `.remove()` sind lineare Scans über eine `MutableList`, in einer
Schleife über `order` → O(n·m). **Verifiziert:** die einzigen Aufrufer von
`sortFavoriteComponents` (→ `sortAppsWithGivenOrder`) sind
`GetFavoriteAppsUseCase.processApps:144` und `SettingsViewModel:133` — `appsToSort`
ist in **beiden** Fällen die reine Favoriten-Liste, nicht die volle App-Liste.
`n` und `m` sind also klein (Favoriten-Anzahl, im Praxis-Bereich zweistellig),
deshalb `low` und nicht höher. Der Loop läuft aber auf dem Favoriten-Combine, der
bei jedem App-Start (Usage-Write) re-fired — reine Fleiß-Optimierung.
**Fix:** einmal `Map<componentName, AppInfo>` aus `appsToSort` bauen (O(m)), dann
Ordering über die Map + ein `HashSet` konsumierter Keys → O(n+m). Der Contract
`FavoritesOrderRepositoryContract` deckt Duplikate/fehlende Order-Einträge bereits
ab, pinnt das Verhalten also gegen einen Umbau.

### F2 — Suche ignoriert den vorberechneten Lower-Key · ✅ umgesetzt
`app/.../ui/appdrawer/AppSearchFilter.kt:30-37`

> **Erledigt:** Query einmal `lowercase()`, Match gegen `displayNameLower`
> statt `contains(ignoreCase=true)` pro App. `AppSearchFilterTest` (9 JVM)
> grün, `AppDrawerFragmentSearchTest` (instrumented) deckt das
> End-to-End-Matching ab.


```kotlin
allApps.filter { app -> app.displayName.contains(query, ignoreCase = true) }
```

`ignoreCase = true` macht Case-Folding **pro App, pro Tastendruck** neu, obwohl
`AppInfo.displayNameLower` (der Sort-Key aus AUDIT-14 Nit §208) genau das schon
einmalig vorhält. `query.lowercase()` einmal ziehen und gegen `displayNameLower`
vergleichen erspart die wiederholte Fold-Arbeit über die ganze Liste.
Entschärft durch den 150 ms-Debounce (`SEARCH_DEBOUNCE_DELAY_MS`), daher `low`.
Kleiner Semantik-Hinweis: `displayNameLower` ist locale-invariant — die Suche
würde damit exakt so (nicht locale-aware) matchen wie der Sort ohnehin schon
ordnet, also konsistent.

### F3 — Doppelter Voll-Listen-Copy pro Favoriten-Emission
`domain/.../usecase/GetFavoriteAppsUseCase.kt:102,137`

`applyNames(rawApps, …)` (`:102`) allokiert `N` `copy()` **und** sortiert; danach
mappt `processApps` (`:137`) erneut über die volle Liste für `isFavorite`. Zwei
volle Kopien pro Emission. **Korrektur zum Sub-Agent-Report:** die Custom-Names
gehen dabei *nicht* verloren — `processApps(namedApps, …)` wird mit der benannten
Liste aufgerufen, der Parameter heißt innen nur wieder `rawApps` (Shadowing).
Real bleibt: (a) der Terminal-Sort in `applyNames` ist auf diesem Pfad verworfen
(= AUDIT-14 F1 §5.3, dort schon vertagt), und (b) die beiden Voll-Listen-`.map`
ließen sich zu einem Durchlauf zusammenziehen (Name + `isFavorite` in einem
`copy`). Gehört sinnvollerweise mit F1 §5.3 aus AUDIT-14 zusammen angefasst.

### F4 — Totes `app_icon` im Favoriten-Item · ✅ umgesetzt
`app/.../res/layout/item_favorite.xml:10-14`, `FavoritesAdapter.kt:32`

> **Erledigt:** `app_icon`-`ImageView` aus dem Layout entfernt, den
> `binding.appIcon.visibility = GONE`-Setter im Adapter gelöscht;
> `drag_handle` bleibt (echtes Reorder-Icon). ViewBinding regeneriert
> ohne das `appIcon`-Feld, `:app:assembleDebug` grün.


`item_favorite.xml` inflatet ein `ImageView app_icon`, das der Adapter bei **jedem**
Bind auf `GONE` setzt (`:32`). Das View wird trotzdem pro Zeile inflatet und
gemessen — tote Inflation. (`drag_handle` daneben ist ein echtes, sichtbares Icon
— das ist gewollt, dies ist die Reorder-Liste, nicht die text-only Home-Ansicht.)
`app_icon` aus dem Layout entfernen erspart die Inflation. `low`, weil die
Reorder-Liste kurzlebig und selten offen ist.

### F5 — Chip-Leiste wird komplett neu gebaut
`app/.../ui/home/HomeFragment.kt:953,976-985`

`updateTimeBasedChips` macht `removeAllViews()` und baut die Kalender-/Zeit-Chips
in einer Per-Event-Schleife mit `addView` neu — Full Teardown-and-Rebuild ohne
Diffing, bei jedem Time-Event-Tick. Betrifft **nicht** die Favoriten (die laufen
sauber über den Adapter), nur die Chip-Leiste; die Anzahl ist durch die
sichtbaren Events begrenzt. `low`; ein diffendes Update oder ein kleiner
`ListAdapter` wäre die saubere Form, lohnt aber erst bei spürbar vielen Chips.

### F6 — Context-Menu-Adapter: Per-Bind-Allokationen · ✅ (a)+(c) umgesetzt, (b) bewusst belassen
`app/.../ui/appcontextmenu/AppContextMenuAdapter.kt:30,70,88`

Drei kleine Ränder: (a) `setOnClickListener` in `onBindViewHolder` (`:70`)
allokiert pro Bind ein Lambda; (b) `bind` ruft `context.getString(...)` pro Bind
für Launcher-Action-Labels (`:88`); (c) `setActionTextColor` nutzt ein
payloadloses `notifyItemRangeChanged(0, itemCount)` (`:30`) → voller Rebind der
sichtbaren Zeilen. Die Liste ist winzig (ein Kontextmenü), daher `low` — aber es
ist der einzige Adapter mit Per-Bind-Allokation/-Lookup; Listener ins Holder-`init`
hoisten (wie im Drawer-Adapter) wäre der konsistente Fix.

> **Erledigt (a):** Listener aus `onBindViewHolder` in `onCreateViewHolder`
> gehoistet — eine Allokation pro Holder statt pro Bind, Item-Auflösung via
> `bindingAdapterPosition` zur Klick-Zeit (Idiom von `AppDrawerAdapter` /
> `HomeFavoritesAdapter`). Abgesichert durch ein vorab gebautes
> Charakterisierungs-Netz (`AppContextMenuAdapterBindingTest`, 9 Tests,
> mutations-geprüft) — der Refactor lief grün gegen das Netz.
>
> **Erledigt (c) — als Konsistenz-, nicht Perf-Fix:** `setActionTextColor`
> feuert jetzt `notifyItemRangeChanged(0, itemCount, COLOR_PAYLOAD)`; ein
> `onBindViewHolder(…, payloads)`-Override recolort nur das Label (kein
> erneuter `getString`), leerer Payload fällt auf den vollen Bind durch.
> **Perf-Begründung trägt hier bewusst nicht** — bei ~5–8 kurz sichtbaren
> Zeilen ist das Signal null; die Sibling-Adapter (`AppDrawerAdapter`,
> `HomeFavoritesAdapter`) nutzen Payloads, weil *ihre* Listen groß werden
> (bis ~50 Favoriten), und die sind seit AUDIT-14 F3 bereits payload-
> optimiert. Umgesetzt wurde (c) rein zur **Angleichung an das Sibling-
> Idiom**: derselbe Marker-Payload-Mechanismus in allen drei Adaptern, kein
> Sonderfall mehr im kleinsten. Abgesichert durch zwei zusätzliche Tests im
> Charakterisierungs-Netz (Farb-only-Rebind ohne Label-Neuauflösung; leerer
> Payload → voller Bind).
>
> **Bewusst belassen (b):** Das Label variiert echt pro Item, ein
> `getString`-Cache wäre künstlich. `low`, kein Signal.
>
> **Nebenprodukt:** Der einmalige Mutations-Check des Netzes ist als
> Autoren-Disziplin in `TESTING_CONVENTIONS.kt` verankert.

---

## 2. Gegengeprüft & bewusst NICHT als Finding gemeldet

- **`applyNames`-Terminal-Sort / `SimpleDateFormat` pro Tick** — bereits AUDIT-14
  F1 §5.3 bzw. Nit §215 (dort vertagt), siehe §0. Kein Doppeleintrag.
- **`SwipeActionsAppListAdapter` bindet ein Icon** (`:53/:57`) — das ist der
  Slot-Indikator, gewollt, kein text-only-Verstoß.
- **`combine`-Re-Map bei Hidden/Custom-Name/Usage-Ticks** — bereits in AUDIT-14
  als reaktives Re-Derive dokumentiert und über `distinctUntilChanged` +
  Modus-Gate entschärft (F2). Kein neuer Befund.
- **Kein `Collator`/Locale-aware Sort** — bestehende, bewusste Entscheidung
  (vorberechneter `displayNameLower`), kein Perf-Problem; höchstens ein
  Korrektheits-*Wunsch* für nicht-lateinische Schriften, nicht Teil dieses Audits.
- **Membership-Checks** (`GetDrawerAppsUseCase:138`, `GetFavoriteAppsUseCase:138/184`,
  `GetRecentAppsUseCase:75`, Onboarding/Hidden/Swipe-VMs) laufen bereits über
  `Set.contains`/`Map` — keine List-Scans. Sauber.
- **`InstalledAppsRepositoryImpl.processResolveInfoList`** — Single-Pass über das
  PackageManager-Ergebnis, auf `Dispatchers.IO`. `loadLabel` pro App ohne
  Label-Cache bleibt der teuerste *Reload*-Posten, ist aber per Debounce/Trigger
  eng gehalten (nur Cold-Start / Paket-Broadcast / Force-Reload) — als
  Reload-, nicht Runtime-Kosten hier bewusst ohne eigenes Finding (Kandidat, falls
  die Reload-Frequenz je zum Thema wird).

---

## 3. Fazit

Nach AUDIT-14 ist die eigentliche Lag-Front geräumt. Was AUDIT-15 findet, sind
sechs `low`-Deltas — ein O(n·m)-Loop über eine kleine Liste (F1), zwei
Reaktiv-Pipeline-Mikros (F2, F3), zwei Adapter-Ränder (F4, F6) und ein
Chip-Rebuild (F5). Nichts davon ist dringend.

**Umgesetzt:** F4 (totes `app_icon` entfernt), F2 (Suche gegen `displayNameLower`),
F6 (a)+(c) (Listener-Hoist + Farb-Payload zur Angleichung an das
Sibling-Adapter-Idiom, abgesichert durch ein mutations-geprüftes
Charakterisierungs-Netz) und F1 (O(n+m)-Map-Lookup, Verhaltensdifferenz an
malformed Input dokumentiert + gepinnt). F6 (b) bewusst belassen (siehe §1).

**Offen** (alle `low`, kein Druck): **F3** am besten zusammen mit dem
vertagten AUDIT-14 F1 §5.3 angehen, sobald die Favoriten-/Naming-Pipeline
ohnehin mal offen ist; **F5** (Chip-Diffing) nur bei spürbar vielen Chips.

## 4. Korrekturen an den Sub-Agent-Zurufen (Transparenz)

- **„`order` up to 3002" (F1)** — falsch. `appsToSort` ist immer die Favoriten-,
  nie die volle App-Liste (verifiziert über alle Aufrufer). Severity von der
  Sub-Agent-Einschätzung („TIER 1, höchster Wert") auf `low` heruntergezogen.
- **„Custom Names gehen verloren, weil `:137` über `rawApps` statt `namedApps`
  mappt" (F3)** — falsch. Parameter-Shadowing: `processApps` bekommt `namedApps`
  übergeben, der lokale Parameter heißt nur wieder `rawApps`. Der reale Punkt ist
  die doppelte Voll-Listen-Kopie, nicht ein Datenverlust.
