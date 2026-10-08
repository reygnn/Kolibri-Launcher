# Roadmap: Nyx ↔ Kolibri – übrige Angleichung

Stand: 03.10.2026 (Revision 4: Spec-Phase 3 (Wallpaper) abgeschlossen; D12 für Nyx erledigt.
Revision 3: D12 hart verdrahtete Dispatcher. Revision 2: Zählungen nachgeprüft)

**Spec-Phase 3 (Wallpaper) ist abgeschlossen (03.10.2026)** – Kolibri und Nyx teilen den Wallpaper-Kern in `:feature-wallpaper`
(Zusammenfassung, bewusste Nähte und offene Punkte in `SPEC_NYX_REWRITE.md`, 3b-7).

## Zweck & Verhältnis zum Spec

`SPEC_NYX_REWRITE.md` deckt Hotfix, Regel-Fundament, Backup, Wallpaper und Storage-Cleanup ab (dort Phase 0–5). Diese Roadmap sammelt alle übrigen Drift-Befunde aus drei Suchrunden (29.09.) und ordnet sie zu Arbeitspaketen. Jedes Paket wird vor Beginn zu einem eigenen, kurzen Spec ausgearbeitet; diese Roadmap legt Befund, Ziel, Reihenfolge und Contract fest, nicht die Umsetzung im Detail.

**Voraussetzung:** Spec-Phase 1 (Gates, Testfixtures, Convention-Plugins, Crash-Bootstrap) ist fertig. Die Pakete hier brauchen die Detektoren und das Paritäts-Gate. Backup und Wallpaper müssen nicht fertig sein, außer wo ein Paket es ausdrücklich nennt.

**Spielregeln** wie im Spec: pro Paket ein Paar a/b (Kolibri extrahiert, stellt um; Nyx dockt direkt an), API-Review gegen Nyx als Merge-Bedingung von a, Nyx-Freeze für den Bereich, ein offenes b blockiert das nächste a. Ausnahme D3: betrifft nur Kolibri, kein b.

**Befund der Suche.** Copy-Paste ist kaum noch das Problem – jscpd findet zwischen `kolibri/` und `nyx/` nur noch 203 Klon-Zeilen (12 Klone, alle Kotlin; nur `src/main`, ohne Tests, ≥ 8 Zeilen / 50 Tokens; die 978 vom 15.09. stammen von vor dem FAB-Umzug und sind nicht vergleichbar). Drift entsteht heute durch **Nachbauen**: 42 Kommentarzeilen in 22 Nyx-Dateien sagen „mirrors“, „parity“ oder „port of“ zusammen mit „Kolibri“, dazu 7 in Kolibri Richtung Nyx – gezählt mit genau dem Muster, das A8 prüft. Jeder davon ist eine Handkopie ohne Gate; die A8-Allowlist aus dem Spec ist die Arbeitsliste dieser Roadmap.

## Reihenfolge

| # | Paket | Befunde | Warum an dieser Stelle |
| --- | --- | --- | --- |
| R1 | Lazy-Verify | L1–L6 | klein; legt das `AppInfo`/`ComponentKey`-Fundament, das R2 und R3 brauchen |
| R2 | App-Start, Shortcuts, Kontextmenü, Sortierung | S1–S6, S8, X2 | L3 geht darin auf |
| R3 | Suche & Drawer-Mechanik | E4 | braucht `AppInfo` aus R1 und die Sortierung aus R2 |
| R4 | Event-Indikatoren, Info-Elemente, geteilte Schalter | I1–I8, X3 | `TimeInfoSettingsStore` folgt dem Store-Muster aus Spec-Phase 3/4 |
| R5 | Usage & Hidden Apps | D9 | nach R2, weil „nur Erfolg zählt“ dort festgelegt wird |
| R6 | Settings-Bausteine & Strings | D5, D6, I7 | sammelt die Strings der vorigen Pakete ein |
| R7 | System-Intents | D7 | klein |
| R8 | Paket-Event-Brücke | D8 | hängt an R1 (Neu-Enumeration) |
| R9 | Preferences, übrige Drawer-Mechanik, App-Bootstrap, `MainActivity` auf Glue | Rest | Aufräumen; `MainActivity` schrumpft über alle Pakete |
| R10 | Basisklassen in Kolibri | D3, D11 | nur Kolibri; parallel möglich |
| R11 | Verriegeln | A9, A8 leer, jscpd voll | Abschluss |

## R1 – Lazy-Verify: App-Verfügbarkeit

