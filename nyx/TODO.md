# Nyx Launcher — TODO & Roadmap

Lebendes Dokument. Aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

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

### BaseActivity + Fehler-Toast-Bus nachrüsten (Robustheit)

Der ViewModel-Teil ist **erledigt**: `HomeViewModel` erweitert bereits das
geteilte `BaseViewModel` (`:common-ui`, `base/BaseViewModel.kt`), das
`launchSafe`/`executeSafe` (CoroutineExceptionHandler, Cancellation-Rethrow-
Disziplin) mitbringt — ein throwender Use-Case wird geloggt statt zum Crash. Der
`ErrorEventBus` liegt ebenfalls schon in `:common-ui`.

Real **offen** bleibt nur:
- **Kein geteiltes `BaseActivity`** — `MainActivity` ist eine schlichte
  `AppCompatActivity`; ihr Coroutine-Crash-Netz ist die ad-hoc
  `home/wallpaper/LaunchSafe.kt`-Extension statt eines gemeinsamen Basistyps.
- **Toast-Bus nicht verdrahtet** — nyx nutzt den `ErrorEventBus`/`UiEvent`-Pfad
  nicht (`HomeViewModel` hat Event-Typ `Nothing`), Fehler werden also nicht
  benutzersichtbar getoastet.

Kein Muss (der HIE-Uhr-Crash kam aus einem synchronen onClick und ist defensiv
via `startActivitySafely` gelöst), aber ein sinnvoller Reife-Schritt. Anker:
Kolibri `ui/base/BaseActivity.kt` als Vorlage; Ziel `:common-ui`
(`base/BaseViewModel.kt`, `ErrorEventBus.kt` liegen dort bereits).

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

### Custom Names — Feature (aus Kolibri portieren)

Noch offen aus der Übernahme-Tabelle (`ICON_HOME_MODEL_SPEC.md` §0.1): frei
umbenennbare App-Namen. `LauncherApp` trägt bereits ein `customName`-Feld und die
Sortierung nutzt `displayName = customName ?: label`, aber es gibt keinen
`CustomNamesRepository` und keinen Umbenennen-Pfad. Muster wie Hidden Apps (heute
portiert): eigenes Repository + reaktives Einfalten in die Drawer-Projektion +
Bearbeiten-UI. Referenz (Kolibri): `CustomNamesRepository`.
