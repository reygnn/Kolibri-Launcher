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

Nach den ersten Fixes verbleiben 11 von ursprünglich 21 `Timber.e`-Aufrufen
im Main-Source: 7 in Gruppe A (bleiben aus Design), 4 in Gruppe B (warten
auf Senior-Sign-off).

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

### Gruppe B — verbleibende `silentError`-Kandidaten

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
    4 Aufrufe zu `silentError(e, ...)` umwandeln.

---

## 2. Wallpaper-State: verbleibende Aufgaben

Verdacht 1-5 aus dem Sherlock-Review wurden auf Branch
`fix/wallpaper-state-coherence` umgesetzt. Übrig bleibt der
`forPersistence`-Cleanup.

- [ ] **`WallpaperState.forPersistence()` aufräumen**
  - Anker: `domain/model/WallpaperState.kt:287-294`
  - Self-deklariert als „Legacy no-op… wird beibehalten weil
    bestehende Tests sie mocken."
  - Fix: Tests umstellen, Methode entfernen. Keine Eile, aber gehört
    in die nächste Test-Welle.



- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