Der Kern ist schon driftfrei, das Drumherum nicht. `LazySlotMembership` (die Regel „leere Installed-Sicht = noch nicht geladen, nichts markieren“) und `NoAutoPruneContract` gibt es einmal für beide. Alles um diese Regel herum – Schlüssel, Installed-Set, Reaktion auf eine verschwundene App, Grau-Darstellung, Entfernen-Dialog – hat jede App selbst gebaut.

| # | Aspekt | Kolibri | Nyx | Ziel |
| --- | --- | --- | --- | --- |
| L1 | Schlüssel für `isMissing` | flacher String `pkg/cls` | `ComponentKey` | nur `ComponentKey` (Fabrik aus Spec B14); das generische `<T>` fällt weg, flache Strings werden am Persistenz-Rand geparst (MRG-INV-10) |
| L2 | Installed-Set | pro Aufruf aus `rawApps` gebaut | eigener `installedKeys`-StateFlow im `HomeViewModel` | ein `installedKeys`-Flow am geteilten `InstalledAppsStateRepository` |
| L3 | Launch trifft verschwundene App (`ComponentGone`) | eigener Toast, Log, stößt Neu-Enumeration an (`shouldReconcile`) | generischer Toast, kein Log, keine Neu-Enumeration – verlässt sich allein auf Paket-Events | Kolibris Reaktion als eine geteilte Funktion in `:common-ui` |
| L4 | Grau-Darstellung | Alpha 0.4 | Alpha 0.35 | eine Konstante in `:common-ui` |
| L5 | Dialog „App nicht gefunden – entfernen?“ | eigener Dialog + Strings | eigener Helfer + Strings (Kachel, Folder-Member) | ein Dialog-Helfer + ein String-Satz in `:common-ui`; pro App nur das Ziel (Home, Ordner) |
| L6 | Grau beim Kaltstart | sofort (provisional) | erst nach dem Laden | **angleichen** (bestätigt 29.09.): Kolibris Provisional-Resolver (`ComponentLabelResolverImpl`, 58 Zeilen, produktneutral) wandert nach `:common-data`; Nyx prüft beim Kaltstart damit parallel die Kacheln von erster Seite + Dock. Dabei den Resolver auf `LauncherApps` umstellen – heute fragt er `PackageManager`, die Enumeration aber `LauncherApps` |

Nyx-eigen und bereits auf der geteilten Regel: Package-Grain-Prüfung bei eigener Deinstallation (`isPackageMissing`), Folder-Member, `DrawerFolderLiveMembers`. Beifund: Kolibris Kommentar an der `ComponentGone`-Reaktion verweist noch auf den gelöschten Orphan-Sweep; wird beim Umzug korrigiert.

**Gates:** Detektor gegen eigene `when`-Zweige auf `AppLaunchResult.ComponentGone` in `kolibri/` oder `nyx/`; erlaubt ist nur der Aufruf der geteilten Reaktion.

**Contract `LazyVerifyContract`** (je App eine Subklasse): leere Sicht markiert nichts; verschwundene App wird grau und bleibt gespeichert; `ComponentGone` löst genau eine Neu-Enumeration aus; entfernt wird nur per Nutzeraktion; beim Kaltstart ist eine verschwundene App schon in der ersten Anzeige grau (L6). `ARCHITECTURAL_DIFFERENCES` §2.1 gilt danach als geschlossen. Zusammen mit `ImportKeepsMissingAppsContract` aus dem Spec deckt das Laufzeit und Restore ab.

## R2 – App-Start, Shortcuts, Kontextmenü, Sortierung

Nur der Systemaufruf ist geteilt (`AppLauncherImpl` → `LauncherApps.startMainActivity`). Alles davor und danach – Doppel-Tap-Schutz, Nutzungszählung, Fehlerreaktion, Shortcuts – baut jede App selbst, und die Ergebnisse unterscheiden sich schon heute.

