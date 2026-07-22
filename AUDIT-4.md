# AUDIT-4 — Whole-Codebase Correctness Audit

> **Erzeugt** 2026-07-22 durch einen Claude-Opus-4.8-Multi-Agent-Audit
> (Version 0.99.98 / code 118, Branch `main`).
> **Methode:** 6 Bereichs-Slices parallel abgefächert — `:domain`,
> `:data`, ViewModels/Coroutines, Fragments/Activities/Lifecycle,
> restliche UI-Features, Build/DI/ACRA/Contracts. Jeder Slice-Agent musste
> jedes Finding am echten Code mit einem konkreten Trigger→Fehler-Szenario
> belegen. Jedes gemeldete Finding wurde anschließend von einem separaten
> **adversarialen Verifikations-Agenten** gegengeprüft, dessen Auftrag es
> war, das Finding zu *widerlegen* (Default REJECTED, nur CONFIRMED bei
> nachvollziehbarem Trigger→Fehler). Style-Nits und dokumentiert-bewusste
> Muster (Rule-5/7/8/9-Ausnahmen, `ACCEPTED_LIMITATIONS.md`,
> `KNOWN_ISSUES.md`, ADR-only-Contracts) sowie in AUDIT/-2/-3 bereits
> gefixte Findings waren als Ausschluss vorgegeben.
> **Reine Diagnose — es wurde kein Code geändert.**
>
> **Selbst-Verifikation (2026-07-22):** Alle drei bestätigten Findings
> wurden vom Orchestrator unabhängig am Quellcode nachgeprüft (nicht nur
> aus den Agenten-Reports übernommen). Ergebnis: alle drei bestätigt,
> keine Severity-Korrekturen.
>
> **✅ RESOLUTION #2 (2026-07-22):** Als Versehen bestätigt und auf Branch
> `fix/audit4-datastore-backup` gefixt. Der ACRA-Consent wurde in eine
> eigene DataStore-Datei (`acra_consent.preferences_pb`,
> `AppConstants.CONSENT_DATASTORE_NAME`) ausgelagert; beide Backup-
> Regeldateien includen jetzt gezielt nur `datastore/kolibri_settings.preferences_pb`,
> sodass der gesamte Nutzer-Config gesichert wird, der Consent (Privacy-
> Entscheidung des Nutzers) aber **nicht** aufs neue Gerät wandert. Bewusst
> **ohne** Migration: bestehende Installs setzen den Consent einmalig zurück
> (Dialog erscheint erneut, ACRA bleibt bis zur erneuten Zustimmung aus —
> privacy-safe), statt die bewusst entfernte Migrations-Maschinerie auf dem
> ACRA-Bootstrap-Pfad wieder einzuführen. Kein Unit-Test: `CrashReportConsentStore`
> nutzt die Singleton-DataStore-Extension direkt (Bootstrap-Zwang, Rule-9-
> Ausnahme) und ist untested-by-design; ein Robolectric-Test gegen die
> Singleton-Datei wäre genau das Flakiness-Muster aus `HISTORY.md`. Verifiziert
> via `assembleDebug` + voller Unit-Test-Suite (`:domain`/`:data`/`:app`) +
> `checkConventions` + `checkRule13` — alle grün. **#1 und #3 bleiben offen.**
>
> **Statistik:** 4 Roh-Findings → 1 in der adversarialen Verifikation
> verworfen (siehe „Verworfene Findings" unten) → **3 bestätigt: 2 Medium,
> 1 Low.** Vier von sechs Slices (`:domain`, ViewModels/Coroutines,
> UI-Features) meldeten *keine* Findings.

---

## Executive summary

Der Code ist weiterhin in sehr gutem Zustand. Die tiefen Slices —
Use-Case-Logik, ColorMath/Coerce-Mathe, ViewModel-Config-Change-Handling
(nach AUDIT-3 sauber), Coroutine-/Flow-Semantik, Hilt-Graph,
Modul-Richtung, ACRA-Reihenfolge, Contract-Triples — kamen ohne Befund
zurück. Die drei bestätigten Findings sind allesamt **Randfälle jenseits
des normalen Betriebs**: ein Reset-Pfad, das Auto-Backup und eine
Leak-Fläche bei wiederholter Navigation. Keins ist ein Absturz oder
akuter Datenverlust im täglichen Gebrauch.

Ein systemisches Muster über zwei der drei Findings hinweg:
**Vollständigkeits-Drift bei „reset/backup alles"-Operationen.** Sowohl
`purgeRepository()` (#1) als auch die Auto-Backup-Regeln (#2) sollen den
*gesamten* Nutzerzustand behandeln, lassen aber je einen Teil aus — bei
#1 einen einzelnen DataStore-Key, bei #2 die komplette DataStore-Datei.
In beiden Fällen fehlt der automatisierte Wächter (ein Contract-Test bei
#1, gar keine Abdeckung bei #2), der die Auslassung gefangen hätte.

Priorisierung: **#2 zuerst** (stiller Verlust *aller* Nutzer-Config bei
Cloud-Restore/Gerätewechsel, breitester Wirkungsradius), dann **#1**
(ein Setting überlebt den Werksreset), dann **#3** (langsames
Memory-Creep auf einem langlebigen Launcher-Prozess).

| # | Severity | Datei | Kategorie | Kurz |
|---|----------|-------|-----------|------|
| 1 | 🟡 Medium | `data/.../SettingsRepositoryImpl.kt:318` | correctness | `purgeRepository()` vergisst `APP_DRAWER_MODE` → `wallpaperSurfaceMode` überlebt den Reset |
| 2 ✅ | 🟡 Medium | `app/src/main/res/xml/data_extraction_rules.xml` + `backup_rules.xml` | backup-round-trip | Auto-Backup includiert nur `sharedpref` → gesamter DataStore (aller Nutzerzustand) wird nicht gesichert — **gefixt** |
| 3 | 🟢 Low | `app/.../ui/home/HomeFragment.kt:1548` | resource-leak | `onDestroyView` nullt `favoritesRecyclerView.adapter` nicht → RecyclerView-Leak pro Home↔Drawer-Wechsel |

---

## #1 — `purgeRepository()` setzt `wallpaperSurfaceMode` nicht zurück · 🟡 Medium

**Datei:** `data/src/main/java/com/github/reygnn/kolibri_launcher/data/SettingsRepositoryImpl.kt:318–341`
**Kategorie:** correctness / reset-Pfad

`purgeRepository()` entfernt 17 Settings-Keys (`SORT_ORDER_KEY` …
`ROTATION_LOCKED`), aber **nicht** `PreferenceKeys.APP_DRAWER_MODE`. Die
einzige bewusste, auskommentierte Ausnahme ist `ONBOARDING_COMPLETED`
(Z. 338–339). `APP_DRAWER_MODE` ist der Key hinter dem echten,
UI-sichtbaren Setting `wallpaperSurfaceMode`:

- gelesen von `wallpaperSurfaceModeFlow` (Z. 266–275, `preferences[PreferenceKeys.APP_DRAWER_MODE]` auf Z. 268)
- geschrieben von `setWallpaperSurfaceMode` (Z. 277–279)
- exponiert in der UI als `ListPreference` in `SettingsFragment` (setzt DARK/LIGHT/AUTO)
- Default `AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE = WallpaperSurfaceMode.AUTO`

> ⚠️ Namens-Falle: Der DataStore-Key heißt historisch `APP_DRAWER_MODE`,
> backt aber `wallpaperSurfaceMode` — nicht mit einem App-Drawer-Setting
> verwechseln.

**Trigger → Fehler:** Nutzer stellt „Wallpaper-Surface-Mode" auf DARK
oder LIGHT, löst später einen Settings-/Werksreset aus
(`ResetRepositoryImpl.resetSettings()` → `purgeAll(...)` →
`settingsRepository.purgeRepository()`). Alle anderen Settings kehren auf
Default zurück; `wallpaperSurfaceModeFlow` emittiert weiterhin das alte
DARK/LIGHT statt AUTO. Der Key überlebt den Reset.

**Warum es durchrutschte:** Der Contract-KDoc
(`SettingsRepositoryContract.kt:33–34`) dokumentiert die Invariante
ausdrücklich — purge „setzt alles auf Default ZURÜCK, außer Onboarding".
Die Impl widerspricht dem. Es gibt Contract-Tests für purge-reset von
Visual-Settings, Feature-Toggles, `sortOrder`, `favoritesAlignment` und
Onboarding — aber **keinen** für `wallpaperSurfaceMode`. Ein solcher Test
wäre rot geworden (die Fake-Impl auf einer nackten Map würde den Key
korrekt zurücksetzen, die echte Impl nicht → Contract-Drift, genau der
Mechanismus aus Rule 2/3).

**Kein** Absturz, kein Datenverlust; vom Nutzer durch erneutes Wählen von
AUTO behebbar. Aber ein Werksreset, der still ein persistentes Setting
zurücklässt und dabei dem dokumentierten Contract widerspricht, ist ein
Korrektheitsdefekt. **Medium.**

**Fix-Richtung (nicht angewandt):** `preferences.remove(PreferenceKeys.APP_DRAWER_MODE)`
in `purgeRepository()` ergänzen **und** einen purge-reset-Contract-Test
für `wallpaperSurfaceMode` nachziehen (sonst fällt die nächste solche
Auslassung wieder durch).

---

## #2 — Auto-Backup schließt den gesamten DataStore aus · 🟡 Medium · ✅ GEFIXT

**Dateien:** `app/src/main/res/xml/data_extraction_rules.xml`
(cloud-backup + device-transfer, aktiv auf minSdk 36) und
`app/src/main/res/xml/backup_rules.xml` (`fullBackupContent`, nur ≤ Android 11)
**Kategorie:** backup-round-trip / Datenverlust bei Gerätewechsel

Beide Regeldateien deklarieren ausschließlich
`<include domain="sharedpref" path="." />`. Android Auto Backup schaltet,
sobald ein explizites `<include>` vorhanden ist, von „sichere alles" auf
eine **Allowlist** um — die `file`-Domain wird damit ausgeschlossen.

Sämtlicher Nutzerzustand (Favoriten, versteckte Apps, Custom-Names,
Swipe-Actions, Layout-/Color-Customization, FAB-Position,
Wallpaper-Settings, ACRA-Consent) lebt per Rule 5 im **einen** DataStore-
Preferences-File `files/datastore/<SETTINGS_DATASTORE_NAME>.preferences_pb`
— also in der `file`-Domain, die nie includiert wird. Der einzige echte
`SharedPreferences`-File im Projekt ist `CrashReportLimiter`s
`acra_report_limiter` (ephemere ACRA-Ratelimit-Timestamps, per
`getSharedPreferences` bestätigt).

**Trigger → Fehler:** Nutzer konfiguriert den Launcher, wechselt das
Gerät (Google-Cloud-Restore oder D2D-Transfer). Wiederhergestellt wird
**nur** der nutzlose `acra_report_limiter`; der Nutzer landet auf einem
komplett unkonfigurierten Launcher. Das ist **schlechter** als der
AGP-Default: mit `allowBackup="true"` und *ohne* Regeldatei wäre der
DataStore gesichert worden — die vorhandenen Regeln schließen ihn aktiv
aus.

**Doku-Status:** Kein Dokument (`CLAUDE.md`, `ACCEPTED_LIMITATIONS.md`,
`KNOWN_ISSUES.md`, `AUDIT*.md`) vermerkt eine bewusste Auto-Backup-
Einschränkung. `AUDIT.md:451` hatte nur bestätigt, dass die Regeldateien
*existieren* (Android-12+-Pflicht), nie deren Inhalt geprüft.

**Milderung → Severity Medium (nicht High):** Die App bringt ein
vollständiges manuelles JSON-Backup/Restore mit (`BackupRepositoryImpl` +
`BackupDataAssembler`). Ein Nutzer, der ein manuelles Backup gezogen hat,
kann wiederherstellen. Es ist also eine stille Komfort-Lücke, kein
unwiederbringlicher Datenverlust.

**Auflösung (angewandt, Branch `fix/audit4-datastore-backup`):** Als
Versehen bestätigt. Beide Regeldateien includen jetzt gezielt
`datastore/kolibri_settings.preferences_pb` — der komplette Nutzer-Config
wird gesichert. Der ACRA-Consent wurde dabei in eine **eigene**
DataStore-Datei (`acra_consent.preferences_pb`) ausgelagert, die vom
Backup ausgenommen bleibt, damit die Consent-Entscheidung nicht still aufs
neue Gerät wandert (privacy-by-default, Rule 8). Bewusst ohne Migration —
bestehende Installs setzen den Consent einmalig zurück (Dialog erscheint
erneut). Siehe Resolution-Notiz oben.

---

## #3 — `HomeFragment.onDestroyView` nullt den RecyclerView-Adapter nicht · 🟢 Low

**Datei:** `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/HomeFragment.kt:1548–1583`
**Kategorie:** resource-leak

`favoritesAdapter` ist ein fragment-lebensdauer-`by lazy`-Feld (Z. 283) —
es überlebt View-Zerstörung/-Neuerstellung, anders als `_binding`.
`setupFavoritesRecyclerView` (Z. 856–863, aufgerufen aus `onViewCreated`)
setzt `binding.favoritesRecyclerView.adapter = favoritesAdapter` bei jeder
View-Neuerstellung. `onDestroyView` (Z. 1548–1583) nullt Wallpaper-
Callbacks und `_binding`, setzt aber **nie** `favoritesRecyclerView.adapter = null`.

**Mechanismus:** `RecyclerView.setAdapter` registriert einen
`RecyclerViewDataObserver` (innere Klasse mit Rückreferenz auf die
RecyclerView) am `AdapterDataObservable` des Adapters. Dieser Observer
wird nur abgemeldet, wenn *auf derselben RecyclerView-Instanz* erneut
`setAdapter` läuft. Da die alte RecyclerView bei View-Zerstörung
verworfen wird, ohne den Adapter zu nullen, bleibt ihr Observer am
langlebigen `favoritesAdapter` registriert.

**Trigger → Fehler:** Nutzer wischt hoch → AppDrawer öffnet
(`navController.navigate`, HomeFragment bleibt auf dem Nav-Back-Stack,
View wird zerstört) → kehrt zurück (`popBackStack`, View neu erstellt).
Jeder Home↔Drawer-Roundtrip registriert einen weiteren Observer, jeder
pinnt eine tote RecyclerView samt ihren Item-Views und dem inflateten
Theme-Context. Akkumulation ist nur durch die Lebensdauer des
Fragments selbst begrenzt → langsames Memory-Creep auf einem
Launcher-Prozess, der tagelang läuft.

**Warum Low:** Konsistent mit AUDIT-3 #13 (dort selbst als „kleine,
beschränkte Leak-Fläche" eingestuft). Die Favoritenliste ist klein (eine
Handvoll Button-Item-Views), der Leak wächst nur bei wiederholter
Navigation und ist auf Fragment-Lebensdauer gedeckelt. Kein Absturz, kein
Datenverlust.

