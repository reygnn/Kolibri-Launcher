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

## Phase 4 — Swipe-Slot-Geste (device-grün, A17)

- **`SwipeSlotActionTaplTest`** — echter Links-nach-Rechts-Swipe auf
  `homeGestureRoot` (`HomeGestureLayout` → `onSwipeRight` →
  `onSwipeFromLeftToRight` → `HandleSwipeActionUseCase` → `LaunchApp`) feuert die
  dem Slot zugewiesene App. `Home.swipeLeftToRight()` (Swipe auf 15–85 % Breite,
  um den System-Back-Gesten-Streifen zu meiden).
- **Lesson / Produktions-Delta:** der echte App-Launch nutzt
  `LauncherApps.startMainActivity` — ein System-Call, den Espresso-`Intents`
  **nicht** abfangen kann und der eine echte App in den Vordergrund holen würde.
  Deshalb: `provideAppLauncher` in ein eigenes `AppLauncherModule` extrahiert, im
  Test per `@UninstallModules` + `@BindValue` durch einen Recording-Fake ersetzt.
  Muster für „Launch/System-Seam verifizieren ohne echten Launch".

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

## nyx — Grid-/Drawer-Drag (umgesetzt)

Der icon-/grid-Launcher ist die größere Fundgrube (fast nur Touch-Nähte). Eigenes
`nyx/.../tapl/`-Set (`NyxLauncher`/`NyxHome`/`NyxDrawer`) gegen dieselbe geteilte
`BasePage`/`awaitUntil`; die konkreten Page-Objekte bleiben pro App getrennt.
Infra-Bootstrap: nyx-`HiltTestRunner` (das im Build referenzierte, zuvor fehlende
File) + `:common-testing-android`-Wiring.

**Phase 1 — device-grün (A17):** Grid-Drag-Reorder innerhalb einer Seite;
Cross-Page-Drag (Kanten-Edge-Advance mitten im Drag). Neues geteiltes Primitive
`longPressDrag` (kontinuierlicher Long-Press-Drag-MotionEvent-Stream mit Waypoints
+ Edge-Dwell) + `probeFloat`.

**Phase 2 — device-grün (A17):** Folder erzeugen (App auf App), App in Folder
(App auf Folder), Drag-to-Remove (Item auf die Remove-Bar), Drawer-App auf Home
(Kontextmenü-Route: Suche → Long-Press → „Aufs Home").

**Phase 3 — device-grün (A17):** Drag-to-Dock (Grid-Item → Dock-Zone → DockSlot);
Extract-from-Folder (Folder-Overlay öffnen → Member rausziehen aufs Grid →
`extractFromFolder`, 2-Member-Folder dissolviert). Neues geteiltes Primitive
`tap()` (Einzel-Tap für id-lose Views, z. B. eine Grid-Zelle öffnen).

**Phase 5 — device-grün (A17):** Drawer-App auf Home via **Drag** (Weg 2):
Drawer-Row-Long-Press → Arm → Move-Promote → Add-to-Home-Bar → Drop
(`onDrop` → `addToHome`). Neuer minimaler read-only Hook `DragLayer.isDragArmed`
— die **eine sanktionierte Ausnahme** von NyxLaunchers „no production test-hooks"
(kein Prod-Write, spiegelt nur `armedPayload != null`), gelesen im ViewAction auf
dem Main-Thread via `awaitUntil`. Das war der Hebel, der die zuvor am Arm/Promote
gescheiterte Geste erst deterministisch machte.

**Phase 6 — device-grün (A17):** Dock→Grid (Umkehrung von Grid→Dock): Dock-Item
long-press → Drag → Grid-Zelle → Drop (`resolveGridDrop` → `DropTarget.Cell` →
`move`). Neues Primitive `dragDockItemToCell`. Dock-Items armieren via `armDrag`
wie Grid-Items → fester Long-Press-Hold reicht (kein Hook nötig).

**Phase 7 — device-grün (A17):** Dock-Reorder: Dock-Item 0 auf die **Außenkante**
(fx ~0.85 > `1 - GRID_INSERT_EDGE_FRACTION`) von Dock-Item 1 → `DropTarget.DockSlot`
(Einfügen zwischen Icons), nicht die zentrale `DockItem`-Bande (Folder). Reihenfolge
`[A, B]` → `[B, A]`. Neues Primitive `dragDockItemAfter`.

**nyx-Lessons (hart erarbeitet):**
- `longPressDrag` dwellt **nur an Zwischen-Waypoints, nie am Drop** — sonst
  re-triggert ein Edge-Advance den Ziel-Drop von der Seite (Phase-1-Review-Fix).
- Drawer-**Ordner**-Long-Press armiert nicht (`onLongClickListener` → `false`); die
  **Suche flacht Ordner zu App-Zeilen ab** → Zeile 0 ist verlässlich eine App.
- Add-to-Home-Bar (`material.primary`) reicht **ohne Insets** bis oben.
- `HomeLayoutRepositoryImpl` seedet Default-Apps **nur wenn `items` UND `dock` leer**
  → für ein „leeres" Home einen **unresolvable Placeholder** seeden (nie eine
  Drawer-Zeile → keine `items∪dock`-Kollision). Der Startup-Reconcile läuft im Test
  nicht (`HiltTestRunner` ersetzt `NyxApplication`).
- Such-Query aus `InstalledAppsRepository.displayName` ableiten (nicht
  `PackageManager.loadLabel` — divergiert); `replaceText` statt `typeText` (Unicode).
- Der **Drawer-Drag (Weg 2)** brauchte den `isDragArmed`-Hook: festes Halte-Timing
  traf das Arm/Promote nicht verlässlich. Im ViewAction (Main-Thread) `awaitUntil {
  dragLayer.isDragArmed }` nach dem DOWN, DANN den Promote-Move — deterministisch.
  Grid-/Dock-Items brauchen das nicht (fester Long-Press-Hold reicht dort).

**Noch offen (nyx):**
- Edge-Auto-Scroll-Feinheiten. (Folder-Öffnen selbst ist über
  Extract-from-Folder mit abgedeckt.)

**Kein Gap (nachgeprüft 2026-09-19):** „Reorder innerhalb eines Folders" ist
**kein Feature** — `FolderMemberAdapter` armiert per Long-Press nur einen
Home-Drag zum *Extrahieren* des Members (`onStartDrag`); es gibt keinen
`ItemTouchHelper` und keinen `reorder`/`moveFolderMember`-Pfad. Also nichts zu
testen, bis (falls) internes Reorder als Feature gebaut wird. Der Extract-Pfad ist
über `HomeFolderExtractTaplTest` abgedeckt.
