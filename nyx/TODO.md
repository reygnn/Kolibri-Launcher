# Nyx Launcher — TODO & Roadmap

Lebendes Dokument. Aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Kürzlich erledigt (2026-09-18)

- **Wallpaper-Edit-FAB-Views nach `:common-ui` dedupliziert** — die drei bis
  dahin app-lokalen, byte-identischen Klassen (`SpeedDialFabCluster`,
  `CommandsPanel`, `SnapIconResolver` + sein Top-Level-`SnapMode`) liegen jetzt
  in `:common-ui` (`…common.ui.wallpaperfab`), zusammen mit ihren 2 internen
  Layouts, 26 wallpaper-edit-only Drawables, 16 Strings (EN+DE) und der
  `wallpaper_panel_slide_distance`-Dimen. App-Kopien gelöscht. Der frühere
  Material-Blocker (`:common-ui` schloss Material bewusst aus) wurde getestet
  und aufgelöst: Material + die material-vor-appcompat-`force()` bauen **beide**
  Apps sauber (Release-Lint grün, `material:1.14.0` löst korrekt auf, kein
  MaterialYou-Downgrade). `spacing_medium` + `wallpaper_edit_foreground` bleiben
  zusätzlich app-lokal (generisch bzw. vom app-eigenen Edit-Overlay genutzt —
  App-Wert überschreibt den Library-Wert beim Merge). Kolibris
  `SnapIconResolverTest` zog nach `:common-ui` mit; sein
  `SpeedDialFabClusterRobolectricTest` bleibt in Kolibri (Hilt-Test-Infra).
  Netto −1382/+97 Zeilen.
- **Notification-Dots (Pixel-faithful)** — kleiner Präsenz-Punkt oben-rechts an
  App-Icons in **Grid + Dock + Drawer**, plus Aggregat-Dots auf **Ordner**-Icons
  (Dot, wenn ein Mitglied eine Notification hat). Opt-in: `NyxNotificationListenerService`
  (@AndroidEntryPoint) speist einen app-scoped `NotificationPresenceStore`
  (`@Singleton`, nur Package-Präsenz — keine Titel/Texte/Counts, Privacy);
  Filterlogik (ongoing/nicht-wegwischbar raus → Pixel-artig) als reiner, getesteter
  `NotificationDotPolicy` in `:nyx:domain`. Settings-Toggle „Benachrichtigungspunkte"
  (DataStore, im Backup) + Weiterleitung zu `ACTION_NOTIFICATION_LISTENER_SETTINGS`
  beim Aktivieren ohne Zugriff. Reaktiv: `HomeViewModel.notificationDots` (gegatet =
  Store ∧ Toggle) → Adapter (`submitNotificationDots`). Der reine
  `HomeCell.hasNotificationDot` ist ebenfalls getestet. Manifest:
  `BIND_NOTIFICATION_LISTENER_SERVICE`.
- **Dev-Fehler-Toasts in nyx** — der `ToastErrorTree` (Timber-Tree, ERROR-Logs →
  globaler `ErrorEventBus`) wurde von Kolibri nach `:feature-crashreporting` gehoben
  (product-neutral, dedupliziert — Kolibris Kopie gelöscht, Import umgebogen) und in
  `NyxApplication` im DEBUG-Block gepflanzt. Der `BaseActivity`-Collector zeigt die
  Toasts nun (DEBUG-only, `SILENT_ERROR`-Tags unterdrückt). Damit ist der optionale
  Rest von #2 (BaseActivity) erledigt.
- **Home ist portrait-only (Pixel-Default)** — die frühere #5-Orientierungsfrage ist
  entschieden: der Home-Screen rotiert NICHT (`MainActivity` mit
  `android:screenOrientation="portrait"` gelockt), wie der Pixel Launcher auf Phones.
  Grund: nyx' geräteabhängiges Grid re-packt bei Orientierungswechsel verlustbehaftet
  (`HomeLayoutRegridder` behält Apps, nicht Positionen), und Launcher3-Qualität im
  Landscape bräuchte ein eigenes orientierungs-invariantes DeviceProfile + Seiten-Dock
  — zu viel für einen Nischen-Modus. Der zwischenzeitlich gebaute Rotationssperre-
  Switch wurde daher wieder entfernt (3 Commits revertet): auf einer Single-Activity
  ohne separat rotierende Screens hätte er nichts mehr gesteuert. (Landscape zeigte
  sonst geclippte bzw. winzige Icons, weil der kurze Landscape-Streifen die
  Portrait-Reihenzahl quetscht.)
