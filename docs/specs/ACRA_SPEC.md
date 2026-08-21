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

Status: **Alle Belange als Entwurf komplett — A (Consent), B (Ingestion),
C (Resilienz) + S (Server-Vertrag).** Offene Punkte sind ausdrücklich benannte
Nicht-Blocker (SPEC-DECISION B-1/C-1, zwei Messungen); siehe Doc-Ende.

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

## S. Server-Vertrag (harte Vorbedingung für Belang B)

`ACRA_FLOW.md` B3/§4.3 verschiebt die **gesamte** Flut-Kontrolle server-seitig: der
Client drosselt nichts, sendet jeden consent-gateten Report. Damit ist der Server
kein Implementierungsdetail, sondern eine **externe Abhängigkeit, ohne die das
Client-Design nicht trägt**. Dieser Abschnitt ist der Vertrag: was der (private,
selbstgehostete) Server erfüllen **muss**, bevor Belang B darauf baut. `MUSS` =
tragende Invariante; `SOLL` = starke Empfehlung.

> **SPEC-DECISION S-1 (Backend-Produkt) — ENTSCHIEDEN: Custom/eigener Ingest.**
> Der Server ist ein selbstgebauter Endpoint (kleiner Server + DB), kein fertiges
> Backend (Acrarium o. Ä.). Damit ist **jeder** `MUSS`-Punkt unten eine
> **Bauvorgabe**, kein Konfig-Schalter — dieser Vertrag *ist* die Spec des Ingest.
> Der Server lebt **außerhalb** dieses Repos; der Vertrag ist die Schnittstelle,
> die Client und Server gemeinsam ehren. Empfohlene Pipeline-Reihenfolge: **S.10**.

### S.0 Rolle & Grenze

- **Einziger Flut-Kontrollpunkt (B3).** Dedup **und** Rate-Limit sitzen hier, nicht
  im Client. Grund: ein Client-Throttle säße nur in `AcraTree` und sähe den
  Crash-Loop (Uncaught umgeht `AcraTree`) nie; nur der Server sieht **beide** Fluten.
- **Privat, nie ein Drittanbieter.** Reports enthalten Stacktraces der App.
- **Kennt kein Consent (B1).** Der Server prüft **nie** Consent — per Konstruktion
  erreichen ihn nur Reports, die der Client bei aktivem Gate gesendet hat. Er hat
  keinen Consent-Begriff.

### S.1 Eingang — was der Client sendet (fix, ACRA 5.13.1 + §4.7)

- **Endpoint:** `POST` an `BuildConfig.ACRA_URL` (`secrets.properties → acra.url`).
- **Auth:** HTTP Basic (`ACRA_LOGIN`/`ACRA_PASSWORD`). **TLS 1.2/1.3** erzwungen.
- **Body:** ACRA-JSON (`reportFormat = JSON`), **exakt** diese Felder (B5):
  `PACKAGE_NAME, ANDROID_VERSION, APP_VERSION_CODE, APP_VERSION_NAME, BRAND,
  PHONE_MODEL, STACK_TRACE`. **Kein** `CUSTOM_DATA`, kein Logcat, keine Geräte-ID.
- **Kadenz (§13, §4.3):** kein Intervall (`setOverrideDeadline(0)`), Bursts bis
  `MAX_SEND_REPORTS = 5`/Pass, Job-Coalescing *innerhalb* eines App-Lebens. Ein
  **Crash-Loop** = neuer Prozess je Crash = Sofort-POST im Crash-Takt. Der Server
  **MUSS** solche Bursts verkraften, ohne umzufallen (das ist der Sinn von S.5).

### S.2 HTTP-Status-Vertrag — die Kopplung an G3-C  *(MUSS, kritisch)*

Der Client leitet seine **einzige** Liveness (G3-C, `PipelineBacklogProbe`) aus dem
Rückstau ungesendeter Dateien ab: 2xx ⇒ ACRA löscht die Datei; jeder Nicht-Erfolg ⇒
`ReportSenderException` ⇒ Datei bleibt liegen ⇒ Rückstau wächst (§13). Damit
„Rückstau wächst" **genau** „Server unerreichbar/kaputt" heißt — und nicht „Server
hat dedupt" —, gilt:

| Server-Ausgang | HTTP | Client-Folge | Warum |
|---|---|---|---|
| Report gespeichert | **2xx** | Datei gelöscht | Normalfall |
| Report **dedup-** oder **rate-limit-verworfen** (bewusst, kein Fehler) | **MUSS 2xx** | Datei gelöscht | sonst falscher Rückstau → G3-C-Fehlalarm + Backup-Schleife |
| Server überlastet / down / DB weg | **5xx** (oder Verbindungsabbruch) | Datei bleibt → Rückstau | **gewollt**: der eine Fall, in dem Rückstau wachsen SOLL — G3-C zeigt „tot" |
| Auth falsch / URL falsch | 4xx/Abbruch | Datei bleibt → Rückstau | Fehlkonfiguration wird als Rückstau sichtbar (§G3) |

**Kernsatz:** ein absichtlicher Drop (Dedup/Rate-Limit) ist für den Client
**ununterscheidbar von „gespeichert"** — und das ist korrekt. Nur echtes
Server-Versagen darf den Rückstau treiben.