| # | Falle | Kolibri | Nyx | Ziel |
| --- | --- | --- | --- | --- |
| S1 | Nutzung zählen | unabhängig vom Ergebnis: Klick-Pfad sendet das Start-Event und zählt dann; Swipe zählt **vor** dem Start – ein fehlgeschlagener Start zählt als Nutzung | zählt nur bei `Launched` | Nyx-Semantik: nur ein erfolgreicher Start zählt |
| S2 | Doppel-Tap-Schutz | 300-ms-Throttle | keiner – Doppel-Tap startet doppelt und zählt doppelt | Kolibris Throttle |
| S3 | Reaktion auf das Ergebnis | vier Zweige: `ComponentGone` → eigener Toast + Log + Neu-Enumeration; `PermissionDenied` und `Failed` → Log + generischer Toast | `ComponentGone` und `PermissionDenied` → gleicher Toast ohne Log; `Failed` → Log + Toast | Kolibris Zweige (erweitert L3) |
| S4 | Wo gestartet wird | eine `launchApp` in `MainActivity` + Swipe-Pfad im Use-Case | `launchApp` in `MainActivity`, aufgerufen von Grid, Dock, Folder, Drawer, Suche | ein `LaunchCoordinator` in `:common-ui`: Throttle → Start → Reaktion → Zählen; Apps rufen nur ihn (Rule 10) |
| S5 | Shortcut-Liste | alle Shortcuts, **auch deaktivierte**, unsortiert, ohne Limit, nur `shortLabel` | nur aktivierte, nach Rang, höchstens 4, `shortLabel ?: longLabel`, mit Icon | Nyx' `AppShortcutSelection` nach `:core`; Limit als Parameter (Kolibri behält vorerst „ohne Limit“) |
| S6 | Shortcut-Start | über (Paket, Id), `catch (Exception)` → `ShortcutLaunchException` | über `ShortcutInfo`, präzise Fehlerklassen (`ActivityNotFoundException` → `silentError`, `IllegalStateException` → `reportToAcra`) | `ShortcutLauncher` mit typisiertem Ergebnis neben `AppLauncher` in `:common-ui`, Nyx' Taxonomie |
| S8 | Sortierung | `sortedByDisplayName()` aus `:core`; Usage-Sort fällt bei Fehler auf alphabetisch zurück | eigene Sortierung (`displayName.lowercase()`), kein Fallback | eine sprachrichtige Sortierung inkl. Usage-Rang in `:core` |
| X2 | Kontextmenü | eigenes Aktionsmodell (`AppContextMenuAction`), inkl. „Nutzung zurücksetzen“ pro App | eigenes Modell (`HomeContextMenuAction`), **ohne** „Nutzung zurücksetzen“, obwohl Nyx nach Nutzung sortiert | gemeinsame Kern-Aktionen (App-Info, Deinstallieren, Aus-/Einblenden, Nutzung zurücksetzen, Shortcuts) + Reihenfolge in `:core`; Produkt-Aktionen (Favorit, Home, Umbenennen) als Erweiterung |

(S7, die Component-Normalisierung, ist als B14 ins Spec gewandert – sie muss vor E1 fertig sein.)

**Sortierung sprachrichtig (entschieden 29.09.).** Heute sortieren beide nach Unicode-Codepunkt, Umlaute landen hinter Z („Ärzte-App“ nach „Zoom“) – ein gemeinsamer Fehler, kein Drift. Die geteilte Sortierung in `:core` nutzt einen `java.text.Collator` mit Stärke SECONDARY: Akzente zählen nach dem Grundbuchstaben („Ä“ direkt bei „A“), Groß-/Kleinschreibung zählt nicht. Sie gilt für App-Namen, Drawer-Ordner-Titel und den Gleichstand-Brecher der Usage-Sortierung. Die Suche bleibt bei `displayNameLower` (Teilstring, kein Sortieren). Vier Festlegungen dazu:

- **Deterministischer Gleichstand.** Bei SECONDARY sind „App“ und „app“ gleich; die Reihenfolge darf dann nicht vom Eingabe-Zufall abhängen. Zweiter Schlüssel ist der `ComponentKey`.
- **Locale als Parameter.** Die Funktion in `:core` bleibt pur und bekommt die Locale übergeben; die App liefert die aktuelle und sortiert beim Sprachwechsel neu.
- **Thread-Sicherheit.** `Collator` ist nicht thread-safe; pro Sortierlauf eine Instanz (`Collator.getInstance(locale)` bzw. `clone()`), nie eine geteilte statische.
- **Vorberechnen.** `AppInfo` hält einen `CollationKey` neben `displayNameLower`, berechnet beim Aufbau der Liste; die Sortierung vergleicht nur Keys.

**Contracts:** `LaunchContract` (je App eine Subklasse) – Doppel-Tap startet einmal, nur Erfolg zählt, jede Ergebnisart hat ihre Reaktion. `ShortcutSelectionTest` – deaktivierte fallen weg, Rang-Ordnung, Limit. `SortTest` – „Ärzte-App“ steht auf Deutsch zwischen „Apotheke“ und „Bahn“; „App“ und „app“ stehen stabil in `ComponentKey`-Reihenfolge; Ergebnis ist unabhängig von der Eingabereihenfolge.

