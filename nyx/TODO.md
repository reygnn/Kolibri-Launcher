# Nyx Launcher — TODO & Roadmap

Lebendes Dokument. Aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Offen

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
