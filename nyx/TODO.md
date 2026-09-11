# Nyx Launcher — TODO & Roadmap

Lebendes Dokument. Aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Offen

### Grid-Rotation / Orientierungswechsel

Das geräteabhängige Home-Grid (ICON_HOME_MODEL_SPEC §10, `GridSpecProvider` +
`HomeLayoutRegridder`) leitet `columns`/`rows` aus den **aktuellen**
`displayMetrics` ab und fittet nur beim **Kaltstart** (`FitHomeGridUseCase` in
`PackageEventCoordinator.start()`). Dreht das Gerät die Orientierung, greift ein
neues Grid erst beim nächsten Kaltstart, nicht live.

Für einen üblicherweise portrait-gelockten Launcher heute ok. Zu entscheiden,
falls Rotation aktiv unterstützt werden soll:

- Grid aus einer orientierungsstabilen Basis ableiten (z. B. immer aus der
  Portrait-Kante), damit Hoch-/Querformat dasselbe Raster teilen — oder bewusst
  zwei Raster pro Orientierung führen.
- Re-Fit an einen Konfigurationswechsel hängen (Activity-Recreate /
  `onConfigurationChanged`), nicht nur an den Kaltstart.
- Verlustfreies Repack (`HomeLayoutRegridder`) ist bereits idempotent und deckt
  den Shrink/Grow-Fall ab; es fehlt nur der Auslöser bei Rotation.

Anker: `GridSpecProviderImpl` (`:data`), `FitHomeGridUseCase`,
`PackageEventCoordinator`, `HomeLayoutRegridder`.