- **Geteilte `BaseActivity` (`:common-ui`)** — Activity-Pendant zur geteilten
  `BaseViewModel`: `BaseActivity<E, VM>` installiert einen
  `CoroutineExceptionHandler` und sammelt (je hinter einem CancellationException-
  Arm) den globalen `ErrorEventBus` (DEBUG-Dev-Toasts) + den VM-Event-Flow
  (`handleEvent`-Hook, Default no-op → Event-Typ `Nothing` braucht kein Override).
  Product-neutral via `TimberWrapper.isDebugBuild`/`SILENT_LOG_TAG` (kein hardcoded
  `UiEvent`/`BuildConfig`). `MainActivity` erbt davon und fährt den UI-Collector-
  Block unter dem Handler → ein Throwable im Render-/Collect-Coroutine crasht den
  Launcher nicht mehr. Kolibri behält vorerst seine eigene `UiEvent`-Base.
- **Wallpaper Delete-Flicker + Recreation-Re-Decode** — **app-scoped** (`@Singleton`)
  Per-Layer-Decode-Cache (`home/wallpaper/WallpaperLayerBitmapCache`, LRU nach
  Bytes, never-recycle), Hilt-injiziert in `MainActivity`, im `bitmapLoader`: der
  `FullRebuild` beim Layer-Löschen re-dekodiert die verbleibenden Layer nicht mehr
  (Cache-Hits) → kein Flackern auf langsamer GPU (A17). Da `@Singleton`, überlebt
  er die `MainActivity`-Neuerstellung → auch das Zurückkehren aus einer anderen App
  (recreate wegen Konfig-Wechsel / Speicherdruck) dekodiert die Collage nicht neu.
  Bei lebender Activity (nur Resume) ist der Render ohnehin ein Diff-Noop. Der
  geteilte single-entry `WallpaperCompositeCache` blieb bewusst weg (cached keine
  N Layer). Cache wird beim Entfernen des Wallpapers geleert.

## Kürzlich erledigt (2026-09-17)

