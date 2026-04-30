# Kolibri Launcher — TODO & Roadmap

Lebendes Dokument. Stand: 2026-04-30, basierend auf einer vollständigen Source-Analyse
des Repos auf Branch `main` (Version `0.99.61` / versionCode `79`).

Was hier steht, ist aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## 1. `Timber.e`-Audit (3 Punkte)

Aus einem `grep Timber.e` ergibt sich eine sehr ungleiche Verteilung:
21 Aufrufe insgesamt, 11 in `KolibriLauncherApp.kt`, 8 in einer einzigen
Dialog-Klasse, 2 mit falschem Severity-Level.

- [ ] **`LayoutCustomizationDialogFragment.kt`: Wall-to-Wall-Catch-Cluster prüfen**
  - 8 `Timber.e`-Aufrufe in einer einzigen Datei (`:45`, `:55`, `:66`, `:94`,
    `:292`, `:306`, `:320`, `:328`). Andere Dialog-Fragmente im Projekt haben
    0–2.
  - Wirkt wie defensives Catch-All nach einem historischen Crash-Cluster.
  - Aufgabe: prüfen, ob die Crashes heute noch in ACRA-Reports auftauchen.
    Falls nein → die meisten Try/Catch-Blöcke sind toter Code und können
    fallen. Falls ja → Symptom in `KNOWN_ISSUES.md` dokumentieren statt
    weiter mit `Timber.e` zuzudecken.

- [ ] **`data/ResetRepositoryImpl.kt`: zwei `Timber.e` ohne Throwable**
  - `:63` `Timber.e("Complete data reset completed with errors")`
  - `:185` `Timber.e("User data reset completed with some errors")`
  - Beide Aufrufe verwenden das `Timber.e(String)`-Overload ohne Exception.
    Semantisch ist das kein Crash, sondern eine Warnung über einen
    Teil-Reset, bei dem einzelne Items nicht gelöscht werden konnten.
  - Fix: `Timber.e` → `Timber.w`. Verhindert, dass ACRA in Production
    Reports für Nicht-Crash-Zustände triggert.

- [ ] **`KolibriLauncherApp.kt`: zwei wortgleiche Doppel-Logs verifizieren**
  - `:179` und `:257` loggen beide `"Failed to setup global exception handler"`.
  - `:199` und `:245` loggen beide `"[LIFECYCLE] FATAL: Could not register
    PackageUpdateReceiver!"`.
  - Erwartung (laut CLAUDE.md Regel 7): das ist Absicht, weil zwei
    verschiedene Code-Pfade (z. B. `attachBaseContext` vs. `onCreate`-Failover).
    Aufgabe: kurz verifizieren, dass beide Pfade wirklich gebraucht werden;
    falls einer toter Fallback ist, fällt er — sonst nicht anfassen
    (`KolibriLauncherApp` ist explizit gegen Vereinfachung geschützt).

---

## Was dieses Dokument nicht ist

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
