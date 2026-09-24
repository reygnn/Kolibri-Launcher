# Unity-Launcher (Kolibri + Nyx) — geteilte TODO / Roadmap

Cross-cutting-Themen, die **beide** Launcher betreffen — typischerweise die geteilten
Module (`:core`, `:common-data`, `:common-ui`, `:common-android`,
`:feature-crashreporting`) oder eine Konvention, die für Kolibri *und* Nyx gilt.
Launcher-spezifisches bleibt in `kolibri/TODO.md` bzw. `nyx/TODO.md`; diese Datei ist die
gemeinsame Ebene darüber.

---

## Rule-9-Nuance: `silentError` vs. `reportToAcra` an fail-safe-to-keep-Grenzen (2026-09-24)

**Gilt für beide Launcher — der Code lebt im geteilten `:common-data`.**

**Merke (die Regel):** `TimberWrapper.silentError` **wirft in DEBUG** (`crashInDebug`) — es ist der
Kanal für *gefangene Programmierfehler*, die im Dev-Build laut auffallen sollen. Für eine
**fail-safe-to-keep-Grenze**, deren Vertrag »gib in **jedem** Build den sicheren Default zurück«
lautet (System-API-Boundary, environmental, self-healing), ist es das falsche Werkzeug: der
DEBUG-Throw bricht genau diesen Vertrag — der Default wird nie erreicht, der Throw entkommt und
wird oben nur mislabeled/abgebrochen weitergereicht. Dort gehört `reportToAcra` hin
(RELEASE-Signal via ACRA, **kein** DEBUG-Throw).

Kurzformel:

- **erwarteter, environmental-transienter, upstream-behandelter Fehler → `reportToAcra`**
- **echter Programmierfehler, der laut werden soll → `silentError`**

**Der konkrete Fund (AUDIT-1 F7 Medium-Review, Branch `fix/f7-partial-enumeration-guard`):** die
drei fail-safe-to-keep-Catches der geteilten Presence/Session-Seams —
`PackageManagerPresence.isComponentPresent` / `isPackagePresent` (→ `true`) und
`PackageManagerInstallSessions.activeSessionPackages` (→ `null`) — nutzten `silentError`. In einem
DEBUG-On-Device-Build gab ein transienter PackageManager-/PackageInstaller-Fehler damit **nicht**
den fail-safe-Default zurück, sondern warf; der Throw entkam der Methode und wurde upstream nur
gefangen (Nyx: fehletikettiert als `SkipReason.STORE_FAILED`; Kolibri: `runCleanup` bricht den
betroffenen Store ab). Fail-safe in der Richtung (kein Datenverlust, ein übersprungener Pass statt
eines zusätzlichen Prunes), aber es machte den dokumentierten Vertrag der Seams (»resolves to
present/null«) **und** die Reconcile-KDoc (»the gate arms … swallow their own platform errors,
rethrowing only cancellation«) in DEBUG falsch. **Fix:** alle drei Sites auf `reportToAcra`
umgestellt — der Vertrag hält jetzt in jedem Build, RELEASE behält das ACRA-Signal, beide Launcher
sind gleich betroffen (ein geteiltes Impl).

**Querverweise:**

- Nyx: `ReconcileHomeLayoutUseCase`s `STORE_FAILED`-Catch (fix 4 in `nyx/TODO.md`,
  F7-Gate-Sektion) traf dieselbe Wahl aus demselben Grund — der Auslöser, an dem die Inkonsistenz
  auffiel.
- Kolibri: die kanonische Rule-9-Historie (report-by-intent, `silentError`/`reportToAcra`/
  `silentDeath`-Kontrakt) steht in `kolibri/CLAUDE.md` Rule 9 und `kolibri/TODO.md` §2/§23; diese
  Notiz ist die praktische Ergänzung *„an einer fail-safe-Grenze ist es reportToAcra, nicht
  silentError"*.
- Enforcement: der Intent-Gate-Linter (`kolibri/tools/check-intent-gate.awk`) fängt **bare**
  `Timber.e(`, aber **nicht** ein falsch gewähltes `silentError` an einer fail-safe-Grenze — das
  bleibt eine Review-/Konventions-Frage, daher dieser Eintrag.

---

## Drift-Prävention App-Verwaltung: geteilter Gate-Helfer + Cross-Launcher-Parity-Test (offen, 2026-09-24)

**Ausgangslage (gemessen 2026-09-24, LOC ohne Tests).** Die App-Verwaltung (Enumeration → Laden
→ In-RAM-State → Delete-Gate → Reconcile der User-Zuweisungen) ist zu **~50 %** geteilt: die
**Engine** (`:core`-Ports/Modelle + `:common-data/installedapps`, ~1150 LOC) läuft in beiden
Launchern als *ein* Impl (100 % geteilt), die **Policy-Schicht** (~1400 LOC) ist zu 0 % geteilt.
Letzteres ist fundamental — nyx verwaltet ein räumliches `HomeLayout` (Positionen/Folder/Dock),
kolibri vier flache Key-Sets (favorites/hidden/swipe/customnames) —, aber es bedeutet, dass das
Delete-Gate **zweimal orchestriert** ist (`nyx ReconcileHomeLayoutUseCase` inline vs. kolibri
`ObserveInstalledAppsUseCase` + `PassSessionGate` über vier Store-Reconciles). Diese Duplikation
ist der Haupt-Drift-Vektor: Session-Arm, `null→keep`-Fail-safe, malformed-key-Kurzschluss und
Presence-vor-Session-Reihenfolge stehen doppelt, und **nichts erzwingt Gleichheit** — Patch 12
(Session-Arm nach kolibri nachziehen) und die schiefe Test-Parität waren genau das. Der
wahrscheinlichste nächste Auslöser ist der in beiden `ACCEPTED_LIMITATIONS.md` genannte
keep-last-good-**count-floor**: landet er nur in einem Launcher, öffnet sich das akzeptierte
Restore-Restrisiko wieder einseitig.