- **Drawer-Folder** — voll umgesetzt (`DrawerFoldersRepository`/
  `DrawerFoldersTransition`, Ordner-Overlay), inkl. Overflow-„Ordner nach Hersteller"
  und Auto-Ordnername beim Hersteller-Hinzufügen. (Ersetzt den früheren offenen
  Punkt „Folder im App-Drawer".)
- **Hidden Apps** — aus Kolibri portiert: `HiddenAppsRepository` (Contract-Trias),
  Filter in der Drawer-Projektion, Reveal-Modus + Kontextmenü + Settings-Manager,
  Backup/Restore. (Ersetzt den früheren offenen Punkt „Hidden Apps".)
- **Usage-Sortierung** (opt-in) — `AppUsageRepository` über separaten `nyx_usage`-
  DataStore, geteilte `:core`-Scoring-Mathematik, Overflow-Toggle.
- **First-Run-Seeding** — Play Store aufs Grid + Google-Apps in einen Drawer-Ordner.
- **60%-Ordnerkappe** im Ordner-Overlay (leichter wegklickbar).
- **HIE Phase C3 — Event-Indikatoren** — die Verdrahtung stand bereits (Indikatoren +
  `ObserveTimeBasedEventsUseCase`-Collector + Settings-Toggles + READ_CALENDAR-
  Permission + DI); ergänzt wurde der Doppel-Tipp-**Event-Dialog auf Kolibri-Parität**
  (Icon-Zeilen, klickbar → Uhr/Kalender-App, gerenderte Trennzeile, Fallback-Titel,
  isFinishing-Guard) plus ein 3dp-Indikator-Nudge. (Bewusst weggelassen: Kolibris
  wallpaper-aware Fenster-Chrome — Kolibri-only-Infra.)
- **DataStore-Reads fail-open (nyx-weit)** — alle Observe-Read-Flows laufen über den
  geteilten `:common-data readFlowFailOpen` (IOException → Defaults statt Crash); die
  First-Run-Seed-Point-Reads sind contained fail-closed (Fehler = Seed überspringen,
  Retry nächster Start, kein Clobber).
- **ACRA-Consent-Dialog (wie Kolibri)** — die geteilte Consent-Infra
  (`:feature-crashreporting`) war schon verdrahtet, aber ohne Prompt; ergänzt: First-Start-
  Dialog in `MainActivity` (`resolveStartupAction` → ShowDialog/Reaffirm/Skip) für alle
  Builds + Settings-Eintrag (Datenschutz → Absturzberichte); dev-only Auto-Consent entfernt.
  Uhr/Datum/Akku zusätzlich auf Doppel-Tipp umgestellt.

---

## Offen

> **Launcher3/Pixel-Scope-Entscheid (2026-09-13):** Widgets (AppWidgetHost),
> Predicted-Apps-Row, Folder-Auto-Naming/Preview-Animationen, Wallpaper-Parallax
> und Widget-Picker sind **out of scope — won't build.** Nyx bleibt ein schlanker,
> kuratierter Grid-Launcher, kein Launcher3-Klon. Die Paritäts-Lücke ist
> geschlossen: Page-Indicator und Notification-Dots sind umgesetzt. Was hier unter
> „Offen" bleibt, sind ein **bewusster won't-build-Entscheid** und ein **offener
> Refactor-Kandidat** (Option B, App-Start-Ausführung teilen) — beide unten. (Die
> Wallpaper-Edit-UI-Dedup gegen `:common-ui`, zuvor hier als won't-build geführt,
> wurde am 2026-09-18 doch umgesetzt — der Material-Blocker war überwindbar; siehe
> „Kürzlich erledigt".)

### NyxDrawer-TAPL härten — Drawer-Open verifizieren vor dem Drag-Arm (2026-09-25)

Die Drawer-Instrumented-Tests (`DrawerAppToHomeTaplTest`, `DrawerAppToHomeDragTaplTest`)
sind auf dem Pixel gesten-flaky: der **Swipe-up-Open** bzw. das **Long-Press-Arming**
schlägt zeitweise fehl. Wenn der Swipe-up flaked, ist der Drawer nicht wirklich offen, aber
`NyxDrawer.assertOnPage()` akzeptiert die ausgelegte (nur unsichtbare) `drawer_panel`, und
`ArmedDrawerDragToHomeBar` findet trotzdem `drawer_panel.getChildAt(0)`. Der Long-Press geht
dann auf die dahinterliegende **Home-Fläche** → `homeRoot.onLongPress` öffnet den
`NyxCustomizationDialog`, der Drag armt nie → irreführende Fehler
(`drawer long-press never armed a drag`) statt einer klaren „Drawer nicht offen"-Meldung.

**Zu tun:** In `NyxDrawer` (androidTest, TAPL) nach dem Swipe-up verifizieren, dass der
Drawer **tatsächlich offen/oben** ist (auf `drawerOverlay.isOpen` bzw. echte Sichtbarkeit +
Endposition der `drawer_panel` warten, nicht nur „laid out"), bevor `ArmedDrawerDragToHomeBar`
den Down-Event sendet. Dann scheitert ein Swipe-up-Flake sauber und leckt nicht in den
Home-Long-Press.

Kein Produkt-Bug: der Home-Long-Press reagiert korrekt auf einen Long-Press auf dem echten
Home; `homeGesturesAllowed()` sperrt bewusst nicht auf den offenen Drawer (der Overlay
verschluckt Touches selbst). Reine Test-Robustheit. Verwandt: als Sofort-Mitigation
`numFlakyTestAttempts` für `:nyx:app` erwägen (kolibris Standard-Flake-Mitigation, die nyx
noch nicht hat). Hinweis: ein gesperrtes Gerät ist ein *anderer* Rotfall (der Homescreen
kommt nicht in den Vordergrund) — für androidTest muss das Gerät entsperrt/wach sein.

### App-Start-Ausführung teilen (Option B — offener Refactor-Kandidat, 2026-09-18)

Die **Launch-Taxonomie** (`AppLaunchResult` + `runLaunchCatching`) liegt seit
2026-09-18 geteilt in `:common-ui` (Option A): nyx toastet jetzt bei Fehlschlag statt
still zu schlucken (`ActivityNotFoundException`) bzw. bei `SecurityException` zu
crashen — Pixel-/Kolibri-Parität. **Offen (Option B):** auch die *Ausführung* teilen —
ein gemeinsames `AppLauncher` auf `LauncherApps.startMainActivity(ComponentName, user)`.
nyx würde dann von seinem expliziten `startActivity(Intent(ACTION_MAIN/LAUNCHER,
component))` auf die launcher-idiomatische API wechseln (work-profile-fähig, wie
Launcher3/Kolibri; Kolibri nutzt sie bereits über `AppLauncherImpl`). Bewusst separat
gehalten: der `startMainActivity`-Umstieg ist eine Verhaltensänderung der nyx-Launch-
Mechanik mit eigenem Test-/Regressionsaufwand — kein Blocker, nur (noch) nicht den
Aufwand wert.

### Presence-Naht teilen (F7-Gate) — Option B + C erledigt (2026-09-24)

> ⚠️ **Obsolet** (Branch `feature/lazy-slot-validation`): das gesamte F7-Gate ist mit dem
> Auto-Prune gelöscht. `ReconcileHomeLayoutUseCase` prunt nicht mehr (nur noch Struktur-
> Repair), `AppPresence`/`InstallSessionInspector`/`DeletionGatePass`/`PackageManager*`
> existieren nicht mehr — ein fehlender Layout-Key wird schlicht als „missing"-Tile behalten
> (Windows-Verknüpfungs-Modell, root TODO.md „✅ UMGESETZT"). Bleibt als Referenz.

Der Partial-Snapshot-Schutz aus **AUDIT-1 F7** ist umgesetzt (RHL-INV-6, das nyx-Analog
zu Kolibris R-INV-2). `ReconcileHomeLayoutUseCase` prunt einen fehlenden Layout-Key nicht
mehr blind, sondern behält ihn, wenn *eine* von zwei unabhängigen Prüfungen anschlägt —
beide fail-safe Richtung „behalten". Umgesetzte Schichten:

- **fix 1 — fail-closed Read:** Kandidaten werden über `HomeLayoutRepository.snapshot()`
  (fail-CLOSED, wie der interne `update`-Read) statt über den fail-open `layout()`-Flow
  berechnet; ein transienter Read-Fehler bricht den Pass ab, statt zu einem leeren Layout
  ohne Schutz zu degradieren.
- **fix 2 — Cross-Surface-Presence:** `AppPresence`-Impl ist `PackageManagerPresence`
  (PackageManager, `ACTION_MAIN`/`CATEGORY_LAUNCHER`, komponentengenau) — ein *anderes*
  Subsystem als die LauncherApps-Enumeration, sodass ein LauncherApps-Transient den Check
  nicht mitvergiftet. Technik von Kolibris `PackagePresenceImpl` übernommen.
- **fix 3 — Session-Gate:** Port `InstallSessionInspector` + Impl
  `PackageManagerInstallSessions` (`PackageInstaller.getAllSessions()`). Launcher3-Muster:
  einen Key, dessen Paket eine aktive Install/Restore-Session hat, nie prunen (Promise).
  Schließt den Mid-Restore-Vektor, den keine Presence-Prüfung schließen kann.
- **fix 4 — einheitlicher Skip-Kanal (Follow-up-Review):** der store-seitige fail-closed
  Read ist jetzt value-honest wie die Enumerations-Seite. Wirft `snapshot()` oder der
  atomare `update()`-RMW eine transiente `IOException`, fängt der Use-Case sie ab und gibt
  `ReconcileResult.Skipped(STORE_FAILED)` zurück, statt zu werfen (neuer `SkipReason`,
  observability-only wie `LOAD_FAILED`). Damit ist `invoke()` total (nur
  `CancellationException` entkommt), und beide Aufrufer (`PackageEventCoordinator`,
  `ImportLayoutUseCase`) sind ohne eigenen Guard korrekt — vorher hing der Import-Pfad am
  weit entfernten `runCatching` im `SettingsFragment`. Der `try/catch` im Coordinator bleibt
  als Defense-in-Depth für seinen langlebigen Collector.
- **fix 5 — fail-safe-Logging via `reportToAcra` statt `silentError` (Medium-Review-Follow-up,
  2026-09-24):** die drei fail-safe-to-keep-Catches der geteilten Seams
  (`PackageManagerPresence` ×2 → `true`, `PackageManagerInstallSessions` → `null`) nutzten
  `silentError`, das in DEBUG wirft und so den fail-safe-Vertrag in DEBUG-Builds brach; auf
  `reportToAcra` umgestellt. **Betrifft beide Launcher gleich** (geteiltes `:common-data`-Impl) —
  die Konvention dahinter (`silentError` ↔ `reportToAcra` an fail-safe-Grenzen) steht daher im
  **root `TODO.md`**, nicht hier. Passt zu fix 4 (der nyx-eigene `STORE_FAILED`-Catch traf
  dieselbe Wahl).

Ein Rest-Fall bleibt bewusst offen (Restore ohne auffindbare Session) — dokumentiert in
`ACCEPTED_LIMITATIONS.md` („… pruned during a restore that exposes no install session").

**Erledigt (Option B):** die Presence-*Naht* ist geteilt, symmetrisch zum `AppEnumerator`.
Ports `AppPresence` + `InstallSessionInspector` → `:core` (neben `AppEnumerator`), Impls
`PackageManagerPresence` + `PackageManagerInstallSessions` → `:common-data` (neben
`LauncherAppsEnumerator`), app-seitig via `@Binds` in nyx' `RepositoryModule` gebunden
(`PackageManager` app-seitig provided). Das Use-Case-Gate blieb in nyx (hängt am
`HomeLayout`). Reiner Modul-/Namespace-Umzug, keine Verhaltensänderung. (Kolibri bindet die
geteilten Ports seit Option C ebenfalls — siehe unten.)

**Erledigt (Option C):** Kolibris eigenes `PackagePresence` (Interface, `PackagePresenceImpl`,
`FakePackagePresence`, dessen Robolectric-Test) ist entfernt; beide Apps nutzen jetzt die
*eine* geteilte `AppPresence` aus `:core`. Die Naht trägt jetzt beide Grains:
`isComponentPresent(ComponentKey)` + `isPackagePresent(String)`, implementiert vom geteilten
`PackageManagerPresence`. Kolibris `ObserveInstalledAppsUseCase` überbrückt seine flachen
`"pkg/class"`-Strings via `ComponentKey.parse` auf die Component-Grain-Methode (malformed →
absent, wie zuvor); Custom-Names nutzen die Package-Grain-Methode direkt. Damit gibt es genau
eine Presence-Abstraktion und eine Impl für nyx **und** kolibri — die F7-Konsolidierung ist
abgeschlossen.

### Custom Names — bewusst NICHT umgesetzt (won't build, 2026-09-18)

Frei umbenennbare App-Namen sind ein Feature für **textbasierte** Launcher
(Kolibri), wo der Name die einzige sichtbare App-Repräsentation ist. nyx ist ein
Icon-/Grid-Launcher — dort ist das **Icon** die Identität, der Name spielt kaum
eine Rolle. Daher kein `CustomNamesRepository` und keine Umbenennen-UI. Das
`customName`-Feld auf `LauncherApp` (+ `displayName = customName ?: label`) bleibt
harmlos bestehen — von der Sortierung genutzt, aber nie befüllt, also effektiv
immer `label`. (Ersetzt den früheren offenen Punkt „Custom Names portieren".)
