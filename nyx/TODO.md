# Nyx Launcher — TODO & Roadmap

Lebendes Dokument. Aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Offen

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