## R3 – Suche & Drawer-Mechanik (E4, entschieden)

Nyx übernimmt Kolibris Suche. Die beiden Drawer-Suchen verhalten sich heute gleich – beide suchen nur im Anzeigenamen. Kolibris Suche im Originalnamen (`includeOriginalName`) nutzt nur der Custom-Names-Screen, nicht der Drawer. Der Unterschied ist Struktur: Kolibris `filterByName` ist die eine Stelle für fünf Aufrufer und nutzt den vorberechneten `AppInfo.displayNameLower`; Nyx hat davon eine Kopie (`LauncherAppSearch`) und von `AppSearchFilter` eine zweite (`DrawerAppSearch`, KDoc „mirrors kolibri's“).

**Umsetzung:** `filterByName` und `AppSearchFilter` wandern als reine Logik nach `:core`; Nyx löscht `LauncherAppSearch` und `DrawerAppSearch`. Voraussetzung: Nyx' Drawer arbeitet auf dem geteilten `AppInfo` statt auf `LauncherApp`. Das Folder-Flattening bei nicht-leerer Suche (`DRAWER_FOLDERS_SPEC` D-3) bleibt beim Nyx-Aufrufer.

**Übriger Drawer:** Schnitt zwischen Mechanik und Darstellung.

- **Geteilt:** Such-Prädikat, Filter/Sortierung (Hidden, Usage-Rank, Custom-Names), Drawer-Overlay und Gesten, Tastatur-Koordination.
- **Pro App:** die Darstellung – Nyx' Icon-Grid mit Drawer-Folders und Dots, Kolibris Textliste.

Notification-Dots und Drawer-Folders bleiben in jedem Fall Nyx-eigen.

## R4 – Event-Indikatoren, Info-Elemente, geteilte Schalter

Der Kern ist geteilt und sauber: `TimeEventFormatter`, `ObserveTimeBasedEventsUseCase`, `TimeBasedEventsRepositoryImpl`, `ClockDelegate`, `EventRowsAdapter`, `openClockApp`/`openCalendarApp`. Gedriftet ist der Klebstoff in den Apps – und dort ist er schon auseinandergelaufen.

| # | Falle | Kolibri | Nyx | Ziel |
| --- | --- | --- | --- | --- |
| I1 | Events-Dialog | ~40 Zeilen in `MainActivity`: oben verankert, Slide-Animation, Dim 0.55, Blur, wallpaper-bewusster Stil, Klick in `runDialogAction` abgesichert | ~40 Zeilen in `MainActivity`: zentriert, OK-Button, Klick **ohne** Absicherung; andere Fehlermeldung (`home_info_no_app` statt `error_activity_not_found`) und anderes Kalender-Icon | ein Dialog-Builder in `:common-ui` mit Kolibris Absicherung; Stil als Parameter |
| I2 | Indikator-Sichtbarkeit | Regel „Alarm/Kalender INVISIBLE, Slot bleibt“ in `HomeFragment` | dieselbe Regel nachgebaut („mirrors Kolibri“) | pure Funktion in `:core` (`EventIndicatorState.from(events)`), Apps binden nur |
| I3 | Lade-Indikatoren (Blitz, Schild) | zeigt `ChargeState` an | ignoriert `ChargeState` | **Lücke (bestätigt):** Nyx bekommt Blitz und Schild; die Regel (höchstens einer sichtbar, GONE am Zeilenende) kommt als pure Funktion neben I2 nach `:core` |
| I4 | Akku-Receiver | Registrierung abgesichert, mit Registriert-Flag | Registrierung in `onResume` ungesichert – ein Fehler dort bringt den Launcher zum Absturz | ein Lifecycle-Helfer am `ClockDelegate` mit Kolibris Absicherung; `ACTION_BATTERY_CHANGED` in App-Code verboten (Forbidden-Import-Liste) |
| I5 | Wo der `ClockDelegate` lebt | im `LauncherViewModel` (übersteht Config-Change, testbar) | in `MainActivity` (neu bei jeder Recreation) | in Nyx' `HomeViewModel` (Rule 10) |
| I6 | Settings-Port `TimeInfoSettings` | in `SettingsRepository`, Keys + Defaults aus `AppConstants` | in `PreferencesRepository`, Keys als Literale (`"show_alarm"`), Default als Literal `false` | Teil von X3 |
| I7 | Settings-Schalter + Kalender-Berechtigung | eigener Flow + Strings | eigener Flow + andere Strings | Teil von R6 |
| I8 | Backup der beiden Schalter | im Kolibri-Schema | im Nyx-Schema | geteilter Backup-Abschnitt `timeInfo`, beide Schemata betten ihn ein (braucht Spec-Phase 2) |
| X3 | Keys und Defaults geteilter Schalter | aus `AppConstants.PrefKeys` / `DEFAULT_*` (z. B. `auto_launch_app`) | Literale (`"search_auto_launch"`, `?: false`) | ein Settings-Store für alle geteilten Schalter (Auto-Start, Usage-Sort, Alarm, Kalender, Tastatur) in `:common-data`, Keys und Defaults einmal – Muster wie `WallpaperDisplaySettings` im Spec |

Nyx ist an einer Stelle besser: es liest Datum und Zone für den Dialog einmal (`LocalDate.now(zone)`), Kolibri zweimal getrennt – das kommt in den geteilten Builder.

**Contract `TimeInfoContract`** (je App eine Subklasse): Indikator-Regeln (Events und Laden); Dialog-Klick bei fehlender App gibt Toast statt Absturz; Receiver-Registrierungsfehler bringt den Launcher nicht zum Absturz.

## R5–R10 – Strukturelle Drift-Fallen

| # | Falle | Befund | Ziel | Paket |
| --- | --- | --- | --- | --- |
| D3 | „Geteilt“, aber nur von Nyx genutzt | Kolibri hat eigene `BaseActivity` (224 statt 165 Zeilen), `DialogDrag`, `ViewFade`, `BottomSheetWindow` (≈ `DialogWindow`), `SwipeDownDismissLayout`/`HomeGestureLayout` (≈ `GestureFrameLayout`). Ein Fix in der geteilten Version erreicht Kolibri nie | Kolibri auf die geteilten Klassen; Kolibris Extras (Toast-Throttle, `UiEvent`) als Hooks in die geteilte `BaseActivity`; Kopien löschen | R10 |
| D5 | Strings | 36 gleiche Keys und 13 gleiche Texte unter anderem Key, inhaltlich schon auseinander (z. B. „Send crash reports“ vs. „Crash reports“, Kalender-Begründung, Factory-Reset) | Strings eines geteilten Features liegen in dessen Modul (EN + DE); Parity-Check auch für Modul-Ressourcen | R6 |
| D6 | Settings-Bausteine | Consent-Eintrag; ACRA-Dev-Kommandos (Kolibri vier inkl. Pipeline-Status, Nyx drei ohne Pipeline-Status, mit anderen Keys); Factory-Reset-Dialog; Kalender-Permission-Flow – je zweimal gebaut | geteilte Preference-Gruppen (XML + Binder) in den Feature-Modulen | R6 |
| D7 | System-Intents | App-Info und Deinstallieren an drei Stellen; Kolibri `ACTION_DELETE`, Nyx `ACTION_UNINSTALL_PACKAGE` | Builder im vorhandenen `SystemLaunch`, eine Variante | R7 |
| D8 | Paket-Event-Brücke | `PackageEventCoordinator` „mirrors Kolibri's `AppManagementDelegate`“ | ein geteilter Coordinator mit App-Hook (Nyx: Icon-Evict + Reconcile) | R8 |
| D9 | Doppelte Repos samt Test-Infra | AppUsage (Kolibri 275 / Nyx 106 Zeilen) und HiddenApps (216 / 59) je mit Interface, Contract, Fake, Impl; dazu `RecordAppLaunchUseCase`, `GetDrawerAppsUseCase`, `UsageDataStore`, `AppLauncherModule` (19-Zeilen-Klon) | einmal in `:common-data`/`:core`, Contract-Triple dort | R5 |
| D11 | Kleine Konstanten | Drawer-Slide-Dauer gespiegelt, Missing-Alpha (L4), Platzhalter für Uhr/Datum/Akku | geteilte `integers.xml`/`dimens.xml` in `:common-ui` | R10 |
| D12 | Hart verdrahtete Dispatcher | Kolibri 30 Stellen (meist `Dispatchers.Main` als Scope-Kontext in Fragmenten), geteilte Module 7, Nyx 6 (verschwinden mit 2b/3b/1c) – **für Nyx erledigt: 0 Einträge seit 3b**. Seit 1a-10 verhindert A13 neue Stellen; die 43 bestehenden stehen in `tools/dispatcher-allowlist.txt` | injizierte `@IoDispatcher` / `@DefaultDispatcher` / `@MainDispatcher`; A13-Liste schrumpft auf 0 | R10 |

(D1, D2, D4 und D10 sind im Spec behandelt: Keep-Regeln, Crash-Bootstrap, Convention-Plugins, Spiegel-Detektor A8.)

## R11 – Verriegeln

**A9 – Geteilt heißt benutzt.** Fängt D3: eine Klasse, die geteilt aussieht, aber nur eine App nutzt, ist ein stiller Fork. Wörtlich „jede öffentliche Klasse muss von beiden Apps referenziert werden“ wäre zu streng, weil Kotlin standardmäßig `public` ist und damit jeder modul-interne Helfer als Verstoß zählte. Deshalb zwei Teile:

- **Explicit API Mode** (`kotlin { explicitApi() }`) für alle `:common-*`/`:feature-*`-Module über das Library-Convention-Plugin. Helfer, die nur das eigene Modul braucht, werden `internal`; alles `public` ist bewusst API.
- **Detektor:** jede `public` Deklaration eines geteilten Moduls muss von beiden Apps erreicht werden – direkt oder über die öffentliche API eines anderen geteilten Moduls, die beide Apps nutzen. Ausnahmen stehen begründet in einer Liste (z. B. Nyx-only-Funktionen wie Notification-Dots, falls sie je geteilt liegen).

Einführung zuerst im Warn-Modus mit Liste der Treffer; scharf erst, wenn R10 (D3) fertig ist.

**Abschluss:**

- A8- und A13-Allowlist sind leer; jeder entfernte A8-Eintrag verweist auf den Contract oder die Forbidden-Regel, die den Ersatz prüft.
- jscpd-Gate (A5) über ganz `kolibri/` ↔ `nyx/` mit kleiner, begründeter Allowlist.
- `MainActivity` in Nyx hält nur noch Glue (Rule 10).
- `ARCHITECTURAL_DIFFERENCES.md` enthält nur noch gewollte Produktunterschiede.

## Nutzersichtbare Änderungen

| App | Änderung | Quelle |
| --- | --- | --- |
| Kolibri | Ein fehlgeschlagener Start zählt nicht mehr als Nutzung (Klick- und Swipe-Pfad) | S1 |
| Kolibri | Shortcut-Liste ohne deaktivierte Shortcuts, nach Rang sortiert | S5 |
| Kolibri | Nutzt die geteilten Basisklassen (Dialog-, Fade-, Gesten-Verhalten kann sich in Details ändern) | D3 |
| beide | Sortierung sprachrichtig: Umlaute bei ihrem Grundbuchstaben | S8 |
| beide | Deinstallieren über eine einheitliche Intent-Variante | D7 |
| Nyx | Doppel-Tap startet nur einmal | S2 |
| Nyx | Verschwundene Apps beim Kaltstart sofort grau; Start einer verschwundenen App löst Neu-Enumeration aus | L3, L6 |
| Nyx | Lade-Indikatoren (Blitz, Schild) | I3 |
| Nyx | „Nutzung zurücksetzen“ im Kontextmenü | X2 |
| Nyx | Events-Dialog im Kolibri-Stil-Rahmen (Stil als Parameter), Klick abgesichert | I1 |
| beide | Grau-Darstellung mit einheitlichem Alpha | L4 |

## Akzeptanz der Roadmap

- [ ] Alle Contracts grün in beiden Apps: `LazyVerifyContract`, `LaunchContract`, `ShortcutSelectionTest`, `SortTest`, `TimeInfoContract`, plus die Contract-Triples der aus D9 geteilten Repos.
- [ ] Kein `when` auf `AppLaunchResult.ComponentGone` in App-Code; `ACTION_BATTERY_CHANGED` nicht in App-Code.
- [ ] A9 scharf, Ausnahmeliste begründet.
- [ ] A8-Allowlist leer.
- [ ] jscpd `kolibri/` ↔ `nyx/` (`src/main`, ≥ 8 Zeilen / 50 Tokens) nur noch mit begründeter Allowlist.
- [ ] Device-Smoke beider Apps: Start aus Grid, Dock, Folder, Drawer, Suche; Doppel-Tap; Start einer deinstallierten App; Sprachwechsel mit Neusortierung.
