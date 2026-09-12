# Nyx Launcher — TODO & Roadmap

Lebendes Dokument. Aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Offen

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
