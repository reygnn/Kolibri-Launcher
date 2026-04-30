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

## 2. `silentError` → `silentDeath`: kontrolliertes Sterben in Lügen-Pfaden

Der Senior-Defense-Stil (`silentError` überall) hat eine Schattenseite:
an manchen Stellen lässt der Catch die App in einem Zustand
weiterlaufen, der **nicht crasht, aber lügt** — die App tut so, als
sei alles OK, während User-Daten halb-gelöscht oder Onboarding-Daten
in Wirklichkeit über echte Daten geschrieben werden.

In diesen Fällen ist ein **kontrollierter Process-Death** ehrlicher als
silent-fail: ACRA-Report durchsenden, dann `exitProcess(1)`. Beim
Restart läuft die kritische Init nochmal — wenn’s wieder failt, häufen
sich die Reports und der Bug wird sichtbar.

### Vorbereitung: Helper im `TimberWrapper`

- [ ] **Neuen Helper `silentDeath(message: String): Nothing` einführen**
  - Loggt `FATAL: <message>` mit `SILENT_LOG_TAG`
  - Wartet kurz, damit der ACRA-Sender den Report rauspusten kann
    (∼100 ms reicht laut ACRA-Doku)
  - Ruft `exitProcess(1)` auf
  - Return-Type `Nothing` — macht den Kontrollfluss explizit
  - Im DEBUG-Build kann der Helper alternativ `throw` für sofortige
    Sichtbarkeit beim Entwickeln; in Release-Build `exitProcess(1)`

### Vier Tatorte für `silentError → silentDeath`

- [ ] **`DataMigrationManager.kt:65` — Migration-Cleanup-Failure**
  - `dataStore.edit { it.clear() }` failt mitten in der Mutation. Catch
    schluckt den Fehler, danach läuft `updateVersionInPreferences(1)`
    — die App schreibt „Migration erfolgreich" obwohl DataStore halb
    geleert ist.
  - Fix: nach `silentError` direkt `silentDeath("Migration cleanup
    failed — refusing to mark version as migrated")`. Die nachfolgende
    Versions-Update-Zeile darf nicht erreicht werden.

- [ ] **`DataMigrationManager.kt:91` — falscher „First-Launch"-Status**
  - `getCurrentVersion()` failt → return `0` → triggert „First launch
    detected", überschreibt User-Daten mit Onboarding-Defaults.
  - Fix: statt `return 0` direkt `silentDeath("Cannot read data version —
    refusing to assume first launch")`.

- [ ] **`MainActivity.kt:252` — Fallback-Init gescheitert**
  - Letzter Recovery-Pfad: wenn `initializeMainApp()` im Fallback
    failed, läuft Activity weiter mit leerem ViewModel. User sieht eine
    Hülle ohne Settings/Wallpaper/Daten.
  - Fix: `silentDeath("Fallback init failed — no recovery path left")`.

- [ ] **`MainActivity.kt:205` und `:210` — `finish()` ist hier Zombie-Maker**
  - Kolibri ist als HOME-Activity registriert. `finish()` triggert
    sofort einen Restart durch Android → Endlos-Loop, ACRA-Spam.
  - Fix: beide `finish()` durch `silentDeath(...)` ersetzen. Der
    Process beendet sich, Android wechselt zum nächsten Default-Launcher.

### Was ausdrücklich KEIN Kandidat ist

Damit das nicht zu einem Wildwuchs wird, hier explizit drinstehen
gelassen:

- UI-User-Aktionen (App-Launch, Dialog-Show, Activity-Start) — Toasts
  und Android-Feedback sind ausreichend.
- Repository-Schreibvorgänge mit Bool-Return — UI kann das Ergebnis
  beobachten.
- Backup-Parsing — definierte `ImportResult`-Fallbacks.
- Wallpaper- und Drawable-Failures — cosmetic, kein State-Verlust.
- Lifecycle-Tear-down (`onDestroy`, `unregisterReceiver`) — Process
  geht ohnehin weg.

---

## 3. Wallpaper-State: verbleibende Aufgaben

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
