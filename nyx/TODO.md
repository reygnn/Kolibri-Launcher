# Nyx Launcher — TODO & Roadmap

Lebendes Dokument. Aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Offen

> **Launcher3/Pixel-Scope-Entscheid (2026-09-13):** Widgets (AppWidgetHost),
> Predicted-Apps-Row, Folder-Auto-Naming/Preview-Animationen, Wallpaper-Parallax
> und Widget-Picker sind **out of scope — won't build.** Nyx bleibt ein schlanker,
> kuratierter Grid-Launcher, kein Launcher3-Klon. Aus der Paritäts-Lücke ist der
> Page-Indicator inzwischen umgesetzt; offen bleibt nur der folgende Kandidat.

### Notification-Dots (nur Punkt, keine Zahlen)

Bewusster Entscheid: **Dots ja (vielleicht), Zahlen-Badges nein** — Counts nerven.
Also nur ein kleiner Präsenz-Punkt auf Icons mit aktiver Notification, kein Zähler,
kein Inhalt.

Braucht einen `NotificationListenerService` (User muss den Zugriff in den
Systemeinstellungen erteilen — Opt-in, kein stiller Grant) plus einen leichten
Store „Packages mit aktiver Notification", der bei `onNotificationPosted/Removed`
aktualisiert und als Flow an die Icon-Bindung fließt. Privacy-Linie: nur
Package-Präsenz halten, keine Titel/Texte/Counts persistieren. Rendern in Grid-
**und** Dock-Icons; Drawer optional.

Anker: neuer `NotificationListenerService` + Präsenz-Store (`:nyx:data` oder
`:common-*`), `home/HomeGridAdapter.kt` (Icon-Bindung), Dock-Icon-Rendering in
`MainActivity`, Manifest (`BIND_NOTIFICATION_LISTENER_SERVICE`).

### BaseActivity / BaseViewModel aus Kolibri übernehmen (Robustheit)

Nyx hat kein gemeinsames Coroutine-Crash-Netz. Kolibris `BaseActivity`/
`BaseViewModel` bündeln `launchSafe`/`executeSafe` (CoroutineExceptionHandler,
Cancellation-Rethrow-Disziplin) + einen Fehler-Toast-Bus (`ErrorEventBus`/
`UiEvent`), sodass ein Throwable in einer Coroutine geloggt/getoastet statt zum
Crash wird. Kein Muss (der HIE-Uhr-Crash kam aus einem synchronen onClick und ist
defensiv via `startActivitySafely` gelöst), aber ein sinnvoller Reife-Schritt.
Eigener Architektur-Port — die Klassen hängen an Kolibris Crash-Infra-
Konventionen (Rule 9/11). Anker (Kolibri): `ui/base/BaseActivity.kt`,
`ui/base/BaseViewModel.kt`, `common/ui/ErrorEventBus.kt`.

### HIE Phase C3 — Event-Indikator (Kalender/Alarm) in Nyx

Phase A/B/C1/C2 sind durch: das Home-Info-Subsystem ist geteilt (`:core.timeinfo`,
`:common-ui`, `:common-data`) und Nyx zeigt Uhr/Datum/Akku. Der Event-Pfad ist
**verdrahtet aber ruhend** — `PreferencesRepository` implementiert `TimeInfoSettings`
(Toggles default aus), der geteilte `ObserveTimeBasedEventsUseCase` ist gebunden,
`ClockDelegate.timeBasedEvents` fließt, wird aber an keine View gebunden. Solange
beide Toggles aus sind, gibt es kein Kalender/Alarm-IPC und kein `READ_CALENDAR`
(HIE-INV-7).

Offen für C3 (HOME_INFO_ELEMENTS_SPEC §5, eigene UX-Entscheidungen):
1. `<uses-permission android:name="android.permission.READ_CALENDAR"/>` ins Nyx-Manifest.
2. Runtime-Permission-Request beim Aktivieren des Kalender-Toggles.
3. Zwei Toggles in `SettingsActivity` (Muster wie `monochrome_switch`) →
   `preferences.setShowAlarm` / `setShowCalendarEvent`.
4. Event-Indikator-View auf dem Home + Tap → Event-Dialog (`showTimeBasedEventsDialog`
   neu: Dialog-Layout, `TimeEventFormatter.buildEventRows`/`formatEventRow`,
   Event-Typ-Icons, Strings) + `clockDelegate.timeBasedEvents`-Binding.

