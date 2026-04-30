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

Aus 21 `Timber.e`-Aufrufen im Main-Source ergeben sich drei Gruppen.

### Gruppe A — bleibt `Timber.e` (7 Aufrufe, kein Handlungsbedarf)

Direkt im Crash-Handler-Code. `silentError` würde hier rekursiv die
eigene Crash-Behandlung auslösen.

| Stelle | Kontext |
|---|---|
| `KolibriLauncherApp.kt:75` | `applicationExceptionHandler` selbst |
| `:179`, `:257` | Catch um `setupGlobalExceptionHandler` (zwei Pfade) |
| `:188` | StrictMode-Setup-Fallback |
| `:270` | `handleUncaughtException` selbst |
| `:295` | Catch innerhalb des Crash-Handlers |
| `:369` | `onTerminate` (Process wird gekillt) |

### Gruppe B — Kandidaten für `Timber.e` → `silentError` (12 Aufrufe)

Echte Bugs in nicht-kritischen Pfaden. Recovery möglich, App-Lifecycle
nicht in Gefahr.

- [ ] **`LayoutCustomizationDialogFragment.kt`: 8 Aufrufe (unkontrovers)**
  - `:45` Failed to inflate LayoutCustomizationDialog
  - `:55` Failed to configure dialog window
  - `:66` Failed to setup LayoutCustomizationDialog
  - `:94` Error in onDestroyView
  - `:292` Error in drag handling
  - `:306` Error in `$context` (generic helper)
  - `:320` Error in coroutine: `$context` (generic helper)
  - `:328` Failed to dismiss dialog
  - Fix: alle 8 von `Timber.e(e, ...)` zu
    `TimberWrapper.silentError(e, ...)`. Dialog-Bugs werden im Debug
    sofort sichtbar; im Release-Build läuft die App weiter, ohne dass
    der User einen kaputten Dialog erlebt.

- [ ] **`KolibriLauncherApp.kt`: 4 Aufrufe — BENÖTIGT SENIOR-SIGN-OFF**
  - `:199`, `:245` — PackageUpdateReceiver-Registrierung
  - `:211` — Backup-Restore-Fehler (Quellcode-Kommentar sagt selbst
    `// Continue anyway - not critical`)
  - `:218` — Migration-Error
  - **Warnung:** `KolibriLauncherApp` ist laut CLAUDE.md Regel 7 explizit
    gegen Vereinfachung geschützt. Es ist möglich, dass die
    `Timber.e`-Wahl hier Absicht ist, weil das Lifecycle-Setup auch im
    Debug-Build nicht crashen darf, wenn etwas Unerwartetes reinkommt.
  - Vor jedem Edit explizit beim Senior abklären. Falls Freigabe:
    4 Aufrufe zu `silentError(e, ...)` umwandeln, gleicher Effekt wie in
    Gruppe-B-Punkt 1.

### Gruppe C — Kandidaten für `Timber.e` → `Timber.w` (2 Aufrufe)

Keine Bugs, sondern erwartete Partial-Failure-Zustände.

- [ ] **`data/ResetRepositoryImpl.kt`: zwei Aufrufe ohne Throwable**
  - `:63` `Timber.e("Complete data reset completed with errors")`
  - `:185` `Timber.e("User data reset completed with some errors")`
  - Beide nutzen das `Timber.e(String)`-Overload ohne Exception.
    Semantisch ist das eine Warnung über einen Teil-Reset, kein Crash.
  - Fix: `Timber.e` → `Timber.w`. Verhindert in Production unnötige
    ACRA-Reports für Nicht-Crash-Zustände.

---

## Was dieses Dokument nicht ist

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