**Bemerkenswert:** Dies ist exakt das Muster, das AUDIT-3 #13 als Leak
zu-fixen behandelte — `AppDrawerFragment.onDestroyView`s `adapter = null`
wurde dort explizit „die Referenz" genannt. Jeder andere RecyclerView-
Host im Code nullt seinen Adapter im Teardown (`AppDrawerFragment`,
`FavoritesSortFragment`, `AppContextMenuDialogFragment:483`,
`HiddenAppsActivity`, `SwipeActionsActivity`, `OnboardingActivity`,
`CustomNamesActivity`). `HomeFragment` war schlicht nicht im Scope jenes
Findings — eine echte, un-gefixte Auslassung, keine dokumentierte
Ausnahme.

**Fix-Richtung (nicht angewandt):** In `onDestroyView`
`_binding?.favoritesRecyclerView?.adapter = null` ergänzen (analog zu
allen Geschwister-Hosts), vor `_binding = null`.

---

## Verworfene Findings (adversariale Verifikation)

Zur Nachvollziehbarkeit — dieses Finding wurde gemeldet, in der
Verifikation aber am Code **widerlegt**:

- **`BackupSerializer.kt:380` — angeblicher Float-Overflow-Import
  (`"wallpaperScale": 1e309` → `Float.POSITIVE_INFINITY`).**
  Verworfen: Die Prämisse ist falsch. kotlinx-serialization 1.11.0
  akzeptiert per Default keine Special-Floating-Point-Werte, und die
  Import-Validierung fängt out-of-range-Werte, bevor sie via
  `?: base.wallpaperScale` durchschlagen könnten. Kein reproduzierbarer
  Trigger→Fehler. Severity effektiv keine.

