# APPLIST_SORT_SPLIT_SPEC.md — Sortierung dem Consumer geben, nicht dem Namens-Helfer (RAL-4)

**Status: ENTWURF v5 (2026-08-13), greenfield — noch nicht gebaut. VERDIKT: ein
FÜNFTES Mal vertagen (§8), v4/v5-Design als akzeptierter Bauplan.** Multi-Agent-
Review-Runde 3 (5 Linsen gegen v4, gegen Code verifiziert) eingearbeitet: **keine
Korrektheits-Regression** — der map-only-Flip ist verhaltensneutral, alle §3-
Beweise halten. ABER zwei substanzielle Findings: der CustomNames-Order-Guard war
**vakuum** (der Test-Fake prä-sortiert, fängt einen fehlenden VM-Sort nicht — M1,
der eine „würde-grün-shippen"-Defekt); und „strikt einfacher" **überzieht** — v4
tauscht eine *strukturell garantierte* Invariante gegen eine *test-/konvention-
enforced* an ~6 Sites (M2). §5/§6 (Test-Plan) und §7/§8 (Framing/Verdikt)
entsprechend korrigiert.

**DESIGN-WENDE gegenüber v1–v3** (steht, vom Review bestätigt als „genuinely
cleaner than v3"): Die ersten drei Fassungen versuchten, den in `applyCustomNames`
gebündelten Sort per *Zwei-Funktionen-Split* (sortierter Default +
`internal`-Opt-in) zu umgehen, und der Multi-Agent-Review (Runde 2) nannte das
zu Recht „lateral". v4/v5 wechselt das Modell auf den Vorschlag, der die vier
gescheiterten Anläufe erklärt: **Namensauflösung ist geteilt (identische Logik,
DRY), Sortierung war nie geteilt** — jeder anzeigende Consumer sortiert seine
Liste selbst. Der Helfer wird eine **reine Map ohne Ordnungs-Post-Condition**.
Kein Funktionspaar, kein `internal`/`public`-Tanz. Der Helfer wird einfacher —
aber die Sort-Invariante wandert von *strukturell* zu *test-enforced* (§7, M2).

> **Erst reviewen, dann bauen.** Der gefährliche Teil ist NICHT der Helfer
> (er wird trivialer, nicht komplexer), sondern der Nachweis (§3), dass **jeder**
> anzeigende Consumer schon selbst sortiert oder es explizit dazubekommt — kein
> Screen darf nach dem Flip still in Enumerations-Ordnung landen.

## Historie — warum vier Anläufe scheiterten (und v4 nicht dasselbe ist)

- **Der gebündelte Sort war Migrations-Gerüst, nicht Architektur.** RAL-1
  (REACTIVE_APPLIST_SPEC §3) baute den `.sortedBy` in `applyCustomNames` ein,
  damit der Umstieg von der sortierenden Enumeration (`processResolveInfoList`)
  **verhaltensneutral** blieb — kein Consumer musste angefasst werden
  (Drop-in-Eigenschaft). Richtig für die Migration. Aber die ist längst durch;
  seither bedient der Sort eine Neutralität, die niemand mehr braucht.
- **RAL-1a** (volle Begründung im `applyCustomNames`-KDoc) hielt den Sort
  gebündelt: ein Split fragmentiere die Invariante „Quell-Grenze liefert die
  sortierte Post-Condition". Dreimal vertagt (AUDIT-14 F1 §5.3, AUDIT-15 F3), der
  `mapOnly`-JMH-Arm bezifferte den Sort auf ~62 % der Funktionskosten (~9,7 µs
  @200, off-Main — vernachlässigbar).
- **v1–v3 + der Multi-Agent-Review** hielten am *sortierten Zustand als geteilte
  Post-Condition* fest und suchten einen sicheren Weg, ihn zu splitten. Jeder
  Weg fragmentierte die Invariante → „lateral, vertagen".
- **v4 gibt die Prämisse auf.** Nicht „wie teile ich den geteilten Sort sicher",
  sondern „der Sort war nie ein geteilter Belang". Damit gibt es keine sortierte
  Post-Condition mehr zu fragmentieren — die RAL-1a-Sorge löst sich auf, statt
  umgangen zu werden.

Verifizierte Code-Fakten aus Runde 2 (Multi-Agent, gegen den Code geprüft) leben
in v4 weiter: die Favoriten-Fehler-Fallbacks (§3.2), der Recents-Tie-Break
(§3.3), `SettingsViewModel` ordnungs-agnostisch (§2). Schwester-Dokumente:
**`REACTIVE_APPLIST_SPEC.md`** (RAL-1/-1a/-4, §3-Inventur), der
**`applyCustomNames`-KDoc**, **`ApplyCustomNamesBenchmark.kt`** (`mapOnly`).

---

## 0. Das Problem in einem Satz

`applyCustomNames` tut zwei Dinge — Custom-Namen auflösen (echt geteilt) UND
alphabetisch sortieren (nie geteilt, jeder Consumer will eine andere Ordnung).
Die Bündelung dieser zwei Verantwortungen ist die Wurzel: der Sort ist für drei
Consumer tot, für zwei tragend, und jeder Versuch, ihn herauszulösen, kollidiert
mit der geteilten Post-Condition. v4 entbündelt: Helfer löst nur Namen auf,
Sortieren ist Sache des Anzeigenden.

---

## 1. Zielbild — EIN reiner Map-Helfer, kein Sort

```kotlin
// Löst Custom-Namen auf. KEINE Ordnungs-Post-Condition — die Liste kommt in
// Eingabe-Reihenfolge zurück. WER ANZEIGT, SORTIERT SELBST (siehe KDoc).
// Kein internal/public-Paar mehr: es gibt nur diese eine Funktion.
fun applyCustomNames(apps: List<AppInfo>, names: Map<String, String>): List<AppInfo> =
    apps.map { it.copy(displayName = names[it.packageName] ?: it.originalName) }
```

Das ist der gesamte Helfer. Der frühere `.sortedBy { it.displayNameLower }`
entfällt hier und wandert an die Anzeige-Sites, die ihn ohnehin größtenteils
schon haben (§2).

**Warum das die RAL-1a-Sorge auflöst statt sie zu umgehen:**

1. **Es gibt keine sortierte Post-Condition mehr, die man fragmentieren könnte.**
   RAL-1a fürchtete, ein Split zerreiße „Quell-Grenze liefert sortiert". v4
   *behauptet das gar nicht mehr*: die Quell-Grenze liefert Namen, Punkt.
   Sortieren ist Präsentation und gehört zum Präsentierenden — die normale
   Trennung, dass eine Daten-Formungs-Funktion keine Anzeige-Ordnung aufzwingt.
2. **Der Fehlermodus wird SICHTBAR statt maskiert.** Heute ist die
   Sort-Abhängigkeit unsichtbar — versteckt in einem Helfer, der `apply…` heißt,
   nicht `…AndSorts`. Ein Consumer, der sich darauf verlässt, weiß nicht, dass er
   es tut (die Maskierung, §4). Bei einem Helfer, dessen KDoc laut sagt
   „Reihenfolge = Input, für Anzeige selbst sortieren", ist ein fehlendes
   `.sortedBy` am Call-Site eine **sichtbare Entscheidung**, kein verborgener Bug.
   Unsichtbar → sichtbar ist strikt besser, nicht nur anders. Das ist genau der
   Einwand, an dem v1–v3 hingen („ein neuer Consumer greift den sortierten
   Default und verlässt sich still darauf") — hier gibt es keinen sortierten
   Default, auf den man sich still verlassen kann.
3. **Eine Impl, echt.** Anders als der v3-Zwei-Funktionen-Ansatz (der die
   Map-Zeile de facto doppelte — Helfer + Benchmark-Inline-Kopie) gibt es hier
   genau eine Map-Funktion. Der `mapOnly`-Benchmark misst weiterhin genau diesen
   Pfad (er IST jetzt die ganze Funktion).
4. **Die Erkennungs-Invariante bleibt.** `originalName != displayName` erkennt
   umbenannte Apps — das ist Namens-*Auflösung*, unberührt von der Sortierung.

**Naming (offene Kleinentscheidung, §8):** der Name `applyCustomNames` impliziert
keine Sortierung, also darf er bleiben — mit einem KDoc, der die
Nicht-Sort-Zusage explizit macht. Alternativ ein sprechenderer Name
(`resolveCustomNames`). Empfehlung: Name behalten, KDoc schärfen (minimaler Churn;
die Call-Sites werden ohnehin angefasst, aber ein Rename an allen fünf ist rein
kosmetisch).

---

## 2. Scope — wer sortiert schon, wer bekommt einen Sort dazu

`applyCustomNames` hat fünf Call-Sites (REACTIVE_APPLIST §3-Inventur). Unter v4
sortiert **jeder anzeigende Consumer selbst**; die meisten tun es bereits.

| Site | Consumer | sortiert heute? | v4-Änderung |
|---|---|---|---|
| Site 2 | `GetDrawerAppsUseCase` | ja (alpha / time-weighted) | keine (nur Helfer liefert unsortiert) |
| Site 2 | `GetFavoriteAppsUseCase` | ja (`sortFavoriteComponents`) | keine |
| Site 3 | `GetRecentAppsUseCase` | Recency + Dedup | + expliziter Alpha-Tie-Break (§3.3) |
| Site 1 | `GetInstalledAppsUseCase` → CustomNames | **nein — verlässt sich** | **+ `.sortedBy { displayNameLower }`** in CustomNamesVM |
| Site 1 | `GetInstalledAppsUseCase` → Hidden | ja (eigener Sort) | keine — **spart** jetzt den toten Upstream-Sort |
| Site 1 | `GetInstalledAppsUseCase` → Swipe | ja (eigener Sort) | keine — **spart** den toten Upstream-Sort |
| Site 1 | `GetInstalledAppsUseCase` → Settings | ordnungs-agnostisch (s.u.) | keine |
| — | `GetOnboardingAppsUseCase` | **nein — verlässt sich** (`filter` erhält Ordnung) | **+ `.sortedBy { displayNameLower }`** |

**Konkret ändern sich nur zwei Anzeige-Sites** (CustomNames, Onboarding) — sie
bekommen je eine explizite Sortierzeile —, plus der Helfer wird map-only und
Recents bekommt seinen Tie-Break. Alle anderen sortieren schon.

**Bonus gegenüber v3: Hidden/Swipe werden mit-befreit.** In v3 blieb Site 1
sortiert, also zahlten Hidden/Swipe weiter einen toten Upstream-Sort *und* ihren
eigenen — „strukturell eingesperrt", hieß es. Unter v4 emittiert
`GetInstalledAppsUseCase` unsortiert; Hidden/Swipe sortieren nur noch einmal
(ihren eigenen), CustomNames sortiert neu, Settings ist egal. Der v3-„gefangene"
Doppel-Sort löst sich also auch — ein Punkt, den der Zwei-Funktionen-Ansatz nicht
erreichte.

**`GetInstalledAppsUseCase` wird map-only.** Sein einziger sort-abhängiger
Consumer (CustomNames) sortiert dann selbst; die drei anderen sind egal oder
sortieren eh. Das ist der Kern der Wende: der geteilte Use-Case liefert Namen,
nicht Ordnung.

**Settings — genauer (Runde-3-Präzisierung, Verdikt hält).** „Ordnungs-agnostisch"
ist knapp: `installedApps.value` speist neben dem `isNotEmpty`/`size`-Gating auch
`prepareFavoritesForSorting` → den Sort-Favoriten-Dialog. Aber der leitet seine
Anzeige-Ordnung über `sortFavoriteComponents` (savedOrder + Alpha-Rest) NEU ab,
hängt also nicht an der Eingabe-Ordnung. (Ein dritter unsortierter Fallback in
`SettingsViewModel` ist effektiv unerreichbar — der Sort fängt seinen eigenen
Throwable intern, rethrowt nur `CancellationException`.) Also bleibt Settings
order-agnostisch — nur die Begründung war unvollständig, nicht das Ergebnis.

---

## 3. Der Kern-Nachweis: sortiert jeder anzeigende Consumer wirklich (total)?

Die Beweislast dreht sich gegenüber v3 leicht: nicht „ist der Downstream-Sort der
drei Ziele total", sondern „sortiert **jeder** anzeigende Consumer, und ist
dieser Sort input-unabhängig". Ein Consumer, der nach dem Flip nicht (total)
sortiert, landet still in Enumerations-Ordnung (§4).

> **Präzision der „total"-Behauptungen.** Alle stützen sich auf `displayNameLower`
> und Kotlins **stabilen** `sortedBy`. Für jeden Consumer, dessen Sort im Code
> steht, reduziert sowohl der alte (Helfer-sortiert) als auch der neue
> (Consumer-sortiert) Pfad einen Schlüssel-Gleichstand auf dieselbe
> `rawApps`-Ordnung → **beweisbar NULL-Änderung**, kein kosmetischer Tie-Flip.
> Die zwei realen Kanten sind Recents' Dedup (§3.3, expliziter Fix) und die
> Favoriten-Fehler-Fallbacks (§3.2, OOM-only Degrade).

### 3.1 Drawer — sortiert schon total, keine Änderung
- **ALPHABETICAL:** `visibleApps.sortedBy { it.displayNameLower }` — total. ✓
- **TIME_WEIGHTED:** `AppUsageRepositoryImpl.sortAppsByTimeWeightedUsage` →
  `compareByDescending { score }.thenBy { it.displayNameLower }` — **expliziter**
  Tie-Break, Gleichstände brechen alphabetisch, nicht über Input. Fallback
  `apps.sortedBy { displayNameLower }` — total. ✓

### 3.2 Favoriten — Steady-State total, ZWEI Fehler-Fallbacks nicht (R2-M1)
`FavoritesOrderRepositoryImpl`:
- **Steady-State (sicher):** geordneter Teil über `order`/`componentName`-Lookup
  (input-unabhängig); Rest-Anhang `.sortedBy { displayNameLower }` (total);
  `order.isEmpty()` → `sortedBy` (total); `putIfAbsent(componentName)` nur bei
  malformed identischem `componentName` input-sensitiv (Dedupe dokumentiert/
  getestet, im Normalbetrieb nie). ✓
- **NICHT total — zwei Fehler-Fallbacks (R2-M1, Runde-2-Fund):**
  `sortAppsWithGivenOrder`s `catch` gibt `appsToSort` **unsortiert** zurück
  (`FavoritesOrderRepositoryImpl:194`); `sortFavoriteComponents`s
  Doppel-Fault-`catch` gibt `favoriteApps` **unsortiert** zurück (`:148`). Der
  `applyCustomNames`-KDoc dokumentiert genau das schon. **Bewertung: akzeptierter
  OOM-only kosmetischer Degrade** — beide Catches umschließen reine `sortedBy` auf
  Non-Null-Strings (per Rule 11 nicht werfbar), feuern also nur bei
  `OutOfMemoryError`; in dem Moment ist „kurz Enumerations- statt Alpha-Ordnung"
  belanglos.

### 3.3 Recents — EIN echter Effekt, braucht einen expliziten Tie-Break

> **Kein Split-*Preis*, ein Klarheits-*Gewinn*.** Der Sort tat hier Doppeldienst:
> Listen-Ordnung (die Recents wegwirft — final zählt `recentPackages`/Recency)
> UND Dedup-Determiniertheit (welche Activity ein Multi-Activity-Paket
> repräsentiert). Recents nutzte nur die zweite, still. Sie explizit zu machen ist
> sauberer als sie aus einem Upstream-Sort zu erben — v4 macht diese vorher
> versteckte Kopplung lokal und sichtbar.

`GetRecentAppsUseCase`: `putIfAbsent(packageName, app)` behält die **erste**
`AppInfo` pro Paket — bei mehreren launchbaren Activities entscheidet die
Iterations-Reihenfolge. Heute alpha-sortiert (aus `applyCustomNames`), also
deterministisch die alphabetisch erste. Der Code-Kommentar sagt selbst „keeps
that deterministic" — die Determiniertheit kommt vom Sort. Map-only → Enumerations-
Ordnung entscheidet → realer (kosmetischer) Change bei Multi-Activity-Paketen.

**Fix (verhaltenserhaltend, O(1)/Kollision statt O(N·log N)-Sort):**
```kotlin
val visibleByPackage = HashMap<String, AppInfo>()   // Iterations-Order egal, finale Order aus recentPackages
for (app in currentApps) {
    if (app.componentName in hidden) continue
    val existing = visibleByPackage[app.packageName]
    // Alphabetisch ersten Repräsentanten pro Paket — input-unabhängig,
    // reproduziert die heutige (vom Sort gelieferte) Auswahl.
    if (existing == null || app.displayNameLower < existing.displayNameLower) {
        visibleByPackage[app.packageName] = app
    }
}
recentPackages.mapNotNull { visibleByPackage[it] }.take(limit)
```
Der `displayNameLower`-Tie-Break-Kommentar bindet auf denselben Schlüssel zurück,
den die Anzeige-Sorts nutzen, damit die zwei nicht still divergieren. (Alternative:
Change akzeptieren, `ACCEPTED_LIMITATIONS.md`. Empfehlung: Fix — drei Zeilen.)

### 3.4 Die zwei NEUEN Sorts (CustomNames, Onboarding) — total, aber Platzierung heikel
Beide bekommen `.sortedBy { it.displayNameLower }` an der Anzeige-Grenze.
`sortedBy` auf einer Non-Null-String-Property ist total und kann nicht werfen —
kein neues Catch, kein neuer Convention-Gate. Aber **wo** genau, ist bei
CustomNames nicht trivial (Runde-3-Fund):
- **CustomNames — auf `masterAppList`, NICHT nur auf `displayedApps`.**
  `CustomNamesViewModel` setzt `masterAppList = fullyProcessedList` und leitet
  daraus **zwei** Ansichten ab: die gefilterte `displayedApps` UND
  `appsWithCustomNames` (die „Apps mit Custom-Name"-Sektion). Sortiert man nur
  die gefilterte Anzeigeliste in `updateUiFromMasterList`, shippt die
  Custom-Name-Sektion still in Enumerations-Ordnung. Der Sort muss auf
  `masterAppList` bei der Zuweisung landen, damit beide Ansichten ihn erben.
- **Onboarding — nach dem `filter`** (ordnungserhaltend), unkritisch.

Damit sortiert **jeder** anzeigende Consumer explizit — aber die CustomNames-
Platzierung ist genau die Sorte Detail, die ein Test absichern muss (§5/§6, M1).

---

## 4. Die Maskierungs-Umkehr (warum §6-Tests Pflicht sind)

Heute maskiert der gebündelte Sort einen latenten Bug: entfernte man aus einem
Consumer versehentlich den Downstream-Sort, käme die Liste trotzdem alpha-sortiert
(aus dem Helfer). Map-only entfernt diese Maskierung **vollständig** — jeder Sort
ist explizit und sichtbar, ein fehlender fällt auf. Das ist der Vorteil; das
Risiko ist die Kehrseite: der Flip könnte eine heute-maskierte Abhängigkeit
freilegen. §3 hat gesucht und genau zwei reale gefunden (Recents-Dedup,
Favoriten-Fallbacks); der Rest ist beweisbar NULL-Änderung. Aber der Beweis ist
nur so gut wie die Tests, die die finale Ordnung **vor** dem Flip festnageln —
besonders für die zwei Consumer, die einen Sort NEU bekommen.

---

## 5. Migrationsreihenfolge (je ein revertierbarer Commit)

Die idempotente-Overlay-Reihenfolge von RAL-1 (2a/2b) wird gespiegelt: erst die
Sorts an die Consumer ziehen, **während der Helfer noch sortiert** (redundant,
aber grün), dann den Helfer-Sort entfernen — der gefährliche Flip ist ein winziger
letzter Commit.

1. **Ordnungs-Assertions zuerst — und sie müssen FEHLSCHLAGEN KÖNNEN (M1, der
   kritischste Punkt).** Guard „Test grün ⟺ Consumer sortiert selbst" hält nur,
   wenn der Test einen fehlenden Sort tatsächlich fangen KANN — und genau das war
   in v4 für CustomNames falsch:
   - **M1 — der Test-Fake prä-sortiert, also ist der Guard vakuum.**
     `ReactiveFakeInstalledAppsRepository.triggerAppsUpdate()` löst Namen auf UND
     `.sortedBy { it.displayName.lowercase() }`, bevor es emittiert. `CustomNamesVM`
     sortiert heute nicht — der Test ist trotzdem grün, WEIL der Fake vorsortiert.
     Auf echtem Gerät emittiert `getInstalledApps()` **original-namen-sortierte
     Original-Label** (`InstalledAppsRepositoryImpl:279`), also landet eine
     umbenannte App (Camera→„My Camera") an der Original-Position, wenn der VM-Sort
     fehlt. Der Test MUSS das fangen, kann es aber nicht.
     **Fix (Pflicht vor Bau):** `ReactiveFakeInstalledAppsRepository` so ändern,
     dass es **original-namen-sortierte Original-Label** emittiert (Spiegel von
     post-2b `processResolveInfoList`) — Prä-Sort + Prä-Auflösung raus. Erst dann
     ist der CustomNames-Order-Test überhaupt fähig, rot zu werden. Assertion auf
     **beide** Ansichten (`displayedApps` UND `appsWithCustomNames`, §3.4).
   - *Nicht-alpha-Fixture-Disziplin gilt für BEIDE neuen Sorts (Minor):* nicht nur
     Recents — auch der Onboarding-Test braucht eine Fixture, deren
     Custom-Name-Anzeigeordnung ≠ Enumerations-Ordnung ist, sonst ist auch er
     vakuum (heutige Onboarding-Tests sind order-insensitiv auf Alpha-Fixtures).
   - *Use-Case/VM-Ebene:* Drawer-ALPHABETICAL, Recents (Multi-Activity,
     **nicht-alpha-erste** Fixture — existiert noch nicht in der Suite), die zwei
     neuen Sorts (mit den obigen Fixture-Fixes).
   - *Impl-Ebene (delegierte Sorts):* `AppUsageRepositoryImplTest` (Score-Tie →
     `thenBy`), `FavoritesOrderRepositoryImplTest` (Alpha-Rest + die zwei
     unsortierten Fallbacks). Ein Fixed-Return-Mock auf Use-Case-Ebene beweist
     hier nichts und darf es nicht vorgeben.
2. **Die zwei Consumer sortieren lassen — während der Helfer noch sortiert.**
   `.sortedBy { displayNameLower }` in CustomNamesVM (auf `masterAppList`, §3.4) +
   OnboardingUseCase ergänzen. No-Op-Overlay (Liste ist schon sortiert), jeder
   Commit grün. Ab jetzt sortiert jeder anzeigende Consumer selbst.
3. **Recents-Fix (§3.3).** `putIfAbsent` → expliziter Alpha-Tie-Break,
   `LinkedHashMap` → `HashMap`. Der Schritt-1-Recents-Test bleibt grün (auf
   alpha-Input dieselbe Auswahl).
4. **Den Helfer-Sort entfernen — der eine Flip.** `applyCustomNames` →
   map-only; `GetInstalledAppsUseCase` emittiert damit unsortiert (CustomNames
   sortiert seit Schritt 2 selbst, Hidden/Swipe eh, Settings egal). Die
   Schritt-1-Tests sind der Wächter: bleiben sie grün, hat jeder Consumer seinen
   Sort. Erst hier wird die Order-Unabhängigkeit real (Recents-Test läuft jetzt
   auf der nicht-alpha-Fixture durch den map-only-Pfad).
5. **Doku angleichen — BEIDE KDoc-Blöcke (Minor).** Der `applyCustomNames`-KDoc
   trägt die Sort-Zusage an **zwei** Stellen: den RAL-1-Kopfblock (Z. 13-18) UND
   den RAL-1a-Block (Z. 27-28, 61) — und beide listen **Settings** als
   sort-abhängig, was §2 widerspricht. Schritt 5 muss beide umschreiben (map-only,
   Nicht-Sort-Zusage explizit) und Settings aus der „verlässt sich"-Aufzählung
   streichen, sonst shippt der geflippte Code mit einem KDoc, der noch einen
   tragenden Sort behauptet. Plus: REACTIVE_APPLIST §11 (RAL-4 → gelöst), die
   „thin pointer back"-Kommentare, und **die `ApplyCustomNamesBenchmark`-Arme:**
   unter map-only werden `applyCustomNames` und `mapOnly` **degeneriert** (beide
   messen dieselbe Map) — die Arme prunen/re-purposen, nicht nur KDoc anpassen.

Jeder Schritt einzeln grün und revertierbar. Der einzige verhaltensberührende ist
Schritt 4, abgesichert durch die Schritt-1-Tests + den Recents-Fix aus Schritt 3.

---

## 6. Test-Impact

- **Neu (Pflicht), und sie müssen fehlschlagen können (M1):** Order-Assertions
  für die zwei neuen Sorts (CustomNamesVM — nach dem Fake-Fix, Assertion auf
  `displayedApps` UND `appsWithCustomNames`; OnboardingUseCase — nicht-alpha-
  Fixture) und für Recents (Multi-Activity, nicht-alpha-erste Fixture, existiert
  noch nicht). **Der `ReactiveFakeInstalledAppsRepository`-Fix ist Teil des
  Test-Impacts, nicht optional** — ohne ihn ist der CustomNames-Guard vakuum
  (§5 Schritt 1). Vorsicht: das existierende `CustomNamesViewModelTest.inOrder(…)`
  ist HEUTE grün nur, weil der Fake vorsortiert — es „bewacht" nichts, bis der
  Fake-Fix es scharf macht.
- **Impl-Ebene (Ordnungs-Nachweis für delegierte Sorts):**
  `AppUsageRepositoryImplTest`, `FavoritesOrderRepositoryImplTest` — existieren,
  müssen grün bleiben (inkl. der zwei unsortierten OOM-Fallback-Pfade, R2-M1).
- **`GetInstalledAppsUseCaseTest` degradiert still:** die Order-Assertion dort
  läuft auf bereits-alpha-Input, bleibt also grün, beweist aber nach dem Flip nur
  noch Passthrough statt Sortierung — auf nicht-alpha-Input umstellen oder annotieren.
- **Benchmark:** `ApplyCustomNamesBenchmark`s `applyCustomNames`- und `mapOnly`-Arm
  werden unter map-only **degeneriert** (messen dasselbe) — prunen/re-purposen.
- **DistinctUntilChanged bleibt korrekt — aber aus dem RICHTIGEN Grund
  (Runde-3-Korrektur):** `GetInstalledAppsUseCase`s eigenes `.distinctUntilChanged()`
  sitzt **vor** jedem Consumer-Sort, dedupt nach dem Flip also die *unsortierte*
  map-only-Liste, und die emittierte Ordnung ändert sich (Original- statt
  Display-Namen-Ordnung). Der Dedup-Count bleibt gleich — **nicht** weil DUC eine
  byte-gleiche Liste sieht (das war v4s falsche Begründung), sondern weil
  `getInstalledApps()` eine **deterministische** Original-Namen-Ordnung emittiert
  (`InstalledAppsRepositoryImpl:279`), die map-only erhält. Gleiche Eingabe-Folge →
  gleiches Dedup-Verhalten.
- **Kein neuer Convention-Gate:** keine neuen `combine`/`.catch`/broad-catch;
  die zwei neuen `sortedBy` sind reine, nicht-werfbare Operationen.

---

## 7. Ehrlich: Kosten und was NICHT auf null geht

- **Kein spürbarer Performance-Gewinn.** ~9,7 µs × 3, off-Main, hinter dem
  DataStore-Read. Der Grund ist Sauberkeit, nicht Speed.
- **Der zentrale Trade (M2, NICHT „strikt einfacher"):** der *Helfer* wird
  einfacher (tut eine Sache), aber die Sort-Invariante wandert von **strukturell
  garantiert** (heute: `applyCustomNames` sortiert IMMER, vom Code erzwungen) zu
  **test-/konvention-enforced** an ~6 Sites. Das ist keine reine Vereinfachung —
  es ist ein Tausch: weniger konzeptuelle Last im Helfer gegen eine schwächer
  durchgesetzte Invariante. Und dieser Codebase gated fast jede querschnittliche
  Invariante mechanisch (`checkConventions`: Purge-Vollständigkeit, Flow.catch-
  Rethrow, unbuffered SharedFlow, Adapter-Null-out, Contract-Triple, Strings-
  Parität …). „Jeder Consumer sortiert selbst" wäre konspicuous test-only — ein
  vergessener Sort shippt still Enumerations-Ordnung, unsichtbar außer der Consumer
  hat einen (echten, M1) Order-Test. Ob das akzeptabel ist, ist DIE offene Frage
  (§8 RAL-4-e). Ein mechanischer Gate ist hier vermutlich infeasible — „dieser
  Consumer zeigt an und MUSS sortieren" hat keinen syntaktischen Tell (derselbe
  Grund, warum die Cancellation-Checks Positiv-Listen sind, kein globaler Scan) —
  aber das muss der Spec ARGUMENTIEREN, nicht überspringen.
- **Bonus Hidden/Swipe — konzeptuell, kein Headline.** Beide sortieren so oder so
  genau einmal; befreit wird nur der tote ~9,7-µs-Upstream-Sort, den §7 selbst
  vernachlässigbar nennt. Ein per-Konstruktion-toter Sort verschwindet — sauber,
  aber kein Performance-Argument (sonst widerspricht es dem ersten Punkt).
- **Sortierschlüssel an ~6 Sites statt einem.** `displayNameLower` steht heute
  schon an vieren (Drawer/Favoriten/Hidden/Swipe); v4 macht +2 (CustomNames,
  Onboarding). Er lebt als Property auf `AppInfo`, ist also zentralisiert; die
  `.sortedBy { it.displayNameLower }` sind dünne Aufrufe.
- **`AppInfo` unverändert, `getInstalledApps`-Vertrag unverändert** (nur die
  *Post-Condition-Zusage* „sortiert" fällt — sie war Migrations-Gerüst).
- **Recents' Tie-Break ist eine neue lokale Kopplung** an `displayNameLower` —
  bewusst sichtbar gemacht (§3.3), mit Rückbind-Kommentar.

---

## 8. Offene Entscheidungen + Verdikt

> **VERDIKT (v5, Runde-3-Review): Design GESCHLOSSEN zugunsten map-only; ein
> FÜNFTES Mal vertagen; den Test-Plan JETZT reparieren.** Der Review bestätigt:
> keine Korrektheits-Regression, der Flip ist verhaltensneutral, v4/v5 ist
> „genuinely cleaner than v3" (entfernt den Sort, statt ihn umzuhängen). ABER es
> ist kein *strikt* einfacherer Zustand — die Sort-Invariante wandert von
> strukturell zu test-enforced (§7, M2), und der Review fand einen realen
> „würde-grün-shippen"-Defekt (M1: der CustomNames-Guard war vakuum). Netto:
> null Nutzer-Nutzen, moderater Blast-Radius, ein echter Trade statt eines reinen
> Gewinns. **Kein Muss — bauen, wenn der Bereich ohnehin angefasst wird**, dann
> aber mit dem reparierten Test-Plan (M1-Fake-Fix ist Pflicht, sonst shippt der
> CustomNames-Bug grün). Der Design-Streit ist beigelegt; die verbleibende Frage
> ist reiner Wille zum Aufräumen, nicht Architektur.

- **RAL-4-e — NEU (M2): reicht ein Test-Gate, oder braucht es einen mechanischen?**
  Diese Codebase gated fast jede querschnittliche Invariante in `checkConventions`.
  „Jeder anzeigende Consumer sortiert selbst" wäre test-only. Die drei Optionen:
  (a) die §6-Order-Tests als DEN Gate akzeptieren (nicht verhandelbar), (b) einen
  leichten Scan bauen, (c) explizit argumentieren, warum genau diese Invariante
  nicht mechanisch gatebar ist. Empfehlung: (a)+(c) — ein Gate wie
  „Consumer-zeigt-an-⇒-sortiert" hat keinen syntaktischen Tell (wie die
  Cancellation-Positiv-Listen), also sind die Order-Tests der Gate, und der Spec
  sagt das explizit. **Vor Bau zu bestätigen.**
- **RAL-4-a — Recents-Fix vs. akzeptierte Limitation (§3.3).** Empfehlung: Fix.
- **RAL-4-b — Helfer-Name.** `applyCustomNames` behalten (impliziert keine
  Sortierung, minimaler Churn, KDoc macht die Nicht-Sort-Zusage laut) vs. Rename
  auf `resolveCustomNames`. Empfehlung: behalten + KDoc schärfen.
- **RAL-4-c — Ganz oder gestaffelt?** Man KÖNNTE nur Site 2/3
  (Drawer/Favoriten/Recents) map-only-mäßig bedienen und Site 1 sortiert lassen
  (näher an v3). Aber das gibt den Hidden/Swipe-Gewinn auf und behält die
  Zwei-Wege-Asymmetrie. Empfehlung: ganz — die Einfachheit ist der Punkt.
- **RAL-4-d — Superseded.** Der v3-`internal`-Zwei-Funktionen-Ansatz ist mit v4
  hinfällig; falls jemand ihn doch will (z.B. um Site 1 sortiert zu halten), lebt
  er in der Git-Historie dieses Files.
