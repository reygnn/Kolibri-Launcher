# ACRA_SPEC.md

**Implementierungs-Spec zum ACRA-Rewrite — die Signatur-/Test-/Konfig-Schicht unter `ACRA_FLOW.md`.**

`ACRA_FLOW.md` ist die konvergierte Ziel-*Architektur* (Prinzipien, Invarianten,
Gabelungen G1–G4 entschieden). Dieses Dokument ist die Schicht darüber, die aus der
Architektur *baubaren* Code macht: konkrete Signaturen, Serialisierungsformat,
DI-Verdrahtung, das Test-Inventar (welche Datei welchen `@Test` trägt) und die
Linter-Deltas. Wo `ACRA_FLOW.md` sagt *was und warum*, sagt diese Spec *wie genau*.

**Verhältnis zum heutigen Code.** Der Rewrite ersetzt die verstreute Ist-Struktur
(`ui/util/`, `core/`, `domain/*`, in `KolibriLauncherApp.kt` genestet) durch das
`crashreporting/`-Paket aus `ACRA_FLOW.md` §9. Namensänderungen sind in der
jeweiligen Mapping-Tabelle festgehalten, damit der Umbau nachvollziehbar bleibt.
`ACRA_FLOW.md` ist bewusst grüne Wiese („keine Bestandsinstallationen"); diese Spec
benennt dort, wo die **reale** App das nicht ignorieren kann, die Konsequenz als
markierte Entscheidung (`SPEC-DECISION`).

**Reihenfolge.** Belang A (Consent) zuerst — reifster Belang, Fundament für B/C.
Belang B (Ingestion) und C (Resilienz) folgen als eigene Abschnitte.

Verweise `A1..A8`, `SR/SW/SD/RC`, `§n` zeigen auf `ACRA_FLOW.md`.

Status: **Belang A — Entwurf.** B, C — offen.

---

## A. Belang A — Consent

### A.0 Namens- & Paket-Mapping (heute → Rewrite)

| Heute (Ist) | Rewrite (`crashreporting/`) | Modul | Änderung |
|---|---|---|---|
| `domain.model.CrashReportConsentState` (2 Booleans) | `ConsentDecision` (sealed tristate) | :domain | **ersetzt** (A8) |
| `domain.model.ConsentReadResult` (`Loaded(state)`) | `ConsentReadResult` (`Loaded(decision)`) | :domain | Loaded trägt jetzt `ConsentDecision` |
| `domain.model.ConsentWriteResult` | `ConsentWriteResult` | :domain | unverändert (`Saved`/`Failed`) |
| `domain.repository.CrashReportConsentRepository` | `CrashReportConsentRepository` | :domain | Name bleibt; Signatur angepasst |
| `data.CrashReportConsentRepositoryImpl` | `CrashReportConsentRepositoryImpl` | :data | ein String-Key statt zwei Booleans |
| `data.CrashReportConsentStore` (object) | `ConsentBootstrap` (object) | :data | + Prozess-Gate (X2), ein Key |
| `ui.util.CrashReportConsentController` | `ConsentController` | :app | Name; Logik ~gleich |
| `ui.util.CrashReportConsent` (object) | `ConsentDialog` (object) | :app | Name; `silentError` statt plain Timber |
| `ui.util.CrashReportToggle`(+Impl) | `AcraToggle`(+Impl) | :app | **+ Queue-Purge (A7)** |
| `ui.util.ConsentSaveFailureNotifier`(+Impl) | `ConsentSaveFailureNotifier`(+Impl) | :app | unverändert |
| `usecase.Get/Set/GetCrashReportConsentState…` | (bleiben; Rückgabetypen folgen) | :domain | Signatur folgt den neuen Modellen |

Zielpakete (`ACRA_FLOW.md` §9):
```
:domain  crashreporting/consent/   ConsentDecision, ConsentReadResult,
                                    ConsentWriteResult, CrashReportConsentRepository
:data    crashreporting/consent/   CrashReportConsentRepositoryImpl, ConsentBootstrap
:app     crashreporting/consent/   ConsentController, ConsentDialog, AcraToggle(+Impl),
                                    ConsentSaveFailureNotifier(+Impl)
```
Namespace-Basis unverändert `com.github.reygnn.kolibri_launcher` (…`.data` in :data).
Der `@ConsentDataStore`-Qualifier + `Context.consentDataStore`-Extension +
DataStore-Name `AppConstants.CONSENT_DATASTORE_NAME` (`"acra_consent"`, eigener
Backing-File, backup-excluded) bleiben **wie heute**.

---

### A.1 Domänenmodelle (`:domain`, pure Kotlin)

```kotlin
// crashreporting/consent/ConsentDecision.kt
/** Genau ein Fakt, tristate. Ersetzt das illegale-Zustände-Paar (A8, §3.1). */
sealed interface ConsentDecision {
    data object NeverAsked : ConsentDecision   // Default; Key abwesend
    data object Granted    : ConsentDecision
    data object Denied     : ConsentDecision
}
```

```kotlin
// crashreporting/consent/ConsentReadResult.kt
/** Kein lügender Read (A2): Unavailable bleibt von NeverAsked/Denied unterscheidbar. */
sealed interface ConsentReadResult {
    data class Loaded(val decision: ConsentDecision) : ConsentReadResult
    data class Unavailable(val cause: Throwable)     : ConsentReadResult
}
```

```kotlin
// crashreporting/consent/ConsentWriteResult.kt
/** Fehlgeschlagener Write ist sichtbar (A4): nie stilles Unit. */
sealed interface ConsentWriteResult {
    data object Saved                        : ConsentWriteResult
    data class  Failed(val cause: Throwable) : ConsentWriteResult
}
```

Alle drei: pure Kotlin, kein Android-Import, kein Parcelable (Modul ist `kotlin("jvm")`).

---

### A.2 Serialisierung (der Persistenz-Rand)

**Ein** String-Preference-Key trägt den tristate (A8 reicht bis auf die Platte):

```kotlin
// in ConsentBootstrap (geteilt mit dem Repo-Impl)
val CONSENT_DECISION_KEY = stringPreferencesKey("consent_decision")
const val VALUE_GRANTED = "GRANTED"
const val VALUE_DENIED  = "DENIED"
```

| Gelesener Wert | R1 (Bootstrap) | R2 (`readState`) |
|---|---|---|
| Key **abwesend** | `NeverAsked` → ACRA AUS | `Loaded(NeverAsked)` → `ShowDialog` |
| `"GRANTED"` | `Granted` → ACRA AN | `Loaded(Granted)` → `Reaffirm(true)` |
| `"DENIED"` | `Denied` → ACRA AUS | `Loaded(Denied)` → `Reaffirm(false)` |
| **unbekannter** String (SR5) | nicht-`Granted` → ACRA AUS | `Unavailable` → `Skip` |
| Read wirft `IOException` (SR2) | wie Fehler → ACRA AUS | `Unavailable` → `Skip` |
| `CancellationException` (A6) | propagiert | propagiert |

Abwesenheit *ist* der Default — kein Sentinel, kein null-Boolean. Der
Unknown-Token → `Unavailable` gilt **nur an R2** (dort folgt der Dialog, der nicht
lügen darf, A2); an R1 genügt „nicht `Granted` ⇒ AUS", weil dort **kein** Write und
**kein** Dialog folgt, nur `setEnabled`.

> **SPEC-DECISION A-1 (Migration von zwei Booleans) — ENTSCHIEDEN: kein
> Migrationscode, bewusstes Re-Ask.**
> `ACRA_FLOW.md` ist grüne Wiese; die reale App hat aber Installs mit
> `acra_has_consent`/`acra_has_asked`. Der neue `consent_decision`-Key ist bei
> denen **abwesend** → `NeverAsked` → **ACRA AUS + Dialog erneut**. Das ist gewollt:
> fail-closed ist privacy-safe (ein zuvor Zustimmender wird nur *erneut gefragt*,
> nichts leakt), passt zur grüne-Wiese-Haltung und zu A5 („frische Installation →
> neu fragen"). Kein `DataMigrationManager` kehrt zurück.
> - **Kosten (akzeptiert):** einmaliger erneuter Consent-Dialog für Bestands-
>   Zustimmende nach dem Update.
> - **Verwaiste Alt-Keys (benannt, harmlos):** `acra_has_consent`/`acra_has_asked`
>   bleiben als tote Einträge im `acra_consent`-Store liegen — das neue Repo liest
>   nur `consent_decision`. Kein Cleanup nötig; *optional* darf `setConsent` sie im
>   selben `edit {}`-Block beiläufig entfernen (eine Zeile, kein Migrations-*Manager*),
>   wenn Aufräumen gewünscht ist. Gehört als Fußnote nach `HISTORY.md`.

---

### A.3 Repository (`:domain` Interface, `:data` Impl)

```kotlin
// crashreporting/consent/CrashReportConsentRepository.kt  (:domain)
interface CrashReportConsentRepository {

    /** Tristate-Read. Wirft NIE für I/O (A2). Unknown-Token → Unavailable (SR5).
     *  CancellationException propagiert (A6). */
    suspend fun readState(): ConsentReadResult

    /** Best-effort-Write. granted=true→GRANTED, false→DENIED. Nie stilles Unit (A4).
     *  CancellationException propagiert (A6). */
    suspend fun setConsent(granted: Boolean): ConsentWriteResult

    /** Display-only Getter, fallen bei jeder Unsicherheit auf false zurück (SR4),
     *  weil kein Write folgt. Abgeleitet aus der Decision:
     *    hasConsent := decision == Granted
     *    hasAsked   := decision != NeverAsked            */
    suspend fun hasConsent(): Boolean
    suspend fun hasAsked(): Boolean
}
```

**Impl (`:data`)** — `@Singleton class CrashReportConsentRepositoryImpl @Inject
constructor(@param:ConsentDataStore private val dataStore: DataStore<Preferences>)`.
- `readState()`: `dataStore.data.first()` → Key mappen (Tabelle A.2). `catch
  (IOException)`/`catch (Exception)` → `Unavailable(e)`; `CancellationException`
  **rethrow zuerst**. Unknown-String → `Unavailable(IllegalStateException("unknown
  consent token"))`.
- `setConsent(granted)`: `dataStore.edit { it[KEY] = if (granted) GRANTED else
  DENIED }` → `Saved`; `catch` → `Failed(e)`; `CancellationException` rethrow.
- `hasConsent()/hasAsked()`: über `readState()`; alles außer `Loaded` → `false`.
- Nutzt `TimberWrapper.silentError` (post-Hilt-Pfad, **nicht** Rule-9-Ausnahme).

**`internal`-Grenze:** der Impl-Konstruktor bleibt öffentlich genug für
`*ImplContractTest` in `data/src/test` (kein `internal`, ggf.
`@VisibleForTesting`).

---

### A.4 Bootstrap (`ConsentBootstrap`, `:data`, pre-Hilt, R1)

```kotlin
// crashreporting/consent/ConsentBootstrap.kt  (:data)
object ConsentBootstrap {
    val CONSENT_DECISION_KEY = stringPreferencesKey("consent_decision")
    const val VALUE_GRANTED = "GRANTED"
    const val VALUE_DENIED  = "DENIED"

    /** R1: pre-Hilt, HAUPTPROZESS-only, synchron aus attachBaseContext aufgerufen.
     *  Nur GRANTED liefert Granted; alles andere (absent/unknown/error) → nicht-Granted.
     *  Liest über die context.consentDataStore-Extension. Plain Timber.e (Rule 9). */
    suspend fun readDecision(context: Context): ConsentDecision

    @VisibleForTesting
    suspend fun seedDecision(context: Context, decision: ConsentDecision) // androidTest-Seed
}
```

Aufruf in `attachBaseContext` (`ACRA_FLOW.md` §3.5, Sequenz-Invariante §12·1):
```kotlin
ACRA.errorReporter.setEnabled(false)                       // 1. sofort nach init, ohne Zwischenanweisung
if (!ACRA.isACRASenderServiceProcess()) {                  // 2. Prozess-Gate (X2)
    val decision = runBlocking { ConsentBootstrap.readDecision(base) }   // 3. sync, kontrollierter Punkt
    if (decision == ConsentDecision.Granted) ACRA.errorReporter.setEnabled(true)
}
```
- **Prozess-Gate (X2):** neu ggü. heute — der ganze Block (Read + Toggle) läuft nur
  im Hauptprozess. Im `:acra`-Prozess ist `errorReporter` ohnehin Stub; der Read
  entfällt dort ersatzlos.
- **StrictMode:** der `runBlocking`-Disk-Read ist die dokumentierte
  DiskReadViolation (`KNOWN_ISSUES.md`, §11/G4). Erwartete Dauer einstellig-ms
  (Messung offen, G4-Rest-To-do).
- **Rule 9:** `ConsentBootstrap` läuft vor Hilt/ACRA → plain `Timber.e`, **nicht**
  `silentError`. Linter-Whitelist-Eintrag nötig (siehe A.8).

---

### A.5 Controller (`ConsentController`, `:app`)

```kotlin
// crashreporting/consent/ConsentController.kt  (:app)
@Singleton
class ConsentController @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val repository: CrashReportConsentRepository,
    private val acraToggle: AcraToggle,
    private val saveFailureNotifier: ConsentSaveFailureNotifier,
) {
    sealed interface StartupAction {
        data object ShowDialog                       : StartupAction
        data class  Reaffirm(val granted: Boolean)   : StartupAction
        data object Skip                             : StartupAction
    }

    /** R2-Gate. readState() → Aktion (rein, kein Seiteneffekt). */
    suspend fun resolveStartupAction(): StartupAction

    /** Echter Nutzer-Tap (Erst-Dialog W1 / Settings W2). Sofort in-memory togglen (A1),
     *  bei Revoke Queue purgen (A7), dann persistieren. */
    fun applyConsent(granted: Boolean)

    /** Rekonziliation ohne Persist (§3.3). Idempotenter setEnabled. */
    fun reaffirmConsent(granted: Boolean)

    /** Persist auf ApplicationScope; inspiziert ConsentWriteResult; bei Failed → Notifier (A4). */
    fun persistConsent(granted: Boolean)

    /** Summary-Anzeige (§8b). Kann Unavailable liefern; Caller rendert alten Wert weiter. */
    suspend fun currentDecision(): ConsentReadResult
}
```

**`resolveStartupAction()`** (rein):
```
readState() →
  Loaded(NeverAsked) → ShowDialog
  Loaded(Granted)    → Reaffirm(true)
  Loaded(Denied)     → Reaffirm(false)
  Unavailable        → Skip          // A2: kein Dialog, kein Write, ACRA behält Bootstrap-Wert
```

**`applyConsent(granted)`**:
```
acraToggle.setEnabled(granted)          // sofort, in-memory (A1)
if (!granted) acraToggle.purgeReportQueue()   // Revoke ist destruktiv (A7)
persistConsent(granted)
```

**`persistConsent(granted)`** — auf `scope` (überlebt UI-Teardown, SW2/A6):
```
scope.launch {
    when (repository.setConsent(granted)) {
        Saved     -> Unit
        is Failed -> saveFailureNotifier.notifySaveFailed()   // A4
    }
}
```

**`reaffirmConsent(granted)`**: `acraToggle.setEnabled(granted)` — **kein** Persist
(Entscheidung steht schon, §3.3), **kein** Purge bei `false` (RC2: „R1=AN, R2=Denied"
ist nicht erreichbar; nichts war an).

---

### A.6 Seams (`:app`)

```kotlin
// crashreporting/consent/AcraToggle.kt
interface AcraToggle {
    fun setEnabled(enabled: Boolean)      // → ACRA.errorReporter.setEnabled
    fun purgeReportQueue()                // → BulkReportDeleter(ctx), BEIDE Ordner (A7, §13)
}
class AcraToggleImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AcraToggle {
    override fun setEnabled(enabled: Boolean) { ACRA.errorReporter.setEnabled(enabled) }
    override fun purgeReportQueue() {
        val deleter = BulkReportDeleter(context)
        deleter.deleteReports(true,  0)   // approved
        deleter.deleteReports(false, 0)   // unapproved — sonst räumt nur die Hälfte (§13)
    }
}
```
> Ggü. heute: `CrashReportToggle` bekommt die **Purge**-Verantwortung dazu (heute
> fehlt sie ganz). Hält den Seiteneffekt in `:app`, Controller bleibt JVM-testbar.

```kotlin
// crashreporting/consent/ConsentDialog.kt
object ConsentDialog {
    /** Baut/zeigt den Dialog. onResult NUR bei echtem Button-Tap (A3), sonst nie.
     *  Gibt null zurück bei Nicht-Activity-Kontext / finishing Activity (SD1/SD2).
     *  setCancelable(false); in currentDialog trackbar für Dismiss on destroy (SD3/SD4).
     *  Off-Bootstrap-Pfad → catches nutzen silentError (nicht plain Timber). */
    fun show(activityContext: Context, onResult: (granted: Boolean) -> Unit): AlertDialog?
}
```

```kotlin
// crashreporting/consent/ConsentSaveFailureNotifier.kt   (unverändert ggü. heute)
interface ConsentSaveFailureNotifier { suspend fun notifySaveFailed() }
class ConsentSaveFailureNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @MainDispatcher private val dispatcher: CoroutineDispatcher,
) : ConsentSaveFailureNotifier   // Toast via showToastSafe
```

---

### A.7 DI-Deltas

- **`RepositoryModule` (`:data`)** — bleibt: `@Binds @Singleton
  bindCrashReportConsentRepository(impl): CrashReportConsentRepository`.
- **`DataStoreModule` (`:data`)** — unverändert (`@ConsentDataStore` + `provideConsentDataStore`).
- **`AppModule` (`:app`)** — `provideConsentSaveFailureNotifier` bleibt;
  `provideCrashReportToggle` → **umbenannt** `provideAcraToggle(impl: AcraToggleImpl):
  AcraToggle`. `AcraToggleImpl` braucht jetzt `@ApplicationContext` (für Purge).
- **`ConsentController`** ist `@Singleton @Inject` — kein Modul-Eintrag nötig.
- Use cases (`Get/Set…`) bleiben `@Inject`, Rückgabetypen folgen A.1/A.3.

---

### A.8 Test-Inventar (Contract-Tripel an den REALEN Pfaden)

Pfade aus der Ist-Kartierung (nicht CLAUDE.mds veraltete Angabe):

| Rolle | Pfad |
|---|---|
| Abstrakter Contract | `domain/src/testFixtures/java/…/data/CrashReportConsentRepositoryContract.kt` |
| Fake | `domain/src/testFixtures/java/…/fakes/FakeCrashReportConsentRepository.kt` |
| Fake-Contract-Test | `domain/src/test/java/…/data/FakeCrashReportConsentRepositoryContractTest.kt` |
| Impl-Contract-Test | `data/src/test/java/…/data/CrashReportConsentRepositoryImplContractTest.kt` |
| Impl-only Failure-Test | `data/src/test/java/…/data/CrashReportConsentRepositoryImplTest.kt` |
| `FakeDataStore` | `data/src/testFixtures/java/…/fakes/FakeDataStore.kt` |
| Controller-Test | `app/src/test/java/…/crashreporting/consent/ConsentControllerTest.kt` |
| Dialog-Test (Robolectric) | `app/src/testDebug/java/…/crashreporting/consent/ConsentDialogTest.kt` |

**Contract (fake + impl, nur SUCCESS-Shapes — §Rule 11-Notiz):**
| `@Test` | pinnt |
|---|---|
| `fresh repository → Loaded(NeverAsked)` | A8, Default |
| `setConsent(true) → Saved; readState → Loaded(Granted)` | A4-Success, A8 |
| `setConsent(false) → Saved; readState → Loaded(Denied)` | A8 |
| `hasConsent true only when Granted` | Getter-Ableitung |
| `hasAsked false only when NeverAsked` | Getter-Ableitung |
| `CancellationException propagiert (read & write)` | A6 |

**Impl-only (Failure-Branches, nur `…ImplTest`, nicht im Contract):**
| `@Test` | pinnt |
|---|---|
| `readState IOException → Unavailable` | A2, SR2 |
| `readState unbekannter Token → Unavailable` (nicht `NeverAsked`) | A2, SR5 |
| `setConsent write wirft → Failed` | A4, SW1 |
| `readState/​setConsent Cancellation → rethrow` (falls nicht im Contract abgedeckt) | A6 |

**Controller-Test (JVM, MockK, `MainDispatcherRule`):**
| `@Test` | pinnt |
|---|---|
| `resolveStartupAction: Loaded(NeverAsked) → ShowDialog` | Gate |
| `… Loaded(Granted) → Reaffirm(true)` | RC-Happy |
| `… Loaded(Denied) → Reaffirm(false)` | RC2 |
| `… Unavailable → Skip` | A2 |
| `applyConsent(true): toggle.setEnabled(true), kein Purge, setConsent aufgerufen` | A1 |
| `applyConsent(false): toggle.setEnabled(false) UND purgeReportQueue()` | A7 |
| `persistConsent: setConsent→Failed ruft notifySaveFailed` | A4 |
| `reaffirmConsent(false): setEnabled(false), KEIN Purge, KEIN Persist` | RC2, §3.3 |
| `persist läuft auf ApplicationScope (UI-Teardown cancelt nicht)` | SW2, A6 |

**Dialog-Test (Robolectric, `app/src/testDebug`):**
| `@Test` | pinnt |
|---|---|
| `positiver Tap → onResult(true)` | A3 |
| `negativer Tap → onResult(false)` | A3 |
| `Nicht-Activity-Kontext → null, onResult feuert nicht` | A3, SD1 |
| `show() wirft (finishing) → null, kein onResult` | A3, SD2 |
| `Activity vor Tap zerstört → dismissed, kein onResult` | A3, SD3/SD4 |

`FakeCrashReportConsentRepository` spiegelt den Impl (immer `Saved`/`Loaded`,
nie Failure) — Rule 3: bei Drift gewinnt der Impl. Failure-Shapes sind
impl-only (Rule 11-Notiz in `ACRA_FLOW.md`).

---

### A.9 Linter-Delta (Consent-Teil)

- **`tools/check-conventions.sh` Rule-9-Whitelist** (Z. 79): `CrashReportConsentStore\.kt`
  → **`ConsentBootstrap\.kt`** umbenennen (bleibt auf dem pre-Hilt-Pfad, plain
  `Timber.e`). Kein weiterer Consent-Eintrag: `ConsentDialog` nutzt `silentError`
  (off-bootstrap), Repo-Impl nutzt `silentError`.
- **Rule-11-Whitelist** (`rule11_files`, Z. 179–182): Consent-Seite bringt keine
  broad catches mit Marker-Pflicht (die Impl-`catch (Exception)` sind das
  Fehler-als-Wert-Muster, kein `Throwable`-Catchall) — **kein** Eintrag nötig,
  solange keine `Throwable`-Fänge dazukommen.
- `src_roots` deckt `crashreporting/` automatisch ab (drei `src/main/java`-Roots).

---

## B. Belang B — Ingestion   *(offen — nächster Slice)*

## C. Belang C — Resilienz   *(offen)*
