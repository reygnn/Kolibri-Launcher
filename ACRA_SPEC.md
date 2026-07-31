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

Status: **Belang A (Consent) — Entwurf; S (Server-Vertrag) — steht.** B (Ingestion),
C (Resilienz) — offen.

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

## B. Belang B — Ingestion   *(offen — nächster Slice; baut auf S)*

## C. Belang C — Resilienz   *(offen)*