Anker: `MainActivity` (clock_container, clockDelegate), `PreferencesRepository`
(showAlarm/showCalendarEvent), `TimeEventFormatter` (:core), `SettingsActivity`.

### DataStore-Reads fail-open absichern (nyx-weit) — Robustheit

Nyx' DataStore-gestützte Stores lesen via `dataStore.data.map { … }` **ohne**
`.catch`-Fallback: eine `IOException` beim Read (Store-Korruption) propagiert in
den Collector → Crash. Kolibri kapselt das in einem geteilten Safe-Read-Helfer
(`:common-data` `DataStoreReadFlow.safeReadFlow`, fail-open auf Defaults). Nyx
sollte einen analogen geteilten Helfer haben (oder `DataStoreReadFlow`
wiederverwenden) und alle Read-Flows darüber leiten — nicht nur die Wallpaper-
Stores, sonst driftet es. Aus dem WV5d-Deep-Review zurückgestellt (nyx-weite
Lücke, eigener Task statt inkonsistenter Teilfix). Niedrige Wahrscheinlichkeit
(nur bei Store-Korruption), aber ein Crash-Pfad. Anker:
`PreferencesRepositoryImpl`, `NyxWallpaperDisplaySettings`, `NyxFabPositionStore`;
Referenz `:common-data/…/DataStoreReadFlow.kt`.

### Wallpaper: Composite-Cache nachrüsten (Delete-Flicker) — optional

Nyx lässt den geteilten `WallpaperCompositeCache` bewusst weg (keine
drawer→home-Teardown-Naht wie bei Kolibri). Folge: beim Löschen eines Layers
baut der Binder die verbleibenden Layer neu auf (Re-Decode) → kurzes Flackern,
auf langsamerer GPU (A17) sichtbar, auf dem Pixel nicht. Kein Korrektheits-
problem. Falls es stört: den geteilten `WallpaperCompositeCache` (`:common-ui`)
für Warm-Reattach einhängen. Anker: `MainActivity` Render-Pfad
(`renderWallpaper`/`wallpaperBinder`), `:common-ui` `WallpaperCompositeCache`.

### Wallpaper-Edit-UI: Dedup gegen `:common-ui`

FAB-Cluster + CommandsPanel + `SnapIconResolver`/`LayerButtonsState` sowie die
Helfer `ViewFade`/`DialogWindow`/`DialogDrag` sind bewusst **byte-identisch aus
Kolibri kopiert** (Maintainer-Entscheid WV5d, kein Ressourcen-Merge-Risiko).
Wenn Kolibri das nächste Mal angefasst wird: in `:common-ui` unifizieren (beide
Apps teilen dann eine Implementierung). Anker: nyx `home/wallpaperfab/*`,
`home/wallpaper/{SnapIconResolver,LayerButtonsState}`, `:common-ui`.

### Grid bei Orientierungswechsel — Design-Frage (Mechanik erledigt)

Das geräteabhängige Home-Grid (ICON_HOME_MODEL_SPEC §10) leitet `columns`/`rows`
aus der real gemessenen Grid-Fläche ab (`MainActivity.applyDeviceGrid`, nach
Layout) und re-fittet via `HomeLayoutRegridder`. Da `MainActivity` keinen
`configChanges` deklariert, wird sie bei Rotation neu erzeugt → `applyDeviceGrid`
läuft erneut → das Grid passt sich **live** an (verlustfreies Repack). Der
Auslöser fehlt also nicht mehr.

Offen ist nur die **Produkt**-Frage: soll Querformat ein **eigenes** Raster
bekommen (aktuelles Verhalten: es rechnet für Landscape neu und packt um) oder
soll ein orientierungsstabiles Portrait-Raster geteilt werden? Bis das
entschieden ist, ist das aktuelle „pro Orientierung neu" ein vernünftiger
Default.

Caveat für später: wird `MainActivity` mal auf `configChanges` umgestellt (kein
Recreate), muss `applyDeviceGrid` zusätzlich aus `onConfigurationChanged`
aufgerufen werden.

Anker: `MainActivity.applyDeviceGrid`, `HomeViewModel.applyDeviceGrid`,
`FitHomeGridUseCase`, `HomeLayoutRegridder`.

