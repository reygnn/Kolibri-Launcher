# Nyx Launcher — TODO & Roadmap

Lebendes Dokument. Aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Kürzlich erledigt (2026-09-18)

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
> „Offen" bleibt, sind nur noch **bewusste won't-build-Entscheide** (unten
> dokumentiert), keine aktiven Kandidaten mehr.

### Wallpaper-Edit-UI: Dedup gegen `:common-ui` — geprüft, bewusst NICHT umgesetzt (2026-09-18)

Der **nicht-Material-Teil ist bereits geteilt**: `LayerButtonsState`, `ViewFade`,
`DialogWindow`, `DialogDrag`, `FabDragHandler`, `FabPositionMath` liegen schon in
`:common-ui`. Übrig sind nur die beiden **Material-View-Kopien**
`SpeedDialFabCluster` + `CommandsPanel`.

Prüfung (2026-09-18): die 3 restlichen Klassen sind byte-identisch zu Kolibri (bis
auf `package` + `R`-Import), und alle 15 referenzierten Ressourcen sind byte-gleich.
ABER ein sauberer Move ist **blockiert**: `SpeedDialFabCluster`/`CommandsPanel`
brauchen Material (`MaterialButton`, `FloatingActionButton`), und `:common-ui`
**schließt Material bewusst aus** — siehe `common-ui/build.gradle.kts`: „material is
NOT pulled here (the FAB/edit-toolbar Views stay in kolibri); the material-before-
appcompat force() rule lives in each :app that has both." Genau deshalb blieben die
zwei Views app-lokal (WV5d), nicht wegen Ressourcen-Merge.

Ein Move würde also erfordern, Material (+ die material-vor-appcompat-`force()`) in
`:common-ui` aufzunehmen — die bewusste Ausschluss-Entscheidung umzukehren, mit dem
Ordering-Risiko in **beiden** Apps. Daher: **won't build**, solange Material aus
`:common-ui` draußen bleibt. (`SnapIconResolver` allein — kein Material — wäre
ziehbar, aber zu wenig Wert für einen eigenen Schritt; sein `SnapMode`-Enum
dupliziert zudem `ZoomableImageView.SnapMode`.)

### Custom Names — bewusst NICHT umgesetzt (won't build, 2026-09-18)

Frei umbenennbare App-Namen sind ein Feature für **textbasierte** Launcher
(Kolibri), wo der Name die einzige sichtbare App-Repräsentation ist. nyx ist ein
Icon-/Grid-Launcher — dort ist das **Icon** die Identität, der Name spielt kaum
eine Rolle. Daher kein `CustomNamesRepository` und keine Umbenennen-UI. Das
`customName`-Feld auf `LauncherApp` (+ `displayName = customName ?: label`) bleibt
harmlos bestehen — von der Sortierung genutzt, aber nie befüllt, also effektiv
immer `label`. (Ersetzt den früheren offenen Punkt „Custom Names portieren".)