Zwei Hebel dagegen (a reduziert die Duplikation, b macht Rest-Drift laut):

### a) ✅ Erledigt (2026-09-24, Branch `refactor/shared-deletion-gate`)

Umgesetzt als `core/DeletionGatePass` — eine per-Pass-Instanz, die `AppPresence` + `InstallSessionInspector`
kapselt und `keepComponent(ComponentKey)` / `keepPackage(String)` anbietet (Presence zuerst,
Session-Set **einmal** pro Pass lazy gelesen, `null→keep`). nyx' inline `sessionsRead`-Schleife und
kolibris `PassSessionGate` + beide Bridges (`isFlatComponentPresentOrRestoring` /
`isPackagePresentOrRestoring`) sind entfernt; beide Reconciles rufen jetzt denselben Helfer. Kolibri
behält nur einen 3-Zeilen-Bridge `DeletionGatePass.keepFlatComponent` (flat-String → parse →
`keepComponent`, malformed → prune), weil seine Store-Keys flache Strings sind. Getrennt bleiben
(fundamental): Kandidaten-Finden (`referencedKeys` vs Store-Orphans), Modell-Anwendung (atomares
`update{}` vs subtract-`edit{}`), das snapshot→RMW-Fenster. Direkt gepinnt durch
`core/DeletionGatePassTest` (8 Fälle: present→reads==0, absent+session→keep, absent+session-less→prune,
undetermined→keep, beide Grains, batching reads==1). Der Fail-safe-Kern (inkl. eines künftigen
count-floors) lebt jetzt an **einer** Stelle. Gate grün.

<details><summary>Ursprünglicher Plan (Referenz)</summary>

Heute steckt „fehlender Key → nur Kandidat → keep, wenn Presence **oder** Session anschlägt (beide
fail-safe), sonst prune" in beiden Policies als eigener Code. Ziel: die **reine, Android-freie**
Gate-Logik einmal in `:core` (neben `AppPresence`/`InstallSessionInspector`), sodass die
Policy-Schicht nur noch zwei launcher-eigene Enden liefert:

- **Input:** die Menge der vom Store referenzierten Keys (nyx: `HomeLayout.referencedKeys()`;
  kolibri: die vier Store-Key-Sets) + die frische Enumeration.
- **Shared:** Kandidaten = referenziert ∧ nicht enumeriert; pro Kandidat `AppPresence` (grain-korrekt)
  ODER die **einmal pro Pass** gelesene `InstallSessionInspector`-Menge; `null→keep`; malformed →
  absent. Rückgabe: „diese Keys prunen".
- **Output:** jeder Launcher wendet das Prune-Set auf sein eigenes Modell an (nyx im atomaren
  `update{}`, kolibri per subtract-only `edit{}` je Store).

Damit lebt der Fail-safe-Kern (inkl. eines künftigen count-floors) an **einer** Stelle; die
Policy-Schicht schrumpft auf „liefere Keys / wende Ergebnis an". `PassSessionGate` und die inline
`sessionsRead`-Mechanik in nyx werden durch den Helfer ersetzt. Akzeptanzkriterium: nach der
Extraktion referenzieren **beide** Reconcile-Eingänge denselben Helfer; kein zweites `null→keep`
oder `session-read-once` mehr im Repo-/UseCase-Code.

</details>

### b) Cross-Launcher-Parity-Test für das Delete-Gate (jetzt billig — nach a)

Heute testen nyx und kolibri ihr Gate **unabhängig** — eine einseitige Semantik-Änderung wird nicht
rot. Ziel: **eine** Tabelle von Gate-Szenarien (present / absent+session / absent+no-session /
undetermined / malformed / partial-load / store-read-fail), die gegen **beide** Reconcile-Eingänge
läuft und identisches keep/prune-Verhalten (plus `reads==1`/`reads==0`) assertet. Zwei Formen
möglich:

- **Nach (a):** ein JVM-Contract-Test direkt gegen den geteilten Helfer (deckt den Kern ab) +
  je ein dünner Adapter-Test pro Launcher (liefert-Keys / wendet-an).
- **Ohne (a):** ein parametrisierter Test, der dieselbe Szenario-Liste einmal durch
  `ReconcileHomeLayoutUseCase` und einmal durch `ObserveInstalledAppsUseCase` schickt und die
  Ergebnisse vergleicht.

Akzeptanzkriterium: eine bewusst eingebaute Divergenz (z. B. `null→keep` in nur einem Launcher auf
`null→prune` kippen) macht den Parity-Test rot. Ergänzt die bestehenden per-Launcher-Suites, ersetzt
sie nicht.

**Reihenfolge:** (a) zuerst macht (b) fast trivial (ein Contract gegen den Helfer). (b) allein geht
auch und ist billiger, fängt aber nur Divergenz ab, statt sie strukturell zu verhindern. Beides ist
echter Aufwand und braucht einen eigenen Branch — kein Drive-by.
