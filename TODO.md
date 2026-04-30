# Kolibri Launcher — TODO & Roadmap

Lebendes Dokument. Stand: 2026-04-30, basierend auf einer vollständigen Source-Analyse
des Repos auf Branch `main` (Version `0.99.61` / versionCode `79`).

Was hier steht, ist aus dem Code abgeleitet — kein Wunsch-Backlog. Dinge ohne
konkreten Anker im Repo gehören in Issues, nicht hierher.

---

## 1. Code-Marker, die heute schon im Tree sind

Diese Stellen tragen `TODO`-Kommentare oder Platzhalter und sind die einzigen
echten Code-TODOs im Projekt.

- [ ] **Scroll-View-Border in `HomeFragment` reaktivieren oder löschen**
  - Anker: `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/HomeFragment.kt:326`
    (`private val showBorder = false  // TODO: Feature flag for future border implementation`),
    sowie auskommentierte `applyScrollViewBorder()` / `removeScrollViewBorder()` bei
    `:1009` und `:1022`.
  - Entscheidung erforderlich: implementieren als echtes Feature (mit Setting +
    Tests) **oder** Code samt Flag entfernen. Toter Code, der „später vielleicht"
    reaktiviert wird, hat sich seit mehreren Releases nicht bewegt.

- [ ] **Auskommentierten Split-Mode-Block in `HomeFragment` aufräumen**
  - Anker: `HomeFragment.kt:1195` ff. — `if (_needsSplit.value) { … }` ist vollständig
    auskommentiert mit Hinweis „Code intakt, bitte behalten".
  - Gleiche Frage wie oben: Roadmap-Eintrag mit Aktivierungs-Plan oder löschen.
    Der Split-Mode-Pfad ist sonst durchgehend live (`SplitModeCalculator`,
    `SplitWeightCalculator` haben Tests).

- [ ] **Issue-Link in `KeyboardShowCoordinator` korrigieren**
  - Anker: `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/KeyboardShowCoordinator.kt:16`
    verweist auf `https://github.com/user/kolibri/issues/XXX` (Platzhalter aus dem
    Template). Entweder echtes Issue verlinken oder den Verweis entfernen.

---

## 2. Test-Infrastruktur: Aufräumarbeit

Die Test-Suite ist insgesamt in sehr gutem Zustand (100 % UseCase-Coverage,
durchgängig MockK, keine `@Ignore`-Tests, Contract-Test-Tripel pro Repository).
Zwei Punkte stechen heraus.

- [ ] **`KeyboardShowCoordinator`-Bug: dokumentierter Bug ohne Tracking-Issue**
  - In `KeyboardShowCoordinatorTest.kt` markiert ein Kommentar:
    `// DER BUG: doOnLayout wird nie aufgerufen wenn View schon gelayoutet ist`.
  - Status unklar: existiert der Bug noch, oder ist der Test bereits ein
    Regressions-Guard? Wenn Bug noch offen → echtes Issue erstellen und
    `// DER BUG`-Kommentar mit Issue-Nummer verknüpfen. Sonst Kommentar
    aufräumen.

- [ ] **`WallpaperViewDiffTest`: Cancel-Bug-Regression-Guard dokumentieren**
  - Kommentar `// THE CANCEL-BUG REGRESSION GUARD` ohne Anker. Was war der
    ursprüngliche Bug? Ohne Verweis ist der Test in 6 Monaten nicht mehr lesbar.
  - Fix: Commit-Hash oder Issue verlinken, sonst droht Knowledge-Erosion.

---

## 3. Bekannte System-Probleme (siehe `KNOWN_ISSUES.md`)

Diese sind als „nicht von uns lösbar" dokumentiert — bleiben hier zur
Sichtbarkeit aufgeführt, damit sie nicht versehentlich nochmal angefasst werden.

- [x] ~~Samsung OneUI `IdsController` SharedPreferences-Disk-IO im
  `handleResumeActivity`~~ — als „🔴 Ignored / Unavoidable" deklariert. Nicht
  in App-Code lösbar.
- [x] ~~Samsung Knox `resolveActivity`-DB-Query~~ — bereits gelöst durch
  `Dispatchers.IO`-Wrapping in `AppContextMenuDialogFragment.loadActions()`.

Vor jedem neuen StrictMode-Bugreport gegen diese beiden bitte zuerst
`KNOWN_ISSUES.md` prüfen.

---

## 4. Future Ideas & Backlog

Bewusst nicht-aktiv. Hier nur Dinge, zu denen es User-Anfragen oder
explizite Design-Diskussionen gab.

- [ ] **Widget-Support (KWGT u. ä.)**
  - Status: **out of scope**.
  - Begründung: Das Android-AppWidget-Framework ist signifikanter Aufwand,
    und das Fehlen von Widgets ist eine bewusste Design-Entscheidung
    (minimalistisch, schnell, leichtgewichtig). Periodisch reevaluieren —
    aktuell nicht geplant.
  - Related Issue: #2

- [ ] **Erweiterte Theming-Optionen**
  - Über die heutige `TextColorCalculator` + Chip-Background-Farbe hinaus.
  - Konkrete Sub-Ideen wären: Schriftart-Auswahl, Akzentfarbe für Highlights,
    Custom-Icon-Pack-Support. Erst Idee — kein Design, kein Plan.

---

## Was dieses Dokument nicht ist

- Keine Architektur-Beschreibung — siehe `README.md` und `CLAUDE.md`.
- Keine Test-Referenz — siehe `app/src/CLAUDE.md` und
  `TESTING_CONVENTIONS.kt`.
- Kein Issue-Tracker-Ersatz — kleinere Bugs gehören in GitHub-Issues, nicht hier.

Einträge fliegen raus, sobald sie erledigt oder explizit verworfen sind.
Keine erledigten Punkte als Achievement-Liste sammeln.
