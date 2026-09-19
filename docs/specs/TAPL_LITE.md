# TAPL-lite — Instrumented-Test-Fassade

Leichtgewichtige Page-Object-Fassade für die Instrumented-Tests (androidTest),
angelehnt an AOSPs TAPL. Ziel: geräteechte Gesten-/Render-Nähte, die Robolectric
nicht ehrlich abdeckt, hinter lesbaren Page-Objekten kapseln — der Rule-10-Wert
(„braucht ein Gerät, um wahr zu sein"), nicht flächendeckende Redundanz.

## Architektur

- **`:common-testing-android`** — launcher-neutrales Support-Modul. Code liegt im
  `src/main`-SourceSet und wird über **`androidTestImplementation`** gezogen, damit
  die Espresso-/androidx.test-Oberfläche ausschließlich im androidTest-Klassenpfad
  der Konsumenten landet, nie in einer Produktions-`implementation`. Niemals als
  normale `implementation` einbinden. Inhalt:
  - `awaitUntil` (bounded polling), `onMainSync`, `currentResumed<T>()`
  - `BasePage` (anchor-basiertes `assertOnPage` + `waitForDisplayed/Gone`)
  - `dragRecyclerItem(from, to)` — echte Long-Press-Drag-MotionEvent-Sequenz für
    `ItemTouchHelper`
  - `probeFloat(id | matcher, reader)` — liest einen gerenderten Skalar (z. B.
    `TextView.textSize`) für Assertions, die Espressos Matcher nicht ausdrücken
- **Konkrete Page-Objekte bleiben pro App** (`kolibri Home != nyx Home`), im
  jeweiligen `.../tapl/`-Ordner des App-Moduls, gegen dieselbe `BasePage`.

## Phase 1 — erledigt

Geteiltes Modul + Kolibri-Fassade + erster portierter Test.

- Page-Objekte: `Launcher` (Einstieg, hält `ActivityScenario`), `Home`,
  `AppDrawer`, `Search`.
- `AppDrawerSwipeDismissTaplTest` — öffnet den Drawer über den Produktionspfad
  (`onFlingUp → ShowAppDrawer`) und weist die DragToDismiss-Geste nach. **Grün auf
  Gerät (A17).**
- `awaitUntil` ist nach `com.github.reygnn.launcher.testing` gewandert; die alte
  `support/AwaitUntil.kt` ist ein `@Deprecated`-Forwarder (die ~12 Altaufrufer
  kompilieren weiter).

## Phase 2 — erledigt (device gaps)

Die zwei geräteechten Kolibri-Lücken, die mehrere Audits als Rule-10-Gap markiert
hatten. Beide **device-verifiziert grün (A17)**.

- **`FavoritesSortDragReorderTaplTest`** (Gap 1) — hostet `FavoritesSortFragment`
  via `newInstance(...)` in `HiltTestActivity`, führt einen echten
  Long-Press-Drag (`dragRecyclerItem`) aus und prüft die persistierte Reihenfolge
  über das injizierte `FavoritesOrderRepository`. `ItemTouchHelper`-Drag ist die
  geräteechte Naht.
- **`LayoutCustomizationLivePreviewTaplTest`** (Gap 2) — öffnet den Layout-Dialog,
  zieht den Textgrößen-Slider mit echtem Finger-Swipe (`fromUser=true` ist der
  einzige Pfad, der `onSetLayoutScale` feuert) und prüft, dass (1)
  `LauncherViewModel.layoutScaleState` steigt und (2) der gerenderte
  Favoriten-Text auf Home wächst.
- Neu in der Fassade: `dragRecyclerItem`, `probeFloat`, Page-Objekte
  `FavoritesSort` + `LayoutCustomization`, `Launcher.openLayoutCustomization()`.

## Lessons aus den Geräteläufen

Hart erarbeitet — beim Erweitern beachten:

- **`fromUser`-Gate:** Der Material-`Slider` feuert `onSetLayoutScale` nur bei
  echter Berührung; ein programmatisches `value =` propagiert **nicht**. Deshalb
  braucht der Live-Preview-Test einen echten Swipe (und ist damit device-worthy).
- **Home-Favorit hat keine ID:** `HomeFavoritesAdapter` baut jeden Favoriten als
  programmatischen `OutlinedButton` — per Typ matchen
  (`isAssignableFrom(Button) + isDescendantOfA(favoritesRecyclerView)`), nicht per
  `R.id`.
- **Customize-Menü ist eine `setItems`-`AdapterView`:** `onView(withText)` matcht
  ListView-Items nicht zuverlässig. Die Menü-Dispatch-Logik ist reine Logik
  (`CustomizationDialogModelTest`, JVM), daher öffnet die Fassade den Dialog direkt
  über denselben `show()`-Aufruf — device-Test bleibt auf der Slider→Preview-Naht.
- **Dim-loser Bottom-Sheet-Dialog:** Views mit `inRoot(isDialog())` matchen (der
  Default-Root bleibt sonst auf der Activity dahinter); `show()`-Transaktion mit
  `executePendingTransactions()` synchron erzwingen, bevor gepollt wird.
- **Drag-Assertion nicht überspezifizieren:** „Item ist nach hinten gewandert +
  Permutation" statt exakter Landeposition — die Drag-Distanz ist Gesten-Präzision,
  nicht das getestete Verhalten.
- **Samsung-Doze** killt Läufe mitten im (langen) Build → für Testläufe
  `adb shell svc power stayon true` (danach zurücksetzen).
- **Test starten:** `connectedDebugAndroidTest` nimmt
  `-Pandroid.testInstrumentationRunnerArguments.class=<FQN>`, **nicht** `--tests`.
  Zwei komma-getrennte Klassen führten nur die erste aus → Klassen einzeln laufen
  lassen.

## Konventionen (unverändert übernommen)

- **Kein `MainDispatcherRule`, kein `runTest`** hier (INSTRUMENTED_TESTING_NOTES §1/§2).
- Hilt-Regel: `@get:Rule(order = 0)`.
- Seeding (`setOnboardingCompleted`, `ConsentBootstrap.seedDecision`,
  Favoriten) im `@Before` **vor** `Launcher.start()`; App-Daten-Cleanup über den
  Orchestrator (isolierter DataStore je `@HiltAndroidTest`).
- Animationen deaktiviert der `HiltTestRunner` global — die Fassade verlässt sich
  darauf.
- Fragment-Hosting für Settings-Unterscreens: `HiltTestActivity` (src/debug-Shim)
  + `replace(android.R.id.content, …).commitNow()`.

## Noch offen

- **Gap 3 — UsageExport SAF-Round-Trip:** der einzige verbleibende echte
  Kolibri-Device-Kandidat (mäßiger Value; braucht Espresso-`Intents`-Stubbing wie
  `BackupFragmentSafImportTest`). Datenschicht ist via `BackupRoundTripSafTest`
  schon abgedeckt; offen ist die Fragment-Verdrahtung (`ActivityResult` → Repo).
- **Facade-Stubs** (`TODO()`): `Home.favoritesCount/longPressFavorite`,
  `Search.resultCount/launchFirst`, leerer `AppContextMenu`-Platzhalter. Komfort,
  **keine** Abdeckungslücken — der Long-Press-Kontextmenü-Pfad ist bereits durch
  `HomeFavoriteLongPressTest` + Robolectric abgedeckt.
- **Aufräumen:** `awaitUntil`-Forwarder (Altimporte umziehen, dann löschen) und die
  alte `AppDrawerSwipeDismissTest` (koexistiert noch neben dem Port).
- **Bewusst NICHT device-getestet:** Settings-/SwipeActions-Activity (reine
  Config-UI → Robolectric ist die richtige Ebene), AppContextMenu-Dispatch
  (Rule-10-Redundanz).

## nyx — die größere Fundgrube

Der icon-/grid-basierte Launcher ist praktisch nur Touch-Nähte und wird der
Hauptabnehmer für TAPL-Tests, sobald nyx-Flows anstehen. Kandidaten:

- **Icon-Drag-and-Drop** übers Grid, inkl. **über Seitengrenzen** (ViewPager2-Paging
  während des Drags) — `dragRecyclerItem` ist der Keim, die Cross-Page-Variante die
  Erweiterung.
- **Seiten-Swipes** + Trailing-Page-GC / `normalizePages`.
- **Folders** (`DRAWER_FOLDERS_SPEC`): Drag-into-Folder, Öffnen, Reorder innerhalb.
- Drop-Targets, Long-Press-Aufnahme, Edge-Auto-Scroll.

Umsetzung: eigenes `nyx/.../tapl/`-Set gegen dieselbe `BasePage`/`awaitUntil`, die
konkreten Page-Objekte pro App getrennt.