### S.3 Fingerprint — der Anker für Dedup & Rate-Limit  *(MUSS)*

Beide Fluten (S.4/S.5) hängen an einem **stabilen Fingerprint** über den
`STACK_TRACE`. Er **MUSS** über Vorkommen desselben Bugs identisch sein und
Variabel-Rauschen ignorieren.

> **SPEC-DECISION S-2 (Normalisierungsregeln) — ENTSCHIEDEN.** Fix:
> - **Basis: Exception-Typ + normalisierte Frames** (`Klasse.methode`), **ohne**
>   die Message. Grund: der Carrier faltet Log-Kontext in die Message
>   (`"[W/Tag] Type: msg"`, B4); Messages tragen oft variable Daten (IDs, Pfade) →
>   im Fingerprint würden sie denselben Bug aufsplittern.
> - **Strippen:** Speicheradressen/`@1a2b3c`-Identity-Hashes, Thread-Namen,
>   Timestamps. **Zeilennummern behalten** (präziser, pro Version stabil).
> - **Gruppierung auf DE-obfuskiertem Trace** (nach S.6-Mapping), nicht auf dem
>   ProGuard-Namen — sonst zerfällt derselbe Bug über Releases hinweg, weil sich
>   obfuskierte Namen je Mapping ändern.

### S.4 Dedup am Storage  *(MUSS)*

Identische Fingerprints **MUSS** der Server zu **einem** Eintrag mit Zähler
kollabieren (Occurrence-Count, first-/last-seen, Menge betroffener
`APP_VERSION_CODE`, Sample `BRAND`/`PHONE_MODEL`/`ANDROID_VERSION`) — **nicht** N
Tickets öffnen. Löst die *Ticket-Hygiene* (§4.3·1).

### S.5 Rate-Limit an der Ingestion-Kante  *(MUSS)*

Body **früh** hashen bzw. Fingerprint **vor** dem vollen Parsen bilden; gleiche
Fingerprints pro Zeitfenster W ablehnen (→ Zähler erhöhen **oder** droppen),
**bevor** voll geparst/gespeichert wird. Dedup allein (S.4) schützt nur den
Storage, nicht den HTTP-/Parse-Pfad — das hier fängt die **Last** eines
Crash-Loops (§4.3·2). Drop-Antwort: **2xx** (S.2). Fenster W: Betreiber-Wahl
(Richtwert Sekunden–Minuten), am Fingerprint, nicht an der Quelle.

### S.6 ProGuard-Mapping  *(MUSS, für Release-Traces)*

Release-`STACK_TRACE` ist R8-obfuskiert. Der Build lädt die Mapping automatisch
hoch (`uploadProguardMapping` nach `assembleRelease`/`bundleRelease`, `versionName`
= Tag). Der Server **MUSS** Mappings **pro Version** (`APP_VERSION_CODE`/`NAME`)
vorhalten und den Trace für Anzeige **und** für S.3-Fingerprinting deobfuskieren.
Ohne das gruppiert S.4 obfuskierten Müll.

### S.7 Auth- & Secret-Modell  *(SOLL)*

Die Basic-Auth-Credentials sind aus dem APK **extrahierbar** (§4.7, §11) — bewusst
akzeptiert, weil der Endpoint **wegwerfbar** ist: er nimmt nur Reports *entgegen*,
liest keine Nutzerdaten. Verteidigung ist deshalb **server-seitig**, nicht
Client-Geheimhaltung:
- **SOLL:** Rate-Limit pro Quelle/Fingerprint (S.5 deckt Letzteres), Credential-
  **Rotation** möglich, Endpoint strikt write-only (nichts anderes exploitierbar).
- IP-Allowlist ist unpraktikabel (mobile Clients roamen) — kein `MUSS`.

### S.8 PII & Retention  *(MUSS minimal, Retention Betreiber-Sache)*

Durch B5 speichert der Server **keine** PII über `BRAND`/`PHONE_MODEL`/
`ANDROID_VERSION` hinaus — kein Logcat, keine ID. Retention-Dauer ist
Betreiber-Politik; der Vertrag garantiert nur, dass **nichts Sensibles ankommt**,
das eine Aufbewahrung heikel machte.

### S.9 Was der Server NICHT tut

- **Kein Consent-Check** (B1, S.0).
- **Kein Zurückschieben von Client-Throttle-Verantwortung** — der Client bleibt
  bewusst dumm/fail-safe (droppt nie einen echten Crash). Alles Dämpfen ist hier.
- **Kein Verändern der Client-HTTP-Semantik** über S.2 hinaus (2xx=konsumiert).

### S.10 Custom-Ingest — empfohlene Pipeline-Reihenfolge  *(Bauhilfe, folgt aus S-1)*

Weil der Ingest ein Eigenbau ist (S-1), ist die *Reihenfolge* der Schritte selbst
Teil der Korrektheit — Rate-Limit **vor** vollem Parse (S.5), Deobfuskierung **vor**
Fingerprint (S.3/S.6):

