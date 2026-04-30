# Kolibri Launcher — TODO & Roadmap

Lebendes Dokument. Stand: 2026-04-30, basierend auf einer vollständigen Source-Analyse
des Repos auf Branch `main` (Version `0.99.61` / versionCode `79`).

Was hier steht, ist aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## 1. `Timber.e` vs. `silentError`: Severity-Audit

`TimberWrapper.silentError(...)` ist als „fail fast in DEBUG, fail safe in
RELEASE" gebaut:

- **DEBUG:** wirft `RuntimeException` → Bug sofort sichtbar, Senior fixt
- **RELEASE:** Logcat-Eintrag mit `SILENT_ERROR`-Tag → App läuft weiter,
  User merkt nichts

Direktes `Timber.e(...)` hat dieses Verhalten **nicht** — es loggt in beiden
Build-Typen still. Bug-Symptome im Debug-Build landen damit in der
Logcat-Mülltonne, statt dem Senior aufzufallen.

Nach den weiteren Fixes verbleiben 9 von ursprünglich 21 `Timber.e`-Aufrufen
im Main-Source: 7 in Gruppe A (bleiben aus Design), 2 in Gruppe B (warten
auf Senior-Sign-off).

### Gruppe A — bleibt `Timber.e` (7 Aufrufe, kein Handlungsbedarf)

Direkt im Crash-Handler-Code. `silentError` würde hier rekursiv die
eigene Crash-Behandlung auslösen.

| Stelle | Kontext |
|---|---|
| `KolibriLauncherApp.kt:76` | `applicationExceptionHandler` selbst |
| `:180`, `:256` | Catch um `setupGlobalExceptionHandler` (zwei Pfade) |
| `:189` | StrictMode-Setup-Fallback |
| `:269` | `handleUncaughtException` selbst |
| `:294` | Catch innerhalb des Crash-Handlers |
| `:368` | `onTerminate` (Process wird gekillt) |

### Gruppe B — verbleibende `silentError`-Kandidaten

- [ ] **`KolibriLauncherApp.kt`: 2 Aufrufe — BENÖTIGT SENIOR-SIGN-OFF**
  - `:210` — Backup-Restore-Fehler (Quellcode-Kommentar sagt selbst
    `// Continue anyway - not critical`)
  - `:217` — Migration-Error
  - **Warnung:** `KolibriLauncherApp` ist laut CLAUDE.md Regel 7 explizit
    gegen Vereinfachung geschützt. Es ist möglich, dass die
    `Timber.e`-Wahl hier Absicht ist, weil das Lifecycle-Setup auch im
    Debug-Build nicht crashen darf, wenn etwas Unerwartetes reinkommt.
  - Vor jedem Edit explizit beim Senior abklären. Falls Freigabe:
    2 Aufrufe zu `silentError(e, ...)` umwandeln.

---

## 2. Rule 10 — Logik aus Android-Runtime-Klassen extrahieren

Audit am 2026-04-30 hat fünf Stellen identifiziert, an denen testbare
Logik direkt in einer Activity oder einem Fragment lebt. Rule 10 gilt
explizit nur für neuen Code — diese Punkte sind pre-existing Tech-Debt,
kein Block-Refactor. Einzeln angehen, wenn die jeweilige Klasse aus
anderen Gründen ohnehin offen ist.

- [ ] **`MainActivity.showCustomizationOptionsDialog()` (`:662-759`)** —
  baut eine dynamische Dialog-Liste anhand von `hasWallpaper` ×
  `isEditMode`. Reine Entscheidung. Vorschlag: `CustomizationDialogModel`-
  Helper analog zum existierenden `LayerButtonsState`-Muster, Fragment
  rendert die fertige Liste.

- [ ] **`MainActivity.handleSpecificEvent()` LaunchApp-Branch
  (`:536-562`)** — „pop back-stack falls in Drawer, dann launch" ist
  Navigation-State-Logik. Vorschlag: ins ViewModel oder einen
  `delegate/`-Sibling, Activity bekommt fertiges `PopThenLaunch`-Event.

- [ ] **`CustomNamesActivity.handleRename()` (`:352-372`)** — vierfache
  Validierung (empty → remove, too long → error, equals original →
  remove, sonst → set) mit Inline-Konstante `MAX_APP_NAME_LENGTH`.
  Vorschlag: `CustomNamesViewModel.onRenameSubmitted(app, newName)` oder
  ein `RenameDecision`-Helper mit Tests pro Branch.

- [ ] **`AppContextMenuDialogFragment.loadActions()` (`:192-346`)** —
  koordiniert vier Repositories (`shortcutManager`, `favoritesManager`,
  `appNamesManager`, `visibilityManager`) und branched auf
  `menuContext`/`hasUsageData`/`isFavorite`/`isHidden`/`hasCustomName`.
  Vorschlag: `BuildAppContextMenuUseCase` retourniert
  `List<AppContextMenuAction>`, Fragment observiert und rendert.

- [ ] **`FavoritesSortFragment.saveFavoritesOrder()` +
  `sortFavoritesAlphabetically()` (`:260-282`, `:318-356`)** —
  Multi-Step-Orchestrierung (Map → Repo → Fragment-Result → Toast). Das
  aktuelle KDoc rechtfertigt explizit die ViewModel-Lücke; Rule 10 dreht
  diesen Kalkül um (JVM-only Test-Target). Vorschlag:
  `FavoritesSortViewModel`.

Audit-Quelle: Sub-Agent-Review der Codebase. Vor jedem Refactoring
selbst gegenlesen — Zeilennummern können durch andere Edits abweichen.

---

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