Vier der sechs Slices (`:domain`, ViewModels/Coroutines, UI-Features,
sowie nach Verifikation der verbleibende `:data`-Kandidat) endeten ohne
bestätigten Befund.

---

## Was NICHT gefunden wurde (bestätigt sauber)

Die adversarialen Slices durchsuchten gezielt die Klassen von
Defekten aus AUDIT-2/-3 und fanden dort **keine** neuen Regressionen:

- **Config-Change-Verhalten der ViewModels** — nach den AUDIT-3-Fixes
  keine weiteren `initialize()`-ohne-`isInitialized`-Fälle.
- **Coroutine-/Flow-Semantik** — kein `advanceUntilIdle` gegen
  `WhileSubscribed`, keine subscriber-lose `SharedFlow`-Overflows in
  Produktionscode.
- **Hilt-Graph, Versions-Pins, Modul-Richtung, ACRA-Reihenfolge,
  `CrashReportLimiter`-Mathe, ColorMath/CoerceExtensions,
  Contract-Triples** — unverändert sauber.
- **Dialog-/Window-Leaks bei Rotation** — nach AUDIT-3 #12 kein neuer
  Fall.

---

*Methodik-Fußnote: 10 Agenten (6 Finder + 4 Verifier), ~1,13 Mio
Subagent-Tokens, 308 Tool-Calls, Laufzeit ~10 min. Jedes Finding hier
ist doppelt am Quellcode geprüft (Verifier-Agent + Orchestrator-Selbst-
Verifikation). Reine Diagnose; keine Code-Änderung.*