```
POST /report  (Basic-Auth, TLS 1.2/1.3)
  1. Auth prüfen              → 401 bei Fehler (Datei bleibt → Rückstau, S.2)
  2. Body billig lesen        → STACK_TRACE + APP_VERSION_CODE extrahieren,
                                 NICHT den vollen Report parsen
  3. Deobfuskieren            → Mapping[APP_VERSION_CODE] auf STACK_TRACE (S.6)
  4. Fingerprint bilden       → Typ + normalisierte Frames, ohne Message (S-2)
  5. Rate-Limit-Check         → fingerprint in Fenster W schon gesehen?
                                 JA → Zähler++, **2xx**, FERTIG (kein Parse/Store, S.5/S.2)
  6. Voll parsen + Upsert     → Dedup am Storage: ein Eintrag/Fingerprint
                                 + Count/first-/last-seen/Versionen (S.4)
  7. Antwort                  → **2xx** (gespeichert). Nur echtes Versagen → 5xx (S.2)
```
- Schritt 5 vor 6 ist der Punkt von S.5: der Crash-Loop-Burst wird abgewiesen,
  **bevor** teures Parsen/Schreiben passiert.
- Jeder absichtliche Ausgang (5 und 6) endet in **2xx** — nur Auth (1) und echtes
  Versagen (7) nicht; genau das hält die G3-C-Kopplung (S.2) sauber.
- **Test-Anker (Server-seitig, außerhalb dieses Repos):** derselbe Fingerprint
  zweimal in W → ein Storage-Eintrag, Count 2, beide Male 2xx; Server-500 →
  Client-Rückstau wächst (manuell gegen `PipelineBacklogProbe` verifizierbar).

---

## B. Belang B — Ingestion

Die Report-Pipeline: vier Quellen, ein Zustellweg, kein Client-Throttle (die
Flut-Kontrolle liegt im Server-Vertrag S). Baut auf **S** (jeder gesendete Report
trifft dort auf Dedup + Rate-Limit) und speist **C** (die Resilienz-Quellen liefern
über denselben Weg).

### B.0 Namens- & Paket-Mapping (heute → Rewrite)

| Heute (Ist) | Rewrite (`crashreporting/ingestion/`) | Modul | Änderung |
|---|---|---|---|
| `AcraTree` (genestet in `KolibriLauncherApp.kt`) | `AcraTree` (eigene Datei) | :app | Auszug; **Throttle-Schritt entfällt** (B3) |
| `core.CrashReportContext` (`buildAcraReportThrowable`, `LoggedThrowable`) | `ReportCarrier` | :domain | Datei-Umbenennung; pure bleibt |
| `ui.util.AnrReporter` + `AnrException` | `AnrReporter` + `AnrException` | :app | Auszug; `AnrException` verliert `UnthrottledReport` |
| `ui.util.CrashReportLimiter` + `UnthrottledReport` | **— gelöscht —** | :app | **entfällt komplett** (B3, §4.3) |
| ACRA-`reportContent`/`httpSender` (in `attachBaseContext`) | bleibt in `attachBaseContext` | :app | Feldliste = B5; Init-*Wiring* gehört zu C |

### B.1 Vier Quellen, zwei ACRA-Eingänge (§4.1)

| Quelle | Belang | ACRA-Eingang |
|---|---|---|
| **Uncaught-Crash** | C | ACRAs eigener `UncaughtExceptionHandler` (Auto-Report, voller `reportContent`) |
| **Geloggter Fehler** (`Timber.e/w(t)`, WARN+) | B | `AcraTree` → `handleSilentException(carrier)` |
| **Post-mortem ANR** (`ApplicationExitInfo` REASON_ANR) | B | `Timber.e(AnrException)` → `AcraTree` → `handleSilentException` |
| **Watchdog-Stall** (`WatchdogStallException`, *vor* Kill) | C→B | `Timber.e(WatchdogStallException)` → `AcraTree` |

Zwei Quellen sind *B-eigen* (geloggter Fehler, ANR); zwei entstehen in *C*
(Uncaught, Stall) und laufen nur durch B. `WatchdogStallException` lebt bei C
(§C, `RecoveryWatchdog`), wird aber hier zugestellt.

### B.2 Der eine Zustellweg — `AcraTree` (:app)  *(B2)*

```kotlin
// crashreporting/ingestion/AcraTree.kt
internal class AcraTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // 1. Gate: nur WARN+ MIT Throwable. Kein Consent-Check (B1 — das enabled-Flag gatet).
        if (priority < Log.WARN || t == null) return
        try {
            // 2. Carrier: per-Report (B4)
            val carrier = buildAcraReportThrowable(priority, tag, message, t)
            // 3. Zustellen: persist + Out-of-process-schedule; No-Op wenn ACRA disabled (B1)
            ACRA.errorReporter.handleSilentException(carrier)
            // KEIN Throttle-Schritt (B3). KEIN Send-Marker (enqueued nur, §4.2/G3-C).
        } catch (t2: Throwable) {
            // C1: schlucken. android.util.Log.e — NICHT Timber (Selbst-Rekursion!),
            //     NICHT silentError (Crash-Infra, kein DEBUG-Throw).
            Log.e("AcraTree", "report enqueue failed", t2)
        }
    }
}
```

