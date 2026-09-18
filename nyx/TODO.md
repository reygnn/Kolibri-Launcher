# Nyx Launcher — TODO & Roadmap

Lebendes Dokument. Aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## Kürzlich erledigt (2026-09-18)

- **Rotationssperre + Grid bei Orientierungswechsel** — `rotation_locked`-Switch
  (Display-Kategorie) über `PreferencesRepository` (Interface + Contract + Fake + Impl),
  im Backup/Restore mitgeführt. **Default gesperrt** (`PreferencesRepository
  .DEFAULT_ROTATION_LOCKED = true`, bewusst abweichend vom geteilten `:core`-Default
  `false`): nyx' Grid re-packt bei Orientierungswechsel verlustbehaftet (Apps ja,
  Positionen nein), also würde „entsperrt per Default" ein Portrait→Landscape→Portrait
  einen kuratierten Layout verwürfeln. `MainActivity` setzt `requestedOrientation`
  (PORTRAIT/UNSPECIFIED) aus einem Eagerly-`HomeViewModel.rotationLocked` (eager in
  onCreate + Collector). Damit ist #5 entschieden: gesperrt (Default) → Hochformat,
  Rotation ist Opt-in. Zusätzlich der bislang **fehlende** Trigger gefixt: das Manifest
  hatte längst `configChanges=orientation` (kein Recreate), aber kein
  `onConfigurationChanged` → das Grid adaptierte sich bei Rotation gar nicht; jetzt
  re-fittet `onConfigurationChanged` via `applyDeviceGrid`/`HomeLayoutRegridder`.
  `applyDeviceGrid` überspringt einen Landscape-Fit im gesperrten Zustand, damit ein
  transienter Landscape-Frame beim Kaltstart nie einen Repack persistiert.

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

### Dev-Fehler-Toasts in nyx speisen (optional, Rest von BaseActivity)

Die geteilte `BaseActivity` (erledigt, siehe oben) **sammelt** bereits den
globalen `ErrorEventBus` und würde in DEBUG Dev-Fehler-Toasts zeigen — aber in
nyx **postet niemand** auf den Bus: die einzige Quelle ist Kolibris
`ToastErrorTree` (eine Timber-Tree), die nyx nicht pflanzt. Der Collector läuft
also leer. Wenn Dev-Fehler-Toasts wie in Kolibri gewünscht sind: eine kleine
`ToastErrorTree` nach `:common-ui` (oder `:feature-crashreporting`) heben und in
`NyxLauncherApp` pflanzen. Rein optional/Dev-Komfort. Anker: Kolibri
`ui/util/ToastErrorTree.kt`, `NyxLauncherApp` (Timber-Trees), `:common-ui`
`ErrorEventBus`.

### Wallpaper-Edit-UI: Dedup gegen `:common-ui`

FAB-Cluster + CommandsPanel + `SnapIconResolver`/`LayerButtonsState` sowie die
Helfer `ViewFade`/`DialogWindow`/`DialogDrag` sind bewusst **byte-identisch aus
Kolibri kopiert** (Maintainer-Entscheid WV5d, kein Ressourcen-Merge-Risiko).
Wenn Kolibri das nächste Mal angefasst wird: in `:common-ui` unifizieren (beide
Apps teilen dann eine Implementierung). Anker: nyx `home/wallpaperfab/*`,
`home/wallpaper/{SnapIconResolver,LayerButtonsState}`, `:common-ui`.

### Custom Names — Feature (aus Kolibri portieren)

Noch offen aus der Übernahme-Tabelle (`ICON_HOME_MODEL_SPEC.md` §0.1): frei
umbenennbare App-Namen. `LauncherApp` trägt bereits ein `customName`-Feld und die
Sortierung nutzt `displayName = customName ?: label`, aber es gibt keinen
`CustomNamesRepository` und keinen Umbenennen-Pfad. Muster wie Hidden Apps (heute
portiert): eigenes Repository + reaktives Einfalten in die Drawer-Projektion +
Bearbeiten-UI. Referenz (Kolibri): `CustomNamesRepository`.