### Folder im App-Drawer — Feature (idealerweise Home-Struktur wiederverwenden)

Der Drawer kennt aktuell **keine** Folder: `GetDrawerAppsUseCase` liefert eine
flache `List<LauncherApp>` (nur `key/label/customName`, kein Children/Folder-
Feld), `AppDrawerAdapter` bindet ausschließlich Einzel-App-ViewHolder (kein
`getItemViewType`, keine Gruppen), und der einzige Interaktionspfad ist
Tap = Launch / Long-Press = `DragPayload.NewApp(key)`. Folder sind bislang rein
ein Home-/Dock-Konzept (`HomeItem.Folder`); die Specs führen den Drawer bewusst
als „nur eine Liste" (`ICON_HOME_MODEL_SPEC.md:46`, `HOME_EDIT_USECASES_SPEC.md`
Scope-Ausschluss).

Ziel: Gruppieren von Apps zu Foldern **innerhalb** des Drawers. **Idealerweise
die Home-Struktur übernehmen statt ein zweites Modell zu bauen** — d.h. den
`HomeItem`/`HomeItem.Folder`-Typ und die reine Transitions-Logik
(`HomeLayoutTransition`: app-onto-app → FolderCreated, app-onto-folder → member,
`removeFromFolder`) für den Drawer wiederverwenden, inkl. der scoped IHM-INV-7-
Uniqueness und der Invarianten-Absicherung (`HomeLayoutInvariants`). Zu klären:
eigener Drawer-Layout-State (persistiert) vs. Ableitung, und wie sich das mit
„Drawer zeigt *alle* Apps" verträgt (Folder als Gruppierungs-Overlay über der
vollständigen Liste, nicht als exklusive Container).

Braucht: einen persistierten Drawer-Layout-Zustand (Store in `:nyx:data`),
`getItemViewType` + Folder-ViewHolder/-Öffnen im `AppDrawerAdapter`, einen
Drag-to-Fold-Pfad im `AppDrawerFragment` und eine ViewModel-Verdrahtung auf die
geteilte Transitions-Logik. Vorab **Produkt-/Scope-Entscheid** nötig (der
Launcher3-Scope-Entscheid oben nennt Drawer-Folder nicht explizit).

Anker: `home/usecase/GetDrawerAppsUseCase.kt`, `home/model/LauncherApp.kt`,
`home/drawer/AppDrawerAdapter.kt`, `home/drawer/AppDrawerFragment.kt`,
`DragPayload.kt`; Wiederverwendung aus `home/model/HomeItem.kt`,
`home/transition/HomeLayoutTransition.kt`, `home/model/HomeLayoutInvariants.kt`.

### Hidden Apps — Feature (aus Kolibri portieren)

Der Drawer zeigt aktuell **alle** installierten Apps: `GetDrawerAppsUseCase`
lädt `repository.loadInstalledApps()`, sortiert nach Anzeigename und gibt zurück
— **kein Hidden-Filter** dazwischen. `HiddenAppsRepository` existiert nur in den
Specs, nicht im nyx-Code.

`ICON_HOME_MODEL_SPEC.md:47` (§0.1-Tabelle) führt Hidden Apps als **Übernahme
aus dem großen Kolibri** (`HiddenAppsRepository`, „keine" Anpassung) — geplant,
aber noch nicht portiert (gleiches Muster wie Custom Names / Backup/Restore in
derselben Tabelle).

Braucht: `HiddenAppsRepository` (Interface + DataStore-Impl in `:nyx:data`, Rule
1/5), Einhängen des Hidden-Sets in `GetDrawerAppsUseCase` (rausfiltern; Sortieren
bleibt Consumer-Job), einen Aus-/Einblenden-Pfad (Long-Press im Drawer bzw.
Settings-Liste) und eine „versteckte Apps"-Verwaltungsansicht. Reconcile-
Interaktion prüfen: eine versteckte App darf beim Reconcile nicht als
deinstalliert gewertet und vom Home entfernt werden.

Anker: `home/usecase/GetDrawerAppsUseCase.kt`,
`home/repository/InstalledAppsRepository.kt`, `home/model/LauncherApp.kt`,
`home/drawer/AppDrawerFragment.kt`, `settings/*`; Referenz (Kolibri):
`HiddenAppsRepository`.