> **Nicht offensichtlich, tragend:** der Swallow nutzt **`android.util.Log.e`**, nicht
> `Timber.e`. `AcraTree` *ist* ein Timber-Tree — ein `Timber.e` in seinem eigenen
> `catch` re-enterte `AcraTree.log` (Endlos-Rekursion). Und **nicht** `silentError`
> (Rule 9): ein DEBUG-Throw im Zustellweg landete wieder im selben Pfad. Genau
> deshalb braucht `AcraTree` **keinen** Rule-9-Whitelist-Eintrag — es verwendet gar
> kein bare `Timber.e` (siehe B.9). *(RS2, C1)*

- **Nie zusätzlich `handleException(…)`** aufrufen — das wäre terminal + Doppelversand
  (B2, §13). Der einzige Aufruf ist `handleSilentException`.
- **Kein Consent-Check** (B1): `AcraTree` verlässt sich darauf, dass A das
  `enabled`-Flag korrekt gesetzt hat. Bei `disabled` ist `handleSilentException` ein
  No-Op (§13).
- **Planting:** in `onCreate` via `Timber.plant(AcraTree())` (Wiring-Detail, C).

### B.3 Was gelöscht wird: `CrashReportLimiter` + `UnthrottledReport`  *(B3)*

Ersatzloser Wegfall — die Flut-Kontrolle ist server-seitig (S.4/S.5):
- `CrashReportLimiter` (object, SharedPreferences `acra_report_limiter`) + sein
  `CrashReportLimiter.init(context)`-Aufruf → **weg**. Damit fällt die **einzige**
  `SharedPreferences`-Ausnahme (CLAUDE.md Rule 5) ersatzlos.
- `UnthrottledReport` (Marker-Interface) → **weg**; es existierte nur, um den
  Throttle zu umgehen. `AnrException` implementiert es nicht mehr.
- **Grund (§4.3):** ein Client-Throttle säße nur in `AcraTree` und sähe den
  Crash-Loop (Uncaught umgeht `AcraTree`) nie; er deckte nur den Silent-Strom. Der
  Server sieht beide Fluten (S.0). Kosten ehrlich: ein Crash-Loop POSTet im
  Crash-Takt (§11-Grenze), gebremst an der Ingestion-Kante (S.5), nicht im Client.

### B.4 `ReportCarrier` (:domain, pure)  *(B4)*

```kotlin
// crashreporting/ingestion/ReportCarrier.kt   (:domain, Android-frei, JVM-testbar)
/** Faltet Log-Kontext in die Message einer frischen Exception, Original als cause.
 *  Landet in STACK_TRACE (das IST in reportContent, B5); kein putCustomData. */
fun buildAcraReportThrowable(priority: Int, tag: String?, message: String, cause: Throwable): Throwable

internal class LoggedThrowable(message: String, cause: Throwable) : Throwable(message, cause)
```
- Message-Format: `"[W/Tag] OriginalType: message"` (Priority-Buchstabe + Tag +
  Original-Typ), Original unter „Caused by:".
- **Diagnose-Note bei `CancellationException`:** ein Zusatz in der Message, der auf
  den fehlerhaften `catch` zeigt (eine geloggte `CancellationException` ist fast
  immer ein Bug).
