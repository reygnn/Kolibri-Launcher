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