- **Warum kein `putCustomData`:** prozess-globale Mutable-Map → Thread-Race (zwei
  Reports vertauschen Metadaten, AUDIT-6 #4); zudem listet `reportContent` kein
  `CUSTOM_DATA` → die Daten erreichten den Server nie. Carrier ist per-Report by
  construction (kein Shared State, kein Lock).

### B.5 `AnrReporter` (:app)  *(§4.5)*

```kotlin
// crashreporting/ingestion/AnrReporter.kt
@Singleton
class AnrReporter @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dataStore: DataStore<Preferences>,          // settings-Store (unqualifiziert)
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Beim nächsten Start: neue REASON_ANR-AEI seit Watermark, chronologisch,
     *  je → handler(report). handler = { Timber.e(AnrException(...)) } (→ B.2). */
    suspend fun reportPendingAnrs(handler: suspend (AnrReport) -> Unit)
    // intern: newAnrsSinceLastReport(), markReported()
}
data class AnrReport(val timestamp: Long, val description: String,
                     val importance: Int, val threadDump: String?)
class AnrException(message: String) : RuntimeException(message)   // KEIN UnthrottledReport mehr
```
- **Kein Live-Sampling** (kein `ANRWatchDog`): `getHistoricalProcessExitReasons` +
  `ApplicationExitInfo.REASON_ANR` + `.getTraceInputStream()` (API 30+, Ziel 36).
- **Dedup = Watermark** `longPreferencesKey("anr_reporter_last_reported_ts")` im
  **settings**-DataStore: jeder AEI-Record genau einmal. AEI-Recordzahl beschränkt →
  flood-safe.
- **Best-effort (C1):** der `handler` schluckt einen **Enqueue**-Fehler (nicht Send —
  out-of-process, §4.2); die Watermark advanced trotzdem → dieser ANR fällt weg.
  Handler **nicht** zum Rethrow bringen (unterliefe den Crash-Infra-Swallow).
- **Wiring:** `onCreate` → `reportPendingAnrsAsync()` auf `ApplicationScope` (C).

### B.6 Report-Inhalt & Transport (ACRA-CoreConfiguration)  *(B5, §4.7)*

Die Config bleibt in `attachBaseContext` (Init-*Reihenfolge* = C, §12); die
*Inhalte* sind B:
```kotlin
initAcra {
    reportFormat = StringFormat.JSON
    httpSender {
        uri = BuildConfig.ACRA_URL                    // privat, selbstgehostet (S)
        basicAuthLogin = BuildConfig.ACRA_LOGIN
        basicAuthPassword = BuildConfig.ACRA_PASSWORD
        httpMethod = HttpSender.Method.POST
        tlsProtocols = listOf(TLS.V1_2, TLS.V1_3)
    }
    reportContent = listOf(                           // EXAKT diese 7, nicht mehr (B5)
        PACKAGE_NAME, ANDROID_VERSION, APP_VERSION_CODE, APP_VERSION_NAME,
        BRAND, PHONE_MODEL, STACK_TRACE)
    // KEIN CUSTOM_DATA, kein Logcat, keine ID.
}
```
> **Konfig-Vollständigkeit (MUSS, sonst UX-Bruch):** **kein** Interaktions-Plugin
> (Dialog-/Notification-Mode). Der Consent-Dialog ist unser eigener (`ConsentDialog`,
> A), die Zustellung ist `handleSilentException` (still) — ACRAs eingebauter
> Dialog/Notification darf **nicht** konfiguriert sein, sonst erscheint ein zweiter,
> fremder Consent-/Report-Dialog. (Kein `dialog{}`/`notification{}`-Block, keine
> `ReportingInteractionMode`-Plugin-Dependency.)

### B.7 DI/Wiring-Deltas

- `AcraTree` — **kein** Hilt (ist ein Timber-Tree); in `onCreate` geplantet (C).
- `ReportCarrier` — reine Funktionen, kein DI.
- `AnrReporter` — `@Singleton @Inject` (unverändert); braucht settings-DataStore
  (unqualifiziert), `@ApplicationContext`, `@IoDispatcher`. Aus `onCreate` gewired (C).
- **Entfällt:** jeder `CrashReportLimiter.init`-Aufruf.

### B.8 Test-Inventar

Pfade: `ReportCarrier` → `domain/src/test/.../crashreporting/ingestion/ReportCarrierTest.kt`
(pure JVM). `AcraTree`/`AnrReporter` → `app/src/test/.../crashreporting/ingestion/…`
(AnrReporter braucht Robolectric o. gemockten `ActivityManager` für AEI).

| `@Test` | Datei | pinnt |
|---|---|---|
| `priority < WARN → kein handleSilentException` | AcraTreeTest | Gate |
| `t == null → kein handleSilentException` | AcraTreeTest | Gate |
| `WARN+ mit t → buildCarrier + handleSilentException genau einmal` | AcraTreeTest | B2 |
| `handleSilentException wirft → geschluckt, kein Rethrow` | AcraTreeTest | C1, RS2 |
| `AcraTree ruft NIE handleException` | AcraTreeTest / Linter | B2 |
| `AcraTree liest NIE Consent, kein Throttle` | AcraTreeTest / Linter | B1, B3 |
| `Carrier: Message "[W/Tag] Type: msg", Original als cause` | ReportCarrierTest | B4 |
| `Carrier: CancellationException → Diagnose-Note` | ReportCarrierTest | B4 |
| `reportContent == exakt die 7 Felder` | Assertion-Test | B5 |
| `AEI-Read wirft → emptyList, Watermark unverändert` | AnrReporterTest | AN1, C1 |
| `Trace-Stream null → Report ohne Dump` | AnrReporterTest | AN2 |
| `Watermark-Write wirft → nicht advanced` | AnrReporterTest | AN3 |
| `Enqueue-Fehler im Handler → Watermark advanced, ANR gedroppt` | AnrReporterTest | AN4 |
| `jeder AEI-Record genau einmal (Watermark-Dedup)` | AnrReporterTest | §4.5 |

### B.9 Linter-Delta (Ingestion-Teil)

- **Rule 9:** **kein** neuer Whitelist-Eintrag. `AcraTree` schluckt mit
  `android.util.Log.e` (nicht `Timber.e`) → vom `Timber.e`-Detektor gar nicht
  erfasst. `AnrReporter` nutzt `silentError` (nicht Crash-Infra-exempt; läuft beim
  nächsten Start, best-effort). `ReportCarrier` wirft nie (pure).
- **B2-Linter:** `handleException(`/`handleSilentException(` nur in `AcraTree`
  erlaubt (kein Zweitaufruf/Doppelversand). *Neuer Check* — nach
  `tools/check-conventions.sh` (analog zu Toast-Routing).
- **B3-Linter:** kein `ReportThrottle`/`shouldSend` in `AcraTree`; `CrashReportLimiter`
  existiert nicht mehr. *Neuer/erweiterter Check.*
- **B4-Linter:** kein `putCustomData(` irgendwo. *Neuer Check.*
- **B1-Linter:** kein Consent-Read außerhalb `crashreporting/consent/`.

> **SPEC-DECISION B-1 (neue Linter-Checks) — Default: ja, aber verschiebbar.**
> B2/B3/B4/B1 sind billige grep-Checks im Stil der bestehenden
> (`check-conventions.sh`). Empfehlung: mit der Implementierung einführen, nicht
> vorab. Kein Blocker für die Spec.

---

## C. Belang C — Resilienz

Das Safety-Net um A und B: die App überlebt eigene Crashes, der Reporter wird nie
selbst zur Crash-Quelle (C1). Enthält die zwei Wert-Gabelungen G1 (Uncaught) und
G2 (Watchdog) — beide zugunsten Variante A geschlossen — sowie die G3-C-Baseline-
Liveness und die harten Init-Reihenfolgen (§12).

### C.0 Namens- & Paket-Mapping (heute → Rewrite)

| Heute (Ist) | Rewrite (`crashreporting/resilience/`) | Modul | Änderung |
|---|---|---|---|
| `setupGlobalExceptionHandler()` (DEBUG-only) + `handleUncaughtException` (in `KolibriLauncherApp.kt`) | `UncaughtCrashHandler` (eigene Datei) | :app | **unified RELEASE+DEBUG (G1)**; **500 ms-Sleep raus** (kein Flush-Fenster); eigener Kill nur Backstop |
| `ui.util.RecoveryWatchdog` (Kill-only) | `RecoveryWatchdog` + `WatchdogStallException` | :app | **+ Capture-vor-Kill (C3/X1)**, **+ Loop-Guard (G2)** |
| ACRA-Init/Wiring verstreut in `KolibriLauncherApp` | `CrashReportingBootstrap` | :app | herausgezogen; `KolibriLauncherApp` wird dünner Glue |
| — (neu, G3-C) | `PipelineBacklogProbe` | :app | Baseline-Liveness aus `ReportLocator` |

### C.1 `UncaughtCrashHandler` (G1 → A: ein Pfad, beide Builds)

```kotlin
// crashreporting/resilience/UncaughtCrashHandler.kt
class UncaughtCrashHandler(
    private val defaultHandler: Thread.UncaughtExceptionHandler?,   // = ACRAs ErrorReporterImpl
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, t: Throwable) {
        if (t is OutOfMemoryError) System.gc()          // Best-effort: Speicher frei, BEVOR ACRA allokiert
        Timber.e(t, "uncaught")                          // Rule 9: plain Timber.e (Crash-Infra)
        defaultHandler?.uncaughtException(thread, t)     // = ACRA: persist + schedule(:acra) + exitProcess(10)
        // ACRA killt hier bereits. Folgender Kill ist reiner Backstop —
        // nur erreicht, falls ACRA NICHT killte (Admin-Veto / Handler warf):
        Process.killProcess(Process.myPid()); exitProcess(10)   // deterministisch (C2)
    }
}
```
- **Unified, kein DEBUG-only-Wrapper (G1):** identisch in RELEASE und DEBUG; der
  reale Build ist Release, Instrumentierung nur in DEBUG wäre verkehrt.
- **Kein Flush-Fenster:** das heutige `Thread.sleep(500)` **entfällt**. ACRA sendet
  out-of-process, die Report-Datei überlebt den Kill, ein Completion-Callback
  existiert nicht (§13/§5.1). Es gibt im sterbenden Prozess nichts zu flushen.
- **Kill ist ACRAs, unserer nur Backstop (C2):** ACRAs `ProcessFinisher` killt in
  beiden Builds; unser `killProcess`/`exitProcess(10)` greift nur, falls ACRA es
  nicht tat.
- **Reihenfolge (§12·2):** **nach** `ACRA.init` installieren, damit `defaultHandler`
  ACRAs `ErrorReporterImpl` ist — sonst delegiert der Wrapper am Reporter vorbei und
  **kein Report entsteht**.

### C.2 `RecoveryWatchdog` (G2 → A: Kill *und* Capture, mit Loop-Guard)

```kotlin
// crashreporting/resilience/RecoveryWatchdog.kt
class RecoveryWatchdog(
    private val timeoutMs: Long = 8_000L,                 // §6: zwischen Input-ANR ~5s und Broadcast ~10s
    private val mainThread: Thread = Looper.getMainLooper().thread,
    private val capture: (Throwable) -> Unit,             // = { Timber.e(it) } → §B.2
    private val loopGuard: LoopGuard,                     // C.3
    private val killSwitch: () -> Unit = { Process.killProcess(Process.myPid()); exitProcess(10) },
) : Thread("RecoveryWatchdog") { init { isDaemon = true } }   // Daemon: läuft, wenn Main-Looper hängt

class WatchdogStallException(mainStack: Array<StackTraceElement>) : RuntimeException("main looper stalled") {
    init { stackTrace = mainStack }
}
```
Trip-Ablauf (§6), Schritt 0 = Loop-Guard:
```
Trip (Main-Looper hat timeoutMs nicht getickt):
  0. if (loopGuard.shouldSuppressKill()) → nur Schritt 1–2, KEIN Kill (Fallback „nur berichten")
  1. Capture:  mainStack = mainThread.stackTrace          # auf dem Daemon-Thread
  2. Enqueue:  capture(WatchdogStallException(mainStack))  # → §B.2 (persist + :acra-schedule)
  3. Kill:     loopGuard.recordKill(); killSwitch()
  (1–2 in try/catch(Throwable) geschluckt — Kill hat Vorrang, ST1; Swallow via Log.e, nicht Timber-Rekursion)
```
- **Warum Capture *vor* Kill (C3/X1):** ein `Process.killProcess` erzeugt kein
  `REASON_ANR` → der `AnrReporter` (B) sieht diesen Stall nie. Ohne Schritt 1–2 wäre
  er unsichtbar.
- **Warum es trotz Kill ankommt:** `handleSilentException` persistiert die Datei
  (überlebt Kill), `:acra` sendet sie; der einzige In-Process-Schritt ist ein
  kleiner File-Write auf dem **Daemon**-Thread, nicht dem hängenden Main-Thread.
- **Loop-Guard (G2):** bricht den deterministischen Kill-Restart-Loop (C.3).
- **Reihenfolge (§12·3):** **nach** `onCreate` starten (`Handler.post`), sonst kann
  schwere Cold-Start-Arbeit den HOME-Prozess in einen Restart-Loop killen.
- **Slow-Device-Kante (§11):** eine legitime > 8 s-Single-Message wird false-
  getrippt — durch den `minSdk 36`-Hardware-Boden praktisch entschärft; akzeptiert.

### C.3 Loop-Guard-Store (`LoopGuard`)  *(G2-Implementierungsnotiz)*

Der Loop-Guard braucht: **cross-restart** (jeder Kill = neuer Prozess), **sync
schreibbar vom Daemon-Thread** *am Kill-Zeitpunkt* (Prozess stirbt gleich). Das ist
exakt die Constraint, für die `CrashReportLimiter` SharedPreferences nutzte — und
die wir in B gerade entfernt haben.

```kotlin
// crashreporting/resilience/LoopGuard.kt
class LoopGuard(
    private val store: File,          // C-1: plain File unter noBackupFilesDir
    private val windowMs: Long = 60_000L,
    private val maxKills: Int = 3,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun shouldSuppressKill(): Boolean // recent kills (aus File, beim Start gelesen) im Fenster >= maxKills
    fun recordKill()                  // SYNCHRON: Timestamp an File anhängen (java.io, überlebt Kill)
}
```

> **SPEC-DECISION C-1 (Loop-Guard-Ablage) — ENTSCHIEDEN: plain File.**
> - **Gewählt: kleine plain-`File`** (Timestamp-Zeilen, Ring über die letzten
>   `maxKills`+1) unter `noBackupFilesDir` — synchron via `java.io` schreibbar
>   (überlebt den Kill), cross-restart lesbar, kein Suspend. `noBackup`, damit ein
>   Geräte-Transfer keine falsche Kill-Historie mitschleppt. **Reine Telemetrie,
>   kein Entscheidungszustand** → die X2-Multi-Prozess-Sorge (Consent) gilt nicht.
> - **Gegen SharedPreferences:** brächte die in B ersatzlos gelöschte
>   `SharedPreferences`-Ausnahme (Rule 5) zurück — für denselben Grund
>   (sync-from-crash-thread), den wir gerade abgeschafft haben. Vermeiden.
> - **Gegen DataStore:** suspend-only, am Kill-Zeitpunkt auf dem Daemon-Thread
>   **nicht** synchron nutzbar. Scheidet aus.

### C.4 `PipelineBacklogProbe` (G3-C: Baseline-Liveness)

```kotlin
// crashreporting/resilience/PipelineBacklogProbe.kt  (Hauptprozess)
class PipelineBacklogProbe @Inject constructor(@ApplicationContext private val context: Context) {
    data class Backlog(val approved: Int, val unapproved: Int, val oldestMillis: Long?)
    /** Liest ReportLocator (ACRA public API, §13): liegenbleibende Report-Dateien.
     *  Wachsend = Sender/Server tot; leer = gesund ODER nie gecrasht. Für Dev-Screen (§8c). */
    fun read(): Backlog   // ReportLocator(context).approvedReports/unapprovedReports (Array<File>), min lastModified
}
```
- **Kein** zweiter Sender, **keine** Cross-Prozess-Ablage (das wäre der Upgrade
  G3-A, `AcraHttpSender` — nicht gebaut).
- **Grenze (akzeptiert, §11):** kein positiver HTTP-Beweis; leer bleibt mehrdeutig.
- Nutzt bei Fehlern `silentError` (nicht Crash-Infra — läuft auf Dev-Screen-Abruf).

### C.5 `CrashReportingBootstrap` (Init-Reihenfolge, X2, Wiring)

Zieht die heute in `KolibriLauncherApp` verstreute Init heraus; die App wird dünner
Glue.
```kotlin
// crashreporting/resilience/CrashReportingBootstrap.kt
object CrashReportingBootstrap {
    /** attachBaseContext, NACH initAcra{} (B.6). */
    fun afterInit(app: Application, base: Context) {
        ACRA.errorReporter.setEnabled(false)                       // §12·1: SOFORT, ohne Zwischenanweisung (A1)
        if (!ACRA.isACRASenderServiceProcess()) {                  // X2: Prozess-Gate
            val decision = runBlocking { ConsentBootstrap.readDecision(base) }   // §3.5 sync (G4)
            if (decision == ConsentDecision.Granted) ACRA.errorReporter.setEnabled(true)
        }
        val default = Thread.getDefaultUncaughtExceptionHandler()  // = ACRA (nach init)
        Thread.setDefaultUncaughtExceptionHandler(UncaughtCrashHandler(default))   // §12·2
        // KolibriLog-Bridge: core/KolibriLog-Lambdas an Timber/silentError wiren
    }
    /** onCreate. */
    fun onCreate(app: Application, scope: CoroutineScope, anrReporter: AnrReporter, watchdog: RecoveryWatchdog) {
        Timber.plant(AcraTree()); Timber.plant(ToastErrorTree())   // + DebugTree in DEBUG
        scope.launch { anrReporter.reportPendingAnrs { report -> Timber.e(AnrException(report.description)) } }  // B.5
        Handler(Looper.getMainLooper()).post { watchdog.start() }  // §12·3: nach onCreate
    }
}
```

### C.6 Harte Sequenz-Invarianten (§12)

| # | Reihenfolge | Bruch-Folge |
|---|---|---|
| §12·1 | `initAcra{}` → **`setEnabled(false)` ohne Zwischenanweisung** (A1) | Mikro-Fenster mit aktivem Reporter bei unbekanntem Consent |
| §12·2 | `ACRA.init` → **dann `UncaughtCrashHandler` installieren** | Wrapper delegiert am Reporter vorbei → **kein Report** |
| §12·3 | `onCreate` fertig → **dann `RecoveryWatchdog.start()`** | Cold-Start-Arbeit killt HOME-Prozess in Restart-Loop |

### C.7 Rule-9-Whitelist-Delta

Neue Crash-Infra-Dateien mit plain `Timber.e` (§5.3) → in `rule9_allowed_files`
(`tools/check-conventions.sh` Z. 79) ergänzen:
- `UncaughtCrashHandler\.kt` — der globale Handler (plain `Timber.e`).
- `RecoveryWatchdog\.kt` — Capture-Swallow (plain `Timber.e`/`Log.e`).
- `CrashReportingBootstrap\.kt` — Bootstrap-Pfad (ACRA-Init, Consent-Read).
- (`ConsentBootstrap\.kt` bereits in A.9; `KolibriLauncherApp\.kt` bleibt.)

`PipelineBacklogProbe`, `LoopGuard` nutzen **kein** bare `Timber.e` (silentError bzw.
reine Logik) → kein Eintrag. `AcraTree` ebenfalls nicht (Log.e, B.9).

### C.8 Test-Inventar

Pfade: `app/src/test/.../crashreporting/resilience/…`; `RecoveryWatchdog`/`LoopGuard`
sind aus dem Daemon-Thread herausgezogene reine Logik (JVM, `now`/`killSwitch`/
`capture` injizierbar — Rule 10).

| `@Test` | Datei | pinnt |
|---|---|---|
| `OOM → System.gc vor Delegation` | UncaughtCrashHandlerTest | §5.1 |
| `delegiert an defaultHandler (ACRA)` | UncaughtCrashHandlerTest | §12·2 |
| `Backstop-Kill erreicht, wenn defaultHandler nicht killt` | UncaughtCrashHandlerTest | C2 |
| `kein Thread.sleep / Flush-Fenster` | UncaughtCrashHandlerTest (Review) | §5.1 |
| `erster Trip → capture UND killSwitch` | RecoveryWatchdogTest | G2, C3 |
| `N-ter Trip in M s → capture, KEIN killSwitch` | RecoveryWatchdogTest | G2 (Loop-Guard) |
| `capture wirft → geschluckt, Kill läuft trotzdem` | RecoveryWatchdogTest | ST1, C1 |
| `LoopGuard: recordKill persistiert; shouldSuppressKill zählt Fenster` | LoopGuardTest | G2 |
| `LoopGuard: cross-restart (neuer Store-Read sieht alte Kills)` | LoopGuardTest | G2 |
| `PipelineBacklogProbe: N Dateien → Backlog(n, …, oldest)` | PipelineBacklogProbeTest | C4 |
| `Bootstrap: setEnabled(false) direkt nach init` | (Bootstrap-/Review) | §12·1, A1 |
| `Bootstrap: Handler NACH init installiert` | (Review) | §12·2 |
| `RS1 ACRA-Init wirft → Log.e-Fallback, App läuft, ACRA AUS` | BootstrapTest | RS1, C1 |

---

*Ende der drei Belange. Offene, ausdrücklich benannte Nicht-Blocker:* SPEC-DECISION
B-1 (neue Linter mit der Implementierung), SPEC-DECISION C-1 (Loop-Guard = plain
File, empfohlen), sowie die Messungen aus `ACRA_FLOW.md` (G4-Read-Dauer, optional
G2-Trip-Rate).
